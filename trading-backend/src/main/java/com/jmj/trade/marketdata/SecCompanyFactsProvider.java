package com.jmj.trade.marketdata;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SecCompanyFactsProvider implements StockDataProvider {

    private static final StockDataProviderId ID = StockDataProviderId.SEC;
    private static final String USD = "USD";
    private static final String SHARES = "shares";
    private static final String USD_PER_SHARE = "USD/shares";
    private static final String TAGS = "us-gaap";
    private static final String TICKER_FILE = "https://www.sec.gov/files/company_tickers.json";
    private static final Set<String> FIELDS = Set.of(
            "filing.form", "filing.accession", "filing.acceptance",
            "fundamental.fiscalPeriod", "fundamental.reportedAt", "fundamental.fiscalYear",
            "fundamental.fiscalPeriodCode", "fundamental.cash", "fundamental.debt",
            "fundamental.dilutedShares", "fundamental.dilutedSharesBasis",
            "fundamental.revenueTTM", "fundamental.revenueGrowthYoY", "fundamental.eps",
            "fundamental.fcfTTM", "fundamental.ebitdaTTM");

    private final ProviderHttpTransport transport;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final URI tickerFile;
    private final URI dataBase;
    private volatile Map<String, Issuer> tickerMap;

    SecCompanyFactsProvider(
            StockAnalysisProviderProperties.ProviderConfiguration configuration,
            ObjectMapper objectMapper
    ) {
        this(configuration, objectMapper, Clock.systemUTC(), URI.create(TICKER_FILE), configuration.baseUrl());
    }

    SecCompanyFactsProvider(
            StockAnalysisProviderProperties.ProviderConfiguration configuration,
            ObjectMapper objectMapper,
            Clock clock,
            URI tickerFile,
            URI dataBase
    ) {
        this.transport = new ProviderHttpTransport(ID, configuration);
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.tickerFile = tickerFile;
        this.dataBase = dataBase;
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
            var cik = String.format(java.util.Locale.ROOT, "%010d", issuer.cik());
            var submissions = objectMapper.readTree(transport.get(uri("/submissions/CIK" + cik + ".json")));
            var companyFacts = objectMapper.readTree(transport.get(uri("/api/xbrl/companyfacts/CIK" + cik + ".json")));
            return map(request, issuer, submissions, companyFacts, clock.instant());
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
        var facts = companyFacts.path("facts").path(TAGS);
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

        var cash = financial == null ? null : latestInstant(facts,
                List.of("CashAndCashEquivalentsAtCarryingValue"), USD, financial, fiscalEnd, cutoff);
        addDecimal(result, "fundamental.cash", cash, USD, fiscalEnd, "CashAndCashEquivalentsAtCarryingValue");

        var currentDebt = financial == null ? null : latestInstant(facts, List.of("DebtCurrent"), USD,
                financial, fiscalEnd, cutoff);
        var noncurrentDebt = financial == null ? null : latestInstant(facts,
                List.of("LongTermDebtNoncurrent", "LongTermDebtAndCapitalLeaseObligations"), USD,
                financial, fiscalEnd, cutoff);
        var debt = currentDebt != null && noncurrentDebt != null
                ? new FactValue(currentDebt.value().add(noncurrentDebt.value()), USD, fiscalEnd,
                financial.accession(), "INSTANT", currentDebt.tag() + "+" + noncurrentDebt.tag()) : null;
        addDecimal(result, "fundamental.debt", debt, USD, fiscalEnd, "DebtCurrent+LongTermDebtNoncurrent");

        var annual = financial != null && financial.form().startsWith("10-K") ? financial : null;
        var eps = annualFact(facts, "EarningsPerShareDiluted", USD_PER_SHARE, annual, cutoff);
        var dilutedShares = annualFact(facts, "WeightedAverageNumberOfDilutedSharesOutstanding", SHARES,
                annual, cutoff);
        var sameAnnual = eps != null && dilutedShares != null && eps.end().equals(dilutedShares.end())
                && eps.start().equals(dilutedShares.start()) && eps.accession().equals(dilutedShares.accession());
        addDecimal(result, "fundamental.eps", sameAnnual ? value(eps) : null, "USD/share",
                annual == null ? null : annual.reportDate(), "EarningsPerShareDiluted");
        addDecimal(result, "fundamental.dilutedShares", sameAnnual ? value(dilutedShares) : null, SHARES,
                annual == null ? null : annual.reportDate(), "WeightedAverageNumberOfDilutedSharesOutstanding");
        addText(result, "fundamental.dilutedSharesBasis", sameAnnual ? "WEIGHTED_AVERAGE_FY" : null,
                annual == null ? null : annual.reportDate().atStartOfDay(ZoneOffset.UTC).toInstant(),
                sameAnnual ? null : "DATA_NOT_PRESENT", annual == null ? null : annual.accession());

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
                ? new FactValue(operatingCashFlow.value().subtract(capex.value()), USD,
                fiscalEnd, financial.accession(), "TTM",
                "NetCashProvidedByUsedInOperatingActivities-PaymentsToAcquirePropertyPlantAndEquipment")
                : null;
        addDecimal(result, "fundamental.fcfTTM", fcf, USD, fiscalEnd,
                "NetCashProvidedByUsedInOperatingActivities-PaymentsToAcquirePropertyPlantAndEquipment");
        addMissing(result, "fundamental.ebitdaTTM", "DATA_NOT_PRESENT", asOf,
                financial == null ? latest.accession() : financial.accession());

        return List.copyOf(result);
    }

    private Map<String, Issuer> tickerMap() {
        var cached = tickerMap;
        if (cached != null) return cached;
        synchronized (this) {
            if (tickerMap == null) {
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
                    tickerMap = Map.copyOf(result);
                } catch (JacksonException exception) {
                    throw unavailable("INVALID_RESPONSE");
                }
            }
            return tickerMap;
        }
    }

    private URI uri(String path) {
        return dataBase.resolve(path.startsWith("/") ? path : "/" + path);
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
                var candidate = new Filing(form, text(accessions.path(index)), filingDate, reportDate, acceptance,
                        optionalInt(fiscalYears.path(index)), blankNull(text(fiscalPeriods.path(index))));
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

    private FactValue flowTtm(JsonNode facts, String tag, Filing current, LocalDate cutoff) {
        if (current.reportDate() == null) return null;
        if (current.form().startsWith("10-K")) {
            var annual = uniqueFact(factRows(facts, tag, USD, cutoff).stream()
                    .filter(row -> row.accession().equals(current.accession()) && row.form().startsWith("10-K")
                            && row.end().equals(current.reportDate()) && row.start() != null
                            && duration(row) >= 330 && duration(row) <= 371).toList());
            return annual == null ? null : new FactValue(annual.value(), USD, current.reportDate(),
                    current.accession(), "TTM", tag);
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
        return new FactValue(result, USD, current.reportDate(), current.accession(), "TTM", tag);
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
        return new FactValue(growth, "ratio", current.end(), current.accession(), "FY", tag);
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
            return new FactValue(growth, "ratio", current.reportDate(), current.accession(), "TTM", tag);
        }
        return null;
    }

    private List<Fact> factRows(JsonNode facts, String tag, String unit, LocalDate cutoff) {
        return allRows(facts, tag, unit, cutoff).stream().filter(row -> row.end() != null).toList();
    }

    private List<Fact> allRows(JsonNode facts, String tag, String unit, LocalDate cutoff) {
        var fact = facts.path(tag);
        if (!fact.isObject() || !fact.path("units").isObject()) return List.of();
        var units = fact.path("units");
        var values = units.path(unit);
        if (!values.isArray()) return List.of();
        var rows = new ArrayList<Fact>();
        for (var row : values) {
            var start = optionalDate(row.path("start"));
            var end = optionalDate(row.path("end"));
            var filed = optionalDate(row.path("filed"));
            var form = text(row.path("form"));
            var accession = text(row.path("accn"));
            var number = decimal(row.get("val"));
            if (filed == null || filed.isAfter(cutoff) || form.isBlank() || accession.isBlank() || number == null) continue;
            rows.add(new Fact(tag, number, unit, start, end, filed, form, accession,
                    optionalInt(row.path("fy")), blankNull(text(row.path("fp")))));
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
                value.tag() + "@" + value.accession(), instant,
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
        var period = fact.start() == null ? "INSTANT" : duration >= 330 && duration <= 371 ? "FY" : "YTD";
        return new FactValue(fact.value(), fact.unit(), fact.end(), fact.accession(), period, fact.tag());
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
    private record TagUnit(String tag, String unit) { }
    private record Filing(String form, String accession, LocalDate filed, LocalDate reportDate,
                          Instant acceptance, Integer fiscalYear, String fiscalPeriod) {
        private Instant orderingTime() {
            return acceptance == null ? filed.atStartOfDay(ZoneOffset.UTC).toInstant() : acceptance;
        }
    }
    private record Fact(String tag, BigDecimal value, String unit, LocalDate start, LocalDate end, LocalDate filed,
                        String form, String accession, Integer fiscalYear, String fiscalPeriod) { }
    private record FactValue(BigDecimal value, String unit, LocalDate end, String accession, String period,
                             String tag) { }
}
