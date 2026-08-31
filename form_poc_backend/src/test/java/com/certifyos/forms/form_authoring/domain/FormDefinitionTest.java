package com.certifyos.forms.form_authoring.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Layout;
import com.certifyos.forms.form_authoring.domain.definition.Origin;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.definition.StepKey;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.shared_kernel.expression.Expression;
import com.certifyos.forms.shared_kernel.expression.Operator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Invariants that live in the aggregates rather than in a service.
 *
 * <p>The headline case is step-key uniqueness. A form composing the same address section twice —
 * Practice Location and Billing Address, which the real Florida Blue form does — shares one answer
 * namespace unless each placement has its own key, and the second address silently destroys the
 * first. That is a wrong answer, not an awkward one, which is why the check is a constructor
 * invariant rather than a validation step someone can forget to call.
 */
class FormDefinitionTest {

    private static final QuestionId ADDR_LINE1 = QuestionId.of("q_addr_line1");
    private static final QuestionId PROVIDER_TYPE = QuestionId.of("q_provider_type");

    private static Step step(String key, String sectionId, int order) {
        return Step.of(key, sectionId, order);
    }

    private static FormDefinition emptyForm() {
        return FormDefinition.draft("fd_1", "tenant_fl", "Florida Blue Recred", "practitioner");
    }

    @Nested
    @DisplayName("step keys are answer namespaces")
    class StepKeys {

        @Test
        @DisplayName("the same section placed twice yields independent answer paths")
        void sameSectionPlacedTwice() {
            var form = emptyForm()
                    .placeStep(step("practiceLocation", "sd_address", 10))
                    .placeStep(step("billingAddress", "sd_address", 20));

            assertEquals(2, form.orderedSteps().size());
            assertEquals(
                    "practiceLocation.line1",
                    form.step("practiceLocation").orElseThrow().pathFor("line1"));
            assertEquals(
                    "billingAddress.line1",
                    form.step("billingAddress").orElseThrow().pathFor("line1"));
        }

        @Test
        @DisplayName("a duplicate step key is rejected — it would overwrite answers")
        void duplicateStepKeyRejected() {
            var form = emptyForm().placeStep(step("billingAddress", "sd_address", 10));
            var e = assertThrows(
                    IllegalArgumentException.class, () -> form.placeStep(step("billingAddress", "sd_other", 20)));
            assertTrue(e.getMessage().contains("already in this form"));
        }

        @Test
        @DisplayName("the constructor enforces uniqueness too, not just the placeStep path")
        void duplicateRejectedAtConstruction() {
            var e = assertThrows(
                    IllegalArgumentException.class,
                    () -> new FormDefinition(
                            "fd",
                            "t",
                            null,
                            "F",
                            "practitioner",
                            null,
                            null,
                            null,
                            List.of(step("dupe", "sd_a", 10), step("dupe", "sd_b", 20)),
                            List.of(),
                            null));
            assertTrue(e.getMessage().contains("overwrite"));
        }

        @Test
        @DisplayName("reserved context namespaces cannot be step keys")
        void reservedNamespacesRejected() {
            for (String reserved : List.of("viewer", "entity", "tenant")) {
                var e = assertThrows(IllegalArgumentException.class, () -> StepKey.of(reserved));
                assertTrue(e.getMessage().contains("reserved"), reserved + " should be reserved");
            }
        }

        @Test
        @DisplayName("punctuation is rejected — a step key is half of every answer path")
        void punctuationRejected() {
            assertThrows(IllegalArgumentException.class, () -> StepKey.of("billing.address"));
            assertThrows(IllegalArgumentException.class, () -> StepKey.of("billing-address"));
        }
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        @Test
        @DisplayName("ordinals follow order, not insertion — forward references depend on this")
        void ordinalsFollowOrder() {
            var form = emptyForm()
                    .placeStep(step("third", "sd_c", 30))
                    .placeStep(step("first", "sd_a", 10))
                    .placeStep(step("second", "sd_b", 20));

            assertEquals(
                    List.of("first", "second", "third"),
                    form.orderedSteps().stream().map(s -> s.key().value()).toList());
            assertEquals(0, form.stepOrdinals().get("first"));
            assertEquals(2, form.stepOrdinals().get("third"));
        }

        @Test
        @DisplayName("a disabled step is excluded from ordinals entirely")
        void disabledStepExcluded() {
            var form = emptyForm()
                    .placeStep(step("first", "sd_a", 10))
                    .placeStep(step("second", "sd_b", 20).disable())
                    .placeStep(step("third", "sd_c", 30));

            assertEquals(2, form.orderedSteps().size());
            assertFalse(form.stepOrdinals().containsKey("second"));
            assertEquals(1, form.stepOrdinals().get("third"));
        }
    }

    @Nested
    @DisplayName("named conditions")
    class NamedConditions {

        private static final Expression EXEMPT =
                new Expression.Leaf("applicantDetails.specialty", Operator.IN, List.of("DC", "OD", "PhD"));

        @Test
        @DisplayName("a condition still in use cannot be removed out from under its steps")
        void cannotRemoveConditionInUse() {
            var form = emptyForm()
                    .withNamedCondition(new FormDefinition.NamedCondition("exempt", "Specialty exempt", EXEMPT))
                    .placeStep(step("dea", "sd_dea", 10)
                            .withVisibleWhen(new Expression.Not(new Expression.Ref("exempt"))));

            var e = assertThrows(IllegalStateException.class, () -> form.removeNamedCondition("exempt"));
            assertTrue(e.getMessage().contains("dea"), "the message should name the steps still using it");
        }

        @Test
        @DisplayName("an unused condition removes cleanly")
        void canRemoveUnusedCondition() {
            var form = emptyForm()
                    .withNamedCondition(new FormDefinition.NamedCondition("exempt", "Specialty exempt", EXEMPT));
            assertTrue(form.removeNamedCondition("exempt").namedConditions().isEmpty());
        }

        @Test
        @DisplayName("usage is found through nesting, not just at the top level")
        void findsRefNestedInsideAGroup() {
            var nested = new Expression.All(List.of(
                    new Expression.Leaf("applicantDetails.npi", Operator.EXISTS, null),
                    new Expression.Not(new Expression.Ref("exempt"))));

            var form = emptyForm()
                    .withNamedCondition(new FormDefinition.NamedCondition("exempt", "Specialty exempt", EXEMPT))
                    .placeStep(step("dea", "sd_dea", 10).withVisibleWhen(nested));

            assertThrows(IllegalStateException.class, () -> form.removeNamedCondition("exempt"));
        }

        @Test
        @DisplayName("a named condition without a label is rejected — the label is what the rule reads as")
        void labelRequired() {
            assertThrows(
                    IllegalArgumentException.class, () -> new FormDefinition.NamedCondition("exempt", "  ", EXEMPT));
        }
    }

    @Nested
    @DisplayName("section definitions")
    class Sections {

        private static SectionDefinition address() {
            return new SectionDefinition(
                    "sd_address",
                    "tenant_fl",
                    "address",
                    "Address",
                    null,
                    "st_address",
                    2,
                    List.of(
                            QuestionInstance.fromTemplate("line1", ADDR_LINE1, 10, true),
                            QuestionInstance.fromTemplate("city", QuestionId.of("q_addr_city"), 20, true)),
                    true);
        }

        @Test
        @DisplayName("duplicate question keys are rejected — they become answer paths")
        void duplicateQuestionKeys() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new SectionDefinition(
                            "sd",
                            "t",
                            "k",
                            "Name",
                            null,
                            null,
                            null,
                            List.of(
                                    QuestionInstance.fromTemplate("line1", ADDR_LINE1, 10, true),
                                    QuestionInstance.fromTemplate("line1", PROVIDER_TYPE, 20, true)),
                            true));
        }

        @Test
        @DisplayName("a template question is disabled, never removed — provenance must survive")
        void templateQuestionCannotBeRemoved() {
            var e = assertThrows(IllegalStateException.class, () -> address().removeQuestion("line1"));
            assertTrue(e.getMessage().contains("Disable it instead"));
        }

        @Test
        @DisplayName("a locally added question can be removed")
        void addedQuestionCanBeRemoved() {
            var section =
                    address().addQuestion(QuestionInstance.added("line2", QuestionId.of("q_addr_line2"), 15, false));
            assertEquals(2, section.removeQuestion("line2").questions().size());
        }

        @Test
        @DisplayName("disabling keeps the question but drops it from what compiles")
        void disableKeepsProvenance() {
            var section = address().disableQuestion("line1");

            assertEquals(2, section.questions().size(), "the question is still recorded");
            assertEquals(1, section.enabledQuestions().size(), "but it will not reach the artifact");
            assertEquals(
                    Origin.TEMPLATE, section.question("line1").orElseThrow().origin());
        }

        @Test
        @DisplayName("enabled questions come back in order, regardless of insertion")
        void enabledQuestionsAreOrdered() {
            var section =
                    address().addQuestion(QuestionInstance.added("line2", QuestionId.of("q_addr_line2"), 15, false));
            assertEquals(
                    List.of("line1", "line2", "city"),
                    section.enabledQuestions().stream()
                            .map(QuestionInstance::key)
                            .toList());
        }

        @Test
        @DisplayName("externalRefs is computed — it is the section's contract, not an author's claim")
        void externalRefsComputed() {
            var section = address()
                    .addQuestion(new QuestionInstance(
                            "billingFax",
                            QuestionId.of("q_fax"),
                            Origin.ADDED,
                            true,
                            30,
                            false,
                            Layout.HALF,
                            null,
                            null,
                            new Expression.Leaf("hasCaqhId", Operator.EQ, "Yes"),
                            null,
                            null,
                            null));

            assertEquals(java.util.Set.of("hasCaqhId"), section.externalRefs());
            assertFalse(section.isSelfContained());
        }

        @Test
        @DisplayName("a section referencing only its own questions is self-contained")
        void selfContainedSection() {
            var section = address()
                    .addQuestion(new QuestionInstance(
                            "line2",
                            QuestionId.of("q_addr_line2"),
                            Origin.ADDED,
                            true,
                            15,
                            false,
                            Layout.HALF,
                            null,
                            null,
                            new Expression.Leaf("line1", Operator.EXISTS, null),
                            null,
                            null,
                            null));

            assertTrue(section.isSelfContained());
        }
    }

    @Nested
    @DisplayName("layout")
    class Layouts {

        @Test
        @DisplayName("defaults to full width when the author expresses no preference")
        void defaultsToFull() {
            assertEquals(
                    12,
                    QuestionInstance.fromTemplate("k", ADDR_LINE1, 10, true)
                            .layout()
                            .columns());
        }

        @Test
        @DisplayName("a column count outside the 12-grid is rejected")
        void rejectsOutOfRange() {
            assertThrows(IllegalArgumentException.class, () -> new Layout(0));
            assertThrows(IllegalArgumentException.class, () -> new Layout(13));
        }
    }
}
