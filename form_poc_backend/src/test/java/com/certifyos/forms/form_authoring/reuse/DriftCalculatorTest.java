package com.certifyos.forms.form_authoring.reuse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.form_authoring.domain.definition.Layout;
import com.certifyos.forms.form_authoring.domain.definition.Origin;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.reuse.DriftCalculator;
import com.certifyos.forms.form_authoring.domain.reuse.SectionDrift;
import com.certifyos.forms.form_authoring.domain.reuse.SectionTemplate;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Drift between a section and the template it came from.
 *
 * <p>The distinction under test is the one that makes a "sync with template" button safe or
 * destructive: what a re-sync would <em>bring in</em> versus what it would <em>overwrite</em>. A
 * calculator that reports only the first is how a tenant's customisations get silently deleted, so
 * both halves are asserted separately rather than as one undifferentiated list.
 *
 * <p>Note the asymmetry being verified: "in the template, not here" and "here, not in the template"
 * are not mirror images. Which one applies depends on {@link Origin}, which is exactly why
 * provenance is recorded rather than inferred.
 */
class DriftCalculatorTest {

    private static final QuestionId STATE = QuestionId.of("q_license_state");
    private static final QuestionId NUMBER = QuestionId.of("q_license_number");
    private static final QuestionId EXPIRY = QuestionId.of("q_license_exp");

    private static SectionTemplate template(int version, SectionTemplate.TemplateQuestion... questions) {
        return new SectionTemplate(
                "st_licensure",
                null,
                "licensure",
                "Licensure",
                version,
                null,
                null,
                List.of(questions),
                SectionTemplate.TemplateStatus.ACTIVE);
    }

    private static SectionTemplate.TemplateQuestion required(String key, QuestionId id, int order) {
        return new SectionTemplate.TemplateQuestion(key, id, order, true, Layout.FULL);
    }

    private static SectionTemplate.TemplateQuestion optional(String key, QuestionId id, int order) {
        return new SectionTemplate.TemplateQuestion(key, id, order, false, Layout.FULL);
    }

    /** The two-question template every case below starts from. */
    private static SectionTemplate baseTemplate() {
        return template(1, required("licenseState", STATE, 10), required("licenseNumber", NUMBER, 20));
    }

    private static QuestionInstance instance(String key, QuestionId id, int order, Origin origin, boolean enabled) {
        return new QuestionInstance(
                key, id, origin, enabled, order, true, Layout.FULL, null, null, null, null, null, null);
    }

    private static SectionDefinition section(Integer fromVersion, QuestionInstance... questions) {
        return new SectionDefinition(
                "sd_licensure",
                "tenant_1",
                "licensure",
                "Licensure",
                null,
                fromVersion == null ? null : "st_licensure",
                fromVersion,
                List.of(questions),
                true);
    }

    private static List<SectionDrift.Code> codes(List<SectionDrift.Finding> findings) {
        return findings.stream().map(SectionDrift.Finding::code).toList();
    }

    @Nested
    @DisplayName("no drift")
    class NoDrift {

        @Test
        @DisplayName("a freshly instantiated template has no drift at all")
        void freshInstantiation() {
            SectionTemplate template = baseTemplate();
            SectionDefinition definition = template.instantiate("sd_licensure", "tenant_1", null);

            SectionDrift drift = DriftCalculator.calculate(definition, Optional.of(template));

            assertFalse(drift.hasDrift(), () -> drift.findings().toString());
            assertFalse(drift.behindTemplate());
        }

        @Test
        @DisplayName("a section authored from scratch cannot drift, and says so rather than reporting none")
        void notFromTemplate() {
            SectionDefinition standalone = section(null, instance("adHoc", STATE, 10, Origin.ADDED, true));

            SectionDrift drift = DriftCalculator.calculate(standalone, Optional.of(baseTemplate()));

            assertFalse(drift.hasDrift());
            // Null rather than a template id: claiming a source it never had would imply a
            // relationship, and a UI would then offer to re-sync against something arbitrary.
            assertEquals(null, drift.sourceTemplateId());
        }
    }

    @Nested
    @DisplayName("what a re-sync would bring in")
    class TemplateChanges {

        @Test
        @DisplayName("a question added to the template since instantiation")
        void addedInTemplate() {
            SectionDefinition definition = baseTemplate().instantiate("sd_licensure", "tenant_1", null);
            SectionTemplate evolved = template(
                    2,
                    required("licenseState", STATE, 10),
                    required("licenseNumber", NUMBER, 20),
                    required("licenseExpiration", EXPIRY, 30));

            SectionDrift drift = DriftCalculator.calculate(definition, Optional.of(evolved));

            assertEquals(List.of(SectionDrift.Code.ADDED_IN_TEMPLATE), codes(drift.templateChanges()));
            assertEquals("licenseExpiration", drift.templateChanges().get(0).questionKey());
            assertTrue(drift.behindTemplate(), "v1 section against a v2 template");
        }

        @Test
        @DisplayName("a question the template has dropped but the section still carries")
        void removedFromTemplate() {
            SectionDefinition definition = baseTemplate().instantiate("sd_licensure", "tenant_1", null);
            SectionTemplate trimmed = template(2, required("licenseState", STATE, 10));

            SectionDrift drift = DriftCalculator.calculate(definition, Optional.of(trimmed));

            assertTrue(codes(drift.findings()).contains(SectionDrift.Code.REMOVED_FROM_TEMPLATE));
        }

        @Test
        @DisplayName("the template changing whether a question is mandatory")
        void requirednessChanged() {
            SectionDefinition definition = baseTemplate().instantiate("sd_licensure", "tenant_1", null);
            SectionTemplate relaxed =
                    template(2, required("licenseState", STATE, 10), optional("licenseNumber", NUMBER, 20));

            SectionDrift drift = DriftCalculator.calculate(definition, Optional.of(relaxed));

            assertTrue(codes(drift.findings()).contains(SectionDrift.Code.REQUIREDNESS_CHANGED_IN_TEMPLATE));
        }

        @Test
        @DisplayName("a deleted template is reported without pretending the section is broken")
        void templateGone() {
            SectionDefinition definition = baseTemplate().instantiate("sd_licensure", "tenant_1", null);

            SectionDrift drift = DriftCalculator.calculate(definition, Optional.empty());

            assertEquals(List.of(SectionDrift.Code.REMOVED_FROM_TEMPLATE), codes(drift.findings()));
            assertTrue(drift.findings().get(0).detail().contains("section itself is unaffected"));
            assertFalse(drift.behindTemplate(), "there is no later version to be behind");
        }
    }

    @Nested
    @DisplayName("what a re-sync would overwrite")
    class LocalCustomisations {

        @Test
        @DisplayName("a template question switched off locally")
        void disabledLocally() {
            SectionDefinition definition = section(
                    1,
                    instance("licenseState", STATE, 10, Origin.TEMPLATE, true),
                    instance("licenseNumber", NUMBER, 20, Origin.TEMPLATE, false));

            SectionDrift drift = DriftCalculator.calculate(definition, Optional.of(baseTemplate()));

            assertEquals(List.of(SectionDrift.Code.DISABLED_LOCALLY), codes(drift.localCustomisations()));
            assertTrue(drift.localCustomisations().get(0).detail().contains("re-sync would bring it back"));
        }

        @Test
        @DisplayName("a locally added question, distinguished from one the template dropped")
        void addedLocally() {
            SectionDefinition definition = section(
                    1,
                    instance("licenseState", STATE, 10, Origin.TEMPLATE, true),
                    instance("licenseNumber", NUMBER, 20, Origin.TEMPLATE, true),
                    instance("internalNote", EXPIRY, 30, Origin.ADDED, true));

            SectionDrift drift = DriftCalculator.calculate(definition, Optional.of(baseTemplate()));

            // Both are "here but not in the template". Origin is what separates them, and getting
            // this backwards would tell an author their own question had been deleted upstream.
            assertEquals(List.of(SectionDrift.Code.ADDED_LOCALLY), codes(drift.localCustomisations()));
            assertEquals("internalNote", drift.localCustomisations().get(0).questionKey());
        }

        @Test
        @DisplayName("a reworded template question")
        void overriddenLocally() {
            QuestionInstance reworded = new QuestionInstance(
                    "licenseState",
                    STATE,
                    Origin.TEMPLATE,
                    true,
                    10,
                    true,
                    Layout.FULL,
                    "State of licensure",
                    null,
                    null,
                    null,
                    null,
                    null);
            SectionDefinition definition =
                    section(1, reworded, instance("licenseNumber", NUMBER, 20, Origin.TEMPLATE, true));

            SectionDrift drift = DriftCalculator.calculate(definition, Optional.of(baseTemplate()));

            assertEquals(List.of(SectionDrift.Code.OVERRIDDEN_LOCALLY), codes(drift.localCustomisations()));
        }

        @Test
        @DisplayName("reordered template questions")
        void reorderedLocally() {
            SectionDefinition definition = section(
                    1,
                    instance("licenseNumber", NUMBER, 10, Origin.TEMPLATE, true),
                    instance("licenseState", STATE, 20, Origin.TEMPLATE, true));

            SectionDrift drift = DriftCalculator.calculate(definition, Optional.of(baseTemplate()));

            assertTrue(codes(drift.findings()).contains(SectionDrift.Code.REORDERED_LOCALLY));
        }

        @Test
        @DisplayName("inserting a local question is not a reorder, so the indicator stays meaningful")
        void insertionIsNotAReorder() {
            // Orders shift, relative order of the shared questions does not. Reporting this as a
            // reorder would fire the indicator on almost every customised section.
            SectionDefinition definition = section(
                    1,
                    instance("licenseState", STATE, 10, Origin.TEMPLATE, true),
                    instance("internalNote", EXPIRY, 15, Origin.ADDED, true),
                    instance("licenseNumber", NUMBER, 20, Origin.TEMPLATE, true));

            SectionDrift drift = DriftCalculator.calculate(definition, Optional.of(baseTemplate()));

            assertFalse(codes(drift.findings()).contains(SectionDrift.Code.REORDERED_LOCALLY));
            assertEquals(List.of(SectionDrift.Code.ADDED_LOCALLY), codes(drift.localCustomisations()));
        }
    }

    @Nested
    @DisplayName("both directions at once")
    class Combined {

        @Test
        @DisplayName("separates what would be gained from what would be lost")
        void separatesGainFromLoss() {
            SectionDefinition definition = section(
                    1,
                    instance("licenseState", STATE, 10, Origin.TEMPLATE, false),
                    instance("licenseNumber", NUMBER, 20, Origin.TEMPLATE, true),
                    instance("internalNote", EXPIRY, 30, Origin.ADDED, true));
            SectionTemplate evolved = template(
                    3,
                    required("licenseState", STATE, 10),
                    required("licenseNumber", NUMBER, 20),
                    required("licenseExpiration", EXPIRY, 30));

            SectionDrift drift = DriftCalculator.calculate(definition, Optional.of(evolved));

            assertEquals(List.of(SectionDrift.Code.ADDED_IN_TEMPLATE), codes(drift.templateChanges()));
            assertEquals(
                    List.of(SectionDrift.Code.DISABLED_LOCALLY, SectionDrift.Code.ADDED_LOCALLY),
                    codes(drift.localCustomisations()));
            assertTrue(drift.behindTemplate());
        }

        @Test
        @DisplayName("a diverged section whose template never moved is still drifted")
        void divergedWithoutBeingBehind() {
            SectionDefinition definition = section(
                    1,
                    instance("licenseState", STATE, 10, Origin.TEMPLATE, false),
                    instance("licenseNumber", NUMBER, 20, Origin.TEMPLATE, true));

            SectionDrift drift = DriftCalculator.calculate(definition, Optional.of(baseTemplate()));

            // `behindTemplate` and `hasDrift` are different questions; conflating them would hide
            // every local customisation made against a stable template.
            assertFalse(drift.behindTemplate());
            assertTrue(drift.hasDrift());
        }
    }

    @Nested
    @DisplayName("promotion")
    class Promotion {

        @Test
        @DisplayName("promoting a customised section mints the next version and clears the drift")
        void promotionClearsDrift() {
            SectionDefinition definition = section(
                    1,
                    instance("licenseState", STATE, 10, Origin.TEMPLATE, true),
                    instance("licenseNumber", NUMBER, 20, Origin.TEMPLATE, true),
                    instance("internalNote", EXPIRY, 30, Origin.ADDED, true));

            SectionTemplate promoted = baseTemplate().nextVersionFrom(definition);

            assertEquals(2, promoted.version());
            assertEquals(3, promoted.questions().size());
            // The local addition is now the template's, so a re-instantiated section matches it.
            SectionDefinition reinstantiated = promoted.instantiate("sd_new", "tenant_1", null);
            assertFalse(DriftCalculator.calculate(reinstantiated, Optional.of(promoted))
                    .hasDrift());
        }

        @Test
        @DisplayName("a promoted question stops reporting as a local addition")
        void promotedQuestionIsNoLongerLocal() {
            // Found by running the loop end to end: promote, and the added question was still marked
            // ADDED, so drift reported ADDED_LOCALLY forever — the indicator never cleared no matter
            // what the author did. The version bump alone was not enough; provenance had to move too.
            SectionDefinition definition = section(
                    1,
                    instance("licenseState", STATE, 10, Origin.TEMPLATE, true),
                    instance("internalNote", EXPIRY, 30, Origin.ADDED, true));

            SectionTemplate promoted = baseTemplate().nextVersionFrom(definition);
            assertTrue(promoted.question("internalNote").isPresent(), "promotion carried it up");

            // The section as the service rewrites it: version raised AND origin reconciled.
            SectionDefinition reconciled = section(
                    2,
                    instance("licenseState", STATE, 10, Origin.TEMPLATE, true),
                    instance("internalNote", EXPIRY, 30, Origin.TEMPLATE, true));

            SectionDrift after = DriftCalculator.calculate(reconciled, Optional.of(promoted));
            assertEquals(List.of(), codes(after.localCustomisations()), () -> after.findings()
                    .toString());
        }

        @Test
        @DisplayName("disable then promote settles completely, rather than reporting a phantom removal")
        void disableThenPromoteSettles() {
            // Found in the browser: after disabling a question and promoting, drift said "the
            // template has dropped this question" — moments after the author was the one who dropped
            // it. Neither side emits it now, so there is nothing left to reconcile.
            SectionDefinition definition = section(
                    1,
                    instance("licenseState", STATE, 10, Origin.TEMPLATE, true),
                    instance("licenseNumber", NUMBER, 20, Origin.TEMPLATE, false));

            SectionTemplate promoted = baseTemplate().nextVersionFrom(definition);
            assertTrue(promoted.question("licenseNumber").isEmpty(), "promotion dropped the disabled one");

            SectionDefinition reconciled = section(
                    2,
                    instance("licenseState", STATE, 10, Origin.TEMPLATE, true),
                    instance("licenseNumber", NUMBER, 20, Origin.TEMPLATE, false));

            SectionDrift after = DriftCalculator.calculate(reconciled, Optional.of(promoted));
            assertFalse(after.hasDrift(), () -> after.findings().toString());
        }

        @Test
        @DisplayName("a question the template dropped while it is still active IS reported")
        void stillActiveRemovalIsReported() {
            // The distinction the rule above turns on: this one the section still emits.
            SectionDefinition definition = section(
                    1,
                    instance("licenseState", STATE, 10, Origin.TEMPLATE, true),
                    instance("licenseNumber", NUMBER, 20, Origin.TEMPLATE, true));
            SectionTemplate trimmed = template(2, required("licenseState", STATE, 10));

            SectionDrift drift = DriftCalculator.calculate(definition, Optional.of(trimmed));

            assertTrue(codes(drift.findings()).contains(SectionDrift.Code.REMOVED_FROM_TEMPLATE));
        }

        @Test
        @DisplayName("promoting drops questions the author switched off")
        void promotionDropsDisabled() {
            SectionDefinition definition = section(
                    1,
                    instance("licenseState", STATE, 10, Origin.TEMPLATE, true),
                    instance("licenseNumber", NUMBER, 20, Origin.TEMPLATE, false));

            SectionTemplate promoted = baseTemplate().nextVersionFrom(definition);

            // Switching something off and then promoting says the template should not carry it.
            assertEquals(1, promoted.questions().size());
            assertEquals("licenseState", promoted.questions().get(0).key());
        }
    }
}
