package com.certifyos.forms.shared_kernel.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The analyzer is the safety net that makes self-serve authoring defensible.
 *
 * <p>Every case here is a rule that would otherwise fail <em>silently</em> at runtime — a step that
 * simply never appears, with no error anywhere and no way for the author to tell whether that was
 * intended. Silent invisibility is the worst failure mode this system has, which is why these are
 * caught at compile time and block publishing.
 */
class ExpressionAnalyzerTest {

    /** Three steps in order: applicantDetails(0), licensure(1), billingAddress(2). */
    private static AnalysisScope threeStepForm() {
        return new AnalysisScope(
                Map.of(
                        "applicantDetails.providerType", 0,
                        "applicantDetails.specialty", 0,
                        "licensure.licenseState", 1,
                        "billingAddress.line1", 2),
                Map.of("applicantDetails.providerType", Set.of("MD", "DO", "DC")),
                Set.of("licensure"),
                Map.of());
    }

    private static Expression.Leaf leaf(String path, Operator op, Object value) {
        return new Expression.Leaf(path, op, value);
    }

    private static List<ExpressionAnalyzer.Code> codesOf(List<ExpressionAnalyzer.Finding> findings) {
        return findings.stream().map(ExpressionAnalyzer.Finding::code).toList();
    }

    @Nested
    @DisplayName("references to questions")
    class Paths {

        @Test
        @DisplayName("a valid backward reference produces no findings")
        void backwardReferenceIsFine() {
            // billingAddress (step 2) looking at applicantDetails (step 0)
            var findings = ExpressionAnalyzer.analyze(
                    leaf("applicantDetails.providerType", Operator.EQ, "MD"), 2, threeStepForm());
            assertTrue(findings.isEmpty(), () -> "expected no findings, got " + findings);
        }

        @Test
        @DisplayName("a question that no longer exists is caught rather than silently never matching")
        void danglingPath() {
            var findings = ExpressionAnalyzer.analyze(
                    leaf("applicantDetails.removedField", Operator.EXISTS, null), 2, threeStepForm());
            assertEquals(List.of(ExpressionAnalyzer.Code.DANGLING_PATH), codesOf(findings));
            assertEquals("applicantDetails.removedField", findings.get(0).path());
        }

        @Test
        @DisplayName("a step cannot depend on an answer given later in the form")
        void forwardReference() {
            // applicantDetails (step 0) looking at billingAddress (step 2)
            var findings =
                    ExpressionAnalyzer.analyze(leaf("billingAddress.line1", Operator.EXISTS, null), 0, threeStepForm());
            assertEquals(List.of(ExpressionAnalyzer.Code.FORWARD_REFERENCE), codesOf(findings));
        }

        @Test
        @DisplayName("a step cannot decide its own visibility from a question it contains")
        void selfReference() {
            var findings =
                    ExpressionAnalyzer.analyze(leaf("licensure.licenseState", Operator.EQ, "FL"), 1, threeStepForm());
            assertEquals(List.of(ExpressionAnalyzer.Code.SELF_REFERENCE), codesOf(findings));
        }

        @Test
        @DisplayName("a QUESTION may depend on a sibling in its own step — the commonest pattern there is")
        void questionMayReferenceItsOwnStep() {
            // "Show the certification number once they say they are board certified."
            var findings = ExpressionAnalyzer.analyze(
                    leaf("licensure.licenseState", Operator.EQ, "FL"),
                    1,
                    threeStepForm(),
                    ExpressionAnalyzer.Level.QUESTION);

            assertTrue(
                    findings.isEmpty(), () -> "same-step sibling references are legal for a question, got " + findings);
        }

        @Test
        @DisplayName("a QUESTION still cannot depend on a later step")
        void questionStillCannotLookForward() {
            var findings = ExpressionAnalyzer.analyze(
                    leaf("billingAddress.line1", Operator.EXISTS, null),
                    1,
                    threeStepForm(),
                    ExpressionAnalyzer.Level.QUESTION);

            assertEquals(List.of(ExpressionAnalyzer.Code.FORWARD_REFERENCE), codesOf(findings));
        }

        @Test
        @DisplayName("viewer / entity / tenant paths are not answers, so they never dangle")
        void contextPathsAreExempt() {
            var findings = ExpressionAnalyzer.analyze(leaf("viewer.role", Operator.EQ, "admin"), 0, threeStepForm());
            assertTrue(findings.isEmpty(), () -> "context paths should be exempt, got " + findings);
        }
    }

    @Nested
    @DisplayName("option sets")
    class OptionSets {

        @Test
        @DisplayName("a value the question cannot hold is caught — the rule could never match")
        void valueOutsideOptionSet() {
            var findings = ExpressionAnalyzer.analyze(
                    leaf("applicantDetails.providerType", Operator.EQ, "NP"), 2, threeStepForm());
            assertEquals(List.of(ExpressionAnalyzer.Code.VALUE_NOT_IN_OPTION_SET), codesOf(findings));
        }

        @Test
        @DisplayName("every bad member of a list is reported, not just the first")
        void everyBadListMemberIsReported() {
            var findings = ExpressionAnalyzer.analyze(
                    leaf("applicantDetails.providerType", Operator.IN, List.of("MD", "NP", "PA")), 2, threeStepForm());
            assertEquals(2, findings.size(), () -> "expected NP and PA to be flagged, got " + findings);
        }

        @Test
        @DisplayName("a question with no option set is not checked")
        void freeTextIsNotChecked() {
            // Owner is step 3, so billingAddress (step 2) is a legitimate backward reference.
            var findings = ExpressionAnalyzer.analyze(
                    leaf("billingAddress.line1", Operator.EQ, "anything"), 3, threeStepForm());
            assertTrue(findings.isEmpty(), () -> "free text should accept any value, got " + findings);
        }

        @Test
        @DisplayName("a regex is matched against the answer, not drawn from the option set")
        void regexIsExempt() {
            var findings = ExpressionAnalyzer.analyze(
                    leaf("applicantDetails.providerType", Operator.MATCHES, "^M"), 2, threeStepForm());
            assertTrue(findings.isEmpty(), () -> "matches should be exempt, got " + findings);
        }
    }

    @Nested
    @DisplayName("named conditions")
    class NamedConditions {

        @Test
        @DisplayName("an unresolved reference is reported, never defaulted to visible")
        void unresolvedRef() {
            var findings = ExpressionAnalyzer.analyze(new Expression.Ref("noSuchCondition"), 2, threeStepForm());
            assertEquals(List.of(ExpressionAnalyzer.Code.UNRESOLVED_REF), codesOf(findings));
        }

        @Test
        @DisplayName("analysis follows a reference into the condition it names")
        void findingsInsideARefAreReported() {
            var scope = new AnalysisScope(
                    threeStepForm().pathOrdinal(),
                    threeStepForm().allowedValues(),
                    threeStepForm().repeatScopes(),
                    Map.of("exempt", leaf("applicantDetails.gone", Operator.EXISTS, null)));

            var findings = ExpressionAnalyzer.analyze(new Expression.Ref("exempt"), 2, scope);
            assertEquals(List.of(ExpressionAnalyzer.Code.DANGLING_PATH), codesOf(findings));
        }

        @Test
        @DisplayName("a condition that refers back to itself terminates instead of hanging")
        void selfReferentialCycle() {
            var scope = new AnalysisScope(
                    threeStepForm().pathOrdinal(), Map.of(), Set.of(), Map.of("loop", new Expression.Ref("loop")));

            var findings = ExpressionAnalyzer.analyze(new Expression.Ref("loop"), 2, scope);
            assertEquals(List.of(ExpressionAnalyzer.Code.NAMED_CONDITION_CYCLE), codesOf(findings));
        }

        @Test
        @DisplayName("a two-step cycle is caught")
        void mutualCycle() {
            var scope = new AnalysisScope(
                    threeStepForm().pathOrdinal(),
                    Map.of(),
                    Set.of(),
                    Map.of(
                            "a", new Expression.Ref("b"),
                            "b", new Expression.Ref("a")));

            var findings = ExpressionAnalyzer.analyze(new Expression.Ref("a"), 2, scope);
            assertEquals(List.of(ExpressionAnalyzer.Code.NAMED_CONDITION_CYCLE), codesOf(findings));
        }
    }

    @Nested
    @DisplayName("quantifiers")
    class Quantifiers {

        @Test
        @DisplayName("a quantifier over a repeating step is fine")
        void validQuantifier() {
            var expr = new Expression.Some("licensure", leaf("@item.licenseState", Operator.EQ, "FL"));
            var findings = ExpressionAnalyzer.analyze(expr, 2, threeStepForm());
            assertTrue(findings.isEmpty(), () -> "expected no findings, got " + findings);
        }

        @Test
        @DisplayName("a quantifier over a step that does not repeat would evaluate false forever")
        void unknownRepeatScope() {
            var expr = new Expression.Some("billingAddress", leaf("@item.line1", Operator.EXISTS, null));
            var findings = ExpressionAnalyzer.analyze(expr, 2, threeStepForm());
            assertEquals(List.of(ExpressionAnalyzer.Code.UNKNOWN_REPEAT_SCOPE), codesOf(findings));
        }

        @Test
        @DisplayName("@item outside a quantifier has nothing to bind to")
        void itemPathOutsideQuantifier() {
            var findings =
                    ExpressionAnalyzer.analyze(leaf("@item.licenseState", Operator.EQ, "FL"), 2, threeStepForm());
            assertEquals(List.of(ExpressionAnalyzer.Code.ITEM_PATH_OUTSIDE_QUANTIFIER), codesOf(findings));
        }
    }

    @Nested
    @DisplayName("collecting findings")
    class Collection {

        @Test
        @DisplayName("every problem is reported at once, not one per publish attempt")
        void reportsAllFindingsTogether() {
            var expr = new Expression.All(List.of(
                    leaf("applicantDetails.gone", Operator.EXISTS, null),
                    leaf("billingAddress.line1", Operator.EXISTS, null),
                    new Expression.Ref("missing")));

            var findings = ExpressionAnalyzer.analyze(expr, 0, threeStepForm());
            assertEquals(3, findings.size(), () -> "expected all three problems, got " + findings);
        }

        @Test
        @DisplayName("referenced paths follow refs and skip item and context paths")
        void referencedPaths() {
            var scope = new AnalysisScope(
                    threeStepForm().pathOrdinal(),
                    Map.of(),
                    Set.of("licensure"),
                    Map.of("exempt", leaf("applicantDetails.specialty", Operator.IN, List.of("DC"))));

            var expr = new Expression.All(List.of(
                    new Expression.Ref("exempt"),
                    leaf("viewer.role", Operator.EQ, "admin"),
                    new Expression.Some("licensure", leaf("@item.licenseState", Operator.EQ, "FL")),
                    leaf("applicantDetails.providerType", Operator.EQ, "MD")));

            assertEquals(
                    Set.of("applicantDetails.specialty", "applicantDetails.providerType"),
                    ExpressionAnalyzer.referencedPaths(expr, scope));
        }
    }
}
