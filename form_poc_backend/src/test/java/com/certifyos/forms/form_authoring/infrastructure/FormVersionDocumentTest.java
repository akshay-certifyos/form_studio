package com.certifyos.forms.form_authoring.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.certifyos.forms.form_authoring.domain.compile.CatalogSnapshot;
import com.certifyos.forms.form_authoring.domain.compile.FormCompiler;
import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.publishing.ChangeClass;
import com.certifyos.forms.form_authoring.domain.publishing.ChangeSet;
import com.certifyos.forms.form_authoring.domain.publishing.CompiledForm;
import com.certifyos.forms.form_authoring.domain.publishing.FormVersion;
import com.certifyos.forms.form_authoring.infrastructure.mongo.FormVersionDocument;
import com.certifyos.forms.question_catalog.domain.CatalogStatus;
import com.certifyos.forms.question_catalog.domain.OptionSet;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.ResponseType;
import com.certifyos.forms.question_catalog.domain.ValidationRule;
import com.certifyos.forms.shared_kernel.expression.Expression;
import com.certifyos.forms.shared_kernel.expression.Operator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The artifact goes through Jackson on its way into Mongo and back.
 *
 * <p>That is riskier than it looks. {@link ChangeSet#between} compares a <em>stored</em> artifact
 * against a <em>freshly compiled</em> one, so any infidelity in this round trip shows up as a
 * spurious difference — and a spurious difference is classified {@code STRUCTURAL}, which wipes
 * providers' answers. A form nobody changed could cost people their work.
 *
 * <p>This is the same failure shape as the {@code IntNode}/{@code LongNode} bug the expression codec
 * test caught, which is why it is worth pinning here too rather than assuming Jackson is symmetric.
 */
class FormVersionDocumentTest {

    private static final QuestionId NPI = QuestionId.of("q_npi");
    private static final QuestionId PROVIDER_TYPE = QuestionId.of("q_provider_type");
    private static final QuestionId CITY = QuestionId.of("q_city");
    private static final Instant PUBLISHED = Instant.parse("2026-08-31T10:00:00Z");

    /** Compiled from a real definition rather than hand-built, so the shape is the true one. */
    private static CompiledForm compiledArtifact() {
        Question npi = new Question(
                NPI,
                null,
                "npi",
                "NPI",
                "10 digits, no dashes",
                ResponseType.TEXT,
                null,
                List.of(
                        ValidationRule.of(ValidationRule.Kind.LENGTH, Map.of("exact", 10)),
                        ValidationRule.of(ValidationRule.Kind.NPI_CHECKSUM)),
                Map.of("practitioner", "npi"),
                Set.of(),
                List.of(),
                null,
                CatalogStatus.ACTIVE,
                Set.of(),
                "identity");

        Question providerType = new Question(
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
                Set.of(),
                "identity");

        Question city = new Question(
                CITY,
                null,
                "city",
                "City",
                null,
                ResponseType.TEXT,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                List.of(),
                null,
                CatalogStatus.ACTIVE,
                Set.of(),
                "identity");

        OptionSet providerTypes = new OptionSet(
                "os_pt",
                null,
                "providerTypes",
                "Provider types",
                List.of(
                        new OptionSet.Option("MD", "MD — Physician", Map.of()),
                        new OptionSet.Option("DC", "DC — Chiropractor", Map.of())),
                true);

        SectionDefinition applicant = new SectionDefinition(
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

        SectionDefinition address = new SectionDefinition(
                "sd_address",
                "t",
                "address",
                "Address",
                null,
                "st_address",
                2,
                List.of(QuestionInstance.fromTemplate("city", CITY, 10, true)),
                true);

        FormDefinition definition = FormDefinition.draft("fd", "t", "Florida Blue Recred", "practitioner")
                .placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                .placeStep(Step.of("practiceLocation", "sd_address", 20))
                .placeStep(Step.of("billingAddress", "sd_address", 30)
                        .withVisibleWhen(new Expression.Not(
                                new Expression.Leaf("applicantDetails.providerType", Operator.IN, List.of("DC")))));

        return FormCompiler.compile(
                definition,
                Map.of("sd_applicant", applicant, "sd_address", address),
                CatalogSnapshot.of(List.of(npi, providerType, city), List.of(providerTypes)));
    }

    private static FormVersion version(CompiledForm artifact, ChangeSet changeSet) {
        return new FormVersion(
                "fv_1",
                "t",
                "fd",
                2,
                artifact,
                null,
                changeSet,
                "Added the DEA exemption",
                "CP-38192",
                PUBLISHED,
                "user_a");
    }

    @Test
    @DisplayName("the artifact survives Jackson unchanged — a spurious diff would wipe answers")
    void artifactRoundTripsExactly() {
        CompiledForm original = compiledArtifact();
        FormVersion reloaded = FormVersionDocument.from(version(original, ChangeSet.firstPublish()))
                .toDomain();

        assertEquals(original, reloaded.artifact());

        // The consequence, asserted directly: comparing a stored artifact against a freshly
        // compiled identical one must find nothing.
        ChangeSet diff = ChangeSet.between(reloaded.artifact(), compiledArtifact());
        assertEquals(ChangeClass.TEXT, diff.changeClass());
        assertEquals(Set.of(), diff.keysRequiringReset(), () -> "spurious diff: " + diff.notes());
    }

    @Test
    @DisplayName("absent optional fields stay absent rather than reloading as empty objects")
    void absentFieldsStayAbsent() {
        FormVersion reloaded = FormVersionDocument.from(version(compiledArtifact(), ChangeSet.firstPublish()))
                .toDomain();

        CompiledForm.CompiledStep applicant = reloaded.artifact().steps().get(0);
        assertNull(applicant.condition(), "an unconditioned step must not gain an empty condition");
        assertNull(applicant.type(), "an unset type must stay unset");

        CompiledForm.CompiledField city =
                reloaded.artifact().steps().get(1).fields().get(0);
        assertNull(city.options(), "a text field must not gain an empty option list");
        assertNull(city.validation(), "and must not gain empty validation");
    }

    @Test
    @DisplayName("a condition survives with its operator and values intact")
    void conditionSurvives() {
        FormVersion reloaded = FormVersionDocument.from(version(compiledArtifact(), ChangeSet.firstPublish()))
                .toDomain();

        var condition = reloaded.artifact().steps().get(2).condition();
        assertNotNull(condition);
        assertEquals("in", condition.path("not").path("op").asText());
        assertEquals("DC", condition.path("not").path("value").get(0).asText());
    }

    @Test
    @DisplayName("validations and layout survive — both are required by the renderer")
    void validationsAndLayoutSurvive() {
        FormVersion reloaded = FormVersionDocument.from(version(compiledArtifact(), ChangeSet.firstPublish()))
                .toDomain();

        CompiledForm.CompiledField npiField =
                reloaded.artifact().steps().get(0).fields().get(0);

        assertEquals(10, npiField.validation().minLength());
        assertEquals("npi-luhn", npiField.validation().customValidator());
        assertEquals(12, npiField.layout().columns());
        assertEquals("10 digits, no dashes", npiField.hint());
    }

    @Test
    @DisplayName("the change set survives, so history explains itself")
    void changeSetSurvives() {
        ChangeSet original = new ChangeSet(
                ChangeClass.STRUCTURAL,
                Set.of("billingAddress.fax"),
                Set.of("practiceLocation.county"),
                Set.of("applicantDetails.npi"),
                List.of("1 question removed.", "1 question changed."));

        FormVersion reloaded =
                FormVersionDocument.from(version(compiledArtifact(), original)).toDomain();

        assertEquals(original, reloaded.changeSet());
        assertEquals(ChangeClass.STRUCTURAL, reloaded.changeClass());
    }

    @Test
    @DisplayName("the publish timestamp survives — a version is an audit record")
    void auditFieldsSurvive() {
        FormVersion reloaded = FormVersionDocument.from(version(compiledArtifact(), ChangeSet.firstPublish()))
                .toDomain();

        assertEquals(PUBLISHED, reloaded.publishedAt());
        assertEquals("user_a", reloaded.publishedBy());
        assertEquals("CP-38192", reloaded.ticketId());
    }
}
