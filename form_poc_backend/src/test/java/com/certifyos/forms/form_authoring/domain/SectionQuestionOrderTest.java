package com.certifyos.forms.form_authoring.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.form_authoring.domain.definition.Layout;
import com.certifyos.forms.form_authoring.domain.definition.Origin;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Reordering the questions in a section.
 *
 * <p>The operation takes the <b>whole key list</b> rather than one question and a target position,
 * and the tests below are mostly about why that is not fussiness. A partial reorder cannot be made
 * safe: any question left out keeps its old number, two questions can end up sharing one, and a tied
 * sort renders the form in a different sequence depending on iteration order. That failure does not
 * throw — it shows up as "the form looked different after a reload", which is close to
 * undebuggable.
 */
class SectionQuestionOrderTest {

    private static QuestionInstance question(String key, int order, Origin origin, boolean enabled) {
        return new QuestionInstance(
                key,
                QuestionId.of("q_" + key),
                origin,
                enabled,
                order,
                true,
                Layout.FULL,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static SectionDefinition section(QuestionInstance... questions) {
        return new SectionDefinition(
                "sd_1", "tenant_1", "address", "Address", null, "st_address", 2, List.of(questions), true);
    }

    private static SectionDefinition threeQuestions() {
        return section(
                question("line1", 10, Origin.TEMPLATE, true),
                question("city", 20, Origin.TEMPLATE, true),
                question("zip", 30, Origin.TEMPLATE, true));
    }

    private static List<String> keysInOrder(SectionDefinition section) {
        return section.questions().stream()
                .sorted(java.util.Comparator.comparingInt(QuestionInstance::order))
                .map(QuestionInstance::key)
                .toList();
    }

    @Nested
    @DisplayName("reordering")
    class Reordering {

        @Test
        @DisplayName("applies the requested sequence")
        void appliesSequence() {
            SectionDefinition reordered = threeQuestions().reorderQuestions(List.of("zip", "line1", "city"));

            assertEquals(List.of("zip", "line1", "city"), keysInOrder(reordered));
        }

        @Test
        @DisplayName("renormalises to 10, 20, 30 so gaps and skew never accumulate")
        void renormalises() {
            SectionDefinition drifted = section(
                    question("line1", 7, Origin.TEMPLATE, true),
                    question("city", 12, Origin.TEMPLATE, true),
                    question("zip", 900, Origin.TEMPLATE, true));

            SectionDefinition reordered = drifted.reorderQuestions(List.of("line1", "city", "zip"));

            assertEquals(
                    List.of(10, 20, 30),
                    reordered.questions().stream().map(QuestionInstance::order).toList());
        }

        @Test
        @DisplayName("never produces two questions sharing an order, which is what makes the sort stable")
        void ordersAreUnique() {
            SectionDefinition collided = section(
                    question("line1", 20, Origin.TEMPLATE, true),
                    question("city", 20, Origin.TEMPLATE, true),
                    question("zip", 20, Origin.TEMPLATE, true));

            SectionDefinition reordered = collided.reorderQuestions(List.of("city", "zip", "line1"));

            long distinct = reordered.questions().stream()
                    .map(QuestionInstance::order)
                    .distinct()
                    .count();
            assertEquals(3, distinct);
        }

        @Test
        @DisplayName("is idempotent — applying the same order twice changes nothing")
        void idempotent() {
            SectionDefinition once = threeQuestions().reorderQuestions(List.of("zip", "city", "line1"));
            SectionDefinition twice = once.reorderQuestions(List.of("zip", "city", "line1"));

            assertEquals(once, twice);
        }

        @Test
        @DisplayName("disabled questions hold a position, so re-enabling restores them in place")
        void disabledQuestionsKeepTheirPlace() {
            SectionDefinition withDisabled = section(
                    question("line1", 10, Origin.TEMPLATE, true),
                    question("city", 20, Origin.TEMPLATE, false),
                    question("zip", 30, Origin.TEMPLATE, true));

            // A reorder covering only the enabled ones would push `city` to the end on re-enable.
            SectionDefinition reordered = withDisabled.reorderQuestions(List.of("zip", "city", "line1"));

            assertEquals(List.of("zip", "city", "line1"), keysInOrder(reordered));
            assertEquals(20, reordered.question("city").orElseThrow().order());
        }

        @Test
        @DisplayName("leaves provenance, overrides and conditions untouched")
        void preservesEverythingElse() {
            QuestionInstance customised = new QuestionInstance(
                    "city",
                    QuestionId.of("q_city"),
                    Origin.ADDED,
                    false,
                    20,
                    false,
                    Layout.HALF,
                    "Town",
                    "help",
                    null,
                    null,
                    null,
                    null);
            SectionDefinition reordered = section(question("line1", 10, Origin.TEMPLATE, true), customised)
                    .reorderQuestions(List.of("city", "line1"));

            QuestionInstance after = reordered.question("city").orElseThrow();
            // A reorder that quietly reset origin would break drift; one that dropped an override
            // would silently restore the template's wording.
            assertEquals(Origin.ADDED, after.origin());
            assertEquals("Town", after.labelOverride());
            assertEquals("help", after.helpTextOverride());
            assertEquals(Layout.HALF, after.layout());
            assertTrue(!after.enabled());
        }
    }

    @Nested
    @DisplayName("rejecting an unsafe request")
    class Rejections {

        @Test
        @DisplayName("a missing key is refused rather than partially applied")
        void refusesMissingKey() {
            // Applying this would leave `zip` on its old number, free to collide with a renumbered one.
            IllegalArgumentException thrown = assertThrows(
                    IllegalArgumentException.class, () -> threeQuestions().reorderQuestions(List.of("city", "line1")));

            assertTrue(thrown.getMessage().contains("Missing"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("zip"), thrown.getMessage());
        }

        @Test
        @DisplayName("a key from another section is refused, and named")
        void refusesUnknownKey() {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> threeQuestions()
                    .reorderQuestions(List.of("line1", "city", "zip", "elsewhere")));

            assertTrue(thrown.getMessage().contains("Not in this section"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("elsewhere"), thrown.getMessage());
        }

        @Test
        @DisplayName("a duplicated key is refused")
        void refusesDuplicate() {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> threeQuestions()
                    .reorderQuestions(List.of("line1", "line1", "city")));

            assertTrue(thrown.getMessage().contains("Duplicate"), thrown.getMessage());
        }

        @Test
        @DisplayName("an empty request against a non-empty section is refused, not treated as a no-op")
        void refusesEmpty() {
            assertThrows(IllegalArgumentException.class, () -> threeQuestions().reorderQuestions(List.of()));
        }
    }
}
