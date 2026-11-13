package com.compact.crm.service;

import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.Product;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppTemplateRendererTest {

    private static final String EXAMPLE = "Hi {{contactPerson}}, we're following up regarding {{interestedProduct}} "
            + "at {{companyName}}. Please let us know if you'd like to discuss your requirements.";

    private Lead lead(String contact, String company, String product) {
        Lead lead = Lead.builder().contactPerson(contact).companyName(company).city("Pune").build();
        if (product != null) {
            lead.getLeadProducts().add(LeadProduct.builder().lead(lead)
                    .product(Product.builder().name(product).build()).quantity(1).build());
        }
        return lead;
    }

    @Test
    void resolvesEveryFieldFromTheRecord() {

        EmailPlaceholderRenderer.Rendered r = WhatsAppTemplateRenderer.render(
                EXAMPLE, lead("Rahul", "ABC Hospitals", "Enterprise Series UPS"), null);

        assertThat(r.issues()).isEmpty();
        assertThat(r.text()).isEqualTo("Hi Rahul, we're following up regarding Enterprise Series UPS at ABC Hospitals. "
                + "Please let us know if you'd like to discuss your requirements.");
        assertThat(r.text()).doesNotContain("{{").doesNotContain("}}");
    }

    @Test
    void aliasesResolveLikeEmail() {
        assertThat(WhatsAppTemplateRenderer.render("Hello {{name}} of {{company}}", lead("Asha", "Acme", null), null).text())
                .isEqualTo("Hello Asha of Acme");
    }

    @Test
    void aMissingValueIsAnIssue_andTheTokenIsNeverBlankedOrGuessed() {

        EmailPlaceholderRenderer.Rendered r = WhatsAppTemplateRenderer.render(EXAMPLE, lead("Rahul", "ABC", null), null);

        assertThat(r.issues()).containsExactly("Product is missing for this recipient");
        assertThat(r.text()).contains("{{interestedProduct}}");
    }

    @Test
    void blankContactIsMissing_notAnEmptyGreeting() {
        assertThat(WhatsAppTemplateRenderer.render("Hi {{contactPerson}}", lead("   ", "ABC", null), null).issues())
                .containsExactly("Contact person is missing for this recipient");
    }

    @Test
    void unknownFieldsAndOpportunityTitleOnALeadAreIssues() {

        assertThat(WhatsAppTemplateRenderer.render("Hi {{firstName}}", lead("A", "B", null), null).issues())
                .containsExactly("Unsupported field \"firstName\"");

        assertThat(WhatsAppTemplateRenderer.render("Re {{opportunityTitle}}", lead("A", "B", null), null).issues())
                .containsExactly("Opportunity title can only be used when emailing opportunities");
    }

    @Test
    void opportunityTitleResolvesForAnOpportunity() {

        Lead l = lead("A", "B", null);
        Opportunity o = Opportunity.builder().title("UPS upgrade").contactPerson("Ravi").lead(l).build();

        EmailPlaceholderRenderer.Rendered r = WhatsAppTemplateRenderer.render("{{contactPerson}}: {{opportunityTitle}}", l, o);

        assertThat(r.issues()).isEmpty();
        assertThat(r.text()).isEqualTo("Ravi: UPS upgrade");
    }

    @Test
    void strayBracesThatAreNotAFieldNeverReachTheCustomer() {

        assertThat(WhatsAppTemplateRenderer.render("Hi {{ first-name }}", lead("A", "B", null), null).issues())
                .containsExactly("The message contains \"{{\" or \"}}\" that is not a supported field");

        assertThat(WhatsAppTemplateRenderer.render("Price }} now", lead("A", "B", null), null).issues()).isNotEmpty();
    }

    @Test
    void emptyTooLongAndUnfinishedAdminSectionsAreIssues() {

        assertThat(WhatsAppTemplateRenderer.render("   ", lead("A", "B", null), null).issues())
                .contains("The message is empty");

        assertThat(WhatsAppTemplateRenderer.render("x".repeat(4097), lead("A", "B", null), null).issues())
                .contains("The message is longer than WhatsApp's 4096-character limit");

        assertThat(WhatsAppTemplateRenderer.render("Hi [[ADMIN: fill me]]", lead("A", "B", null), null).issues())
                .isNotEmpty();
    }

    @Test
    void valuesAreNotHtmlEscaped_sinceWhatsAppIsPlainText() {
        assertThat(WhatsAppTemplateRenderer.render("For {{companyName}}", lead("A", "O'Brien & Sons", null), null).text())
                .isEqualTo("For O'Brien & Sons");
    }

    @Test
    void validate_reportsOnlyTemplateProblems_neverMissingRecipientData() {

        assertThat(WhatsAppTemplateRenderer.validate(EXAMPLE)).isEmpty();
        assertThat(WhatsAppTemplateRenderer.validate("Re {{opportunityTitle}} for {{designation}} in {{city}} "
                + "({{interestedBattery}})")).isEmpty();
        assertThat(WhatsAppTemplateRenderer.validate("Hi {{nickname}}")).containsExactly("Unsupported field \"nickname\"");
        assertThat(WhatsAppTemplateRenderer.validate("Hi {{ bad-token }}")).isNotEmpty();
    }
}
