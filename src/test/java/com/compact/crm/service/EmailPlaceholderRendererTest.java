package com.compact.crm.service;

import com.compact.crm.entity.Battery;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.LeadBattery;
import com.compact.crm.entity.LeadProduct;
import com.compact.crm.entity.Opportunity;
import com.compact.crm.entity.OpportunityBattery;
import com.compact.crm.entity.OpportunityProduct;
import com.compact.crm.entity.Product;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmailPlaceholderRendererTest {

    private static Product product(String name) {
        return Product.builder().name(name).build();
    }

    private static Battery battery(String name) {
        return Battery.builder().name(name).build();
    }

    private static Lead lead() {

        Lead lead = Lead.builder()
                .companyName("Acme & Sons")
                .contactPerson("Asha")
                .designation("Plant Head")
                .city("Pune")
                .build();

        lead.getLeadProducts().add(LeadProduct.builder().lead(lead).product(product("UPS 10kVA")).quantity(2).build());
        lead.getLeadProducts().add(LeadProduct.builder().lead(lead).product(product("Inverter")).quantity(1).build());
        lead.getLeadProducts().add(LeadProduct.builder().lead(lead).product(product("UPS 10kVA")).quantity(1).build());
        lead.getLeadBatteries().add(LeadBattery.builder().lead(lead).battery(battery("SMF 42Ah")).quantity(8).build());

        return lead;
    }

    @Test
    void lead_resolvesEveryFieldFromThatLead() {

        EmailPlaceholderRenderer.Rendered r = EmailPlaceholderRenderer.render(
                "{{contactPerson}}|{{companyName}}|{{designation}}|{{city}}|{{interestedProduct}}|{{interestedBattery}}",
                lead(), null);

        assertThat(r.issues()).isEmpty();
        // several products: every distinct name, in line-item order
        assertThat(r.text()).isEqualTo("Asha|Acme & Sons|Plant Head|Pune|UPS 10kVA, Inverter|SMF 42Ah");
    }

    // The draft editor shows these values in place of the tokens, so they
    // must be exactly what render() substitutes - for every spelling.
    @Test
    void fieldValues_areExactlyWhatRenderSubstitutes_forEverySpelling() {

        Lead lead = lead();
        java.util.Map<String, EmailPlaceholderRenderer.FieldValue> values = EmailPlaceholderRenderer.fieldValues(lead, null);

        assertThat(values).containsKeys("contactPerson", "companyName", "designation", "city",
                "interestedProduct", "interestedBattery", "name", "company", "product", "products", "battery", "batteries");
        assertThat(values).doesNotContainKey("opportunityTitle");

        values.forEach((spelling, field) -> {
            assertThat(EmailPlaceholderRenderer.render("{{" + spelling + "}}", lead, null).text()).isEqualTo(field.value());
            assertThat(EmailPlaceholderRenderer.FIELDS).containsEntry(field.key(), field.label());
        });
    }

    @Test
    void fieldValues_reportAMissingValueAsNull_andAnOpportunitysOwnValues() {

        Lead lead = lead();
        lead.setDesignation("  ");

        assertThat(EmailPlaceholderRenderer.fieldValues(lead, null).get("designation").value()).isNull();

        Opportunity o = Opportunity.builder().title("AMC 2027").contactPerson(" Ravi ").build();
        var values = EmailPlaceholderRenderer.fieldValues(lead, o);

        assertThat(values.get("contactPerson").value()).isEqualTo("Ravi");
        assertThat(values.get("opportunityTitle").value()).isEqualTo("AMC 2027");
        assertThat(values.get("interestedProduct").value()).isNull();      // the opportunity has no line items
    }

    @Test
    void shortAliases_resolveToTheSameFields() {

        assertThat(EmailPlaceholderRenderer.render("{{name}} / {{company}} / {{product}} / {{battery}}", lead(), null).text())
                .isEqualTo("Asha / Acme & Sons / UPS 10kVA, Inverter / SMF 42Ah");
    }

    @Test
    void opportunity_usesItsOwnContactAndLineItems_notTheLeads() {

        Lead lead = lead();

        Opportunity o = Opportunity.builder()
                .title("Genset AMC")
                .lead(lead)
                .contactPerson("Ravi")
                .opportunityProducts(new ArrayList<>())
                .opportunityBatteries(new ArrayList<>())
                .build();

        o.getOpportunityProducts().add(OpportunityProduct.builder().opportunity(o).product(product("Genset 50kVA")).quantity(1).build());
        o.getOpportunityBatteries().add(OpportunityBattery.builder().opportunity(o).battery(battery("Tubular 150Ah")).quantity(4).build());

        EmailPlaceholderRenderer.Rendered r = EmailPlaceholderRenderer.render(
                "{{contactPerson}} at {{companyName}}: {{opportunityTitle}} - {{product}} + {{battery}}", lead, o);

        assertThat(r.issues()).isEmpty();
        assertThat(r.text()).isEqualTo("Ravi at Acme & Sons: Genset AMC - Genset 50kVA + Tubular 150Ah");
    }

    @Test
    void missingValues_areReported_andTheTokenIsKept_neverBlankedOrInvented() {

        Lead lead = Lead.builder().companyName("Acme").contactPerson("  ").build();

        EmailPlaceholderRenderer.Rendered r = EmailPlaceholderRenderer.render(
                "Dear {{contactPerson}}, about {{interestedBattery}}", lead, null);

        assertThat(r.text()).isEqualTo("Dear {{contactPerson}}, about {{interestedBattery}}");
        assertThat(r.issues()).containsExactly(
                "Contact person is missing for this recipient",
                "Battery is missing for this recipient");
    }

    @Test
    void unknownFields_andOpportunityOnlyFieldsOnALead_areReported() {

        EmailPlaceholderRenderer.Rendered r = EmailPlaceholderRenderer.render(
                "{{nmae}} {{opportunityTitle}}", lead(), null);

        assertThat(r.text()).isEqualTo("{{nmae}} {{opportunityTitle}}");
        assertThat(r.issues()).containsExactly(
                "Unsupported field \"nmae\"",
                "Opportunity title can only be used when emailing opportunities");
    }

    @Test
    void html_escapesValuesSoCrmDataCannotInjectMarkup() {

        Lead lead = Lead.builder()
                .companyName("<script>alert(1)</script> & \"Co\"")
                .contactPerson("O'Brien")
                .build();

        EmailPlaceholderRenderer.Rendered html = EmailPlaceholderRenderer.render(
                "<p>Dear {{contactPerson}} of {{companyName}}</p>", lead, null, EmailPlaceholderRenderer.Format.HTML);

        // the template's own markup is kept; only the substituted values are escaped
        assertThat(html.text()).isEqualTo(
                "<p>Dear O&#39;Brien of &lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;Co&quot;</p>");
    }

    @Test
    void text_keepsValuesVerbatim_becausePlainTextHasNoMarkupToEscape() {

        Lead lead = Lead.builder().companyName("Acme & Sons").contactPerson("O'Brien").build();

        assertThat(EmailPlaceholderRenderer.render("{{name}} - {{company}}", lead, null).text())
                .isEqualTo("O'Brien - Acme & Sons");
    }

    @Test
    void valuesContainingRegexOrTokenSyntax_areInsertedLiterally_andNotReRendered() {

        Lead lead = Lead.builder().companyName("$1 {{city}} \\ Ltd").contactPerson("Asha").city("Pune").build();

        EmailPlaceholderRenderer.Rendered r = EmailPlaceholderRenderer.render("{{companyName}}", lead, null);

        assertThat(r.text()).isEqualTo("$1 {{city}} \\ Ltd");
        assertThat(r.issues()).isEmpty();
    }

    @Test
    void theFieldCatalogue_isTheOneTheFrontendOffers() {

        assertThat(List.copyOf(EmailPlaceholderRenderer.FIELDS.keySet())).containsExactly(
                "contactPerson", "companyName", "designation", "city",
                "interestedProduct", "interestedBattery", "opportunityTitle");
    }
}
