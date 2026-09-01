package com.certifyos.forms.form_authoring.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.form_authoring.domain.compile.CatalogSnapshot;
import com.certifyos.forms.form_authoring.domain.compile.CompilationFailedException;
import com.certifyos.forms.form_authoring.domain.compile.CompilationReport;
import com.certifyos.forms.form_authoring.domain.compile.FormCompiler;
import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Layout;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.publishing.CompiledForm;
import com.certifyos.forms.question_catalog.domain.CatalogStatus;
import com.certifyos.forms.question_catalog.domain.OptionSet;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.ResponseType;
import com.certifyos.forms.question_catalog.domain.ValidationRule;
import com.certifyos.forms.shared_kernel.expression.Expression;
import com.certifyos.forms.shared_kernel.expression.ExpressionAnalyzer;
import com.certifyos.forms.shared_kernel.expression.Operator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The compiler is where the whole approach either works or does not.
 *
 * <p>Its output is the artifact the existing renderer already consumes, which is what makes this
 * incremental — nothing downstream learns that a catalog exists. These tests pin that output shape
 * and the rules that shape it.
 */
class FormCompilerTest {

    // ---- catalog -----------------------------------------------------

    private static final QuestionId LINE1 = QuestionId.of("q_addr_line1");
    private static final QuestionId CITY = QuestionId.of("q_addr_city");
    private static final QuestionId PROVIDER_TYPE = QuestionId.of("q_provider_type");
    private static final QuestionId NPI = QuestionId.of("q_npi");
    private static final QuestionId DEA = QuestionId.of("q_dea");

    private static Question text(QuestionId id, String key, String label, ValidationRule... rules) {
        return new Question(
                id,
                null,
                key,
                label,
                null,
                ResponseType.TEXT,
                null,
                List.of(rules),
                Map.of(),
                Set.of(),
                List.of(),
                null,
                CatalogStatus.ACTIVE,
                Set.of());
    }

    private static final OptionSet PROVIDER_TYPES = new OptionSet(
            "os_pt",
            null,
            "providerTypes",
            "Provider types",
            List.of(
                    new OptionSet.Option("MD", "MD — Physician", Map.of()),
                    new OptionSet.Option("DO", "DO — Osteopathic Physician", Map.of()),
                    new OptionSet.Option("DC", "DC — Chiropractor", Map.of())),
            true);

    private static CatalogSnapshot catalog() {
        return CatalogSnapshot.of(
                List.of(
                        text(LINE1, "line1", "Address line 1"),
                        text(CITY, "city", "City"),
                        text(
                                NPI,
                                "npi",
                                "NPI",
                                ValidationRule.of(ValidationRule.Kind.LENGTH, Map.of("exact", 10)),
                                ValidationRule.of(ValidationRule.Kind.NPI_CHECKSUM)),
                        text(DEA, "deaNumber", "DEA registration number"),
                        new Question(
                                PROVIDER_TYPE,
                                null,
                                "providerType",
                                "Provider type",
                                null,
                                ResponseType.SINGLE_SELECT,
                                "providerTypes",
                                List.of(),
                                Map.of(),
                                Set.of(),
                                List.of(),
                                null,
                                CatalogStatus.ACTIVE,
                                Set.of())),
                List.of(PROVIDER_TYPES));
    }

    // ---- sections ----------------------------------------------------

    private static SectionDefinition address() {
        return new SectionDefinition(
                "sd_address",
                "t",
                "address",
                "Address",
                null,
                "st_address",
                2,
                List.of(
                        QuestionInstance.fromTemplate("line1", LINE1, 10, true),
                        QuestionInstance.fromTemplate("city", CITY, 20, true)),
                true);
    }

    private static SectionDefinition applicant() {
        return new SectionDefinition(
                "sd_applicant",
                "t",
                "applicant",
                "Applicant Details",
                null,
                null,
                null,
                List.of(
                        QuestionInstance.fromTemplate("npi", NPI, 10, true),
                        QuestionInstance.fromTemplate("providerType", PROVIDER_TYPE, 20, true)),
                true);
    }

    private static SectionDefinition dea() {
        return new SectionDefinition(
                "sd_dea",
                "t",
                "dea",
                "DEA Registration",
                null,
                null,
                null,
                List.of(QuestionInstance.fromTemplate("deaNumber", DEA, 10, true)),
                true);
    }

    private static Map<String, SectionDefinition> sections() {
        return Map.of(
                "sd_address", address(),
                "sd_applicant", applicant(),
                "sd_dea", dea());
    }

    private static FormDefinition form() {
        return FormDefinition.draft("fd", "t", "Florida Blue Recred", "practitioner");
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the artifact shape")
    class Shape {

        @Test
        @DisplayName("one step in, one step out — the reason there is a single grouping level")
        void oneStepPerStep() {
            var definition = form().placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                    .placeStep(Step.of("practiceLocation", "sd_address", 20));

            var artifact = FormCompiler.compile(definition, sections(), catalog());

            assertEquals(2, artifact.steps().size());
            assertEquals("applicantDetails", artifact.steps().get(0).id());
            assertEquals("Applicant Details", artifact.steps().get(0).title());
        }

        @Test
        @DisplayName("every field carries layout — the production renderer requires it")
        void everyFieldHasLayout() {
            var definition = form().placeStep(Step.of("practiceLocation", "sd_address", 10));
            var artifact = FormCompiler.compile(definition, sections(), catalog());

            artifact.steps().get(0).fields().forEach(f -> {
                assertNotNull(f.layout(), f.name() + " must carry layout");
                assertEquals(12, f.layout().columns());
            });
        }

        @Test
        @DisplayName("a title override replaces the section name")
        void titleOverride() {
            var step = new Step(
                    com.certifyos.forms.form_authoring.domain.definition.StepKey.of("billingAddress"),
                    "sd_address",
                    10,
                    true,
                    "Billing Address",
                    "Billing",
                    null,
                    null,
                    null);

            var artifact = FormCompiler.compile(form().placeStep(step), sections(), catalog());
            assertEquals("Billing Address", artifact.steps().get(0).title());
        }

        @Test
        @DisplayName("validations flatten into the wire shape")
        void validationsFlatten() {
            var definition = form().placeStep(Step.of("applicantDetails", "sd_applicant", 10));
            var artifact = FormCompiler.compile(definition, sections(), catalog());

            var npiField = artifact.steps().get(0).fields().stream()
                    .filter(f -> f.name().equals("applicantDetails.npi"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(10, npiField.validation().minLength());
            assertEquals(10, npiField.validation().maxLength());
            assertEquals("npi-luhn", npiField.validation().customValidator());
        }

        @Test
        @DisplayName("select questions carry their option list")
        void optionsEmitted() {
            var definition = form().placeStep(Step.of("applicantDetails", "sd_applicant", 10));
            var artifact = FormCompiler.compile(definition, sections(), catalog());

            var field = artifact.steps().get(0).fields().stream()
                    .filter(f -> f.name().endsWith("providerType"))
                    .findFirst()
                    .orElseThrow();

            assertEquals(3, field.options().size());
            assertEquals("MD — Physician", field.options().get(0).label());
        }
    }

    @Nested
    @DisplayName("placement scoping — the finding that prompted the model change")
    class PlacementScoping {

        @Test
        @DisplayName("the same section placed twice produces two independent answer namespaces")
        void twoAddressesDoNotCollide() {
            var definition = form().placeStep(Step.of("practiceLocation", "sd_address", 10))
                    .placeStep(Step.of("billingAddress", "sd_address", 20));

            var artifact = FormCompiler.compile(definition, sections(), catalog());

            var practice = artifact.steps().get(0).fields().stream()
                    .map(CompiledForm.CompiledField::name)
                    .toList();
            var billing = artifact.steps().get(1).fields().stream()
                    .map(CompiledForm.CompiledField::name)
                    .toList();

            assertEquals(List.of("practiceLocation.line1", "practiceLocation.city"), practice);
            assertEquals(List.of("billingAddress.line1", "billingAddress.city"), billing);

            // The bug this prevents: without step scoping both would be "line1", and a provider
            // entering a billing address would silently overwrite their practice address.
            assertTrue(java.util.Collections.disjoint(practice, billing));
        }

        /**
         * The tests below cover the half this class used to miss.
         *
         * <p>Answer namespaces were scoped per placement from the start, and asserted above. Question
         * <em>rules</em> were not, and nothing here noticed — because the two halves of that feature
         * were built on opposite assumptions and no test spanned both. {@code SectionDefinition} and
         * its unit tests address a sibling by bare key; the compiler's scope was keyed only by
         * qualified paths, so a bare key compiled to {@code DANGLING_PATH}; and the fixtures were
         * then written qualified to satisfy the compiler — which hardcodes a placement key into
         * reusable content.
         */
        @Test
        @DisplayName("a question rule names a sibling by bare key, and resolves to this placement")
        void bareSiblingKeyResolves() {
            var section = addressWithRule(new Expression.Leaf("line1", Operator.EXISTS, null));
            var definition = form().placeStep(Step.of("practiceLocation", "sd_address", 10));

            var artifact = FormCompiler.compile(definition, sectionsWith(section), catalog());
            var line2 = fieldNamed(artifact, 0, "practiceLocation.line2");

            assertEquals(
                    "practiceLocation.line1",
                    line2.condition().get("field").asText(),
                    "a bare key must resolve against the step that placed the section");
        }

        @Test
        @DisplayName("placed twice, each copy's rule reads its own answer — not the first placement's")
        void ruleResolvesPerPlacement() {
            var section = addressWithRule(new Expression.Leaf("line1", Operator.EXISTS, null));
            var definition = form().placeStep(Step.of("practiceLocation", "sd_address", 10))
                    .placeStep(Step.of("billingAddress", "sd_address", 20));

            var artifact = FormCompiler.compile(definition, sectionsWith(section), catalog());

            // The defect this closes: with the rule authored as `practiceLocation.line1`, both copies
            // compiled clean and both were gated on the practice location's answer — so the billing
            // address's field appeared because of something typed in a different section.
            assertEquals(
                    "practiceLocation.line1",
                    fieldNamed(artifact, 0, "practiceLocation.line2")
                            .condition()
                            .get("field")
                            .asText());
            assertEquals(
                    "billingAddress.line1",
                    fieldNamed(artifact, 1, "billingAddress.line2")
                            .condition()
                            .get("field")
                            .asText());
        }

        @Test
        @DisplayName("dependsOn is emitted for every placement, not just the first")
        void dependsOnSurvivesBothPlacements() {
            var section = addressWithRule(new Expression.Leaf("line1", Operator.EXISTS, null));
            var definition = form().placeStep(Step.of("practiceLocation", "sd_address", 10))
                    .placeStep(Step.of("billingAddress", "sd_address", 20));

            var artifact = FormCompiler.compile(definition, sectionsWith(section), catalog());

            // Previously null on the second placement, because the path did not start with that
            // step's prefix. The compiler knew the reference was cross-step and said nothing.
            assertEquals(
                    "line1", fieldNamed(artifact, 0, "practiceLocation.line2").dependsOn());
            assertEquals(
                    "line1", fieldNamed(artifact, 1, "billingAddress.line2").dependsOn());
        }

        @Test
        @DisplayName("a rule reaching into another placement of its own section fails compilation")
        void crossPlacementReferenceIsRefused() {
            var section = addressWithRule(new Expression.Leaf("practiceLocation.line1", Operator.EXISTS, null));
            var definition = form().placeStep(Step.of("practiceLocation", "sd_address", 10))
                    .placeStep(Step.of("billingAddress", "sd_address", 20));

            var report = FormCompiler.analyze(definition, sectionsWith(section), catalog())
                    .report();

            assertFalse(report.isClean(), "this compiled clean before, and gated both copies on one answer");
            assertTrue(
                    report.problems().stream()
                            .anyMatch(problem -> problem.code() == ExpressionAnalyzer.Code.CROSS_PLACEMENT_REFERENCE),
                    "expected CROSS_PLACEMENT_REFERENCE, got " + report.problems());
        }

        @Test
        @DisplayName("a rule naming its own placement key is a notice: correct today, no longer reusable")
        void ownPlacementKeyIsNoticed() {
            var section = addressWithRule(new Expression.Leaf("practiceLocation.line1", Operator.EXISTS, null));
            var definition = form().placeStep(Step.of("practiceLocation", "sd_address", 10));

            var report = FormCompiler.analyze(definition, sectionsWith(section), catalog())
                    .report();

            // Placed once, so nothing is wrong — but the section now breaks under any other key, and
            // an author should hear that while it is still cheap to fix.
            assertTrue(report.isClean(), "nothing is actually wrong yet");
            assertTrue(
                    report.notices().stream()
                            .anyMatch(notice ->
                                    notice.code() == CompilationReport.Notice.Code.PLACEMENT_KEY_IN_QUESTION_RULE),
                    "expected a reusability notice, got " + report.notices());
        }

        @Test
        @DisplayName("a genuine cross-section reference is untouched — that is the legitimate case")
        void crossSectionReferenceStillWorks() {
            var section = addressWithRule(new Expression.Leaf("applicant.npi", Operator.EXISTS, null));
            var definition = form().placeStep(Step.of("applicant", "sd_applicant", 10))
                    .placeStep(Step.of("practiceLocation", "sd_address", 20));

            var artifact = FormCompiler.compile(definition, sectionsWith(section), catalog());

            // A question in one section gated on an answer in an earlier one is common and correct.
            // The guard must not catch it: the referenced step placed a *different* section.
            assertEquals(
                    "applicant.npi",
                    fieldNamed(artifact, 1, "practiceLocation.line2")
                            .condition()
                            .get("field")
                            .asText());
        }

        @Test
        @DisplayName("a context path is left alone, having no placement to belong to")
        void contextPathUntouched() {
            var section = addressWithRule(new Expression.Leaf("viewer.role", Operator.EQ, "admin"));
            var definition = form().placeStep(Step.of("practiceLocation", "sd_address", 10));

            var artifact = FormCompiler.compile(definition, sectionsWith(section), catalog());

            assertEquals(
                    "viewer.role",
                    fieldNamed(artifact, 0, "practiceLocation.line2")
                            .condition()
                            .get("field")
                            .asText());
        }

        // ---- fixture -------------------------------------------------

        /** The address section with a third question carrying the rule under test. */
        private static SectionDefinition addressWithRule(Expression rule) {
            return address()
                    .addQuestion(QuestionInstance.fromTemplate("line2", CITY, 30, false)
                            .withVisibleWhen(rule));
        }

        private static Map<String, SectionDefinition> sectionsWith(SectionDefinition address) {
            Map<String, SectionDefinition> all = new java.util.LinkedHashMap<>(sections());
            all.put(address.id(), address);
            return all;
        }

        private static CompiledForm.CompiledField fieldNamed(CompiledForm artifact, int step, String name) {
            return artifact.steps().get(step).fields().stream()
                    .filter(field -> field.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no field " + name + " in step " + step));
        }
    }

    @Nested
    @DisplayName("what does and does not reach the artifact")
    class Filtering {

        @Test
        @DisplayName("a disabled question is compiled out entirely, not hidden")
        void disabledQuestionOmitted() {
            var sections = new java.util.HashMap<>(sections());
            sections.put("sd_address", address().disableQuestion("city"));

            var definition = form().placeStep(Step.of("practiceLocation", "sd_address", 10));
            var artifact = FormCompiler.compile(definition, sections, catalog());

            assertEquals(1, artifact.steps().get(0).fields().size());
            assertEquals(
                    "practiceLocation.line1",
                    artifact.steps().get(0).fields().get(0).name());
        }

        @Test
        @DisplayName("a disabled step never appears")
        void disabledStepOmitted() {
            var definition = form().placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                    .placeStep(Step.of("practiceLocation", "sd_address", 20).disable());

            assertEquals(
                    1,
                    FormCompiler.compile(definition, sections(), catalog())
                            .steps()
                            .size());
        }
    }

    @Nested
    @DisplayName("conditions")
    class Conditions {

        @Test
        @DisplayName("CP-38192: a not+in rule survives compilation as config")
        void floridaBlueDeaExemption() {
            var exemption = new Expression.Not(
                    new Expression.Leaf("applicantDetails.providerType", Operator.IN, List.of("DC")));

            var definition = form().placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                    .placeStep(Step.of("deaRegistration", "sd_dea", 20).withVisibleWhen(exemption));

            var artifact = FormCompiler.compile(definition, sections(), catalog());
            var condition = artifact.steps().get(1).condition();

            assertNotNull(condition);
            assertEquals("in", condition.path("not").path("op").asText());
            assertEquals("DC", condition.path("not").path("value").get(0).asText());
        }

        @Test
        @DisplayName("a named condition is inlined, so a published version cannot change later")
        void namedConditionInlined() {
            var definition = form().withNamedCondition(new FormDefinition.NamedCondition(
                            "exempt",
                            "Specialty exempt",
                            new Expression.Leaf("applicantDetails.providerType", Operator.IN, List.of("DC"))))
                    .placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                    .placeStep(Step.of("deaRegistration", "sd_dea", 20)
                            .withVisibleWhen(new Expression.Not(new Expression.Ref("exempt"))));

            var artifact = FormCompiler.compile(definition, sections(), catalog());
            var condition = artifact.steps().get(1).condition();

            // The weak version of this test asserted only that the condition was non-null, which
            // let a real bug through: the compiler was emitting {"ref": "exempt"} unexpanded. That
            // breaks P3 — the renderer would need the form's named conditions alongside the
            // artifact, and editing one would change the behaviour of an already-published version
            // that a provider may have signed against.
            assertNull(condition.get("ref"), "a ref must not survive into the artifact");
            assertTrue(condition.has("not"), "the Hide verb should still be present");
            assertEquals(
                    "in",
                    condition.path("not").path("op").asText(),
                    "the named condition's body should be expanded in place");
            assertEquals("DC", condition.path("not").path("value").get(0).asText());
        }

        @Test
        @DisplayName("a named condition nested inside a group is inlined too")
        void nestedRefInlined() {
            var definition = form().withNamedCondition(new FormDefinition.NamedCondition(
                            "exempt",
                            "Specialty exempt",
                            new Expression.Leaf("applicantDetails.providerType", Operator.IN, List.of("DC"))))
                    .placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                    .placeStep(Step.of("deaRegistration", "sd_dea", 20)
                            .withVisibleWhen(new Expression.All(List.of(
                                    new Expression.Leaf("applicantDetails.npi", Operator.EXISTS, null),
                                    new Expression.Not(new Expression.Ref("exempt"))))));

            var condition = FormCompiler.compile(definition, sections(), catalog())
                    .steps()
                    .get(1)
                    .condition();

            assertEquals(2, condition.path("all").size());
            assertNull(condition.path("all").get(1).path("not").get("ref"), "nested refs must be expanded too");
            assertEquals(
                    "in", condition.path("all").get(1).path("not").path("op").asText());
        }

        @Test
        @DisplayName("a simple 'parent is answered' rule emits dependsOn, as production forms do")
        void simpleRuleEmitsDependsOn() {
            var section = applicant()
                    .addQuestion(new QuestionInstance(
                            "boardCertNumber",
                            DEA,
                            com.certifyos.forms.form_authoring.domain.definition.Origin.ADDED,
                            true,
                            30,
                            false,
                            Layout.HALF,
                            null,
                            null,
                            new Expression.Leaf("applicantDetails.npi", Operator.EXISTS, null),
                            null,
                            null,
                            null));

            var sections = new java.util.HashMap<>(sections());
            sections.put("sd_applicant", section);

            var definition = form().placeStep(Step.of("applicantDetails", "sd_applicant", 10));
            var artifact = FormCompiler.compile(definition, sections, catalog());

            var field = artifact.steps().get(0).fields().stream()
                    .filter(f -> f.name().endsWith("boardCertNumber"))
                    .findFirst()
                    .orElseThrow();

            assertEquals("npi", field.dependsOn(), "should compile to production's own dependsOn shape");
        }

        @Test
        @DisplayName("a step with no condition emits none, rather than an empty object")
        void noConditionMeansAbsent() {
            var definition = form().placeStep(Step.of("practiceLocation", "sd_address", 10));
            assertNull(FormCompiler.compile(definition, sections(), catalog())
                    .steps()
                    .get(0)
                    .condition());
        }
    }

    @Nested
    @DisplayName("problems block publishing")
    class Problems {

        @Test
        @DisplayName("a forward reference fails compilation")
        void forwardReferenceFails() {
            var definition = form().placeStep(Step.of("applicantDetails", "sd_applicant", 10)
                            .withVisibleWhen(new Expression.Leaf("practiceLocation.line1", Operator.EXISTS, null)))
                    .placeStep(Step.of("practiceLocation", "sd_address", 20));

            var e = assertThrows(
                    CompilationFailedException.class, () -> FormCompiler.compile(definition, sections(), catalog()));
            assertEquals(
                    ExpressionAnalyzer.Code.FORWARD_REFERENCE,
                    e.report().problems().get(0).code());
        }

        @Test
        @DisplayName("a value outside the option set fails — the rule could never match")
        void valueOutsideOptionSetFails() {
            var definition = form().placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                    .placeStep(Step.of("deaRegistration", "sd_dea", 20)
                            .withVisibleWhen(new Expression.Leaf("applicantDetails.providerType", Operator.EQ, "NP")));

            var e = assertThrows(
                    CompilationFailedException.class, () -> FormCompiler.compile(definition, sections(), catalog()));
            assertEquals(
                    ExpressionAnalyzer.Code.VALUE_NOT_IN_OPTION_SET,
                    e.report().problems().get(0).code());
        }

        @Test
        @DisplayName("an unresolved named condition fails rather than defaulting to visible")
        void unresolvedRefFails() {
            var definition = form().placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                    .placeStep(Step.of("deaRegistration", "sd_dea", 20)
                            .withVisibleWhen(new Expression.Ref("neverDefined")));

            assertThrows(
                    CompilationFailedException.class, () -> FormCompiler.compile(definition, sections(), catalog()));
        }

        @Test
        @DisplayName("every problem is reported at once, not one per publish attempt")
        void allProblemsCollected() {
            var definition = form().placeStep(Step.of("applicantDetails", "sd_applicant", 10)
                            .withVisibleWhen(new Expression.Leaf("practiceLocation.line1", Operator.EXISTS, null)))
                    .placeStep(Step.of("practiceLocation", "sd_address", 20))
                    .placeStep(Step.of("deaRegistration", "sd_dea", 30)
                            .withVisibleWhen(new Expression.Ref("neverDefined")));

            var result = FormCompiler.analyze(definition, sections(), catalog());
            assertEquals(
                    2, result.report().problems().size(), () -> result.report().summary());
        }

        @Test
        @DisplayName("problems are pinned to a step, so the UI can attach them to a node")
        void problemsCarryTheirStep() {
            var definition = form().placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                    .placeStep(Step.of("deaRegistration", "sd_dea", 20)
                            .withVisibleWhen(new Expression.Ref("neverDefined")));

            var result = FormCompiler.analyze(definition, sections(), catalog());
            assertEquals("deaRegistration", result.report().problems().get(0).stepKey());
            assertEquals(1, result.report().forStep("deaRegistration").size());
        }

        @Test
        @DisplayName("problem messages are written for an author, not an engineer")
        void messagesAreHumanReadable() {
            var definition = form().placeStep(Step.of("applicantDetails", "sd_applicant", 10)
                            .withVisibleWhen(new Expression.Leaf("practiceLocation.line1", Operator.EXISTS, null)))
                    .placeStep(Step.of("practiceLocation", "sd_address", 20));

            var message = FormCompiler.analyze(definition, sections(), catalog())
                    .report()
                    .problems()
                    .get(0)
                    .message();

            assertTrue(message.contains("later in the form"), message);
            assertTrue(!message.contains("FORWARD_REFERENCE"), "codes belong in logs, not in the message");
        }
    }
}
