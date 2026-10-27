package com.compact.crm.service;

import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.Opportunity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Per-recipient placeholder substitution for bulk email, using the same
// closed set the single-recipient flow already understands
// ({{contactPerson}}, {{companyName}}, {{interestedProduct}}) plus
// {{opportunityTitle}} when emailing opportunities.
//
// Unlike the single flow (which silently substitutes an empty string), a bulk
// message must never go out with a hole in it, so every problem is REPORTED:
//   - an unknown {{name}}                       (typo / unsupported)
//   - a known {{name}} that is empty for this recipient
//   - {{opportunityTitle}} used outside an opportunity batch
//   - an unfinished [[...]] admin section (the AMC template ships with one)
// The caller refuses to send to a recipient that has any issue.
public final class EmailPlaceholderRenderer {

    public record Rendered(String text, List<String> issues) {
    }

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");
    private static final Pattern ADMIN_MARKER = Pattern.compile("\\[\\[[^\\]]*]]");

    private EmailPlaceholderRenderer() {
    }

    // True when the text still contains an unfinished [[...]] admin section.
    public static boolean hasAdminMarker(String text) {
        return text != null && ADMIN_MARKER.matcher(text).find();
    }

    public static Rendered render(String template, Lead lead, Opportunity opportunity) {

        List<String> issues = new ArrayList<>();

        if (template == null) {
            return new Rendered("", issues);
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("contactPerson", lead.getContactPerson());
        values.put("companyName", lead.getCompanyName());
        values.put("interestedProduct", interestedProduct(lead));

        if (opportunity != null) {
            values.put("opportunityTitle", opportunity.getTitle());
        }

        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();

        while (m.find()) {

            String name = m.group(1);
            String replacement;

            if (name.equals("opportunityTitle") && opportunity == null) {
                addOnce(issues, "{{opportunityTitle}} can only be used when emailing opportunities");
                replacement = m.group();
            } else if (!values.containsKey(name)) {
                addOnce(issues, "Unknown placeholder {{" + name + "}}");
                replacement = m.group();
            } else if (values.get(name) == null || values.get(name).isBlank()) {
                addOnce(issues, "{{" + name + "}} is empty for this recipient");
                replacement = "";
            } else {
                replacement = values.get(name).trim();
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

    private static String interestedProduct(Lead lead) {

        if (lead.getLeadProducts() == null) {
            return "";
        }

        return lead.getLeadProducts().stream()
                .map(LeadProduct::getProduct)
                .filter(p -> p != null && p.getName() != null)
                .map(p -> p.getName())
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private static void addOnce(List<String> issues, String issue) {
        if (!issues.contains(issue)) {
            issues.add(issue);
        }
    }
}
