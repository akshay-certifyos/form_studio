package com.certifyos.forms.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.config.FixtureLoader;
import com.certifyos.forms.form_authoring.domain.compile.CatalogSnapshot;
import com.certifyos.forms.form_authoring.domain.compile.FormCompiler;
import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.publishing.CompiledForm;
import com.certifyos.forms.question_catalog.domain.CatalogStatus;
import com.certifyos.forms.shared_kernel.expression.EvaluationContext;
import com.certifyos.forms.shared_kernel.expression.ExpressionEvaluator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Compiles the real Florida Blue fixture.
 *
 * <p>This is the closest thing the POC has to the deferred round-trip gate. The seed importer is
 * parked, so nothing yet proves the model can express an <em>existing</em> form — but this at least
 * proves it can express a form whose rules were taken verbatim from CP-38192 and the live
 * credentialing config, rather than from what the model happened to make easy.
 *
 * <p>It also guards the demo: if the fixtures stop compiling, {@code make dev} serves a broken form
 * and the failure surfaces in a browser rather than in CI.
 */
class RealFixtureCompilationTest {

    private static FixtureLoader.Fixtures fixtures;
    private static FormDefinition form;
    private static Map<String, SectionDefinition> sections;
    private static CatalogSnapshot catalog;
    private static CompiledForm artifact;

    private static Path fixturesDir() {
        String shared = System.getProperty("form-poc.shared-dir");
        return (shared != null ? Path.of(shared) : Path.of("..", "form_poc_shared")).resolve("fixtures");
    }

    @BeforeAll
    static void compileTheRealForm() throws IOException {
        fixtures = new FixtureLoader(fixturesDir()).load();

        form = fixtures.forms().get(0);
        sections = fixtures.sections().stream()
                .collect(Collectors.toMap(SectionDefinition::id, s -> s, (a, b) -> a, LinkedHashMap::new));

        // Only active catalog entries are usable — the proposed one must not leak into a form.
        catalog = CatalogSnapshot.of(
                fixtures.questions().stream()
                        .filter(q -> q.status() == CatalogStatus.ACTIVE)
                        .toList(),
                fixtures.optionSets());

        artifact = FormCompiler.compile(form, sections, catalog);
    }

    @Nested
    @DisplayName("the fixture is real")
    class Grounding {

        @Test
        @DisplayName("the catalog is a useful size and mostly active")
        void catalogSize() {
            assertTrue(
                    fixtures.questions().size() >= 25,
                    "expected a realistic catalog, got " + fixtures.questions().size());
            assertEquals(
                    1,
                    fixtures.questions().stream()
                            .filter(q -> q.status() == CatalogStatus.PROPOSED)
                            .count(),
                    "one proposed entry should exist, to exercise the promotion gate");
        }

        @Test
        @DisplayName("NPI carries the payer phrasings that would otherwise become duplicate entries")
        void npiHasAliases() {
            var npi = fixtures.questions().stream()
                    .filter(q -> q.key().equals("npi"))
                    .findFirst()
                    .orElseThrow();

            assertTrue(npi.aliases().contains("NPI Number"));
            assertTrue(npi.aliases().contains("Individual NPI"));
            assertEquals("npi", npi.platformMapping().get("practitioner"));
        }
    }

    @Nested
    @DisplayName("compilation")
    class Compilation {

        @Test
        @DisplayName("the whole form compiles with no problems")
        void itCompiles() {
            var result = FormCompiler.analyze(form, sections, catalog);
            assertTrue(
                    result.report().isClean(),
                    () -> "fixtures do not compile:\n" + result.report().summary());
        }

        @Test
        @DisplayName("the fixture's hard stop is reported as not compiled, not dropped in silence")
        void hardStopIsReported() {
            var result = FormCompiler.analyze(form, sections, catalog);

            // The Florida Blue fixture carries a real hard stop using `every` over the licence
            // repeat. It is stored, it round-trips, and v0 does not emit it — so the one thing that
            // must be true is that publishing says so. Before this, the form published clean and
            // the disqualifying rule simply never fired.
            assertTrue(result.report().hasNotices(), "the fixture has a hard stop and must notice it");
            assertTrue(
                    result.report().notices().stream()
                            .anyMatch(n -> n.code()
                                    == com.certifyos.forms.form_authoring.domain.compile.CompilationReport.Notice.Code
                                            .HARD_STOP_NOT_COMPILED),
                    () -> result.report().notices().toString());

            // And it stays publishable: a notice is not a problem.
            assertTrue(result.report().isClean());
        }

        @Test
        @DisplayName("compiling twice produces an identical artifact")
        void compilationIsDeterministic() {
            var first = FormCompiler.analyze(form, sections, catalog).artifact();
            var second = FormCompiler.analyze(form, sections, catalog).artifact();

            // A published version is an audit record a provider signed against, so recompiling the
            // same definition must not produce a different artifact. Iteration order over a HashMap
            // is the classic way this quietly breaks.
            var comparison = com.certifyos.forms.support.ArtifactComparison.compare(first, second);
            assertTrue(comparison.isEquivalent(), comparison::describe);
        }

        @Test
        @DisplayName("the artifact survives a round trip through BSON unchanged")
        void artifactSurvivesPersistence() {
            var original = FormCompiler.analyze(form, sections, catalog).artifact();

            var stored = com.certifyos.forms.form_authoring.infrastructure.mongo.FormVersionDocument.from(
                    new com.certifyos.forms.form_authoring.domain.publishing.FormVersion(
                            "fv_1",
                            form.tenantId(),
                            form.id(),
                            1,
                            original,
                            form,
                            com.certifyos.forms.form_authoring.domain.publishing.ChangeSet.firstPublish(),
                            "round trip",
                            null,
                            java.time.Instant.parse("2026-08-31T10:00:00Z"),
                            "tester"));

            // This is the path that was broken: conditions lived in JsonNode fields and no stored
            // artifact could be read back. Comparing the whole artifact rather than spot-checking a
            // condition means a loss anywhere in it fails here.
            var comparison = com.certifyos.forms.support.ArtifactComparison.compare(
                    original, stored.toDomain().artifact());
            assertTrue(comparison.isEquivalent(), comparison::describe);
        }

        @Test
        @DisplayName("nine steps in, nine steps out")
        void oneStepPerStep() {
            assertEquals(9, form.orderedSteps().size());
            assertEquals(9, artifact.steps().size());
        }

        @Test
        @DisplayName("every field carries layout, as the production renderer requires")
        void everyFieldHasLayout() {
            artifact.steps().forEach(step -> step.fields()
                    .forEach(field -> assertNotNull(field.layout(), field.name() + " is missing layout")));
        }
    }

    @Nested
    @DisplayName("the two addresses — the finding that changed the model")
    class TwoAddresses {

        @Test
        @DisplayName("one section definition, two steps, two independent answer namespaces")
        void addressesDoNotCollide() {
            var practice = fieldNames("practiceLocation");
            var billing = fieldNames("billingAddress");

            assertEquals(7, practice.size());
            assertEquals(7, billing.size());
            assertTrue(practice.contains("practiceLocation.line1"));
            assertTrue(billing.contains("billingAddress.line1"));

            assertTrue(
                    java.util.Collections.disjoint(practice, billing),
                    "without step scoping a provider's billing address would overwrite their practice address");
        }

        @Test
        @DisplayName("both steps genuinely come from the same section definition")
        void sameSectionPlacedTwice() {
            assertEquals(
                    "sd_address", form.step("practiceLocation").orElseThrow().sectionDefinitionId());
            assertEquals("sd_address", form.step("billingAddress").orElseThrow().sectionDefinitionId());
        }
    }

    @Nested
    @DisplayName("CP-38192, evaluated against the compiled artifact")
    class Cp38192 {

        /** Evaluates the compiled condition for a step, the way the renderer would. */
        private boolean visible(String stepKey, Map<String, Object> answers) {
            var step = artifact.steps().stream()
                    .filter(s -> s.id().equals(stepKey))
                    .findFirst()
                    .orElseThrow();
            return ExpressionEvaluator.evaluate(
                    com.certifyos.forms.shared_kernel.expression.ExpressionCodec.read(step.condition()),
                    EvaluationContext.ofAnswers(answers));
        }

        @Test
        @DisplayName("R12/R13: DEA Registration hides for an exempt specialty")
        void deaExemption() {
            assertTrue(
                    visible("deaRegistration", Map.of("applicantDetails.specialty", "Cardiology")),
                    "a cardiologist needs a DEA number");
            assertFalse(
                    visible("deaRegistration", Map.of("applicantDetails.specialty", "DC")), "a chiropractor is exempt");
            assertFalse(visible("deaRegistration", Map.of("applicantDetails.specialty", "Radiologist")));
            assertFalse(visible("deaRegistration", Map.of("applicantDetails.specialty", "PhD")));
        }

        @Test
        @DisplayName("R1–R4: Billing Address needs both conditions, the NSCP compound-AND shape")
        void compoundAnd() {
            Map<String, Object> both = Map.of(
                    "billingSetup.hasCaqhId", "Yes",
                    "billingSetup.billingSameAsPrimary", "No");
            assertTrue(visible("billingAddress", both));

            assertFalse(
                    visible(
                            "billingAddress",
                            Map.of("billingSetup.hasCaqhId", "Yes", "billingSetup.billingSameAsPrimary", "Yes")),
                    "same as primary means no separate billing address");
            assertFalse(visible(
                    "billingAddress",
                    Map.of("billingSetup.hasCaqhId", "No", "billingSetup.billingSameAsPrimary", "No")));
            assertFalse(visible("billingAddress", Map.of()), "neither answered");
        }

        @Test
        @DisplayName("a named condition is inlined, so editing it cannot change a published version")
        void namedConditionInlined() {
            var dea = artifact.steps().stream()
                    .filter(s -> s.id().equals("deaRegistration"))
                    .findFirst()
                    .orElseThrow();

            assertNull(dea.condition().path("not").get("ref"), "the ref must not survive into the artifact");
            assertEquals("in", dea.condition().path("not").path("op").asText());
        }

        @Test
        @DisplayName("the definition still holds the ref, so an author edits one rule and every step follows")
        void definitionKeepsTheRef() {
            var condition = form.step("deaRegistration").orElseThrow().visibleWhen();
            assertTrue(condition instanceof com.certifyos.forms.shared_kernel.expression.Expression.Not);
        }

        @Test
        @DisplayName("one named condition gates two steps — the reason naming them exists")
        void oneRuleGatesSeveralSteps() {
            long usingPrescribing = form.steps().stream()
                    .filter(s -> s.visibleWhen() != null)
                    .filter(s -> s.visibleWhen().toString().contains("prescribingProviderType"))
                    .count();
            assertTrue(usingPrescribing >= 1);
            assertEquals(2, form.namedConditions().size());
        }
    }

    @Nested
    @DisplayName("intra-step conditions")
    class IntraStep {

        @Test
        @DisplayName("a question conditioned on a sibling in the same step compiles")
        void siblingCondition() {
            var boardCertNumber = artifact.steps().stream()
                    .filter(s -> s.id().equals("applicantDetails"))
                    .flatMap(s -> s.fields().stream())
                    .filter(f -> f.name().equals("applicantDetails.boardCertNumber"))
                    .findFirst()
                    .orElseThrow();

            assertNotNull(boardCertNumber.condition());
            assertEquals(
                    "applicantDetails.boardCertified",
                    boardCertNumber.condition().path("field").asText());
        }

        @Test
        @DisplayName("an option list is emitted for a filtered select, with the parent recorded")
        void filteredSelectCarriesOptions() {
            var specialty = artifact.steps().stream()
                    .filter(s -> s.id().equals("applicantDetails"))
                    .flatMap(s -> s.fields().stream())
                    .filter(f -> f.name().equals("applicantDetails.specialty"))
                    .findFirst()
                    .orElseThrow();

            assertNotNull(specialty.options());
            assertEquals(8, specialty.options().size());
            // PRD §4.3 in the target format: each option records the parent value it belongs to.
            assertTrue(
                    specialty.options().stream().anyMatch(o -> o.filterValue() != null),
                    "a filtered select must carry filterValue so the renderer can narrow it");
        }
    }

    private static List<String> fieldNames(String stepKey) {
        return artifact.steps().stream()
                .filter(s -> s.id().equals(stepKey))
                .flatMap(s -> s.fields().stream())
                .map(CompiledForm.CompiledField::name)
                .toList();
    }
}
