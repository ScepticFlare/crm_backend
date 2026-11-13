package com.compact.crm.service;

import com.compact.crm.entity.Battery;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadBattery;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.OpportunityBattery;
import com.compact.crm.entity.OpportunityProduct;
import com.compact.crm.entity.Product;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// Renders a CRM WhatsApp template for ONE recipient. The authoritative renderer
// for previews AND sends - the frontend never substitutes anything itself.
//
// Field resolution is delegated to EmailPlaceholderRenderer, so WhatsApp
// supports exactly the same closed set of {{fields}} (and aliases), resolved
// from the same Lead / Opportunity values, with the same rules: an unknown
// field, a field with no value for this recipient, {{opportunityTitle}} outside
// an opportunity, or an unfinished [[...]] section is an ISSUE and the token is
// left in place - never blanked or guessed. Callers refuse to send a message
// with any issue. WhatsApp adds:
//   - no stray "{{" / "}}" may remain (e.g. "{{ first-name }}", which is not a
//     valid token and would otherwise reach the customer as raw braces);
//   - the rendered text must not be blank and must fit WhatsApp's 4,096-character
//     text limit.
public final class WhatsAppTemplateRenderer {

    public static final int MAX_LENGTH = 4096;

    private static final Pattern STRAY_BRACES = Pattern.compile("\\{\\{|}}");

    private WhatsAppTemplateRenderer() {
    }

    public static EmailPlaceholderRenderer.Rendered render(String body, Lead lead, Opportunity opportunity) {

        EmailPlaceholderRenderer.Rendered r = EmailPlaceholderRenderer.render(
                body, lead, opportunity, EmailPlaceholderRenderer.Format.TEXT);

        List<String> issues = new ArrayList<>(r.issues());
        String text = r.text();

        // Only report raw braces when every {{field}} itself resolved - otherwise
        // the unresolved field is already reported (and is what left the braces).
        if (issues.isEmpty() && STRAY_BRACES.matcher(text).find()) {
            issues.add("The message contains \"{{\" or \"}}\" that is not a supported field");
        }

        if (text.isBlank()) {
            issues.add("The message is empty");
        }

        if (text.length() > MAX_LENGTH) {
            issues.add("The message is longer than WhatsApp's " + MAX_LENGTH + "-character limit");
        }

        return new EmailPlaceholderRenderer.Rendered(text, issues);
    }

    // Template-level problems (independent of any recipient): rendered against a
    // representative sample in which EVERY field has a value, so whatever is
    // still reported is a problem with the template itself (unsupported field,
    // stray braces, unfinished [[...]] section, too long, empty).
    public static List<String> validate(String body) {
        return render(body, sampleLead(), sampleOpportunity()).issues();
    }

    // ---- representative data for the admin preview (clearly labelled as sample) ----

    public static Lead sampleLead() {

        Lead lead = Lead.builder()
                .companyName("ABC Hospitals")
                .contactPerson("Rahul Sharma")
                .designation("Facilities Manager")
                .city("Chennai")
                .build();

        lead.getLeadProducts().add(LeadProduct.builder()
                .lead(lead).product(Product.builder().name("Enterprise Series UPS").build()).quantity(1).build());
        lead.getLeadBatteries().add(LeadBattery.builder()
                .lead(lead).battery(Battery.builder().name("SMF 12V 100Ah").build()).quantity(1).build());

        return lead;
    }

    public static Opportunity sampleOpportunity() {

        Lead lead = sampleLead();

        Opportunity opportunity = Opportunity.builder()
                .title("Hospital UPS upgrade")
                .contactPerson("Rahul Sharma")
                .lead(lead)
                .build();

        opportunity.getOpportunityProducts().add(OpportunityProduct.builder()
                .opportunity(opportunity).product(Product.builder().name("Enterprise Series UPS").build())
                .quantity(1).build());
        opportunity.getOpportunityBatteries().add(OpportunityBattery.builder()
                .opportunity(opportunity).battery(Battery.builder().name("SMF 12V 100Ah").build())
                .quantity(1).build());

        return opportunity;
    }
}
