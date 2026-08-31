package com.certifyos.forms.form_authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.form_authoring.application.FormPublishingService;
import com.certifyos.forms.form_authoring.application.command.PreviewChangeSet;
import com.certifyos.forms.form_authoring.application.command.PublishFormVersion;
import com.certifyos.forms.form_authoring.domain.compile.CompilationFailedException;
import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Layout;
import com.certifyos.forms.form_authoring.domain.definition.Origin;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.event.FormVersionPublished;
import com.certifyos.forms.form_authoring.domain.publishing.ChangeClass;
import com.certifyos.forms.question_catalog.domain.CatalogStatus;
import com.certifyos.forms.question_catalog.domain.OptionSet;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.ResponseType;
import com.certifyos.forms.shared_kernel.expression.Expression;
import com.certifyos.forms.shared_kernel.expression.Operator;
import com.certifyos.forms.support.InMemoryRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Publishing, end to end through the ports.
 *
 * <p>The behaviour under test is the one that matters most operationally: today a hand-ticked
 * checkbox decides whether publishing wipes {@code answers} for every in-progress application. Here
 * the classification is computed, the affected keys are named, and publishing announces rather than
 * reaches — a subscriber decides what to do with its own answers.
 */
class FormPublishingServiceTest {

    private static final QuestionId LINE1 = QuestionId.of("q_line1");
    private static final QuestionId CITY = QuestionId.of("q_city");
    private static final QuestionId FAX = QuestionId.of("q_fax");
    private static final QuestionId PROVIDER_TYPE = QuestionId.of("q_provider_type");
    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");

    private InMemoryRepositories.Forms forms;
    private InMemoryRepositories.Sections sections;
    private InMemoryRepositories.Versions versions;
    private InMemoryRepositories.Events events;
    private FormPublishingService service;

    private static Question text(QuestionId id, String key, String label) {
        return new Question(
                id,
                null,
                key,
                label,
                null,
                ResponseType.TEXT,
                null,
                List.of(),
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
                    new OptionSet.Option("DC", "DC — Chiropractor", Map.of())),
            true);

    private static SectionDefinition address(String id) {
        return new SectionDefinition(
                id,
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
                List.of(new QuestionInstance(
                        "providerType",
                        PROVIDER_TYPE,
                        Origin.TEMPLATE,
                        true,
                        10,
                        true,
                        Layout.HALF,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)),
                true);
    }

    @BeforeEach
    void setUp() {
        forms = new InMemoryRepositories.Forms();
        sections = new InMemoryRepositories.Sections().with(address("sd_address"), applicant());
        versions = new InMemoryRepositories.Versions();
        events = new InMemoryRepositories.Events();

        var catalog = new InMemoryRepositories.Catalog()
                .with(
                        text(LINE1, "line1", "Address line 1"),
                        text(CITY, "city", "City"),
                        text(FAX, "billingFax", "Billing fax"),
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
                                Set.of()))
                .with(PROVIDER_TYPES);

        service =
                new FormPublishingService(forms, sections, versions, catalog, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private FormDefinition saveForm(FormDefinition definition) {
        return forms.save(definition);
    }

    private FormDefinition twoAddressForm() {
        return FormDefinition.draft("fd", "t", "Florida Blue Recred", "practitioner")
                .placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                .placeStep(Step.of("practiceLocation", "sd_address", 20))
                .placeStep(Step.of("billingAddress", "sd_address", 30));
    }

    private PublishFormVersion publishCommand() {
        return new PublishFormVersion("t", "fd", "Initial publish", "CP-38192", "user_a");
    }

    @Nested
    @DisplayName("first publish")
    class FirstPublish {

        @Test
        @DisplayName("version numbering starts at 1")
        void versionStartsAtOne() {
            saveForm(twoAddressForm());
            assertEquals(1, service.handle(publishCommand()).version());
        }

        @Test
        @DisplayName("nothing exists to disturb, so the change is additive")
        void firstPublishIsAdditive() {
            saveForm(twoAddressForm());
            assertEquals(ChangeClass.ADDITIVE, service.handle(publishCommand()).changeClass());
        }

        @Test
        @DisplayName("the artifact carries one step per step, with scoped answer paths")
        void artifactShape() {
            saveForm(twoAddressForm());
            var artifact = service.handle(publishCommand()).artifact();

            assertEquals(3, artifact.steps().size());
            assertEquals(
                    List.of("practiceLocation.line1", "practiceLocation.city"),
                    artifact.steps().get(1).fields().stream().map(f -> f.name()).toList());
            assertEquals(
                    List.of("billingAddress.line1", "billingAddress.city"),
                    artifact.steps().get(2).fields().stream().map(f -> f.name()).toList());
        }

        @Test
        @DisplayName("the definition is snapshotted, so the version stays reproducible")
        void definitionSnapshotted() {
            saveForm(twoAddressForm());
            var version = service.handle(publishCommand());
            assertEquals("Florida Blue Recred", version.definitionSnapshot().name());
        }

        @Test
        @DisplayName("the clock is injected, so a version is reproducible in a test")
        void injectedClock() {
            saveForm(twoAddressForm());
            assertEquals(NOW, service.handle(publishCommand()).publishedAt());
        }
    }

    @Nested
    @DisplayName("the active version is derived, not flagged")
    class ActiveVersion {

        @Test
        @DisplayName("publishing twice leaves the higher version active — no archive write")
        void highestVersionWins() {
            saveForm(twoAddressForm());
            service.handle(publishCommand());
            var second = service.handle(publishCommand());

            assertEquals(2, second.version());
            assertEquals(2, versions.findActive("fd").orElseThrow().version());
            assertEquals(2, versions.findHistory("fd").size(), "history keeps both — versions are immutable");
        }
    }

    @Nested
    @DisplayName("change classification replaces the checkbox")
    class Classification {

        @Test
        @DisplayName("a relabelled question is TEXT — nobody loses work")
        void relabelIsTextOnly() {
            saveForm(twoAddressForm());
            service.handle(publishCommand());

            // Same question, new label.
            sections.with(new SectionDefinition(
                    "sd_address",
                    "t",
                    "address",
                    "Address",
                    null,
                    "st_address",
                    2,
                    List.of(
                            new QuestionInstance(
                                    "line1",
                                    LINE1,
                                    Origin.TEMPLATE,
                                    true,
                                    10,
                                    true,
                                    Layout.FULL,
                                    "Street address",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null),
                            QuestionInstance.fromTemplate("city", CITY, 20, true)),
                    true));

            var preview = service.handle(new PreviewChangeSet("t", "fd"));
            assertEquals(ChangeClass.TEXT, preview.changeSet().changeClass());
            assertFalse(preview.changeSet().requiresReset(), "a label change must never wipe answers");
        }

        @Test
        @DisplayName("a new optional question is ADDITIVE")
        void optionalAdditionIsAdditive() {
            saveForm(twoAddressForm());
            service.handle(publishCommand());

            sections.with(address("sd_address").addQuestion(QuestionInstance.added("billingFax", FAX, 30, false)));

            var preview = service.handle(new PreviewChangeSet("t", "fd"));
            assertEquals(ChangeClass.ADDITIVE, preview.changeSet().changeClass());
            assertFalse(preview.changeSet().requiresReset());
        }

        @Test
        @DisplayName("a new REQUIRED question is STRUCTURAL — it blocks someone already past that step")
        void requiredAdditionIsStructural() {
            saveForm(twoAddressForm());
            service.handle(publishCommand());

            sections.with(address("sd_address").addQuestion(QuestionInstance.added("billingFax", FAX, 30, true)));

            var preview = service.handle(new PreviewChangeSet("t", "fd"));
            assertEquals(ChangeClass.STRUCTURAL, preview.changeSet().changeClass());
        }

        @Test
        @DisplayName("a removed question is STRUCTURAL, and only its own answers are at risk")
        void removalIsSurgical() {
            saveForm(twoAddressForm());
            service.handle(publishCommand());

            sections.with(address("sd_address").disableQuestion("city"));

            var changeSet = service.handle(new PreviewChangeSet("t", "fd")).changeSet();

            assertEquals(ChangeClass.STRUCTURAL, changeSet.changeClass());
            // The point of computing this: two placements of the section each lose one key.
            // Not every answer on the form.
            assertEquals(Set.of("practiceLocation.city", "billingAddress.city"), changeSet.keysRequiringReset());
        }
    }

    @Nested
    @DisplayName("publishing announces; it does not reach")
    class EventBoundary {

        @Test
        @DisplayName("an event is emitted carrying the change class and the affected keys")
        void eventEmitted() {
            saveForm(twoAddressForm());
            var version = service.handle(publishCommand());

            var events = FormPublishingServiceTest.this.events.ofType(FormVersionPublished.class);
            assertEquals(1, events.size());
            assertEquals(version.id(), events.get(0).formVersionId());
            assertEquals(NOW, events.get(0).publishedAt());
        }

        @Test
        @DisplayName("a structural change asks a subscriber for a surgical reset, not a total one")
        void structuralEventNamesTheKeys() {
            saveForm(twoAddressForm());
            service.handle(publishCommand());
            sections.with(address("sd_address").disableQuestion("city"));
            service.handle(publishCommand());

            var published = events.ofType(FormVersionPublished.class).get(1);

            assertTrue(published.requiresAnswerReset());
            assertEquals(Set.of("practiceLocation.city", "billingAddress.city"), published.changedKeys());
        }

        @Test
        @DisplayName("a text-only change asks for nothing")
        void textEventRequiresNoReset() {
            saveForm(twoAddressForm());
            service.handle(publishCommand());
            service.handle(publishCommand());

            assertFalse(events.ofType(FormVersionPublished.class).get(1).requiresAnswerReset());
        }
    }

    @Nested
    @DisplayName("a form that does not compile is not published")
    class Compilation {

        @Test
        @DisplayName("publish throws with the full report, and writes nothing")
        void publishBlockedOnCompilationFailure() {
            saveForm(FormDefinition.draft("fd", "t", "Broken", "practitioner")
                    .placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                    .placeStep(Step.of("billingAddress", "sd_address", 20)
                            .withVisibleWhen(new Expression.Ref("neverDefined"))));

            var e = assertThrows(CompilationFailedException.class, () -> service.handle(publishCommand()));
            assertFalse(e.report().isClean());
            assertTrue(versions.findActive("fd").isEmpty(), "nothing should have been written");
            assertTrue(events.published().isEmpty(), "and nothing announced");
        }

        @Test
        @DisplayName("preview reports problems instead of throwing, so the UI can render them inline")
        void previewReportsProblems() {
            saveForm(FormDefinition.draft("fd", "t", "Broken", "practitioner")
                    .placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                    .placeStep(Step.of("billingAddress", "sd_address", 20)
                            .withVisibleWhen(new Expression.Leaf("applicantDetails.providerType", Operator.EQ, "NP"))));

            var preview = service.handle(new PreviewChangeSet("t", "fd"));
            assertFalse(preview.compiles());
            assertEquals("billingAddress", preview.report().problems().get(0).stepKey());
        }
    }
}
