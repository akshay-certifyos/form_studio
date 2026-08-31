package com.certifyos.forms.form_authoring.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.definition.StepKey;
import com.certifyos.forms.form_authoring.infrastructure.mongo.FormDefinitionDocument;
import com.certifyos.forms.shared_kernel.expression.Expression;
import com.certifyos.forms.shared_kernel.expression.Operator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A mapper is where fields get silently dropped.
 *
 * <p>Forgetting one in {@code from()} loses data on save; forgetting one in {@code toDomain()} loses
 * it on load. Either way there is no error — the value is simply gone, and a condition that quietly
 * became null means a step that is quietly always visible.
 *
 * <p>So this asserts on the <b>whole aggregate</b> after a round trip rather than field by field.
 * Records give value equality, which means adding a field to {@link FormDefinition} without
 * extending the mapper fails here automatically. A field-by-field test would have needed someone to
 * remember to update it, which is exactly the thing that does not happen.
 */
class FormDefinitionDocumentTest {

    /** A definition using every part of the shape worth persisting. */
    private static FormDefinition fullyPopulated() {
        Expression exemption =
                new Expression.Leaf("applicantDetails.specialty", Operator.IN, List.of("DC", "OD", "PhD"));

        Expression compound = new Expression.All(List.of(
                new Expression.Leaf("billingSetup.hasCaqhId", Operator.EQ, "Yes"),
                new Expression.Leaf("billingSetup.billingSameAsPrimary", Operator.EQ, "No")));

        Expression quantified = new Expression.Some(
                "licensure", new Expression.Leaf("@item.licenseExpiration", Operator.LT, "2027-01-01"));

        Step applicant = Step.of("applicantDetails", "sd_applicant", 10).withGroup("Applicant Information");

        Step licensure = new Step(
                StepKey.of("licensure"),
                "sd_licensure",
                20,
                true,
                "Licensure",
                "Credentials",
                new Step.Repeating(1, 20, "License"),
                null,
                null);

        Step dea = Step.of("deaRegistration", "sd_dea", 30)
                .withVisibleWhen(new Expression.Not(new Expression.Ref("specialtyExempt")));

        Step billing = new Step(
                StepKey.of("billingAddress"),
                "sd_address",
                40,
                true,
                "Billing Address",
                "Billing",
                null,
                compound,
                new Expression.Leaf("viewer.role", Operator.EQ, "admin"));

        Step disabled = Step.of("legacyStep", "sd_legacy", 50).disable();

        return new FormDefinition(
                        "fd_fl_blue",
                        "tenant_fl",
                        "ft_existing",
                        "Florida Blue Recred Practitioner Application",
                        "practitioner",
                        "fb_practitioner_recred",
                        2,
                        null,
                        List.of(applicant, licensure, dea, billing, disabled),
                        List.of(new FormDefinition.HardStop(
                                "noActiveLicense",
                                quantified,
                                "An active state license is required to proceed.",
                                "next")),
                        FormDefinition.DefinitionStatus.READY)
                .withNamedCondition(new FormDefinition.NamedCondition(
                        "specialtyExempt", "Specialty exempt from DEA requirements", exemption))
                .withNamedCondition(new FormDefinition.NamedCondition(
                        "licenseExpiringSoon", "Any license expires within 90 days", quantified));
    }

    @Test
    @DisplayName("the whole aggregate survives a save and load unchanged")
    void roundTripsCompletely() {
        FormDefinition original = fullyPopulated();
        FormDefinition reloaded = FormDefinitionDocument.from(original).toDomain();

        // Value equality on records: any field added to FormDefinition but missed in the mapper
        // fails right here, with no test maintenance required.
        assertEquals(original, reloaded);
    }

    @Test
    @DisplayName("a second round trip is stable — repeated saves do not drift")
    void isIdempotent() {
        FormDefinition once = FormDefinitionDocument.from(fullyPopulated()).toDomain();
        FormDefinition twice = FormDefinitionDocument.from(once).toDomain();
        assertEquals(once, twice);
    }

    @Test
    @DisplayName("a disabled step is persisted, not dropped")
    void disabledStepSurvives() {
        FormDefinition reloaded = FormDefinitionDocument.from(fullyPopulated()).toDomain();

        Step legacy = reloaded.step("legacyStep").orElseThrow();
        assertTrue(!legacy.enabled(), "disabled steps must persist — an author expects to re-enable them");
        assertEquals(4, reloaded.orderedSteps().size(), "but they stay out of what compiles");
    }

    @Test
    @DisplayName("a ref survives persistence unexpanded — inlining happens at compile, not at save")
    void refIsNotInlinedOnSave() {
        FormDefinition reloaded = FormDefinitionDocument.from(fullyPopulated()).toDomain();

        Expression condition = reloaded.step("deaRegistration").orElseThrow().visibleWhen();
        assertTrue(condition instanceof Expression.Not, "the Hide verb should survive");
        assertTrue(
                ((Expression.Not) condition).operand() instanceof Expression.Ref,
                "the definition keeps the reference; only the published artifact inlines it, which is "
                        + "what lets an author edit a named condition and see every step update");
    }

    @Test
    @DisplayName("repeating configuration survives")
    void repeatingSurvives() {
        FormDefinition reloaded = FormDefinitionDocument.from(fullyPopulated()).toDomain();

        Step.Repeating repeating = reloaded.step("licensure").orElseThrow().repeating();
        assertNotNull(repeating);
        assertEquals(20, repeating.max());
        assertEquals("License", repeating.itemLabel());
    }

    @Test
    @DisplayName("a step with no condition reloads as null, not as an empty expression")
    void absentConditionStaysAbsent() {
        FormDefinition reloaded = FormDefinitionDocument.from(fullyPopulated()).toDomain();
        assertNull(reloaded.step("applicantDetails").orElseThrow().visibleWhen());
    }

    @Test
    @DisplayName("non-answer context in a condition survives — viewer, entity, tenant")
    void contextConditionSurvives() {
        FormDefinition reloaded = FormDefinitionDocument.from(fullyPopulated()).toDomain();

        Expression audience = reloaded.step("billingAddress").orElseThrow().audienceWhen();
        assertTrue(audience instanceof Expression.Leaf);
        assertEquals("viewer.role", ((Expression.Leaf) audience).path());
    }
}
