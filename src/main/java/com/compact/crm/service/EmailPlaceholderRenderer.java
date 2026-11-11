package com.compact.crm.service;

import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadBattery;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.OpportunityBattery;
import com.compact.crm.entity.OpportunityProduct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// Per-recipient merge-field substitution, shared by every customer-facing send
// path (single email, bulk email, bulk Keep in Touch) so they all resolve the
// same closed set of fields the same way. The email draft editors show a
// template filled in with one recipient's values from fieldValues() below
// (frontend utils/draftFields.js); the admin template editor's "Insert field"
// menu (utils/mergeFields.js) offers exactly the keys in FIELDS.
//
// Values always come from the record being emailed:
//  - a Lead: its own contact person, company, products and batteries;
//  - an Opportunity: its OWN contact person and product/battery line items
//    (V14 - copied from the Lead on conversion, then edited on the
//    Opportunity), plus company / designation / city from its Lead, of which
//    the Opportunity keeps no copy.
// A recipient with several products or batteries gets all of their distinct
// names, comma-separated, in line-item order (no quantities).
//
// A message must never go out with a hole in it or a raw {{token}}, so every
// problem is REPORTED and the token is left in place (never blanked, never
// guessed). Issue messages are shown to users, so they name the field
// ("Contact person is missing ...") rather than quoting the {{token}}:
//   - an unknown {{name}}                       (typo / unsupported)
//   - a known {{name}} that is empty for this recipient
//   - {{opportunityTitle}} used outside an opportunity
//   - an unfinished [[...]] admin section (the AMC template ships with one)
// Callers refuse to send to a recipient that has any issue.
public final class EmailPlaceholderRenderer {

    public record Rendered(String text, List<String> issues) {
    }

    // One merge field as it resolves for one recipient: its canonical key,
    // label and value (null when the recipient has none - render() reports
    // that as an issue). The draft editor shows these in place of the
    // {{token}}, so what is shown there is exactly what render() substitutes.
    public record FieldValue(String key, String label, String value) {
    }

    // How substituted values are written. Every send today is plain text
    // (the providers in service.mail send text/plain), where escaping would corrupt a
    // name like "O'Brien & Sons". HTML is for an HTML body: values are escaped
    // so CRM data can never inject markup into the message.
    public enum Format { TEXT, HTML }

    // Supported fields, in menu order: key -> label used in issue messages.
    public static final Map<String, String> FIELDS;

    static {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("contactPerson", "Contact person");
        fields.put("companyName", "Company");
        fields.put("designation", "Designation");
        fields.put("city", "City");
        fields.put("interestedProduct", "Product");
        fields.put("interestedBattery", "Battery");
        fields.put("opportunityTitle", "Opportunity title");
        FIELDS = Collections.unmodifiableMap(fields);
    }

    // Short spellings people naturally type, resolved to a field above.
    private static final Map<String, String> ALIASES = Map.of(
            "name", "contactPerson",
            "company", "companyName",
            "product", "interestedProduct",
            "products", "interestedProduct",
            "battery", "interestedBattery",
            "batteries", "interestedBattery"
    );

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");
    private static final Pattern ADMIN_MARKER = Pattern.compile("\\[\\[[^\\]]*]]");

    private EmailPlaceholderRenderer() {
    }

    // True when the text still contains an unfinished [[...]] admin section.
    public static boolean hasAdminMarker(String text) {
        return text != null && ADMIN_MARKER.matcher(text).find();
    }

    public static Rendered render(String template, Lead lead, Opportunity opportunity) {
        return render(template, lead, opportunity, Format.TEXT);
    }

    public static Rendered render(String template, Lead lead, Opportunity opportunity, Format format) {

        List<String> issues = new ArrayList<>();

        if (template == null) {
            return new Rendered("", issues);
        }

        Map<String, String> values = values(lead, opportunity);

        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();

        while (m.find()) {

            String name = ALIASES.getOrDefault(m.group(1), m.group(1));
            String value = clean(values.get(name));
            String replacement = m.group();

            if (name.equals("opportunityTitle") && opportunity == null) {
                addOnce(issues, "Opportunity title can only be used when emailing opportunities");
            } else if (!FIELDS.containsKey(name)) {
                addOnce(issues, "Unsupported field \"" + m.group(1) + "\"");
            } else if (value == null) {
                addOnce(issues, FIELDS.get(name) + " is missing for this recipient");
            } else {
                replacement = format == Format.HTML ? escapeHtml(value) : value;
            }

            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }

        m.appendTail(out);

        String text = out.toString();

        if (hasAdminMarker(text)) {
            addOnce(issues, "The message still contains an unfinished [[...]] section that an admin must complete");
        }

        return new Rendered(text, issues);
    }

    // Every spelling a {{token}} may use (the keys of FIELDS and their
    // aliases) -> how it resolves for this recipient, from the same values
    // render() substitutes. {{opportunityTitle}} is only offered for an
    // opportunity, as render() only accepts it there.
    public static Map<String, FieldValue> fieldValues(Lead lead, Opportunity opportunity) {

        Map<String, String> values = values(lead, opportunity);
        Map<String, FieldValue> out = new LinkedHashMap<>();

        FIELDS.forEach((key, label) -> {
            if (!key.equals("opportunityTitle") || opportunity != null) {
                out.put(key, new FieldValue(key, label, clean(values.get(key))));
            }
        });

        ALIASES.forEach((alias, key) -> {
            if (out.containsKey(key)) {
                out.put(alias, out.get(key));
            }
        });

        return out;
    }

    // Trimmed; blank -> null (no value).
    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // The issues of several renderings (subject + body), each reported once.
    public static List<String> issuesOf(Rendered... rendered) {

        List<String> issues = new ArrayList<>();

        for (Rendered r : rendered) {
            r.issues().forEach(i -> addOnce(issues, i));
        }

        return issues;
    }

    public static String escapeHtml(String value) {

        StringBuilder out = new StringBuilder(value.length());

        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }

        return out.toString();
    }

    private static Map<String, String> values(Lead lead, Opportunity opportunity) {

        Map<String, String> values = new LinkedHashMap<>();

        values.put("companyName", lead.getCompanyName());
        values.put("designation", lead.getDesignation());
        values.put("city", lead.getCity());

        if (opportunity != null) {

            values.put("contactPerson", opportunity.getContactPerson());
            values.put("interestedProduct", names(opportunity.getOpportunityProducts() == null ? Stream.empty()
                    : opportunity.getOpportunityProducts().stream()
                        .map(OpportunityProduct::getProduct).filter(Objects::nonNull).map(p -> p.getName())));
            values.put("interestedBattery", names(opportunity.getOpportunityBatteries() == null ? Stream.empty()
                    : opportunity.getOpportunityBatteries().stream()
                        .map(OpportunityBattery::getBattery).filter(Objects::nonNull).map(b -> b.getName())));
            values.put("opportunityTitle", opportunity.getTitle());

        } else {

            values.put("contactPerson", lead.getContactPerson());
            values.put("interestedProduct", names(lead.getLeadProducts() == null ? Stream.empty()
                    : lead.getLeadProducts().stream()
                        .map(LeadProduct::getProduct).filter(Objects::nonNull).map(p -> p.getName())));
            values.put("interestedBattery", names(lead.getLeadBatteries() == null ? Stream.empty()
                    : lead.getLeadBatteries().stream()
                        .map(LeadBattery::getBattery).filter(Objects::nonNull).map(b -> b.getName())));
        }

        return values;
    }

    private static String names(Stream<String> names) {

        return names
                .filter(n -> n != null && !n.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private static void addOnce(List<String> issues, String issue) {
        if (!issues.contains(issue)) {
            issues.add(issue);
        }
    }
}
