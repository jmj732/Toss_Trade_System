package com.jmj.trade.marketdata;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parses numeric facts already present in a SEC inline XBRL filing; it never retrieves references. */
final class SecInlineXbrlParser {

    private static final String INLINE_NS = "http://www.xbrl.org/2013/inlineXBRL";
    private static final String INSTANCE_NS = "http://www.xbrl.org/2003/instance";
    private static final String DIMENSIONS_NS = "http://xbrl.org/2006/xbrldi";
    private static final String LINK_NS = "http://www.xbrl.org/2003/linkbase";
    private static final String XLINK_NS = "http://www.w3.org/1999/xlink";
    private static final String XML_SCHEMA_NS = "http://www.w3.org/2001/XMLSchema";
    private static final int MAX_NUMERIC_LENGTH = 2_048;
    private static final int MAX_SCALE = 1_000;

    record ConceptInfo(String semantic, String label, String definition, boolean verified) {
    }

    record TaxonomyConcept(String label, String definition) {
    }

    record Dimension(QName axis, QName member, String typedMember) {
        Dimension(QName axis, QName member) {
            this(axis, member, null);
        }
    }

    record InlineFact(
            QName concept,
            String semantic,
            String namespace,
            String localName,
            String unit,
            BigDecimal value,
            LocalDate start,
            LocalDate end,
            boolean instant,
            long entityCik,
            List<Dimension> dimensions,
            String contextRef,
            String accession,
            LocalDate filed,
            String form
    ) {
        InlineFact {
            dimensions = List.copyOf(dimensions);
        }
    }

    static final class InlineXbrlParseException extends RuntimeException {
        private final String code;

        InlineXbrlParseException(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    List<InlineFact> parse(
            String source,
            String accession,
            LocalDate filed,
            String form,
            long issuerCik,
            Set<String> standardLocalNames,
            Map<QName, ConceptInfo> verifiedTaxonomy
    ) {
        if (source == null || filed == null || issuerCik <= 0 || standardLocalNames == null || verifiedTaxonomy == null) {
            throw new IllegalArgumentException("invalid parser arguments");
        }
        var document = parseDocument(source);
        var contexts = contexts(document);
        var units = units(document);
        var facts = new ArrayList<InlineFact>();
        for (var element : elements(document, INLINE_NS, "nonFraction")) {
            var fact = numericFact(element, false, contexts, units, standardLocalNames, verifiedTaxonomy,
                    accession, filed, form, issuerCik);
            if (fact != null) facts.add(fact);
        }
        for (var element : elements(document, INLINE_NS, "fraction")) {
            var fact = numericFact(element, true, contexts, units, standardLocalNames, verifiedTaxonomy,
                    accession, filed, form, issuerCik);
            if (fact != null) facts.add(fact);
        }
        return List.copyOf(facts);
    }

    /** Extracts document-relative schema references from ix:references without fetching them. */
    Set<String> schemaReferences(String inlineDocument) {
        var document = parseDocument(inlineDocument);
        var references = new LinkedHashSet<String>();
        for (var schemaRef : elements(document, LINK_NS, "schemaRef")) {
            var href = attribute(schemaRef, XLINK_NS, "href");
            if (href != null && !href.isBlank()) references.add(href.trim());
        }
        return Collections.unmodifiableSet(references);
    }

    /** Extracts document-relative label linkbase references from an extension schema, without fetching them. */
    Set<String> labelLinkbaseReferences(String schemaDocument) {
        var document = parseDocument(schemaDocument);
        var references = new LinkedHashSet<String>();
        for (var reference : elements(document, LINK_NS, "linkbaseRef")) {
            var href = attribute(reference, XLINK_NS, "href");
            if (href == null || href.isBlank()) continue;
            var role = attribute(reference, XLINK_NS, "role");
            var lowerHref = href.toLowerCase(Locale.ROOT);
            var lowerRole = role == null ? "" : role.toLowerCase(Locale.ROOT);
            var filename = lowerHref.substring(lowerHref.lastIndexOf('/') + 1);
            if (lowerRole.endsWith("/labellinkbaseref") || lowerRole.endsWith("labellinkbaseref")
                    || filename.matches(".*(?:^|[_-])(?:lab|labels?)(?:[_-].*)?\\.(?:xml|xsd)(?:[?#].*)?")) {
                references.add(href.trim());
            }
        }
        return Collections.unmodifiableSet(references);
    }

    /** Maps extension schema QNames to standard English label and definition text when present. */
    Map<QName, TaxonomyConcept> parseTaxonomy(String schemaDocument, String labelLinkbaseDocument) {
        var schema = parseDocument(schemaDocument);
        var linkbase = labelLinkbaseDocument == null || labelLinkbaseDocument.isBlank()
                ? null : parseDocument(labelLinkbaseDocument);
        var schemaRoot = schema.getDocumentElement();
        var targetNamespace = attribute(schemaRoot, null, "targetNamespace");
        if (targetNamespace == null || targetNamespace.isBlank()) return Map.of();

        var ids = new LinkedHashMap<String, QName>();
        var concepts = new LinkedHashMap<QName, MutableTaxonomyConcept>();
        for (var declaration : elements(schema, XML_SCHEMA_NS, "element")) {
            var name = attribute(declaration, null, "name");
            var id = attribute(declaration, null, "id");
            if (name == null || name.isBlank()) continue;
            var concept = new QName(targetNamespace, name);
            if (id != null && !id.isBlank()) ids.put(id, concept);
            ids.putIfAbsent(name, concept);
            var annotation = firstChild(declaration, XML_SCHEMA_NS, "annotation");
            var documentation = annotation == null ? null
                    : firstDescendant(annotation, XML_SCHEMA_NS, "documentation");
            if (documentation != null) {
                concepts.computeIfAbsent(concept, ignored -> new MutableTaxonomyConcept())
                        .addDefinition(normalizedText(documentation));
            }
        }

        var locators = new LinkedHashMap<String, QName>();
        for (var locator : linkbase == null ? List.<Element>of() : elements(linkbase, LINK_NS, "loc")) {
            var label = attribute(locator, XLINK_NS, "label");
            var href = attribute(locator, XLINK_NS, "href");
            if (label == null || href == null) continue;
            var fragment = fragment(href);
            var concept = ids.get(fragment);
            if (concept != null) locators.put(label, concept);
        }

        var resources = new LinkedHashMap<String, Resource>();
        for (var label : linkbase == null ? List.<Element>of() : elements(linkbase, LINK_NS, "label")) {
            var key = attribute(label, XLINK_NS, "label");
            var role = attribute(label, XLINK_NS, "role");
            var language = label.getAttributeNS(XMLConstants.XML_NS_URI, "lang");
            if (key == null || role == null || language == null || !language.toLowerCase(Locale.ROOT).startsWith("en")) {
                continue;
            }
            var localRole = role.toLowerCase(Locale.ROOT);
            if (localRole.endsWith("/label")) {
                resources.put(key, new Resource(false, normalizedText(label)));
            } else if (localRole.endsWith("/documentation") || localRole.endsWith("/definition")) {
                resources.put(key, new Resource(true, normalizedText(label)));
            }
        }

        ids.values().forEach(concept -> concepts.putIfAbsent(concept, new MutableTaxonomyConcept()));
        for (var arc : linkbase == null ? List.<Element>of() : elements(linkbase, LINK_NS, "labelArc")) {
            addTaxonomyResource(concepts, locators, resources, arc);
        }
        var result = new LinkedHashMap<QName, TaxonomyConcept>();
        concepts.forEach((name, values) -> result.put(name,
                new TaxonomyConcept(values.uniqueLabel(), values.uniqueDefinition())));
        return Collections.unmodifiableMap(result);
    }

    private static void addTaxonomyResource(Map<QName, MutableTaxonomyConcept> concepts,
                                            Map<String, QName> locators, Map<String, Resource> resources,
                                            Element arc) {
        var from = attribute(arc, XLINK_NS, "from");
        var to = attribute(arc, XLINK_NS, "to");
        var concept = from == null ? null : locators.get(from);
        var resource = to == null ? null : resources.get(to);
        if (concept == null || resource == null) return;
        var value = concepts.computeIfAbsent(concept, ignored -> new MutableTaxonomyConcept());
        if (resource.definition) value.addDefinition(resource.text);
        else value.addLabel(resource.text);
    }

    private static InlineFact numericFact(Element element, boolean fraction, Map<String, Context> contexts,
                                         Map<String, String> units, Set<String> standardLocalNames,
                                         Map<QName, ConceptInfo> verifiedTaxonomy, String accession, LocalDate filed,
                                         String form, long issuerCik) {
        var concept = resolveQName(element, attribute(element, null, "name"));
        if (concept == null) return null;
        var semantic = semanticFor(concept, standardLocalNames, verifiedTaxonomy);
        if (semantic == null) return null;
        var contextRef = attribute(element, null, "contextRef");
        var unitRef = attribute(element, null, "unitRef");
        if (contextRef == null || unitRef == null) return null;
        var context = contexts.get(contextRef);
        var unit = units.get(unitRef);
        if (context == null || unit == null || context.entityCik != issuerCik || context.end.isAfter(filed)) return null;
        var nil = element.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil");
        if ("true".equalsIgnoreCase(nil) || "1".equals(nil)) return null;
        if (context.start != null && context.start.isAfter(context.end)) return null;
        var raw = fraction ? fractionValue(element) : numericText(element);
        var normalized = normalizeNumeric(raw, element);
        if (normalized == null) return null;
        var scaleText = attribute(element, null, "scale");
        if (scaleText != null && !scaleText.isBlank()) {
            try {
                var scale = Integer.parseInt(scaleText.trim());
                if (Math.abs((long) scale) > MAX_SCALE) return null;
                normalized = normalized.movePointRight(scale);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        var sign = attribute(element, null, "sign");
        if ("-".equals(sign) && normalized.signum() < 0) return null;
        if ("-".equals(sign)) normalized = normalized.negate();
        else if (sign != null && !sign.isBlank() && !"+".equals(sign)) return null;
        return new InlineFact(concept, semantic, concept.getNamespaceURI(), concept.getLocalPart(), unit,
                normalized, context.start, context.end, context.start == null, context.entityCik,
                context.dimensions, contextRef, accession, filed, form);
    }

    private static String semanticFor(QName concept, Set<String> standardLocalNames,
                                      Map<QName, ConceptInfo> verifiedTaxonomy) {
        var localName = concept.getLocalPart();
        if (standardLocalNames.contains(localName) && recognizedStandardNamespace(concept.getNamespaceURI())) {
            return localName;
        }
        var info = verifiedTaxonomy.get(concept);
        return info != null && info.verified() && info.semantic() != null && !info.semantic().isBlank()
                ? info.semantic() : null;
    }

    private static boolean recognizedStandardNamespace(String namespace) {
        if (namespace == null) return false;
        return namespace.startsWith("http://fasb.org/us-gaap/")
                || namespace.startsWith("https://fasb.org/us-gaap/")
                || namespace.startsWith("http://xbrl.sec.gov/dei/")
                || namespace.startsWith("https://xbrl.sec.gov/dei/");
    }

    private static Map<String, Context> contexts(Document document) {
        var contexts = new LinkedHashMap<String, Context>();
        for (var element : elements(document, INSTANCE_NS, "context")) {
            var id = attribute(element, null, "id");
            var entity = firstDescendant(element, INSTANCE_NS, "identifier");
            var period = firstDescendant(element, INSTANCE_NS, "period");
            if (id == null || entity == null || period == null) continue;
            long cik;
            try {
                cik = Long.parseLong(normalizedText(entity));
            } catch (NumberFormatException exception) {
                continue;
            }
            if (cik <= 0) continue;
            var instantNode = firstChild(period, INSTANCE_NS, "instant");
            var startNode = firstChild(period, INSTANCE_NS, "startDate");
            var endNode = firstChild(period, INSTANCE_NS, "endDate");
            LocalDate start = null;
            LocalDate end;
            try {
                if (instantNode != null) {
                    end = LocalDate.parse(normalizedText(instantNode));
                } else if (startNode != null && endNode != null) {
                    start = LocalDate.parse(normalizedText(startNode));
                    end = LocalDate.parse(normalizedText(endNode));
                } else {
                    continue;
                }
            } catch (RuntimeException exception) {
                continue;
            }
            var dimensions = dimensions(element);
            if (dimensions == null) continue;
            contexts.putIfAbsent(id, new Context(cik, start, end, dimensions));
        }
        return contexts;
    }

    private static List<Dimension> dimensions(Element context) {
        var result = new ArrayList<Dimension>();
        for (var member : elements(context, DIMENSIONS_NS, "explicitMember")) {
            var axis = resolveQName(member, attribute(member, null, "dimension"));
            var value = resolveQName(member, normalizedText(member));
            if (axis == null || value == null) return null;
            result.add(new Dimension(axis, value));
        }
        for (var member : elements(context, DIMENSIONS_NS, "typedMember")) {
            var axis = resolveQName(member, attribute(member, null, "dimension"));
            if (axis == null) return null;
            var child = firstElementChild(member);
            if (child == null) return null;
            result.add(new Dimension(axis, null, normalizedText(child)));
        }
        return List.copyOf(result);
    }

    private static Map<String, String> units(Document document) {
        var result = new LinkedHashMap<String, String>();
        for (var element : elements(document, INSTANCE_NS, "unit")) {
            var id = attribute(element, null, "id");
            if (id == null) continue;
            var divide = firstChild(element, INSTANCE_NS, "divide");
            var numerator = divide == null ? null : firstChild(divide, INSTANCE_NS, "unitNumerator");
            var denominator = divide == null ? null : firstChild(divide, INSTANCE_NS, "unitDenominator");
            var numeratorMeasures = measures(numerator == null ? element : numerator);
            var denominatorMeasures = denominator == null ? List.<String>of() : measures(denominator);
            if (numeratorMeasures.isEmpty()) continue;
            var value = String.join("*", numeratorMeasures);
            if (!denominatorMeasures.isEmpty()) value += "/" + String.join("*", denominatorMeasures);
            result.putIfAbsent(id, value);
        }
        return result;
    }

    private static List<String> measures(Element container) {
        var result = new ArrayList<String>();
        for (var measure : childElements(container, INSTANCE_NS, "measure")) {
            var lexicalName = normalizedText(measure);
            var name = lexicalName.indexOf(':') < 0
                    ? new QName("", lexicalName) : resolveQName(measure, lexicalName);
            if (name == null) return List.of();
            var namespace = name.getNamespaceURI();
            var local = name.getLocalPart();
            if (namespace.contains("iso4217")) result.add(local);
            else if (namespace.isBlank() || INSTANCE_NS.equals(namespace)
                    || namespace.startsWith("http://www.xbrl.org/2003/instance")) result.add(local);
            else result.add("{" + namespace + "}" + local);
        }
        return result;
    }

    private static BigDecimal normalizeNumeric(String source, Element element) {
        if (source == null || source.length() > MAX_NUMERIC_LENGTH) return null;
        var text = source.trim().replace('\u00a0', ' ').replace('\u202f', ' ').trim();
        var format = attribute(element, null, "format");
        if (format == null || format.isBlank()) return parseNumber(text, '.', '\0', false);
        var transform = resolveQName(element, format);
        if (transform == null || !recognizedTransformNamespace(transform.getNamespaceURI())) return null;
        var result = switch (transform.getLocalPart()) {
            case "num-dot-decimal", "numdotdecimal" -> parseTransformedNumber(text, '.', ',');
            case "num-comma-decimal", "numcommadecimal" -> parseTransformedNumber(text, ',', '.');
            case "num-dot-decimal-in", "numdotdecimalin" -> parseIndianNumber(text);
            case "zero-dash", "zerodash" -> isDash(text) ? BigDecimal.ZERO : null;
            default -> null;
        };
        return result != null && result.signum() >= 0 ? result : null;
    }

    private static boolean recognizedTransformNamespace(String namespace) {
        return Set.of(
                "http://www.xbrl.org/inlineXBRL/transformation/2015-02-26",
                "http://www.xbrl.org/inlineXBRL/transformation/2011-07-31",
                "http://www.xbrl.org/inlineXBRL/transformation/2010-04-20",
                "http://www.xbrl.org/2008/inlineXBRL/transformation",
                "http://www.xbrl.org/inlineXBRL/transformation/2022-02-16"
        ).contains(namespace);
    }

    private static BigDecimal parseTransformedNumber(String text, char decimal, char grouping) {
        if (text.startsWith("-") || text.startsWith("+") || text.startsWith("(")
                || text.endsWith(")")) return null;
        return parseNumber(text, decimal, grouping, false);
    }

    private static BigDecimal parseIndianNumber(String text) {
        var value = text.replace(" ", "").replace('\u00a0', ' ').trim();
        if (value.startsWith("-") || value.startsWith("+") || value.startsWith("(")) return null;
        var parts = value.split("\\.", -1);
        if (parts.length > 2 || parts[0].isEmpty()) return null;
        var groups = parts[0].split(",", -1);
        if (groups.length > 1) {
            if (!groups[0].matches("\\d{1,2}") || !groups[groups.length - 1].matches("\\d{3}")) return null;
            for (int index = 1; index < groups.length - 1; index++) {
                if (!groups[index].matches("\\d{2}")) return null;
            }
        } else if (!groups[0].matches("\\d+")) {
            return null;
        }
        if (parts.length == 2 && !parts[1].matches("\\d*")) return null;
        var canonical = String.join("", groups) + (parts.length == 2 ? "." + parts[1] : "");
        try {
            return new BigDecimal(canonical, MathContext.UNLIMITED);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static BigDecimal parseNumber(String text, char decimal, char grouping, boolean allowParentheses) {
        if (text == null || text.isBlank()) return null;
        var value = text.replace(" ", "").replace("\u2212", "-");
        if (isDash(value)) return null;
        boolean parenthesized = allowParentheses && value.length() > 2
                && value.charAt(0) == '(' && value.charAt(value.length() - 1) == ')';
        if (parenthesized) value = value.substring(1, value.length() - 1);
        if (value.indexOf('(') >= 0 || value.indexOf(')') >= 0) return null;
        var sign = "";
        if (value.startsWith("+") || value.startsWith("-")) {
            sign = value.substring(0, 1);
            value = value.substring(1);
        }
        if (parenthesized && !sign.isEmpty()) return null;
        var parts = value.split(java.util.regex.Pattern.quote(String.valueOf(decimal)), -1);
        if (parts.length > 2) return null;
        var integer = parts[0];
        var fraction = parts.length == 2 ? parts[1] : null;
        if (integer.isEmpty() && (fraction == null || fraction.isEmpty())) return null;
        if (grouping != '\0' && integer.indexOf(grouping) >= 0) {
            var groups = integer.split(java.util.regex.Pattern.quote(String.valueOf(grouping)), -1);
            if (groups.length < 2 || !groups[0].matches("\\d{1,3}")) return null;
            for (int index = 1; index < groups.length; index++) {
                if (!groups[index].matches("\\d{3}")) return null;
            }
            integer = String.join("", groups);
        } else if (!integer.isEmpty() && !integer.matches("\\d+")) {
            return null;
        }
        if (fraction != null && !fraction.matches("\\d*")) return null;
        if (integer.isEmpty()) integer = "0";
        var canonical = sign + integer + (fraction == null ? "" : "." + fraction);
        try {
            var result = new BigDecimal(canonical, MathContext.UNLIMITED);
            return parenthesized ? result.negate() : result;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean isDash(String text) {
        return "-".equals(text) || "–".equals(text) || "—".equals(text) || "−".equals(text);
    }

    private static String numericText(Element element) {
        var text = new StringBuilder();
        appendText(element, text);
        return text.toString();
    }

    private static String fractionValue(Element element) {
        var numerator = firstDescendant(element, INLINE_NS, "numerator");
        var denominator = firstDescendant(element, INLINE_NS, "denominator");
        if (numerator == null || denominator == null) return null;
        var num = normalizeNumeric(numericText(numerator), numerator);
        var den = normalizeNumeric(numericText(denominator), denominator);
        if (num == null || den == null || den.signum() == 0) return null;
        try {
            return num.divide(den).toPlainString();
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    private static void appendText(Node node, StringBuilder result) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            result.append(node.getNodeValue());
            return;
        }
        if (node instanceof Element element && INLINE_NS.equals(element.getNamespaceURI())
                && "exclude".equals(element.getLocalName())) return;
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) appendText(child, result);
    }

    private static QName resolveQName(Node node, String lexical) {
        if (lexical == null) return null;
        var value = lexical.trim();
        var colon = value.indexOf(':');
        if (colon >= 0) {
            if (colon == 0 || colon == value.length() - 1 || value.indexOf(':', colon + 1) >= 0) return null;
            var prefix = value.substring(0, colon);
            var namespace = node.lookupNamespaceURI(prefix);
            if (namespace == null) return null;
            var local = value.substring(colon + 1);
            if (!isXmlName(local)) return null;
            return new QName(namespace, local, prefix);
        }
        if (!isXmlName(value)) return null;
        var namespace = node.lookupNamespaceURI(null);
        return new QName(namespace == null ? "" : namespace, value);
    }

    private static boolean isXmlName(String value) {
        return value.matches("[A-Za-z_][A-Za-z0-9._-]*");
    }

    private static Document parseDocument(String source) {
        if (source == null || source.isBlank()) throw new InlineXbrlParseException("INLINE_XBRL_NOT_WELL_FORMED");
        if (hasInternalDoctypeSubset(source)) {
            throw new InlineXbrlParseException("INLINE_XBRL_NOT_WELL_FORMED");
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) {
                    // SEC documents can carry harmless DTD warnings; no external resource is resolved.
                }

                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            return builder.parse(new InputSource(new StringReader(replaceCommonHtmlEntities(source))));
        } catch (ParserConfigurationException | SAXException | IOException | IllegalArgumentException exception) {
            throw new InlineXbrlParseException("INLINE_XBRL_NOT_WELL_FORMED");
        }
    }

    private static boolean hasInternalDoctypeSubset(String source) {
        var upper = source.toUpperCase(Locale.ROOT);
        var start = upper.indexOf("<!DOCTYPE");
        if (start < 0) return false;
        char quote = 0;
        for (int index = start + "<!DOCTYPE".length(); index < source.length(); index++) {
            var current = source.charAt(index);
            if (quote != 0) {
                if (current == quote) quote = 0;
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '[') {
                return true;
            } else if (current == '>') {
                return false;
            }
        }
        return false;
    }

    private static String replaceCommonHtmlEntities(String source) {
        return source.replace("&nbsp;", "&#160;")
                .replace("&mdash;", "&#8212;")
                .replace("&ndash;", "&#8211;")
                .replace("&rsquo;", "&#8217;")
                .replace("&lsquo;", "&#8216;")
                .replace("&rdquo;", "&#8221;")
                .replace("&ldquo;", "&#8220;")
                .replace("&hellip;", "&#8230;")
                .replace("&bull;", "&#8226;")
                .replace("&copy;", "&#169;")
                .replace("&reg;", "&#174;")
                .replace("&trade;", "&#8482;")
                .replace("&middot;", "&#183;");
    }

    private static String attribute(Element element, String namespace, String name) {
        if (namespace == null) {
            var value = element.getAttribute(name);
            return value.isEmpty() ? null : value;
        }
        var value = element.getAttributeNS(namespace, name);
        return value.isEmpty() ? null : value;
    }

    private static List<Element> elements(Document document, String namespace, String local) {
        var nodes = document.getElementsByTagNameNS(namespace, local);
        var result = new ArrayList<Element>(nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) result.add((Element) nodes.item(index));
        return result;
    }

    private static List<Element> elements(Element root, String namespace, String local) {
        var nodes = root.getElementsByTagNameNS(namespace, local);
        var result = new ArrayList<Element>(nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) result.add((Element) nodes.item(index));
        return result;
    }

    private static Element firstDescendant(Element root, String namespace, String local) {
        var nodes = root.getElementsByTagNameNS(namespace, local);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static Element firstChild(Element root, String namespace, String local) {
        for (var child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && namespace.equals(element.getNamespaceURI())
                    && local.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private static List<Element> childElements(Element root, String namespace, String local) {
        var result = new ArrayList<Element>();
        for (var child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && namespace.equals(element.getNamespaceURI())
                    && local.equals(element.getLocalName())) result.add(element);
        }
        return result;
    }

    private static Element firstElementChild(Element root) {
        for (var child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) return element;
        }
        return null;
    }

    private static String normalizedText(Node element) {
        var result = new StringBuilder();
        appendText(element, result);
        return result.toString().trim().replaceAll("\\s+", " ");
    }

    private static String fragment(String href) {
        var index = href.lastIndexOf('#');
        return index < 0 || index == href.length() - 1 ? "" : href.substring(index + 1);
    }

    private record Context(long entityCik, LocalDate start, LocalDate end, List<Dimension> dimensions) {
    }

    private record Resource(boolean definition, String text) {
    }

    private static final class MutableTaxonomyConcept {
        private final Set<String> labels = new LinkedHashSet<>();
        private final Set<String> definitions = new LinkedHashSet<>();

        private void addLabel(String value) {
            if (value != null && !value.isBlank()) labels.add(value);
        }

        private void addDefinition(String value) {
            if (value != null && !value.isBlank()) definitions.add(value);
        }

        private String uniqueLabel() {
            return labels.size() == 1 ? labels.iterator().next() : null;
        }

        private String uniqueDefinition() {
            return definitions.size() == 1 ? definitions.iterator().next() : null;
        }
    }
}
