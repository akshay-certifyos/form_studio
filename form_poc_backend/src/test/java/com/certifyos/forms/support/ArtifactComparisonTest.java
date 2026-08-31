package com.certifyos.forms.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.form_authoring.domain.publishing.CompiledForm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The comparator is the round-trip gate's measuring instrument, so it needs calibrating before any
 * conclusion drawn with it means anything.
 *
 * <p>Two failure modes matter and they are opposite. A comparator that is too strict reports
 * differences that are not real — option ordering, absent-versus-empty — and the genuine findings
 * drown. One that is too lax reports equivalence for an artifact that lost content, which would let
 * the POC claim it round-trips production forms when it does not. Both are tested here.
 */
class ArtifactComparisonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CompiledForm.CompiledField field(String name, String label, boolean required) {
        return new CompiledForm.CompiledField(
                name,
                label,
                "text",
                required,
                null,
                null,
                null,
                new CompiledForm.CompiledLayout(12),
                null,
                null,
                null,
                null,
                null);
    }

    private static CompiledForm form(CompiledForm.CompiledStep... steps) {
        return new CompiledForm("Recred", null, List.of(steps), null);
    }

    private static CompiledForm.CompiledStep step(String id, CompiledForm.CompiledField... fields) {
        return new CompiledForm.CompiledStep(id, id + " title", null, List.of(fields), null, null, null);
    }

    @Nested
    @DisplayName("equivalence")
    class Equivalence {

        @Test
        @DisplayName("an artifact is equivalent to itself")
        void identity() {
            CompiledForm artifact = form(step("applicant", field("applicant.npi", "NPI", true)));

            assertTrue(ArtifactComparison.compare(artifact, artifact).isEquivalent());
        }

        @Test
        @DisplayName("option order is not a difference — existing configs list the same set in different orders")
        void optionOrderIgnored() {
            CompiledForm.CompiledOption md = new CompiledForm.CompiledOption("MD", "Medical Doctor", null);
            CompiledForm.CompiledOption dc = new CompiledForm.CompiledOption("DC", "Chiropractor", null);

            CompiledForm expected = form(step("s", withOptions(List.of(md, dc))));
            CompiledForm actual = form(step("s", withOptions(List.of(dc, md))));

            assertTrue(ArtifactComparison.compare(expected, actual).isEquivalent());
        }

        @Test
        @DisplayName("absent and empty option lists are the same field")
        void absentVersusEmpty() {
            CompiledForm expected = form(step("s", withOptions(null)));
            CompiledForm actual = form(step("s", withOptions(List.of())));

            assertTrue(ArtifactComparison.compare(expected, actual).isEquivalent());
        }

        @Test
        @DisplayName("absent and false `required` are the same field")
        void absentVersusFalseRequired() {
            CompiledForm.CompiledField absent = new CompiledForm.CompiledField(
                    "s.a", "A", "text", null, null, null, null, null, null, null, null, null, null);
            CompiledForm.CompiledField explicit = new CompiledForm.CompiledField(
                    "s.a", "A", "text", false, null, null, null, null, null, null, null, null, null);

            assertTrue(ArtifactComparison.compare(form(step("s", absent)), form(step("s", explicit)))
                    .isEquivalent());
        }

        @Test
        @DisplayName("a condition with its keys in a different order is the same rule")
        void conditionKeyOrderIgnored() throws Exception {
            CompiledForm.CompiledStep a = conditioned("{\"field\":\"s.x\",\"op\":\"eq\",\"value\":\"Y\"}");
            CompiledForm.CompiledStep b = conditioned("{\"op\":\"eq\",\"value\":\"Y\",\"field\":\"s.x\"}");

            assertTrue(ArtifactComparison.compare(form(a), form(b)).isEquivalent());
        }
    }

    @Nested
    @DisplayName("differences it must catch")
    class Differences {

        @Test
        @DisplayName("a missing step")
        void missingStep() {
            CompiledForm expected = form(step("applicant"), step("licensure"));
            CompiledForm actual = form(step("applicant"));

            ArtifactComparison.Result result = ArtifactComparison.compare(expected, actual);
            assertFalse(result.isEquivalent());
            assertTrue(result.describe().contains("step 'licensure' is missing"), result.describe());
        }

        @Test
        @DisplayName("a missing field — the shape a lossy importer takes")
        void missingField() {
            CompiledForm expected =
                    form(step("applicant", field("applicant.npi", "NPI", true), field("applicant.ssn", "SSN", true)));
            CompiledForm actual = form(step("applicant", field("applicant.npi", "NPI", true)));

            ArtifactComparison.Result result = ArtifactComparison.compare(expected, actual);
            assertTrue(result.describe().contains("missing field 'applicant.ssn'"), result.describe());
        }

        @Test
        @DisplayName("reordered steps, reported once rather than as a difference on every field")
        void reorderedSteps() {
            CompiledForm expected = form(step("a", field("a.x", "X", false)), step("b", field("b.y", "Y", false)));
            CompiledForm actual = form(step("b", field("b.y", "Y", false)), step("a", field("a.x", "X", false)));

            ArtifactComparison.Result result = ArtifactComparison.compare(expected, actual);
            assertEquals(1, result.differences().size(), result.describe());
            assertTrue(result.differences().get(0).contains("step order differs"), result.describe());
        }

        @Test
        @DisplayName("reordered fields within a step — the provider sees this, so it counts")
        void reorderedFields() {
            CompiledForm expected = form(step("s", field("s.a", "A", false), field("s.b", "B", false)));
            CompiledForm actual = form(step("s", field("s.b", "B", false), field("s.a", "A", false)));

            assertTrue(ArtifactComparison.compare(expected, actual).describe().contains("field order differs"));
        }

        @Test
        @DisplayName("a lost hint — pedantic, and exactly the kind of loss worth knowing about")
        void lostHint() {
            CompiledForm.CompiledField withHint = new CompiledForm.CompiledField(
                    "s.npi",
                    "NPI",
                    "text",
                    true,
                    "10 digits, no dashes",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            CompiledForm.CompiledField withoutHint = field("s.npi", "NPI", true);

            ArtifactComparison.Result result =
                    ArtifactComparison.compare(form(step("s", withHint)), form(step("s", withoutHint)));
            assertTrue(result.describe().contains(".hint"), result.describe());
        }

        @Test
        @DisplayName("a lost validation, which no answer-level diff would notice")
        void lostValidation() {
            CompiledForm.CompiledValidation validation =
                    new CompiledForm.CompiledValidation(10, 10, null, null, "^\\d{10}$", null, null, null, null, null);
            CompiledForm.CompiledField validated = new CompiledForm.CompiledField(
                    "s.npi", "NPI", "text", true, null, null, validation, null, null, null, null, null, null);

            ArtifactComparison.Result result = ArtifactComparison.compare(
                    form(step("s", validated)), form(step("s", field("s.npi", "NPI", true))));
            assertTrue(result.describe().contains(".validation"), result.describe());
        }

        @Test
        @DisplayName("a changed condition — a silently inverted gate is the worst possible loss")
        void changedCondition() {
            CompiledForm.CompiledStep shown = conditioned("{\"field\":\"s.x\",\"op\":\"eq\",\"value\":\"Y\"}");
            CompiledForm.CompiledStep hidden =
                    conditioned("{\"not\":{\"field\":\"s.x\",\"op\":\"eq\",\"value\":\"Y\"}}");

            assertFalse(ArtifactComparison.compare(form(shown), form(hidden)).isEquivalent());
        }

        @Test
        @DisplayName("a dropped condition, so an always-visible step cannot pass as a gated one")
        void droppedCondition() {
            CompiledForm.CompiledStep gated = conditioned("{\"field\":\"s.x\",\"op\":\"eq\",\"value\":\"Y\"}");
            CompiledForm ungated = form(step("s"));

            ArtifactComparison.Result result = ArtifactComparison.compare(form(gated), ungated);
            assertTrue(result.describe().contains(".condition"), result.describe());
        }

        @Test
        @DisplayName("a lost option-level filter, which is how PRD §4.3 cascades are expressed")
        void lostFilterValue() {
            CompiledForm.CompiledOption filtered = new CompiledForm.CompiledOption("Cardiology", "Cardiology", "MD");
            CompiledForm.CompiledOption unfiltered = new CompiledForm.CompiledOption("Cardiology", "Cardiology", null);

            ArtifactComparison.Result result = ArtifactComparison.compare(
                    form(step("s", withOptions(List.of(filtered)))), form(step("s", withOptions(List.of(unfiltered)))));
            assertFalse(result.isEquivalent(), result.describe());
        }

        @Test
        @DisplayName("dropped sub-fields of a grouped question")
        void lostGroupFields() {
            CompiledForm.CompiledField grouped = new CompiledForm.CompiledField(
                    "s.address",
                    "Address",
                    "group",
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(field("s.address.line1", "Line 1", true)));

            ArtifactComparison.Result result = ArtifactComparison.compare(
                    form(step("s", grouped)), form(step("s", field("s.address", "Address", false))));
            assertTrue(result.describe().contains("sub-field"), result.describe());
        }

        @Test
        @DisplayName("every difference is reported, not just the first")
        void reportsAllDifferences() {
            CompiledForm expected = form(
                    step("a", field("a.x", "X", true), field("a.y", "Y", true)), step("b", field("b.z", "Z", true)));
            CompiledForm actual = form(step("a", field("a.x", "X-changed", true)));

            ArtifactComparison.Result result = ArtifactComparison.compare(expected, actual);
            // Missing step b, missing field a.y, changed label on a.x — an importer author fixes
            // these in one pass or spends a day rediscovering them one at a time.
            assertTrue(result.differences().size() >= 3, result.describe());
        }
    }

    // ------------------------------------------------------------------

    private static CompiledForm.CompiledField withOptions(List<CompiledForm.CompiledOption> options) {
        return new CompiledForm.CompiledField(
                "s.providerType",
                "Provider type",
                "select",
                true,
                null,
                options,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static CompiledForm.CompiledStep conditioned(String conditionJson) {
        try {
            return new CompiledForm.CompiledStep(
                    "s", "s title", null, List.of(), MAPPER.readTree(conditionJson), null, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
