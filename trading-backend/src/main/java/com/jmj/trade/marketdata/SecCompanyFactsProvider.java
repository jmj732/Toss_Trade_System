package com.jmj.trade.marketdata;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.namespace.QName;

final class SecCompanyFactsProvider implements StockDataProvider {

    private static final StockDataProviderId ID = StockDataProviderId.SEC;
    private static final String USD = "USD";
    private static final String SHARES = "shares";
    private static final String USD_PER_SHARE = "USD/shares";
    private static final String TAGS = "us-gaap";
    private static final String TICKER_FILE = "https://www.sec.gov/files/company_tickers.json";
    private static final URI SEC_ARCHIVES_BASE = URI.create("https://www.sec.gov");
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofHours(6);
    private static final Duration MAX_CACHE_TTL = Duration.ofDays(1);
    private static final int MAX_INLINE_FILING_PERIODS = 6;
    private static final long INLINE_FILING_LOOKBACK_DAYS = 550;
    private static final Set<String> FIELDS = Set.of(
            "filing.form", "filing.accession", "filing.acceptance",
            "fundamental.fiscalPeriod", "fundamental.reportedAt", "fundamental.fiscalYear",
            "fundamental.fiscalPeriodCode", "fundamental.cash", "fundamental.debt",
            "fundamental.basicShares", "fundamental.basicSharesBasis",
            "fundamental.dilutedShares", "fundamental.dilutedSharesBasis", "fundamental.currency",
            "fundamental.revenueTTM", "fundamental.revenueGrowthYoY", "fundamental.eps",
            "fundamental.fcfTTM", "fundamental.ebitdaTTM", "fundamental.ebitdaTTMType",
            "fundamental.ebitdaTTMFormula", "fundamental.ebitdaTTMSource");

    private final ProviderHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final URI tickerFile;
    private final URI dataBase;
    private final Duration cacheTtl;
    private final Object cacheLock = new Object();
    private final Map<Long, CacheEntry<IssuerData>> issuerDataCache = new HashMap<>();
    private final Map<String, CacheEntry<InlineFetchResult>> inlineFactCache = new HashMap<>();
    private volatile CacheEntry<Map<String, Issuer>> tickerMap;

    SecCompanyFactsProvider(
            StockAnalysisProviderProperties.ProviderConfiguration configuration,
            ObjectMapper objectMapper
    ) {
        this(configuration, objectMapper, Clock.systemUTC(), URI.create(TICKER_FILE), configuration.baseUrl(),
                DEFAULT_CACHE_TTL);
    }

    SecCompanyFactsProvider(
            StockAnalysisProviderProperties.ProviderConfiguration configuration,
            ObjectMapper objectMapper,
            Clock clock,
            URI tickerFile,
            URI dataBase
    ) {
        this(configuration, objectMapper, clock, tickerFile, dataBase, DEFAULT_CACHE_TTL);
    }

    SecCompanyFactsProvider(
            StockAnalysisProviderProperties.ProviderConfiguration configuration,
            ObjectMapper objectMapper,
            Clock clock,
            URI tickerFile,
            URI dataBase,
            Duration cacheTtl
    ) {
        this.transport = new ProviderHttpTransport(ID, configuration);
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tickerFile = tickerFile;
        this.dataBase = dataBase;
        if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()
                || cacheTtl.compareTo(MAX_CACHE_TTL) > 0) {
            throw new IllegalArgumentException("SEC cacheTtl must be positive and at most one day");
        }
        this.cacheTtl = cacheTtl;
    }

    @Override
    public StockDataProviderId id() {
        return ID;
    }

    @Override
    public DataProviderRole role() {
        return ProviderCatalog.roleOf(ID);
    }

    @Override
    public Set<String> fields() {
        return FIELDS;
    }

    @Override
    public List<ProviderValue> fetch(ProviderRequest request) {
        try {
            var issuer = tickerMap().get(request.symbol().toUpperCase(java.util.Locale.ROOT));
            if (issuer == null) throw unavailable("SYMBOL_NOT_FOUND");
            var issuerData = issuerData(issuer);
            return map(request, issuer, issuerData.submissions(), issuerData.companyFacts(), clock.instant());
        } catch (JacksonException exception) {
            throw unavailable("INVALID_RESPONSE");
        }
    }

    private List<ProviderValue> map(ProviderRequest request, Issuer issuer, JsonNode submissions,
                                    JsonNode companyFacts, Instant observedAt) {
        if (!submissions.isObject() || !companyFacts.isObject()
                || number(submissions.get("cik")) != issuer.cik()
                || number(companyFacts.get("cik")) != issuer.cik()
                || !containsTicker(submissions.path("tickers"), request.symbol())
                || text(companyFacts.get("entityName")).isBlank()) {
            throw unavailable("ISSUER_MISMATCH");
        }
        var recent = submissions.path("filings").path("recent");
        var filings = eligibleFilings(recent, observedAt);
        var latest = filings.stream().findFirst().orElse(null);
        if (latest == null) throw unavailable("NO_RECENT_FILING");
        var inlineEnrichment = inlineFactsIfNeeded(companyFacts, issuer, filings);
        var enrichedCompanyFacts = inlineEnrichment.companyFacts();
        var allFacts = enrichedCompanyFacts.path("facts");
        var facts = allFacts.path(TAGS);
        var deiFacts = allFacts.path("dei");
        if (!facts.isObject()) throw unavailable("INVALID_RESPONSE");
        var cutoff = observedAt.atZone(ZoneOffset.UTC).toLocalDate();
        var financial = filings.stream().filter(filing -> hasCoreFacts(facts, filing, cutoff))
                .findFirst().orElse(null);

        var result = new ArrayList<ProviderValue>();
        var filingReportedAt = latest.acceptance() == null
                ? latest.filed().atStartOfDay(ZoneOffset.UTC).toInstant() : latest.acceptance();
        addText(result, "filing.form", latest.form(), filingReportedAt, null, latest.accession());
        addText(result, "filing.accession", latest.accession(), filingReportedAt, null, latest.accession());
        addText(result, "filing.acceptance", latest.acceptance() == null ? null : latest.acceptance().toString(),
                latest.acceptance(), latest.acceptance() == null ? "DATA_NOT_PRESENT" : null, latest.accession());

        var fiscalEnd = financial == null ? null : financial.reportDate();
        var reportedAt = financial == null ? null : financial.acceptance() == null
                ? financial.filed().atStartOfDay(ZoneOffset.UTC).toInstant() : financial.acceptance();
        var asOf = fiscalEnd == null ? null : fiscalEnd.atStartOfDay(ZoneOffset.UTC).toInstant();
        addText(result, "fundamental.fiscalPeriod", fiscalEnd == null ? null : fiscalEnd.toString(), asOf,
                fiscalEnd == null ? "DATA_NOT_PRESENT" : null,
                financial == null ? latest.accession() : financial.accession());
        addText(result, "fundamental.reportedAt", reportedAt == null ? null : reportedAt.toString(), asOf,
                reportedAt == null ? "DATA_NOT_PRESENT" : null,
                financial == null ? latest.accession() : financial.accession());
        var context = financial == null ? null : latestPeriodFacts(facts, financial, fiscalEnd, cutoff);
        var fiscalYear = financial == null ? null
                : financial.fiscalYear() == null && context != null ? context.fiscalYear() : financial.fiscalYear();
        var fiscalPeriod = financial == null ? null
                : financial.fiscalPeriod() == null && context != null
                ? context.fiscalPeriod() : financial.fiscalPeriod();
        addText(result, "fundamental.fiscalYear", fiscalYear == null ? null : Integer.toString(fiscalYear), asOf,
                fiscalYear == null ? "DATA_NOT_PRESENT" : null,
                financial == null ? latest.accession() : financial.accession());
        addText(result, "fundamental.fiscalPeriodCode", fiscalPeriod, asOf,
                fiscalPeriod == null ? "DATA_NOT_PRESENT" : null,
                financial == null ? latest.accession() : financial.accession());

        var cash = latestReportedInstant(facts, List.of("CashAndCashEquivalentsAtCarryingValue"), USD, cutoff);
        addDecimal(result, "fundamental.cash", cash, USD, null, "CashAndCashEquivalentsAtCarryingValue");

        var directDebt = latestReportedInstant(facts, List.of("DebtLongtermAndShorttermCombinedAmount"), USD, cutoff);
        var currentDebt = latestReportedInstant(facts,
                List.of("DebtCurrent", "LongTermDebtCurrent",
                        "LongTermDebtAndCapitalLeaseObligationsCurrent"), USD, cutoff);
        var noncurrentDebt = latestReportedInstant(facts,
                List.of("LongTermDebtNoncurrent", "LongTermDebtAndCapitalLeaseObligations"), USD, cutoff);
        FactValue componentDebt = null;
        if (currentDebt != null && noncurrentDebt != null && currentDebt.end().equals(noncurrentDebt.end())
                && currentDebt.accession().equals(noncurrentDebt.accession())) {
            componentDebt = calculatedValue(currentDebt.value().add(noncurrentDebt.value()), USD, currentDebt.end(),
                    "INSTANT", currentDebt.tag() + "+" + noncurrentDebt.tag(), List.of(currentDebt, noncurrentDebt));
        }
        var debt = latestTotalDebt(directDebt, componentDebt);
        addDecimal(result, "fundamental.debt", debt, USD, null,
                debt == null ? "DebtLongtermAndShorttermCombinedAmount" : debt.tag());

        var annual = financial != null && financial.form().startsWith("10-K") ? financial : null;
        var eps = latestQuarterOrAnnualFact(facts, "EarningsPerShareDiluted", USD_PER_SHARE, cutoff);
        var dilutedShares = latestQuarterOrAnnualFact(facts,
                "WeightedAverageNumberOfDilutedSharesOutstanding", SHARES, cutoff);
        var epsValue = value(eps);
        var dilutedSharesValue = value(dilutedShares);
        addDecimal(result, "fundamental.eps", epsValue, "USD/share",
                eps == null ? null : eps.end(), "EarningsPerShareDiluted");
        addDecimal(result, "fundamental.dilutedShares", dilutedSharesValue, SHARES,
                dilutedShares == null ? null : dilutedShares.end(),
                "WeightedAverageNumberOfDilutedSharesOutstanding");
        addText(result, "fundamental.dilutedSharesBasis", dilutedSharesValue == null ? null
                        : "WEIGHTED_AVERAGE_" + dilutedSharesValue.period(),
                dilutedSharesValue == null ? asOf : dilutedSharesValue.end().atStartOfDay(ZoneOffset.UTC).toInstant(),
                dilutedSharesValue == null ? "DATA_NOT_PRESENT" : null,
                dilutedSharesValue == null ? null : factIdentifier(dilutedSharesValue));

        var deiBasicShares = latestReportedInstant(deiFacts,
                List.of("EntityCommonStockSharesOutstanding"), SHARES, cutoff);
        var gaapBasicShares = latestReportedInstant(facts,
                List.of("CommonStockSharesOutstanding"), SHARES, cutoff);
        var basicShares = newestBasicShares(deiBasicShares, gaapBasicShares);
        addDecimal(result, "fundamental.basicShares", basicShares, SHARES,
                basicShares == null ? null : basicShares.end(), "EntityCommonStockSharesOutstanding");
        addText(result, "fundamental.basicSharesBasis", basicShares == null ? null
                        : basicShares.tag() + "_INSTANT",
                basicShares == null ? asOf : basicShares.end().atStartOfDay(ZoneOffset.UTC).toInstant(),
                basicShares == null ? "DATA_NOT_PRESENT" : null,
                basicShares == null ? null : factIdentifier(basicShares));

        var revenue = financial == null ? null
                : flowTtm(facts, "RevenueFromContractWithCustomerExcludingAssessedTax", financial, cutoff);
        var operatingCashFlow = financial == null ? null
                : flowTtm(facts, "NetCashProvidedByUsedInOperatingActivities", financial, cutoff);
        var capex = financial == null ? null
                : flowTtm(facts, "PaymentsToAcquirePropertyPlantAndEquipment", financial, cutoff);
        addDecimal(result, "fundamental.revenueTTM", revenue, USD, fiscalEnd,
                "RevenueFromContractWithCustomerExcludingAssessedTax");
        var revenueGrowth = annual != null ? annualRevenueGrowth(facts, annual, cutoff)
                : financial == null ? null : quarterlyRevenueGrowth(facts, filings, financial, revenue, cutoff);
        addDecimal(result, "fundamental.revenueGrowthYoY", revenueGrowth, "ratio", fiscalEnd,
                "RevenueFromContractWithCustomerExcludingAssessedTax");
        var fcf = operatingCashFlow != null && capex != null
                ? calculatedValue(operatingCashFlow.value().subtract(capex.value()), USD, fiscalEnd, "TTM",
                "NetCashProvidedByUsedInOperatingActivities-PaymentsToAcquirePropertyPlantAndEquipment",
                List.of(operatingCashFlow, capex))
                : null;
        addDecimal(result, "fundamental.fcfTTM", fcf, USD, fiscalEnd,
                "NetCashProvidedByUsedInOperatingActivities-PaymentsToAcquirePropertyPlantAndEquipment");
        var ebitda = financial == null ? null : ebitdaTtm(facts, financial, cutoff);
        addDecimal(result, "fundamental.ebitdaTTM", ebitda, USD, fiscalEnd,
                ebitda == null ? null : ebitda.tag());
        if (ebitda == null) {
            addMissing(result, "fundamental.ebitdaTTMType", "DATA_NOT_PRESENT", asOf,
                    financial == null ? latest.accession() : financial.accession());
            addMissing(result, "fundamental.ebitdaTTMFormula", "DATA_NOT_PRESENT", asOf,
                    financial == null ? latest.accession() : financial.accession());
            addMissing(result, "fundamental.ebitdaTTMSource", "DATA_NOT_PRESENT", asOf,
                    financial == null ? latest.accession() : financial.accession());
        } else {
            addText(result, "fundamental.ebitdaTTMType", "CALCULATED",
                    ebitda.end().atStartOfDay(ZoneOffset.UTC).toInstant(), null, factIdentifier(ebitda));
            var formula = ebitda.concept() != null
                    && ebitda.concept().contains("+DEPRECIATION_PLUS_INTANGIBLE_AMORTIZATION")
                    ? "GAAP net income + income tax expense (benefit) + interest expense + depreciation, depletion and amortization; where no total is reported, aligned us-gaap:Depreciation + us-gaap:AmortizationOfIntangibleAssets; four contiguous quarters"
                    : "GAAP net income + income tax expense (benefit) + interest expense + depreciation, depletion and amortization; four contiguous quarters";
            addText(result, "fundamental.ebitdaTTMFormula", formula,
                    ebitda.end().atStartOfDay(ZoneOffset.UTC).toInstant(), null, factIdentifier(ebitda));
            addText(result, "fundamental.ebitdaTTMSource", ebitda.contextRef(),
                    ebitda.end().atStartOfDay(ZoneOffset.UTC).toInstant(), null, factIdentifier(ebitda));
        }
        var currency = currencyFrom(revenue != null ? revenue.unit()
                : cash != null ? cash.unit() : debt == null ? null : debt.unit());
        var currencyAsOf = revenue != null ? revenue.end()
                : cash != null ? cash.end() : debt == null ? fiscalEnd : debt.end();
        addText(result, "fundamental.currency", currency,
                currencyAsOf == null ? asOf : currencyAsOf.atStartOfDay(ZoneOffset.UTC).toInstant(),
                currency == null ? "DATA_NOT_PRESENT" : null,
                revenue != null ? factIdentifier(revenue) : cash != null ? factIdentifier(cash)
                        : debt == null ? null : factIdentifier(debt));

        if (inlineEnrichment.failureReason() != null) {
            result = withInlineFailureReason(result, inlineEnrichment.failureReason());
        }
        return List.copyOf(result);
    }

    private Map<String, Issuer> tickerMap() {
        var now = clock.instant();
        var cached = tickerMap;
        if (cached != null && cached.expiresAt().isAfter(now)) return cached.value();
        synchronized (cacheLock) {
            cached = tickerMap;
            now = clock.instant();
            if (cached == null || !cached.expiresAt().isAfter(now)) {
                try {
                    var root = objectMapper.readTree(transport.get(tickerFile));
                    if (root == null || !root.isObject()) throw unavailable("INVALID_RESPONSE");
                    var result = new HashMap<String, Issuer>();
                    root.properties().forEach(entry -> {
                        var row = entry.getValue();
                        var ticker = text(row.get("ticker"));
                        var title = text(row.get("title"));
                        var cik = number(row.get("cik_str"));
                        if (!ticker.isBlank() && !title.isBlank() && cik > 0) {
                            result.put(ticker.toUpperCase(java.util.Locale.ROOT), new Issuer(cik, title));
                        }
                    });
                    cached = new CacheEntry<>(Map.copyOf(result), clock.instant().plus(cacheTtl));
                    tickerMap = cached;
                } catch (JacksonException exception) {
                    throw unavailable("INVALID_RESPONSE");
                }
            }
            return cached.value();
        }
    }

    private IssuerData issuerData(Issuer issuer) {
        synchronized (cacheLock) {
            var cached = issuerDataCache.get(issuer.cik());
            var now = clock.instant();
            if (cached != null && cached.expiresAt().isAfter(now)) return cached.value();
            var cik = String.format(java.util.Locale.ROOT, "%010d", issuer.cik());
            try {
                var submissions = objectMapper.readTree(transport.get(uri("/submissions/CIK" + cik + ".json")));
                var companyFacts = objectMapper.readTree(transport.get(uri("/api/xbrl/companyfacts/CIK" + cik + ".json")));
                var value = new IssuerData(submissions, companyFacts);
                issuerDataCache.put(issuer.cik(), new CacheEntry<>(value, clock.instant().plus(cacheTtl)));
                return value;
            } catch (JacksonException exception) {
                throw unavailable("INVALID_RESPONSE");
            }
        }
    }

    private URI uri(String path) {
        return dataBase.resolve(path.startsWith("/") ? path : "/" + path);
    }

    private InlineEnrichment inlineFactsIfNeeded(JsonNode companyFacts, Issuer issuer, List<Filing> filings) {
        if (filings.isEmpty()) return new InlineEnrichment(companyFacts, null);
        var latest = filings.getFirst();
        var cutoff = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        var enriched = companyFacts;
        String failureReason = null;

        if (filingDocumentUri(issuer, latest) != null
                && (needsInlineFallback(enriched.path("facts"), latest, cutoff)
                || historicalTtmMissing(enriched.path("facts"), filings, cutoff))) {
            var inlineResult = inlineFactCache(issuer, latest);
            failureReason = firstFailure(failureReason, inlineResult.failureReason());
            enriched = mergeInlineFacts(enriched, latest, inlineResult);
        }

        var candidateFilings = boundedInlineFilings(filings, latest.reportDate());
        for (var filing : candidateFilings) {
            if (filing.accession().equals(latest.accession())
                    || filingDocumentUri(issuer, filing) == null
                    || !historicalFilingCanHelp(enriched.path("facts"), filings, filing, cutoff)) continue;
            var inlineResult = inlineFactCache(issuer, filing);
            failureReason = firstFailure(failureReason, inlineResult.failureReason());
            enriched = mergeInlineFacts(enriched, filing, inlineResult);
        }

        return new InlineEnrichment(enriched, failureReason);
    }

    private JsonNode mergeInlineFacts(JsonNode companyFacts, Filing filing, InlineFetchResult inlineResult) {
        if (inlineResult.facts().isEmpty()) return companyFacts;
        var root = (ObjectNode) companyFacts.deepCopy();
        if (!(root.get("facts") instanceof ObjectNode allFacts)) return companyFacts;
        for (var fact : inlineResult.facts()) {
            if (!fact.dimensions().isEmpty()) continue;
            if (hasCompanyFactForInlineKey(allFacts, fact)) continue;
            var namespace = fact.tag().equals("EntityCommonStockSharesOutstanding") ? "dei" : "us-gaap";
            var taxonomy = (ObjectNode) allFacts.get(namespace);
            if (taxonomy == null) {
                taxonomy = objectMapper.createObjectNode();
                allFacts.set(namespace, taxonomy);
            }
            var concept = taxonomy.get(fact.tag());
            if (!(concept instanceof ObjectNode)) {
                concept = objectMapper.createObjectNode();
                taxonomy.set(fact.tag(), concept);
            }
            var units = (ObjectNode) ((ObjectNode) concept).get("units");
            if (units == null) {
                units = objectMapper.createObjectNode();
                ((ObjectNode) concept).set("units", units);
            }
            var rows = units.get(fact.unit());
            if (!(rows instanceof tools.jackson.databind.node.ArrayNode)) {
                rows = objectMapper.createArrayNode();
                units.set(fact.unit(), rows);
            }
            var row = ((tools.jackson.databind.node.ArrayNode) rows).addObject();
            if (fact.start() != null) row.put("start", fact.start().toString());
            row.put("end", fact.end().toString());
            row.put("filed", fact.filed().toString());
            row.put("form", fact.form());
            row.put("accn", fact.accession());
            row.put("val", fact.value());
            row.put("fy", filing.fiscalYear() == null ? 0 : filing.fiscalYear());
            if (filing.fiscalPeriod() != null) row.put("fp", filing.fiscalPeriod());
            row.put("contextRef", fact.contextRef());
            row.put("concept", fact.concept());
        }
        return root;
    }

    private boolean historicalTtmMissing(JsonNode companyFacts, List<Filing> filings, LocalDate cutoff) {
        var facts = companyFacts.path(TAGS);
        var financial = filings.stream().filter(filing -> hasCoreFacts(facts, filing, cutoff)).findFirst().orElse(null);
        if (financial == null) return true;
        return ebitdaTtm(facts, financial, cutoff) == null
                || flowTtm(facts, "RevenueFromContractWithCustomerExcludingAssessedTax", financial, cutoff) == null;
    }

    private boolean historicalFilingCanHelp(JsonNode companyFacts, List<Filing> filings, Filing candidate,
                                            LocalDate cutoff) {
        var facts = companyFacts.path(TAGS);
        var financial = filings.stream().filter(filing -> hasCoreFacts(facts, filing, cutoff)).findFirst().orElse(null);
        if (financial == null) return true;
        if (ebitdaTtm(facts, financial, cutoff) == null) return true;
        return financial.form().startsWith("10-Q") && candidate.form().startsWith("10-K")
                && candidate.reportDate().isBefore(financial.reportDate())
                && flowTtm(facts, "RevenueFromContractWithCustomerExcludingAssessedTax", financial, cutoff) == null;
    }

    private List<Filing> boundedInlineFilings(List<Filing> filings, LocalDate latestPeriod) {
        if (latestPeriod == null) return List.of();
        var earliest = latestPeriod.minusDays(INLINE_FILING_LOOKBACK_DAYS);
        var periods = new HashSet<LocalDate>();
        var selected = new ArrayList<Filing>();
        for (var filing : filings) {
            if (filing.reportDate().isBefore(earliest) || filing.reportDate().isAfter(latestPeriod)
                    || !periods.add(filing.reportDate())) continue;
            selected.add(filing);
            if (selected.size() == MAX_INLINE_FILING_PERIODS) break;
        }
        return List.copyOf(selected);
    }

    private static String firstFailure(String current, String next) {
        return current == null ? next : current;
    }

    private boolean hasCompanyFactForInlineKey(JsonNode allFacts, Fact inlineFact) {
        var targetTag = canonicalTag(inlineFact.tag());
        if (targetTag == null || !allFacts.isObject()) return false;
        for (var namespace : allFacts.properties()) {
            if (!namespace.getValue().isObject()) continue;
            for (var concept : namespace.getValue().properties()) {
                if (!targetTag.equals(canonicalTag(concept.getKey()))) continue;
                var rows = concept.getValue().path("units").path(inlineFact.unit());
                if (!rows.isArray()) continue;
                for (var row : rows) {
                    if (inlineFact.accession().equals(text(row.path("accn")))
                            && java.util.Objects.equals(inlineFact.start(), optionalDate(row.path("start")))
                            && java.util.Objects.equals(inlineFact.end(), optionalDate(row.path("end")))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private InlineFetchResult inlineFactCache(Issuer issuer, Filing filing) {
        var key = issuer.cik() + ":" + filing.accession() + ":" + filing.primaryDocument();
        synchronized (cacheLock) {
            var cached = inlineFactCache.get(key);
            var now = clock.instant();
            if (cached != null && cached.expiresAt().isAfter(now)) return cached.value();
            var result = fetchInlineFacts(issuer, filing);
            inlineFactCache.put(key, new CacheEntry<>(result, now.plus(cacheTtl)));
            return result;
        }
    }

    private InlineFetchResult fetchInlineFacts(Issuer issuer, Filing filing) {
        var documentUri = filingDocumentUri(issuer, filing);
        if (documentUri == null) return new InlineFetchResult(List.of(), "INLINE_XBRL_REFERENCE_REJECTED");
        try {
            var document = transport.get(documentUri);
            if (document.length() > 12_000_000) {
                return new InlineFetchResult(List.of(), "INLINE_XBRL_RESPONSE_TOO_LARGE");
            }
            var parser = new SecInlineXbrlParser();
            var standardNames = standardInlineNames();
            var verifiedTaxonomy = new HashMap<QName, SecInlineXbrlParser.ConceptInfo>();
            var conflictedConcepts = new HashSet<QName>();
            var referenceRejected = false;
            var responseTooLarge = false;
            for (var schemaRef : parser.schemaReferences(document)) {
                var schemaUri = safeReference(documentUri, schemaRef);
                if (schemaUri == null) {
                    referenceRejected = true;
                    continue;
                }
                var schema = transport.get(schemaUri);
                if (schema.length() > 2_000_000) {
                    responseTooLarge = true;
                    continue;
                }
                for (var labelRef : parser.labelLinkbaseReferences(schema)) {
                    var labelUri = safeReference(schemaUri, labelRef);
                    if (labelUri == null) {
                        referenceRejected = true;
                        continue;
                    }
                    var labelLinkbase = transport.get(labelUri);
                    if (labelLinkbase.length() > 4_000_000) {
                        responseTooLarge = true;
                        continue;
                    }
                    parser.parseTaxonomy(schema, labelLinkbase).forEach((concept, details) -> {
                        var semantic = verifiedCustomSemantic(details.label(), details.definition());
                        if (semantic != null) {
                            var verified = new SecInlineXbrlParser.ConceptInfo(
                                    semantic, details.label(), details.definition(), true);
                            var prior = verifiedTaxonomy.putIfAbsent(concept, verified);
                            if (prior != null && !prior.equals(verified)) conflictedConcepts.add(concept);
                        }
                    });
                }
            }
            conflictedConcepts.forEach(verifiedTaxonomy::remove);
            var facts = parser.parse(document, filing.accession(), filing.filed(), filing.form(), issuer.cik(),
                            standardNames, verifiedTaxonomy).stream()
                    .filter(fact -> fact.dimensions().isEmpty())
                    .map(this::inlineFact)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            var failureReason = referenceRejected ? "INLINE_XBRL_REFERENCE_REJECTED"
                    : responseTooLarge ? "INLINE_XBRL_RESPONSE_TOO_LARGE"
                    : facts.isEmpty() ? "INLINE_XBRL_NO_VERIFIED_FACTS" : null;
            return new InlineFetchResult(facts, failureReason);
        } catch (ProviderUnavailableException exception) {
            return new InlineFetchResult(List.of(), inlineTransportFailureCode(exception));
        } catch (SecInlineXbrlParser.InlineXbrlParseException | IllegalArgumentException exception) {
            return new InlineFetchResult(List.of(), "INLINE_XBRL_PARSE_FAILED");
        }
    }

    private static String inlineTransportFailureCode(ProviderUnavailableException exception) {
        var code = exception.reasonCode();
        if (code.matches("HTTP_[0-9]{3}")) return "INLINE_XBRL_" + code;
        return switch (code) {
            case "NETWORK" -> "INLINE_XBRL_NETWORK_ERROR";
            case "EMPTY_RESPONSE" -> "INLINE_XBRL_EMPTY_RESPONSE";
            case "INTERRUPTED", "RATE_LIMITER_INTERRUPTED" -> "INLINE_XBRL_INTERRUPTED";
            case "CLIENT" -> "INLINE_XBRL_FETCH_ERROR";
            default -> "INLINE_XBRL_FETCH_FAILED";
        };
    }

    private static ArrayList<ProviderValue> withInlineFailureReason(List<ProviderValue> values, String reason) {
        var affected = Set.of("fundamental.cash", "fundamental.debt", "fundamental.basicShares",
                "fundamental.dilutedShares", "fundamental.dilutedSharesBasis", "fundamental.eps",
                "fundamental.revenueTTM", "fundamental.revenueGrowthYoY", "fundamental.fcfTTM",
                "fundamental.ebitdaTTM", "fundamental.ebitdaTTMType", "fundamental.ebitdaTTMFormula",
                "fundamental.ebitdaTTMSource", "fundamental.currency");
        var result = new ArrayList<ProviderValue>(values.size());
        for (var value : values) {
            if (affected.contains(value.field()) && value.value() == null
                    && value.missingData().contains("DATA_NOT_PRESENT")) {
                result.add(new ProviderValue(value.field(), null, value.unit(), value.period(), value.identifier(),
                        value.asOf(), List.of(reason), value.asOfBasis()));
            } else {
                result.add(value);
            }
        }
        return result;
    }

    private Fact inlineFact(SecInlineXbrlParser.InlineFact fact) {
        var tag = canonicalTag(fact.semantic());
        if (tag == null || fact.unit() == null || fact.value() == null || fact.end() == null) return null;
        return new Fact(tag, fact.value(), fact.unit(), fact.start(), fact.end(), fact.filed(), fact.form(),
                fact.accession(), null, null, fact.contextRef(), "{" + fact.namespace() + "}" + fact.localName(),
                List.of());
    }

    private URI filingDocumentUri(Issuer issuer, Filing filing) {
        var name = filing.primaryDocument();
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,180}") || name.contains("..")) return null;
        var accession = filing.accession().replace("-", "");
        if (!accession.matches("[0-9]{18}")) return null;
        return safeReference(archiveBase(),
                "/Archives/edgar/data/" + issuer.cik() + "/" + accession + "/" + name);
    }

    private URI archiveBase() {
        return isLocalTestHost(dataBase.getHost()) ? dataBase : SEC_ARCHIVES_BASE;
    }

    private URI safeReference(URI base, String reference) {
        try {
            var resolved = base.resolve(reference).normalize();
            var host = resolved.getHost();
            if (host == null || resolved.getUserInfo() != null) {
                return null;
            }
            var local = isLocalTestHost(dataBase.getHost());
            if (local) {
                if (!host.equalsIgnoreCase(dataBase.getHost()) || !isLocalTestHost(host)) return null;
            } else if (!Set.of("sec.gov", "www.sec.gov", "data.sec.gov").contains(host.toLowerCase(Locale.ROOT))
                    || !"https".equalsIgnoreCase(resolved.getScheme())) {
                return null;
            }
            if (resolved.getPath() == null || !resolved.getPath().startsWith("/")) return null;
            return resolved;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private boolean isLocalTestHost(String host) {
        return host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.startsWith("127."));
    }

    private boolean needsInlineFallback(JsonNode facts, Filing filing, LocalDate cutoff) {
        if (!facts.isObject()) return true;
        if (!hasAnyTag(facts.path(TAGS), "CashAndCashEquivalentsAtCarryingValue", USD)
                || !(hasAnyTag(facts.path(TAGS), "DebtLongtermAndShorttermCombinedAmount", USD)
                || (hasAnyTag(facts.path(TAGS), "DebtCurrent", USD)
                || hasAnyTag(facts.path(TAGS), "LongTermDebtCurrent", USD)
                || hasAnyTag(facts.path(TAGS), "LongTermDebtAndCapitalLeaseObligationsCurrent", USD))
                && (hasAnyTag(facts.path(TAGS), "LongTermDebtNoncurrent", USD)
                || hasAnyTag(facts.path(TAGS), "LongTermDebtAndCapitalLeaseObligations", USD)))
                || !hasAnyTag(facts.path(TAGS), "RevenueFromContractWithCustomerExcludingAssessedTax", USD)
                && !hasAnyTag(facts.path(TAGS), "Revenues", USD)
                && !hasAnyTag(facts.path(TAGS), "SalesRevenueNet", USD)
                || !hasAnyTag(facts.path(TAGS), "NetCashProvidedByUsedInOperatingActivities", USD)
                || !hasAnyTag(facts.path(TAGS), "PaymentsToAcquirePropertyPlantAndEquipment", USD)
                || !hasAnyTag(facts.path(TAGS), "NetIncomeLoss", USD)
                && !hasAnyTag(facts.path(TAGS), "ProfitLoss", USD)
                || !hasAnyTag(facts.path(TAGS), "IncomeTaxExpenseBenefit", USD)
                || !hasAnyTag(facts.path(TAGS), "InterestExpenseNonOperating", USD)
                && !hasAnyTag(facts.path(TAGS), "InterestExpenseNonoperating", USD)
                && !hasAnyTag(facts.path(TAGS), "InterestAndDebtExpense", USD)
                || !hasDepreciationAddbackFact(facts.path(TAGS))
                || !hasAnyTag(facts.path("dei"), "EntityCommonStockSharesOutstanding", SHARES)
                && !hasAnyTag(facts.path(TAGS), "CommonStockSharesOutstanding", SHARES)) return true;
        var eps = quarterFact(facts.path(TAGS), "EarningsPerShareDiluted", USD_PER_SHARE, filing, cutoff);
        var dilutedShares = quarterFact(facts.path(TAGS), "WeightedAverageNumberOfDilutedSharesOutstanding",
                SHARES, filing, cutoff);
        if (filing.form().startsWith("10-K")) {
            if (eps == null) eps = annualFact(facts.path(TAGS), "EarningsPerShareDiluted", USD_PER_SHARE,
                    filing, cutoff);
            if (dilutedShares == null) dilutedShares = annualFact(facts.path(TAGS),
                    "WeightedAverageNumberOfDilutedSharesOutstanding", SHARES, filing, cutoff);
        }
        return eps == null || dilutedShares == null || ebitdaTtm(facts.path(TAGS), filing, cutoff) == null;
    }

    private static boolean hasAnyTag(JsonNode namespace, String tag, String unit) {
        var units = namespace.path(tag).path("units");
        if (!units.isObject()) return false;
        if (units.path(unit).isArray() && !units.path(unit).isEmpty()) return true;
        if (USD_PER_SHARE.equals(unit)) {
            return List.of("USD / shares", "USD/share", "USD / share")
                    .stream().anyMatch(name -> units.path(name).isArray() && !units.path(name).isEmpty());
        }
        return false;
    }

    private static Set<String> standardInlineNames() {
        return Set.of(
                "CashAndCashEquivalentsAtCarryingValue", "DebtLongtermAndShorttermCombinedAmount",
                "DebtCurrent", "LongTermDebtNoncurrent", "LongTermDebtCurrent",
                "LongTermDebtAndCapitalLeaseObligationsCurrent", "LongTermDebtAndCapitalLeaseObligations",
                "RevenueFromContractWithCustomerExcludingAssessedTax", "Revenues",
                "SalesRevenueNet", "EarningsPerShareDiluted", "WeightedAverageNumberOfDilutedSharesOutstanding",
                "EntityCommonStockSharesOutstanding", "CommonStockSharesOutstanding",
                "NetCashProvidedByUsedInOperatingActivities", "PaymentsToAcquirePropertyPlantAndEquipment",
                "NetIncomeLoss", "ProfitLoss", "IncomeTaxExpenseBenefit", "InterestExpenseNonOperating",
                "InterestExpenseNonoperating", "InterestAndDebtExpense", "DepreciationDepletionAndAmortization",
                "DepreciationAndAmortization", "Depreciation", "AmortizationOfIntangibleAssets");
    }

    private static boolean hasDepreciationAddbackFact(JsonNode facts) {
        return hasAnyTag(facts, "DepreciationDepletionAndAmortization", USD)
                || hasAnyTag(facts, "DepreciationAndAmortization", USD)
                || hasAnyTag(facts, "Depreciation", USD)
                && hasAnyTag(facts, "AmortizationOfIntangibleAssets", USD);
    }

    private static String verifiedCustomSemantic(String label, String definition) {
        var normalizedLabel = normalizeTaxonomyText(label);
        var normalizedDefinition = normalizeTaxonomyText(definition);
        if (normalizedLabel.isBlank() || normalizedDefinition.isBlank()) return null;
        if (Set.of("revenue", "revenue from contracts with customers", "sales revenue")
                .contains(normalizedLabel)
                && (normalizedDefinition.contains("revenue from contracts with customers")
                || normalizedDefinition.contains("revenue recognized from contracts with customers"))) {
            return "RevenueFromContractWithCustomerExcludingAssessedTax";
        }
        if (normalizedLabel.equals("cash and cash equivalents")
                && normalizedDefinition.contains("cash and cash equivalents")) {
            return "CashAndCashEquivalentsAtCarryingValue";
        }
        if (Set.of("current portion of long term debt", "current maturities of long term debt")
                .contains(normalizedLabel)
                && normalizedDefinition.contains("current portion of long term debt")) return "DebtCurrent";
        if (normalizedLabel.equals("long term debt noncurrent")
                && normalizedDefinition.contains("long term debt due after one year")) return "LongTermDebtNoncurrent";
        if (normalizedLabel.equals("diluted earnings per share")
                && normalizedDefinition.contains("diluted earnings per share")) return "EarningsPerShareDiluted";
        if (normalizedLabel.equals("weighted average number of diluted shares outstanding")
                && normalizedDefinition.contains("weighted average number of shares")
                && normalizedDefinition.contains("diluted")) {
            return "WeightedAverageNumberOfDilutedSharesOutstanding";
        }
        if (normalizedLabel.equals("common shares outstanding")
                && normalizedDefinition.contains("number of shares of common stock outstanding")) {
            return "CommonStockSharesOutstanding";
        }
        if (normalizedLabel.equals("net cash provided by operating activities")
                && normalizedDefinition.contains("net cash provided by used in operating activities")) {
            return "NetCashProvidedByUsedInOperatingActivities";
        }
        if (normalizedLabel.equals("payments to acquire property plant and equipment")
                && normalizedDefinition.contains("payments to acquire property plant and equipment")) {
            return "PaymentsToAcquirePropertyPlantAndEquipment";
        }
        if (Set.of("net income", "net income loss", "net income (loss)").contains(normalizedLabel)
                && normalizedDefinition.contains("net income")) return "NetIncomeLoss";
        if (Set.of("income tax expense", "income tax expense benefit").contains(normalizedLabel)
                && normalizedDefinition.contains("income tax expense")) return "IncomeTaxExpenseBenefit";
        if (normalizedLabel.equals("interest expense") && normalizedDefinition.contains("interest expense")) {
            return "InterestExpenseNonOperating";
        }
        if (Set.of("depreciation depletion and amortization", "depreciation and amortization")
                .contains(normalizedLabel) && normalizedDefinition.contains("depreciation")) {
            return "DepreciationDepletionAndAmortization";
        }
        return null;
    }

    private static String normalizeTaxonomyText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String canonicalTag(String semantic) {
        return switch (semantic) {
            case "ProfitLoss" -> "NetIncomeLoss";
            case "InterestExpenseNonoperating", "InterestAndDebtExpense" -> "InterestExpenseNonOperating";
            case "DepreciationAndAmortization" -> "DepreciationDepletionAndAmortization";
            case "Depreciation", "AmortizationOfIntangibleAssets" -> semantic;
            case "Revenues", "SalesRevenueNet" -> "RevenueFromContractWithCustomerExcludingAssessedTax";
            case "LongTermDebtCurrent", "LongTermDebtAndCapitalLeaseObligationsCurrent" -> "DebtCurrent";
            case "EntityCommonStockSharesOutstanding", "CommonStockSharesOutstanding",
                    "CashAndCashEquivalentsAtCarryingValue", "DebtLongtermAndShorttermCombinedAmount",
                    "DebtCurrent", "LongTermDebtNoncurrent",
                    "LongTermDebtAndCapitalLeaseObligations", "RevenueFromContractWithCustomerExcludingAssessedTax",
                    "EarningsPerShareDiluted", "WeightedAverageNumberOfDilutedSharesOutstanding",
                    "NetCashProvidedByUsedInOperatingActivities", "PaymentsToAcquirePropertyPlantAndEquipment",
                    "NetIncomeLoss", "IncomeTaxExpenseBenefit", "InterestExpenseNonOperating",
                    "DepreciationDepletionAndAmortization" -> semantic;
            default -> null;
        };
    }

    private FactValue ebitdaTtm(JsonNode facts, Filing filing, LocalDate cutoff) {
        var income = standaloneQuarters(facts, "NetIncomeLoss", cutoff);
        var taxes = standaloneQuarters(facts, "IncomeTaxExpenseBenefit", cutoff);
        var interest = standaloneQuarters(facts, "InterestExpenseNonOperating", cutoff);
        var depreciation = depreciationQuarters(facts, cutoff);
        var quarterEnds = income.stream().map(Fact::end).distinct().sorted(Comparator.reverseOrder()).toList();
        var quarterGroups = new ArrayList<List<Fact>>();
        for (var end : quarterEnds) {
            var netIncome = findQuarter(income, end);
            var tax = findQuarter(taxes, end);
            var interestExpense = findQuarter(interest, end);
            var da = findQuarter(depreciation, end);
            if (netIncome == null || tax == null || interestExpense == null || da == null) continue;
            if (netIncome.start() == null || !netIncome.start().equals(tax.start())
                    || !netIncome.start().equals(interestExpense.start()) || !netIncome.start().equals(da.start())
                    || !netIncome.end().equals(tax.end()) || !netIncome.end().equals(interestExpense.end())
                    || !netIncome.end().equals(da.end()) || interestExpense.value().signum() < 0
                    || da.value().signum() < 0) continue;
            quarterGroups.add(List.of(netIncome, tax, interestExpense, da));
            if (quarterGroups.size() == 4) break;
        }
        if (quarterGroups.size() != 4) return null;
        var quarters = quarterGroups.stream()
                .map(group -> new PeriodKey(group.getFirst().start(), group.getFirst().end())).toList();
        if (quarters.size() != 4 || !quarters.getFirst().end().equals(filing.reportDate())) return null;
        var ascending = quarters.stream().sorted(Comparator.comparing(PeriodKey::start)).toList();
        for (int index = 1; index < ascending.size(); index++) {
            if (!ascending.get(index - 1).end().plusDays(1).equals(ascending.get(index).start())) return null;
        }
        var total = BigDecimal.ZERO;
        var sourceFacts = new ArrayList<String>();
        for (var quarter : ascending) {
            var ni = findQuarter(income, quarter.end());
            var tax = findQuarter(taxes, quarter.end());
            var interestExpense = findQuarter(interest, quarter.end());
            var da = findQuarter(depreciation, quarter.end());
            if (ni == null || tax == null || interestExpense == null || da == null
                    || !quarter.start().equals(ni.start()) || !quarter.start().equals(tax.start())
                    || !quarter.start().equals(interestExpense.start()) || !quarter.start().equals(da.start())) {
                return null;
            }
            total = total.add(ni.value()).add(tax.value()).add(interestExpense.value()).add(da.value());
            sourceFacts.add(factIdentifier(value(ni)));
            sourceFacts.add(factIdentifier(value(tax)));
            sourceFacts.add(factIdentifier(value(interestExpense)));
            sourceFacts.add(factIdentifier(value(da)));
        }
        var end = ascending.getLast().end();
        var start = ascending.getFirst().start();
        var source = String.join(" | ", sourceFacts);
        var usesComponentDa = quarterGroups.stream().anyMatch(group ->
                group.get(3).concept() != null
                        && group.get(3).concept().contains("AmortizationOfIntangibleAssets"));
        return new FactValue(total, USD, end, filing.accession(), "TTM", "GAAP_NET_INCOME_PLUS_ADDBACKS",
                start, filing.filed(), filing.form(), source,
                "GAAP_NET_INCOME_PLUS_TAX_PLUS_INTEREST_PLUS_DEPRECIATION_AMORTIZATION"
                        + (usesComponentDa ? "+DEPRECIATION_PLUS_INTANGIBLE_AMORTIZATION" : ""));
    }

    private List<Fact> depreciationQuarters(JsonNode facts, LocalDate cutoff) {
        var direct = standaloneQuarters(facts, "DepreciationDepletionAndAmortization", cutoff);
        var depreciation = standaloneQuarters(facts, "Depreciation", cutoff);
        var amortization = standaloneQuarters(facts, "AmortizationOfIntangibleAssets", cutoff);
        var ends = new java.util.TreeSet<LocalDate>();
        direct.stream().map(Fact::end).forEach(ends::add);
        depreciation.stream().map(Fact::end).forEach(ends::add);
        amortization.stream().map(Fact::end).forEach(ends::add);
        var quarters = new ArrayList<Fact>();
        for (var end : ends) {
            var reportedTotal = findQuarter(direct, end);
            var hasReportedTotal = direct.stream().anyMatch(row -> row.end().equals(end));
            if (reportedTotal != null) {
                quarters.add(reportedTotal);
                continue;
            }
            if (hasReportedTotal) continue;
            var tangibleDepreciation = findQuarter(depreciation, end);
            var intangibleAmortization = findQuarter(amortization, end);
            if (!samePeriod(tangibleDepreciation, intangibleAmortization)
                    || tangibleDepreciation.value().signum() < 0
                    || intangibleAmortization.value().signum() < 0) continue;
            var componentIds = factIdentifier(value(tangibleDepreciation)) + " | "
                    + factIdentifier(value(intangibleAmortization));
            var components = new Fact("DepreciationDepletionAndAmortization",
                    tangibleDepreciation.value().add(intangibleAmortization.value()), USD,
                    tangibleDepreciation.start(), tangibleDepreciation.end(), tangibleDepreciation.filed(),
                    tangibleDepreciation.form(), tangibleDepreciation.accession(),
                    tangibleDepreciation.fiscalYear(), tangibleDepreciation.fiscalPeriod(), componentIds,
                    tangibleDepreciation.concept() + "+" + intangibleAmortization.concept(), List.of());
            quarters.add(components);
        }
        return List.copyOf(quarters);
    }

    private List<Fact> standaloneQuarters(JsonNode facts, String tag, LocalDate cutoff) {
        var rows = factRows(facts, tag, USD, cutoff);
        var quarters = new ArrayList<Fact>();
        for (var row : rows) {
            var days = duration(row);
            if (days >= 70 && days <= 105 && row.form().startsWith("10-Q")) quarters.add(row);
            if (days < 106 || days > 285 || !row.form().startsWith("10-Q")) continue;
            var previousYtd = rows.stream().filter(previous -> previous.start() != null
                            && previous.end() != null && previous.start().equals(row.start())
                            && previous.end().isBefore(row.end()) && !previous.filed().isAfter(row.filed())
                            && previous.form().startsWith("10-Q")
                            && duration(previous) >= 1 && duration(previous) < days)
                    .filter(previous -> nearDays(previous.end(), row.end(), 70, 105))
                    .max(Comparator.comparing(Fact::end).thenComparing(Fact::filed)).orElse(null);
            if (previousYtd != null) {
                var start = previousYtd.end().plusDays(1);
                var quarterDuration = ChronoUnit.DAYS.between(start, row.end()) + 1;
                if (quarterDuration >= 70 && quarterDuration <= 105) {
                    quarters.add(new Fact(tag, row.value().subtract(previousYtd.value()), USD, start, row.end(),
                            row.filed(), row.form(), row.accession(), row.fiscalYear(), row.fiscalPeriod(),
                            row.contextRef() + "-" + previousYtd.contextRef(),
                            row.concept() + "-" + previousYtd.concept(), List.of()));
                }
            }
        }
        for (var annual : rows.stream().filter(row -> row.form().startsWith("10-K")
                && duration(row) >= 330 && duration(row) <= 371).toList()) {
            var priorYtd = rows.stream().filter(row -> row.form().startsWith("10-Q") && row.start() != null
                            && row.end() != null && row.start().equals(annual.start())
                            && nearDays(row.end(), annual.end(), 70, 105)
                            && !row.filed().isAfter(annual.filed())
                            && duration(row) >= 200 && duration(row) <= 300)
                    .max(Comparator.comparing(Fact::filed)).orElse(null);
            if (priorYtd == null) continue;
            var start = priorYtd.end().plusDays(1);
            var quarterDuration = ChronoUnit.DAYS.between(start, annual.end()) + 1;
            if (quarterDuration < 70 || quarterDuration > 105) continue;
            quarters.add(new Fact(tag, annual.value().subtract(priorYtd.value()), USD, start, annual.end(),
                    annual.filed(), annual.form(), annual.accession(), annual.fiscalYear(), annual.fiscalPeriod(),
                    annual.contextRef() + "-" + priorYtd.contextRef(),
                    annual.concept() + "-" + priorYtd.concept(), List.of()));
        }
        var byPeriod = new HashMap<PeriodKey, List<Fact>>();
        quarters.forEach(row -> byPeriod.computeIfAbsent(new PeriodKey(row.start(), row.end()), ignored -> new ArrayList<>())
                .add(row));
        var result = new ArrayList<Fact>();
        byPeriod.forEach((period, versions) -> {
            var latestFiled = versions.stream().map(Fact::filed).max(LocalDate::compareTo).orElse(null);
            if (latestFiled == null) return;
            var latest = versions.stream().filter(row -> row.filed().equals(latestFiled)).toList();
            var unique = uniqueFact(latest);
            if (unique != null) result.add(unique);
        });
        return result.stream().sorted(Comparator.comparing(Fact::end).reversed()).toList();
    }

    private static Fact findQuarter(List<Fact> quarters, LocalDate end) {
        var matches = quarters.stream().filter(row -> row.end().equals(end)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private List<Filing> eligibleFilings(JsonNode recent, Instant observedAt) {
        if (!recent.isObject()) return List.of();
        var forms = recent.path("form");
        var accessions = recent.path("accessionNumber");
        var filed = recent.path("filingDate");
        var reportDates = recent.path("reportDate");
        var acceptances = recent.path("acceptanceDateTime");
        var fiscalYears = recent.path("fy");
        var fiscalPeriods = recent.path("fp");
        var primaryDocuments = recent.path("primaryDocument");
        if (!forms.isArray() || !accessions.isArray() || !filed.isArray()) return List.of();
        var filings = new ArrayList<Filing>();
        for (var index = 0; index < forms.size(); index++) {
            var form = text(forms.get(index));
            if (!Set.of("10-K", "10-K/A", "10-Q", "10-Q/A").contains(form)) continue;
            try {
                var filingDate = LocalDate.parse(text(filed.get(index)));
                var reportDate = optionalDate(reportDates.path(index));
                var acceptance = optionalInstant(acceptances.path(index));
                var cutoffDate = observedAt.atZone(ZoneOffset.UTC).toLocalDate();
                if (filingDate.isAfter(cutoffDate) || (reportDate != null && reportDate.isAfter(cutoffDate))
                        || (acceptance != null && acceptance.isAfter(observedAt))) continue;
                var primaryDocument = primaryDocuments.isArray() ? blankNull(text(primaryDocuments.path(index))) : null;
                var candidate = new Filing(form, text(accessions.path(index)), filingDate, reportDate, acceptance,
                        optionalInt(fiscalYears.path(index)), blankNull(text(fiscalPeriods.path(index))), primaryDocument);
                if (candidate.accession().isBlank() || candidate.reportDate() == null) continue;
                filings.add(candidate);
            } catch (RuntimeException ignored) {
                // Ignore malformed submission rows; other rows may still be usable.
            }
        }
        return filings.stream().sorted((left, right) -> compareFiling(right, left)).toList();
    }

    private boolean hasCoreFacts(JsonNode facts, Filing filing, LocalDate cutoff) {
        var tags = List.of(
                new TagUnit("CashAndCashEquivalentsAtCarryingValue", USD),
                new TagUnit("DebtLongtermAndShorttermCombinedAmount", USD),
                new TagUnit("DebtCurrent", USD),
                new TagUnit("LongTermDebtNoncurrent", USD),
                new TagUnit("LongTermDebtAndCapitalLeaseObligations", USD),
                new TagUnit("RevenueFromContractWithCustomerExcludingAssessedTax", USD),
                new TagUnit("EarningsPerShareDiluted", USD_PER_SHARE),
                new TagUnit("WeightedAverageNumberOfDilutedSharesOutstanding", SHARES),
                new TagUnit("NetCashProvidedByUsedInOperatingActivities", USD),
                new TagUnit("PaymentsToAcquirePropertyPlantAndEquipment", USD));
        return tags.stream().anyMatch(tag -> factRows(facts, tag.tag(), tag.unit(), cutoff).stream()
                .anyMatch(row -> row.accession().equals(filing.accession())
                        && row.end().equals(filing.reportDate())
                        && row.form().equals(filing.form())));
    }

    private static int compareFiling(Filing left, Filing right) {
        var dateOrder = Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder())
                .compare(left.reportDate(), right.reportDate());
        return dateOrder != 0 ? dateOrder : left.orderingTime().compareTo(right.orderingTime());
    }

    private static boolean containsTicker(JsonNode tickers, String requested) {
        if (!tickers.isArray()) return false;
        return java.util.stream.StreamSupport.stream(tickers.spliterator(), false)
                .map(SecCompanyFactsProvider::text)
                .anyMatch(ticker -> ticker.equalsIgnoreCase(requested));
    }

    private FactValue latestInstant(JsonNode facts, List<String> tags, String unit, Filing filing,
                                    LocalDate end, LocalDate cutoff) {
        if (end == null) return null;
        for (var tag : tags) {
            var rows = factRows(facts, tag, unit, cutoff);
            var match = uniqueFact(rows.stream().filter(row -> row.start() == null && end.equals(row.end())
                    && filing.accession().equals(row.accession())).toList());
            if (match != null) return value(match);
        }
        return null;
    }

    private Fact latestPeriodFacts(JsonNode facts, Filing filing, LocalDate end, LocalDate cutoff) {
        if (end == null) return null;
        var rows = factRows(facts, "RevenueFromContractWithCustomerExcludingAssessedTax", USD, cutoff).stream()
                .filter(row -> row.accession().equals(filing.accession()) && end.equals(row.end())
                        && row.form().equals(filing.form())).toList();
        if (filing.form().startsWith("10-K")) {
            return uniqueFact(rows.stream().filter(row -> duration(row) >= 330 && duration(row) <= 371).toList());
        }
        var maxDuration = rows.stream().mapToLong(SecCompanyFactsProvider::duration).max().orElse(-1);
        return uniqueFact(rows.stream().filter(row -> duration(row) == maxDuration).toList());
    }

    private Fact annualFact(JsonNode facts, String tag, String unit, Filing annual, LocalDate cutoff) {
        if (annual == null) return null;
        return uniqueFact(factRows(facts, tag, unit, cutoff).stream()
                .filter(row -> row.accession().equals(annual.accession()) && row.form().startsWith("10-K")
                        && row.start() != null && row.end().equals(annual.reportDate())
                        && ChronoUnit.DAYS.between(row.start(), row.end()) >= 330
                        && ChronoUnit.DAYS.between(row.start(), row.end()) <= 371).toList());
    }

    private Fact quarterFact(JsonNode facts, String tag, String unit, Filing filing, LocalDate cutoff) {
        if (filing.reportDate() == null) return null;
        return uniqueFact(factRows(facts, tag, unit, cutoff).stream()
                .filter(row -> row.accession().equals(filing.accession())
                        && row.form().equals(filing.form()) && row.end().equals(filing.reportDate())
                        && row.start() != null && duration(row) >= 70 && duration(row) <= 105).toList());
    }

    private Fact latestQuarterFact(JsonNode facts, String tag, String unit, LocalDate cutoff) {
        var rows = factRows(facts, tag, unit, cutoff).stream()
                .filter(row -> row.start() != null && duration(row) >= 70 && duration(row) <= 105).toList();
        var latestEnd = rows.stream().map(Fact::end).max(LocalDate::compareTo).orElse(null);
        if (latestEnd == null) return null;
        var latestFiled = rows.stream().filter(row -> row.end().equals(latestEnd))
                .map(Fact::filed).max(LocalDate::compareTo).orElse(null);
        if (latestFiled == null) return null;
        var latest = rows.stream().filter(row -> row.end().equals(latestEnd) && row.filed().equals(latestFiled))
                .toList();
        if (latest.stream().map(Fact::accession).distinct().count() != 1) return null;
        return uniqueFact(latest);
    }

    private Fact latestAnnualFact(JsonNode facts, String tag, String unit, LocalDate cutoff) {
        var rows = factRows(facts, tag, unit, cutoff).stream()
                .filter(row -> row.start() != null && duration(row) >= 330 && duration(row) <= 371).toList();
        var latestEnd = rows.stream().map(Fact::end).max(LocalDate::compareTo).orElse(null);
        if (latestEnd == null) return null;
        var latestFiled = rows.stream().filter(row -> row.end().equals(latestEnd))
                .map(Fact::filed).max(LocalDate::compareTo).orElse(null);
        if (latestFiled == null) return null;
        var latest = rows.stream().filter(row -> row.end().equals(latestEnd) && row.filed().equals(latestFiled))
                .toList();
        if (latest.stream().map(Fact::accession).distinct().count() != 1) return null;
        return uniqueFact(latest);
    }

    private Fact latestQuarterOrAnnualFact(JsonNode facts, String tag, String unit, LocalDate cutoff) {
        var quarter = latestQuarterFact(facts, tag, unit, cutoff);
        var annual = latestAnnualFact(facts, tag, unit, cutoff);
        if (quarter == null) return annual;
        if (annual == null || !annual.end().isAfter(quarter.end())) return quarter;
        return annual;
    }

    private FactValue latestReportedInstant(JsonNode facts, List<String> tags, String unit, LocalDate cutoff) {
        var rows = tags.stream().flatMap(tag -> allRows(facts, tag, unit, cutoff).stream())
                .filter(row -> row.start() == null && row.end() != null && !row.end().isAfter(cutoff)).toList();
        var latestEnd = rows.stream().map(Fact::end).max(LocalDate::compareTo).orElse(null);
        if (latestEnd == null) return null;
        var latestFiled = rows.stream().filter(row -> row.end().equals(latestEnd))
                .map(Fact::filed).max(LocalDate::compareTo).orElse(null);
        if (latestFiled == null) return null;
        var latest = rows.stream().filter(row -> row.end().equals(latestEnd) && row.filed().equals(latestFiled))
                .toList();
        for (var tag : tags) {
            var matches = latest.stream().filter(row -> row.tag().equals(tag)).toList();
            if (!matches.isEmpty()) {
                var selected = uniqueFact(matches);
                return selected == null ? null : value(selected);
            }
        }
        return null;
    }

    private static FactValue latestTotalDebt(FactValue direct, FactValue components) {
        if (direct == null) return components;
        if (components == null || !components.end().isAfter(direct.end())) return direct;
        return components;
    }

    private static FactValue newestBasicShares(FactValue dei, FactValue gaap) {
        if (dei == null) return gaap;
        if (gaap == null || !gaap.end().isAfter(dei.end())) return dei;
        return gaap;
    }

    private static boolean samePeriod(Fact left, Fact right) {
        return left != null && right != null && left.end().equals(right.end())
                && left.start().equals(right.start()) && left.unit().equals(right.unit())
                && left.accession().equals(right.accession()) && left.form().equals(right.form());
    }

    private FactValue flowTtm(JsonNode facts, String tag, Filing current, LocalDate cutoff) {
        if (current.reportDate() == null) return null;
        if (current.form().startsWith("10-K")) {
            var annual = uniqueFact(factRows(facts, tag, USD, cutoff).stream()
                    .filter(row -> row.accession().equals(current.accession()) && row.form().startsWith("10-K")
                            && row.end().equals(current.reportDate()) && row.start() != null
                            && duration(row) >= 330 && duration(row) <= 371).toList());
            return annual == null ? null : calculatedValue(annual.value(), USD, current.reportDate(), "TTM", tag,
                    List.of(value(annual)));
        }

        var currentRows = factRows(facts, tag, USD, cutoff).stream()
                .filter(row -> row.accession().equals(current.accession()) && row.form().startsWith("10-Q")
                        && row.end().equals(current.reportDate()) && row.start() != null
                        && duration(row) >= 1 && duration(row) < 371).toList();
        var maxDuration = currentRows.stream().mapToLong(SecCompanyFactsProvider::duration).max().orElse(-1);
        var ytd = uniqueFact(currentRows.stream().filter(row -> duration(row) == maxDuration).toList());
        if (ytd == null || duration(ytd) < 70) return null;

        var priorYtd = uniqueFact(factRows(facts, tag, USD, cutoff).stream()
                .filter(row -> row.accession().equals(current.accession()) && row.start() != null
                        && row.end() != null && row.form().startsWith("10-Q")
                        && nearDays(row.start(), ytd.start(), 364, 371)
                        && nearDays(row.end(), ytd.end(), 364, 371)
                        && Math.abs(duration(row) - duration(ytd)) <= 7).toList());
        if (priorYtd == null) return null;

        var annual = latestVersion(factRows(facts, tag, USD, cutoff).stream()
                .filter(row -> row.form().startsWith("10-K") && row.start() != null && row.end() != null
                        && row.end().plusDays(1).equals(ytd.start())
                        && row.start().equals(priorYtd.start())
                        && !row.filed().isAfter(current.filed())).toList());
        if (annual == null) return null;
        var result = annual.value().add(ytd.value()).subtract(priorYtd.value());
        return calculatedValue(result, USD, current.reportDate(), "TTM", tag,
                List.of(value(annual), value(ytd), value(priorYtd)));
    }

    private FactValue annualRevenueGrowth(JsonNode facts, Filing annual, LocalDate cutoff) {
        var tag = "RevenueFromContractWithCustomerExcludingAssessedTax";
        var current = uniqueFact(factRows(facts, tag, USD, cutoff).stream()
                .filter(row -> row.accession().equals(annual.accession()) && row.form().startsWith("10-K")
                        && row.start() != null && row.end().equals(annual.reportDate())
                        && duration(row) >= 330 && duration(row) <= 371).toList());
        if (current == null) return null;
        var prior = latestVersion(factRows(facts, tag, USD, cutoff).stream()
                .filter(row -> row.form().startsWith("10-K") && row.start() != null && row.end() != null
                        && row.end().plusDays(1).equals(current.start())
                        && Math.abs(duration(row) - duration(current)) <= 7
                        && !row.filed().isAfter(annual.filed())).toList());
        if (prior == null || prior.value().signum() <= 0) return null;
        var growth = current.value().subtract(prior.value())
                .divide(prior.value(), java.math.MathContext.DECIMAL128);
        return calculatedValue(growth, "ratio", current.end(), "FY", tag,
                List.of(value(current), value(prior)));
    }

    private FactValue quarterlyRevenueGrowth(JsonNode facts, List<Filing> filings, Filing current,
                                             FactValue currentRevenue, LocalDate cutoff) {
        if (currentRevenue == null) return null;
        var tag = "RevenueFromContractWithCustomerExcludingAssessedTax";
        for (var prior : filings) {
            if (!prior.form().startsWith("10-Q") || prior.reportDate() == null
                    || !nearDays(prior.reportDate(), current.reportDate(), 364, 371)
                    || prior.filed().isAfter(current.filed())
                    || prior.orderingTime().isAfter(current.orderingTime())
                    || (prior.fiscalPeriod() != null && current.fiscalPeriod() != null
                    && !prior.fiscalPeriod().equalsIgnoreCase(current.fiscalPeriod()))
                    || !hasCoreFacts(facts, prior, cutoff)) continue;
            var priorRevenue = flowTtm(facts, tag, prior, cutoff);
            if (priorRevenue == null) continue;
            if (priorRevenue.value().signum() <= 0) return null;
            var growth = currentRevenue.value().subtract(priorRevenue.value())
                    .divide(priorRevenue.value(), java.math.MathContext.DECIMAL128);
            return calculatedValue(growth, "ratio", current.reportDate(), "TTM", tag,
                    List.of(currentRevenue, priorRevenue));
        }
        return null;
    }

    private List<Fact> factRows(JsonNode facts, String tag, String unit, LocalDate cutoff) {
        var rows = new ArrayList<Fact>();
        var coveredPeriods = new HashSet<FactRowKey>();
        for (var alias : tagAliases(tag)) {
            var candidates = allRows(facts, alias, unit, cutoff).stream()
                    .filter(row -> row.end() != null).toList();
            for (var row : candidates) {
                if (!coveredPeriods.contains(FactRowKey.of(row))) rows.add(row);
            }
            candidates.stream().map(FactRowKey::of).forEach(coveredPeriods::add);
        }
        return rows.stream().filter(row -> row.end() != null).toList();
    }

    private static List<String> tagAliases(String tag) {
        return switch (tag) {
            case "CashAndCashEquivalentsAtCarryingValue" -> List.of(tag);
            case "RevenueFromContractWithCustomerExcludingAssessedTax" ->
                    List.of(tag, "Revenues", "SalesRevenueNet");
            case "DebtCurrent" ->
                    List.of(tag, "LongTermDebtCurrent");
            case "DebtLongtermAndShorttermCombinedAmount" -> List.of(tag);
            case "LongTermDebtNoncurrent" ->
                    List.of(tag, "LongTermDebtAndCapitalLeaseObligations");
            case "NetIncomeLoss" -> List.of(tag, "ProfitLoss");
            case "InterestExpenseNonOperating" ->
                    List.of(tag, "InterestExpenseNonoperating", "InterestAndDebtExpense");
            case "DepreciationDepletionAndAmortization" -> List.of(tag, "DepreciationAndAmortization");
            case "CommonStockSharesOutstanding" -> List.of(tag);
            default -> List.of(tag);
        };
    }

    private List<Fact> allRows(JsonNode facts, String tag, String unit, LocalDate cutoff) {
        var fact = facts.path(tag);
        if (!fact.isObject() || !fact.path("units").isObject()) return List.of();
        var units = fact.path("units");
        var rows = new ArrayList<Fact>();
        var unitNames = unit.equals(USD_PER_SHARE) ? List.of(unit, "USD / shares", "USD/share", "USD / share")
                : List.of(unit);
        for (var unitName : unitNames) {
            var values = units.path(unitName);
            if (!values.isArray()) continue;
            for (var row : values) {
            var start = optionalDate(row.path("start"));
            var end = optionalDate(row.path("end"));
            var filed = optionalDate(row.path("filed"));
            var form = text(row.path("form"));
            var accession = text(row.path("accn"));
            var number = decimal(row.get("val"));
            if (filed == null || filed.isAfter(cutoff) || form.isBlank() || accession.isBlank() || number == null) continue;
            rows.add(new Fact(tag, number, unit, start, end, filed, form, accession,
                    optionalInt(row.path("fy")), blankNull(text(row.path("fp"))),
                    blankNull(text(row.path("contextRef"))),
                    blankNull(text(row.path("concept"))) == null
                            ? "{" + (tag.equals("EntityCommonStockSharesOutstanding") ? "dei" : TAGS) + "}" + tag
                            : text(row.path("concept")), List.of()));
            }
        }
        return List.copyOf(rows);
    }

    private void addDecimal(List<ProviderValue> values, String field, FactValue value,
                            String unit, LocalDate asOf, String identifier) {
        if (value == null) {
            addMissing(values, field, "DATA_NOT_PRESENT", asOf == null ? null
                    : asOf.atStartOfDay(ZoneOffset.UTC).toInstant(), identifier);
            return;
        }
        var instant = value.end() == null ? null : value.end().atStartOfDay(ZoneOffset.UTC).toInstant();
        values.add(new ProviderValue(field, objectMapper.valueToTree(value.value()), unit, value.period(),
                factIdentifier(value), instant,
                List.of(), StockAnalysisInput.AsOfBasis.SOURCE_AS_OF));
    }

    private void addText(List<ProviderValue> values, String field, String text, Instant asOf,
                         String missing, String identifier) {
        if (text == null || text.isBlank()) {
            addMissing(values, field, missing == null ? "DATA_NOT_PRESENT" : missing, asOf, identifier);
        } else {
            values.add(new ProviderValue(field, objectMapper.valueToTree(text), null, null, identifier,
                    asOf, List.of(), StockAnalysisInput.AsOfBasis.SOURCE_AS_OF));
        }
    }

    private void addMissing(List<ProviderValue> values, String field, String missing, Instant asOf, String identifier) {
        values.add(new ProviderValue(field, null, null, null, identifier, asOf,
                List.of(missing == null ? "DATA_NOT_PRESENT" : missing), StockAnalysisInput.AsOfBasis.SOURCE_AS_OF));
    }

    private void addMissing(List<ProviderValue> values, String field, String missing, LocalDate asOf, String identifier) {
        addMissing(values, field, missing, asOf == null ? null : asOf.atStartOfDay(ZoneOffset.UTC).toInstant(), identifier);
    }

    private static FactValue value(Fact fact) {
        if (fact == null) return null;
        var duration = duration(fact);
        var period = fact.start() == null ? "INSTANT"
                : duration >= 330 && duration <= 371 ? "FY"
                : duration >= 70 && duration <= 105 ? "Q" : "YTD";
        return new FactValue(fact.value(), fact.unit(), fact.end(), fact.accession(), period, fact.tag(),
                fact.start(), fact.filed(), fact.form(), fact.contextRef(), fact.concept());
    }

    private static String factIdentifier(FactValue value) {
        return value.concept() + "@" + value.accession()
                + ";start=" + (value.start() == null ? "instant" : value.start())
                + ";end=" + value.end()
                + ";filed=" + value.filed()
                + ";form=" + value.form()
                + (value.contextRef() == null ? "" : ";context=" + value.contextRef());
    }

    private static FactValue calculatedValue(BigDecimal value, String unit, LocalDate end, String period, String tag,
                                             List<FactValue> sources) {
        var start = sources.stream().map(FactValue::start).filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo).orElse(null);
        var filed = sources.stream().map(FactValue::filed).filter(java.util.Objects::nonNull)
                .max(LocalDate::compareTo).orElse(null);
        var accession = sources.stream().map(FactValue::accession).filter(java.util.Objects::nonNull)
                .distinct().collect(java.util.stream.Collectors.joining(","));
        var form = sources.stream().map(FactValue::form).filter(java.util.Objects::nonNull)
                .distinct().collect(java.util.stream.Collectors.joining(","));
        var context = sources.stream().map(SecCompanyFactsProvider::factIdentifier)
                .collect(java.util.stream.Collectors.joining(" | "));
        var concept = sources.stream().map(FactValue::concept).filter(java.util.Objects::nonNull)
                .distinct().collect(java.util.stream.Collectors.joining("+"));
        return new FactValue(value, unit, end, accession, period, tag, start, filed, form, context, concept);
    }

    private static String currencyFrom(String unit) {
        if (unit == null) return null;
        try {
            return java.util.Currency.getInstance(unit).getCurrencyCode();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static long duration(Fact fact) {
        return fact.start() == null || fact.end() == null ? -1
                : ChronoUnit.DAYS.between(fact.start(), fact.end()) + 1;
    }

    private static boolean nearDays(LocalDate left, LocalDate right, long minimum, long maximum) {
        var difference = ChronoUnit.DAYS.between(left, right);
        return difference >= minimum && difference <= maximum;
    }

    private static Fact uniqueFact(List<Fact> rows) {
        if (rows.isEmpty()) return null;
        var first = rows.get(0);
        return rows.stream().allMatch(row -> row.value().compareTo(first.value()) == 0
                && java.util.Objects.equals(row.start(), first.start())
                && java.util.Objects.equals(row.end(), first.end())
                && row.accession().equals(first.accession()) && row.filed().equals(first.filed())) ? first : null;
    }

    private static Fact latestVersion(List<Fact> rows) {
        var latestFiled = rows.stream().map(Fact::filed).max(LocalDate::compareTo).orElse(null);
        if (latestFiled == null) return null;
        return uniqueFact(rows.stream().filter(row -> row.filed().equals(latestFiled)).toList());
    }

    private static LocalDate optionalDate(JsonNode node) {
        var value = text(node);
        if (value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Instant optionalInstant(JsonNode node) {
        var value = text(node);
        if (value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static Integer optionalInt(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return Integer.valueOf(node.asText());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static long number(JsonNode node) {
        if (node == null || node.isNull()) return -1;
        try {
            return Long.parseLong(node.asText());
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return new BigDecimal(node.asText());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    private static String blankNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static ProviderUnavailableException unavailable(String reason) {
        return new ProviderUnavailableException(ID, reason);
    }

    private record Issuer(long cik, String title) { }
    private record IssuerData(JsonNode submissions, JsonNode companyFacts) { }
    private record CacheEntry<T>(T value, Instant expiresAt) { }
    private record InlineEnrichment(JsonNode companyFacts, String failureReason) { }
    private record InlineFetchResult(List<Fact> facts, String failureReason) { }
    private record TagUnit(String tag, String unit) { }
    private record FactRowKey(String unit, LocalDate start, LocalDate end, String accession,
                              LocalDate filed, String form) {
        private static FactRowKey of(Fact fact) {
            return new FactRowKey(fact.unit(), fact.start(), fact.end(), fact.accession(), fact.filed(), fact.form());
        }
    }
    private record PeriodKey(LocalDate start, LocalDate end) { }
    private record Filing(String form, String accession, LocalDate filed, LocalDate reportDate,
                          Instant acceptance, Integer fiscalYear, String fiscalPeriod, String primaryDocument) {
        private Filing(String form, String accession, LocalDate filed, LocalDate reportDate,
                       Instant acceptance, Integer fiscalYear, String fiscalPeriod) {
            this(form, accession, filed, reportDate, acceptance, fiscalYear, fiscalPeriod, null);
        }

        private Instant orderingTime() {
            return acceptance == null ? filed.atStartOfDay(ZoneOffset.UTC).toInstant() : acceptance;
        }
    }
    private record Fact(String tag, BigDecimal value, String unit, LocalDate start, LocalDate end, LocalDate filed,
                        String form, String accession, Integer fiscalYear, String fiscalPeriod, String contextRef,
                        String concept, List<SecInlineXbrlParser.Dimension> dimensions) {
        private Fact(String tag, BigDecimal value, String unit, LocalDate start, LocalDate end, LocalDate filed,
                     String form, String accession, Integer fiscalYear, String fiscalPeriod) {
            this(tag, value, unit, start, end, filed, form, accession, fiscalYear, fiscalPeriod, null,
                    "{" + TAGS + "}" + tag, List.of());
        }
    }
    private record FactValue(BigDecimal value, String unit, LocalDate end, String accession, String period,
                             String tag, LocalDate start, LocalDate filed, String form, String contextRef,
                             String concept) {
        private FactValue(BigDecimal value, String unit, LocalDate end, String accession, String period, String tag) {
            this(value, unit, end, accession, period, tag, null, null, null, null, "{" + TAGS + "}" + tag);
        }
    }
}
