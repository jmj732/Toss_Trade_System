package com.jmj.trade.marketdata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.namespace.QName;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecInlineXbrlParserTest {

    private static final long ISSUER_CIK = 1234L;
    private static final LocalDate FILED = LocalDate.parse("2026-02-20");
    private static final String ACCESSION = "0000001234-26-000001";
    private static final String FORM = "10-K";
    private static final String US_GAAP = "http://fasb.org/us-gaap/2025";

    private final SecInlineXbrlParser parser = new SecInlineXbrlParser();

    @Test
    void appliesInlineScaleAndSignAndKeepsReportedContextAndUnit() {
        var source = document("""
                <ix:nonFraction name="us-gaap:CashAndCashEquivalentsAtCarryingValue" contextRef="FY"
                    unitRef="USD" scale="3" sign="-" format="ixt:numdotdecimal">1,234.50</ix:nonFraction>
                <ix:nonFraction name="us-gaap:EarningsPerShareDiluted" contextRef="FY"
                    unitRef="USDPerShare" format="ixt:numdotdecimal">2.50</ix:nonFraction>
                <ix:nonFraction name="us-gaap:CashAndCashEquivalentsAtCarryingValue" contextRef="FY"
                    unitRef="USD" format="ixt:zerodash">–</ix:nonFraction>
                """);

        var facts = parser.parse(source, ACCESSION, FILED, FORM, ISSUER_CIK,
                Set.of("CashAndCashEquivalentsAtCarryingValue", "EarningsPerShareDiluted"), Map.of());

        assertThat(facts).hasSize(3);
        var fact = facts.getFirst();
        assertThat(fact.concept()).isEqualTo(new QName(US_GAAP, "CashAndCashEquivalentsAtCarryingValue"));
        assertThat(fact.value()).isEqualByComparingTo("-1234500.00");
        assertThat(fact.unit()).isEqualTo("USD");
        assertThat(fact.start()).isEqualTo(LocalDate.parse("2025-01-01"));
        assertThat(fact.end()).isEqualTo(LocalDate.parse("2025-12-31"));
        assertThat(fact.instant()).isFalse();
        assertThat(fact.entityCik()).isEqualTo(ISSUER_CIK);
        assertThat(fact.contextRef()).isEqualTo("FY");
        assertThat(fact.accession()).isEqualTo(ACCESSION);
        assertThat(fact.filed()).isEqualTo(FILED);
        assertThat(fact.form()).isEqualTo(FORM);
        assertThat(facts.get(1).unit()).isEqualTo("USD/shares");
        assertThat(facts.get(1).value()).isEqualByComparingTo("2.50");
        assertThat(facts.get(2).value()).isEqualByComparingTo("0");
    }

    @Test
    void acceptsCustomConceptOnlyWhenItsExactQNameWasTaxonomyVerifiedAndPreservesDimensions() {
        var custom = new QName("https://example.test/taxonomy/2026", "NetSales");
        var source = document("""
                <xbrli:context id="CLASS"><xbrli:entity><xbrli:identifier scheme="http://www.sec.gov/CIK">0000001234</xbrli:identifier>
                <xbrli:segment><xbrldi:explicitMember dimension="issuer:ClassAxis">issuer:ClassA</xbrldi:explicitMember></xbrli:segment>
                </xbrli:entity><xbrli:period><xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate></xbrli:period></xbrli:context>
                <xbrli:unit id="USD"><xbrli:measure>iso4217:USD</xbrli:measure></xbrli:unit>
                <ix:nonFraction name="ex:NetSales" contextRef="CLASS" unitRef="USD" format="ixt:num-dot-decimal">42</ix:nonFraction>
                <ix:nonFraction name="ex:OperatingSales" contextRef="CLASS" unitRef="USD" format="ixt:num-dot-decimal">99</ix:nonFraction>
                """);
        var concepts = Map.of(custom, new SecInlineXbrlParser.ConceptInfo(
                "revenue", "Net sales", "Revenue from customers", true));

        var facts = parser.parse(source, ACCESSION, FILED, FORM, ISSUER_CIK, Set.of(), concepts);

        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().concept()).isEqualTo(custom);
        assertThat(facts.getFirst().semantic()).isEqualTo("revenue");
        assertThat(facts.getFirst().dimensions()).containsExactly(
                new SecInlineXbrlParser.Dimension(new QName("https://example.test/taxonomy/2026", "ClassAxis"),
                        new QName("https://example.test/taxonomy/2026", "ClassA")));
    }

    @Test
    void skipsUnknownTransformsAndContextsThatDoNotBelongToIssuerOrAreFutureDated() {
        var source = document("""
                <xbrli:context id="OTHER"><xbrli:entity><xbrli:identifier scheme="http://www.sec.gov/CIK">9876</xbrli:identifier></xbrli:entity>
                <xbrli:period><xbrli:instant>2025-12-31</xbrli:instant></xbrli:period></xbrli:context>
                <xbrli:context id="FUTURE"><xbrli:entity><xbrli:identifier scheme="http://www.sec.gov/CIK">1234</xbrli:identifier></xbrli:entity>
                <xbrli:period><xbrli:instant>2026-02-21</xbrli:instant></xbrli:period></xbrli:context>
                <ix:nonFraction name="us-gaap:CashAndCashEquivalentsAtCarryingValue" contextRef="OTHER" unitRef="USD" format="ixt:num-dot-decimal">10</ix:nonFraction>
                <ix:nonFraction name="us-gaap:CashAndCashEquivalentsAtCarryingValue" contextRef="FUTURE" unitRef="USD" format="ixt:num-dot-decimal">20</ix:nonFraction>
                <ix:nonFraction name="us-gaap:CashAndCashEquivalentsAtCarryingValue" contextRef="FY" unitRef="USD" format="ixt:unknown-transform">30</ix:nonFraction>
                <ix:nonFraction name="us-gaap:CashAndCashEquivalentsAtCarryingValue" contextRef="FY" unitRef="USD" xsi:nil="true" format="ixt:zerodash">-</ix:nonFraction>
                """);

        var facts = parser.parse(source, ACCESSION, FILED, FORM, ISSUER_CIK,
                Set.of("CashAndCashEquivalentsAtCarryingValue"), Map.of());

        assertThat(facts).isEmpty();
    }

    @Test
    void externalEntitiesCannotContributeInlineFinancialValues(@TempDir Path tempDir) throws IOException {
        var marker = "PRIVATE-ENTITY-CONTENT";
        var secret = tempDir.resolve("entity.txt");
        Files.writeString(secret, marker);
        var source = """
                <!DOCTYPE html [<!ENTITY privateValue SYSTEM "%s">]>
                """.formatted(secret.toUri()) + document("""
                <ix:nonFraction name="us-gaap:CashAndCashEquivalentsAtCarryingValue" contextRef="FY"
                    unitRef="USD" format="ixt:num-dot-decimal">&privateValue;</ix:nonFraction>
                """).replaceFirst("(?s)^\\s*<\\?xml.*?\\?>", "");

        try {
            var facts = parser.parse(source, ACCESSION, FILED, FORM, ISSUER_CIK,
                    Set.of("CashAndCashEquivalentsAtCarryingValue"), Map.of());
            assertThat(facts).noneMatch(fact -> fact.value().toPlainString().contains(marker));
        } catch (SecInlineXbrlParser.InlineXbrlParseException expectedSafeRejection) {
            assertThat(expectedSafeRejection.code()).isEqualTo("INLINE_XBRL_NOT_WELL_FORMED");
        }
    }

    @Test
    void readsCustomLabelAndDocumentationResourcesConnectedByLabelArcs() {
        var schema = """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                    targetNamespace="https://example.test/taxonomy/2026">
                  <xs:element id="acme_NetSales" name="NetSales">
                    <xs:annotation><xs:documentation>Revenue from customer sales</xs:documentation></xs:annotation>
                  </xs:element>
                </xs:schema>
                """;
        var labels = """
                <link:linkbase xmlns:link="http://www.xbrl.org/2003/linkbase"
                    xmlns:xlink="http://www.w3.org/1999/xlink">
                  <link:labelLink xlink:type="extended" xlink:role="http://www.xbrl.org/2003/role/label">
                    <link:loc xlink:type="locator" xlink:href="acme.xsd#acme_NetSales" xlink:label="concept"/>
                    <link:label xlink:type="resource" xlink:label="label" xlink:role="http://www.xbrl.org/2003/role/label" xml:lang="en-US">Net sales</link:label>
                    <link:label xlink:type="resource" xlink:label="definition" xlink:role="http://www.xbrl.org/2003/role/documentation" xml:lang="en-US">Revenue from customer sales</link:label>
                    <link:labelArc xlink:type="arc" xlink:from="concept" xlink:to="label"/>
                    <link:labelArc xlink:type="arc" xlink:from="concept" xlink:to="definition"/>
                  </link:labelLink>
                </link:linkbase>
                """;

        var taxonomy = parser.parseTaxonomy(schema, labels);

        assertThat(taxonomy).containsEntry(new QName("https://example.test/taxonomy/2026", "NetSales"),
                new SecInlineXbrlParser.TaxonomyConcept("Net sales", "Revenue from customer sales"));
    }

    @Test
    void leavesConflictingCustomLabelsUnverifiedInsteadOfTakingTheFirst() {
        var schema = """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="https://example.test/ext">
                  <xs:element id="acme_NetSales" name="NetSales"/>
                </xs:schema>
                """;
        var labels = """
                <link:linkbase xmlns:link="http://www.xbrl.org/2003/linkbase" xmlns:xlink="http://www.w3.org/1999/xlink">
                  <link:labelLink xlink:type="extended">
                    <link:loc xlink:type="locator" xlink:href="acme.xsd#acme_NetSales" xlink:label="concept"/>
                    <link:label xlink:type="resource" xlink:label="a" xlink:role="http://www.xbrl.org/2003/role/label" xml:lang="en-US">Net sales</link:label>
                    <link:label xlink:type="resource" xlink:label="b" xlink:role="http://www.xbrl.org/2003/role/label" xml:lang="en-US">Sales revenue</link:label>
                    <link:labelArc xlink:type="arc" xlink:from="concept" xlink:to="a"/>
                    <link:labelArc xlink:type="arc" xlink:from="concept" xlink:to="b"/>
                  </link:labelLink>
                </link:linkbase>
                """;

        var concept = parser.parseTaxonomy(schema, labels)
                .get(new QName("https://example.test/ext", "NetSales"));

        assertThat(concept).isNotNull();
        assertThat(concept.label()).isNull();
    }

    @Test
    void extractsOnlySchemaAndLabelLinkbaseReferencesWithoutResolvingThem() {
        var inline = """
                <html xmlns:ix="http://www.xbrl.org/2013/inlineXBRL"
                      xmlns:link="http://www.xbrl.org/2003/linkbase"
                      xmlns:xlink="http://www.w3.org/1999/xlink">
                  <ix:header><ix:references><link:schemaRef xlink:type="simple" xlink:href="acme.xsd"/></ix:references></ix:header>
                </html>
                """;
        var schema = """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                    xmlns:link="http://www.xbrl.org/2003/linkbase" xmlns:xlink="http://www.w3.org/1999/xlink">
                  <xs:annotation><xs:appinfo>
                    <link:linkbaseRef xlink:type="simple" xlink:href="acme_lab.xml"
                      xlink:role="http://www.xbrl.org/2003/role/labelLinkbaseRef"/>
                    <link:linkbaseRef xlink:type="simple" xlink:href="acme_pre.xml"
                      xlink:role="http://www.xbrl.org/2003/role/presentationLinkbaseRef"/>
                  </xs:appinfo></xs:annotation>
                </xs:schema>
                """;

        assertThat(parser.schemaReferences(inline)).containsExactly("acme.xsd");
        assertThat(parser.labelLinkbaseReferences(schema)).containsExactly("acme_lab.xml");
    }

    private static String document(String inlineContent) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ix="http://www.xbrl.org/2013/inlineXBRL"
                      xmlns:xbrli="http://www.xbrl.org/2003/instance"
                      xmlns:xbrldi="http://xbrl.org/2006/xbrldi"
                      xmlns:us-gaap="%s"
                      xmlns:ex="https://example.test/taxonomy/2026"
                      xmlns:issuer="https://example.test/taxonomy/2026"
                      xmlns:iso4217="http://www.xbrl.org/2003/iso4217"
                      xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                      xmlns:ixt="http://www.xbrl.org/inlineXBRL/transformation/2015-02-26">
                  <ix:header><ix:resources>
                    <xbrli:context id="FY"><xbrli:entity>
                      <xbrli:identifier scheme="http://www.sec.gov/CIK">0000001234</xbrli:identifier>
                    </xbrli:entity><xbrli:period>
                      <xbrli:startDate>2025-01-01</xbrli:startDate><xbrli:endDate>2025-12-31</xbrli:endDate>
                    </xbrli:period></xbrli:context>
                    <xbrli:unit id="USD"><xbrli:measure>iso4217:USD</xbrli:measure></xbrli:unit>
                    <xbrli:unit id="USDPerShare"><xbrli:divide>
                      <xbrli:unitNumerator><xbrli:measure>iso4217:USD</xbrli:measure></xbrli:unitNumerator>
                      <xbrli:unitDenominator><xbrli:measure>shares</xbrli:measure></xbrli:unitDenominator>
                    </xbrli:divide></xbrli:unit>
                  </ix:resources></ix:header>
                  %s
                </html>
                """.formatted(US_GAAP, inlineContent);
    }
}
