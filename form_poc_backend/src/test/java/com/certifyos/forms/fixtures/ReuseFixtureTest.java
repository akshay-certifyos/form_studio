package com.certifyos.forms.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.config.FixtureLoader;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.reuse.DriftCalculator;
import com.certifyos.forms.form_authoring.domain.reuse.FormBlueprint;
import com.certifyos.forms.form_authoring.domain.reuse.SectionDrift;
import com.certifyos.forms.form_authoring.domain.reuse.SectionTemplate;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.shared_kernel.expression.Expression;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The reusable shapes, against the real fixtures.
 *
 * <p>This exists because of a specific defect. Section definitions and forms carried
 * {@code sourceTemplateId} and {@code sourceBlueprintId} from the start, persisted them, and exposed
 * them over the API — while no template or blueprint existed anywhere. The API answered
 * {@code "sourceTemplateId": "st_dea"} for a template that was not in the system: a dangling
 * reference that read as a working feature. Nothing failed, because nothing asserted that the
 * reference resolved.
 *
 * <p>So the first test here is referential integrity, and the rest check that drift against the real
 * data reports something meaningful rather than uniformly clean — a drift indicator that never fires
 * is indistinguishable from one that does not work.
 */
class ReuseFixtureTest {

    private static FixtureLoader.Fixtures fixtures;
    private static Map<String, SectionTemplate> templatesById;
    private static Map<String, SectionDefinition> sectionsById;

    @BeforeAll
    static void load() throws Exception {
        fixtures = new FixtureLoader(Path.of("../form_poc_shared/fixtures")).load();
        templatesById = fixtures.sectionTemplates().stream()
                .collect(Collectors.toMap(SectionTemplate::id, t -> t, (a, b) -> a, LinkedHashMap::new));
        sectionsById = fixtures.sections().stream()
                .collect(Collectors.toMap(SectionDefinition::id, s -> s, (a, b) -> a, LinkedHashMap::new));
    }

    @Nested
    @DisplayName("referential integrity")
    class ReferentialIntegrity {

        @Test
        @DisplayName("every sourceTemplateId a section records resolves to a template that exists")
        void everySectionTemplateReferenceResolves() {
            for (SectionDefinition section : fixtures.sections()) {
                if (section.sourceTemplateId() == null) {
                    continue;
                }
                assertTrue(
                        templatesById.containsKey(section.sourceTemplateId()),
                        () -> "section " + section.id() + " points at missing template " + section.sourceTemplateId());
            }
        }

        @Test
        @DisplayName("every sourceBlueprintId a form records resolves to a blueprint that exists")
        void everyFormBlueprintReferenceResolves() {
            Set<String> blueprintIds =
                    fixtures.formBlueprints().stream().map(FormBlueprint::id).collect(Collectors.toSet());

            fixtures.forms().forEach(form -> {
                if (form.sourceBlueprintId() != null) {
                    assertTrue(
                            blueprintIds.contains(form.sourceBlueprintId()),
                            () -> "form " + form.id() + " points at missing blueprint " + form.sourceBlueprintId());
                }
            });
        }

        @Test
        @DisplayName("a recorded template version is never ahead of the template itself")
        void recordedVersionIsNotFromTheFuture() {
            for (SectionDefinition section : fixtures.sections()) {
                SectionTemplate template = templatesById.get(section.sourceTemplateId());
                if (template == null || section.sourceTemplateVersion() == null) {
                    continue;
                }
                assertTrue(
                        section.sourceTemplateVersion() <= template.version(),
                        () -> "section " + section.id() + " claims template version "
                                + section.sourceTemplateVersion() + " but the template is only at v"
                                + template.version());
            }
        }

        @Test
        @DisplayName("every template question references a catalog entry that exists")
        void everyTemplateQuestionResolves() {
            Set<QuestionId> catalog =
                    fixtures.questions().stream().map(Question::id).collect(Collectors.toSet());

            for (SectionTemplate template : fixtures.sectionTemplates()) {
                for (SectionTemplate.TemplateQuestion question : template.questions()) {
                    assertTrue(
                            catalog.contains(question.catalogQuestionId()),
                            () -> "template " + template.id() + " question '" + question.key()
                                    + "' references missing catalog entry " + question.catalogQuestionId());
                }
            }
        }

        @Test
        @DisplayName("every blueprint placement references a template that exists")
        void everyBlueprintPlacementResolves() {
            for (FormBlueprint blueprint : fixtures.formBlueprints()) {
                assertEquals(
                        java.util.List.of(),
                        blueprint.missingTemplates(templatesById),
                        () -> "blueprint " + blueprint.id() + " references missing templates");
            }
        }
    }

    @Nested
    @DisplayName("drift against the real data")
    class Drift {

        private SectionDrift driftOf(String sectionId) {
            SectionDefinition section = sectionsById.get(sectionId);
            assertNotNull(section, sectionId + " missing from fixtures");
            return DriftCalculator.calculate(
                    section, Optional.ofNullable(templatesById.get(section.sourceTemplateId())));
        }

        @Test
        @DisplayName("a locally added question shows as a customisation a re-sync would destroy")
        void applicantHasLocalAddition() {
            SectionDrift drift = driftOf("sd_applicant");

            // boardCertNumber was added by the tenant; the template does not have it.
            assertTrue(
                    drift.localCustomisations().stream()
                            .anyMatch(f -> f.code() == SectionDrift.Code.ADDED_LOCALLY
                                    && "boardCertNumber".equals(f.questionKey())),
                    () -> drift.findings().toString());
            assertFalse(drift.behindTemplate(), "sd_applicant records v4 and the template is v4");
        }

        @Test
        @DisplayName("a section behind its template shows what a re-sync would bring in")
        void licensureIsBehindTemplate() {
            SectionDrift drift = driftOf("sd_licensure");

            assertTrue(drift.behindTemplate(), "records v3 against a v4 template");
            assertEquals(3, drift.definitionTemplateVersion());
            assertEquals(4, drift.currentTemplateVersion());
            assertTrue(
                    drift.templateChanges().stream()
                            .anyMatch(f -> f.code() == SectionDrift.Code.ADDED_IN_TEMPLATE
                                    && "licenseIssueDate".equals(f.questionKey())),
                    () -> drift.findings().toString());
        }

        @Test
        @DisplayName("a section level with its template reports no drift at all")
        void addressIsClean() {
            SectionDrift drift = driftOf("sd_address");

            // If everything reported drift the indicator would be noise; this is the control case.
            assertFalse(drift.hasDrift(), () -> drift.findings().toString());
        }

        @Test
        @DisplayName("a section authored from scratch reports no template rather than a phantom one")
        void attestationHasNoTemplate() {
            SectionDrift drift = driftOf("sd_attestation");

            assertFalse(drift.hasDrift());
            assertEquals(null, drift.sourceTemplateId());
        }
    }

    @Nested
    @DisplayName("instantiating the real blueprint")
    class Instantiation {

        @Test
        @DisplayName("the blueprint places every step the seeded form has a template for")
        void blueprintCoversTheTemplatedSteps() {
            FormBlueprint blueprint = fixtures.formBlueprints().get(0);

            // Eight, not nine: Attestation is tenant-specific and was authored after instantiation,
            // which is why sd_attestation has no template. A blueprint is a starting shape.
            assertEquals(8, blueprint.placements().size());
            assertEquals(7, blueprint.requiredTemplateIds().size(), "st_address placed twice");
        }

        @Test
        @DisplayName("repetition is per placement, so one address step repeats and the other does not")
        void repetitionIsPerPlacement() {
            FormBlueprint blueprint = fixtures.formBlueprints().get(0);

            FormBlueprint.BlueprintPlacement practice = placement(blueprint, "practiceLocation");
            FormBlueprint.BlueprintPlacement billing = placement(blueprint, "billingAddress");

            assertEquals(practice.sectionTemplateId(), billing.sectionTemplateId(), "same template");
            assertNotNull(practice.repeating(), "practice locations repeat");
            assertEquals(null, billing.repeating(), "a billing address does not");
        }

        @Test
        @DisplayName("instantiating a template yields questions marked as template-inherited")
        void instantiationRecordsProvenance() {
            SectionTemplate template = templatesById.get("st_dea");
            SectionDefinition created = template.instantiate("sd_new", "tenant_fl_blue", null);

            assertEquals(template.questions().size(), created.questions().size());
            created.questions()
                    .forEach(q -> assertEquals(
                            com.certifyos.forms.form_authoring.domain.definition.Origin.TEMPLATE, q.origin()));
            assertEquals("st_dea", created.sourceTemplateId());
            assertEquals(template.version(), created.sourceTemplateVersion());
            assertFalse(
                    DriftCalculator.calculate(created, Optional.of(template)).hasDrift());
        }

        private static FormBlueprint.BlueprintPlacement placement(FormBlueprint blueprint, String stepKey) {
            return blueprint.placements().stream()
                    .filter(p -> p.stepKey().equals(stepKey))
                    .findFirst()
                    .orElseThrow();
        }
    }

    /**
     * A blueprint has to carry its logic, and the fixture has to prove it.
     *
     * <p>Same failure shape as the defect at the top of this file, one level along. The blueprint
     * fixture originally held placements and nothing else, so instantiating it produced a form with
     * every step unconditional — and that reads as a success, because the form renders perfectly. It
     * is also the precise defect three Florida Blue sections ship with in production today, which
     * would have made it very easy to mistake for correct behaviour.
     */
    @Nested
    @DisplayName("the blueprint carries logic, not only shape")
    class BlueprintLogic {

        private FormBlueprint blueprint() {
            return fixtures.formBlueprints().stream()
                    .filter(b -> b.id().equals("fb_practitioner_recred"))
                    .findFirst()
                    .orElseThrow();
        }

        @Test
        @DisplayName("every ref in a placement resolves against the blueprint's own conditions")
        void refsResolve() {
            FormBlueprint blueprint = blueprint();
            Set<String> defined = blueprint.namedConditions().keySet();

            assertFalse(defined.isEmpty(), "a blueprint with no named conditions cannot carry a ref at all");

            for (FormBlueprint.BlueprintPlacement placement : blueprint.placements()) {
                for (String key : refsIn(placement.visibleWhen())) {
                    assertTrue(
                            defined.contains(key),
                            "placement '" + placement.stepKey() + "' references condition '" + key
                                    + "', which the blueprint does not define — instantiating it would produce a "
                                    + "form that cannot compile, and the author would meet the failure one step "
                                    + "removed from the cause");
                }
            }
        }

        @Test
        @DisplayName("the placements' rules match the seeded form they describe")
        void staysInLockstepWithTheForm() {
            FormBlueprint blueprint = blueprint();

            var form = fixtures.forms().stream()
                    .filter(f -> f.id().equals("fd_fl_blue_recred"))
                    .findFirst()
                    .orElseThrow();

            // Deliberately in lockstep, unlike the templates and sections, which are kept apart on
            // purpose so drift is demonstrable. There is no drift concept between a blueprint and a
            // form, so a mismatch here is not a scenario — it is just the fixture lying about what
            // instantiating this shape would give you.
            for (var step : form.steps()) {
                FormBlueprint.BlueprintPlacement placement = blueprint.placements().stream()
                        .filter(p -> p.stepKey().equals(step.key().value()))
                        .findFirst()
                        .orElse(null);
                if (placement == null) {
                    continue;
                }
                assertEquals(
                        step.visibleWhen(),
                        placement.visibleWhen(),
                        "step '" + step.key() + "' and the blueprint placement of the same name disagree");
            }

            assertEquals(
                    form.namedConditions().keySet(),
                    blueprint.namedConditions().keySet(),
                    "the blueprint must define the same rules the form does, or a ref would dangle");
        }

        @Test
        @DisplayName("at least one placement is actually conditioned, so the assertion above can fail")
        void someRuleIsCarried() {
            long conditioned = blueprint().placements().stream()
                    .filter(p -> p.visibleWhen() != null)
                    .count();

            // Without this, a blueprint that lost every rule would satisfy the lockstep test only if
            // the form lost them too — and a suite that passes on two empty sets is not a check.
            assertTrue(conditioned >= 4, "expected the recred blueprint to carry its four rules, found " + conditioned);
        }

        private static Set<String> refsIn(Expression expression) {
            if (expression == null) {
                return Set.of();
            }
            return switch (expression) {
                case Expression.Ref ref -> Set.of(ref.key());
                case Expression.Not not -> refsIn(not.operand());
                case Expression.All all -> all.operands().stream()
                        .flatMap(e -> refsIn(e).stream())
                        .collect(Collectors.toSet());
                case Expression.Any any -> any.operands().stream()
                        .flatMap(e -> refsIn(e).stream())
                        .collect(Collectors.toSet());
                case Expression.Some some -> refsIn(some.where());
                case Expression.Every every -> refsIn(every.where());
                case Expression.Leaf ignored -> Set.of();
            };
        }
    }
}
