package com.certifyos.forms.form_authoring.interfaces;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.port.FormDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionDefinitionRepository;
import com.certifyos.forms.question_catalog.domain.CatalogStatus;
import com.certifyos.forms.question_catalog.domain.OptionSet;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.ResponseType;
import com.certifyos.forms.question_catalog.domain.port.OptionSetRepository;
import com.certifyos.forms.question_catalog.domain.port.QuestionRepository;
import com.certifyos.forms.shared_kernel.expression.Expression;
import com.certifyos.forms.shared_kernel.expression.Operator;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The resource layer, exercised over HTTP.
 *
 * <p>Every other test here constructs objects directly. That verifies the model but says nothing
 * about serialisation, status codes, or whether the exception mappers are actually registered — all
 * of which are invisible until a real request goes through. A 500 where a 422 was intended is not
 * something a unit test can see.
 *
 * <p>Runs against in-memory repositories via {@link RestTestProfile}, so no Mongo and no Docker.
 */
@QuarkusTest
@TestProfile(RestTestProfile.class)
class FormDefinitionResourceTest {

    private static final String TENANT = "tenant_fl";
    private static final QuestionId NPI = QuestionId.of("q_npi");
    private static final QuestionId PROVIDER_TYPE = QuestionId.of("q_provider_type");
    private static final QuestionId LINE1 = QuestionId.of("q_line1");

    @Inject
    FormDefinitionRepository forms;

    @Inject
    SectionDefinitionRepository sections;

    @Inject
    QuestionRepository questions;

    @Inject
    OptionSetRepository optionSets;

    @Inject
    com.certifyos.forms.form_authoring.domain.port.FormVersionRepository versions;

    @BeforeEach
    void seed() {
        // These fakes are @ApplicationScoped, so state survives between test methods exactly as a
        // real database would. Clearing first keeps each test independent.
        List.of(forms, sections, questions, optionSets, versions)
                .forEach(r -> ((TestRepositories.Resettable) r).reset());

        questions.save(new Question(
                NPI,
                null,
                "npi",
                "NPI",
                "10 digits, no dashes",
                ResponseType.TEXT,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                List.of(),
                null,
                CatalogStatus.ACTIVE,
                Set.of()));
        questions.save(new Question(
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
                Set.of()));
        questions.save(new Question(
                LINE1,
                null,
                "line1",
                "Address line 1",
                null,
                ResponseType.TEXT,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                List.of(),
                null,
                CatalogStatus.ACTIVE,
                Set.of()));

        optionSets.save(new OptionSet(
                "os_pt",
                null,
                "providerTypes",
                "Provider types",
                List.of(
                        new OptionSet.Option("MD", "MD — Physician", Map.of()),
                        new OptionSet.Option("DC", "DC — Chiropractor", Map.of())),
                true));

        sections.save(new SectionDefinition(
                "sd_applicant",
                TENANT,
                "applicant",
                "Applicant Details",
                null,
                null,
                null,
                List.of(
                        QuestionInstance.fromTemplate("npi", NPI, 10, true),
                        QuestionInstance.fromTemplate("providerType", PROVIDER_TYPE, 20, true)),
                true));

        sections.save(new SectionDefinition(
                "sd_address",
                TENANT,
                "address",
                "Address",
                null,
                "st_address",
                2,
                List.of(QuestionInstance.fromTemplate("line1", LINE1, 10, true)),
                true));

        forms.save(twoAddressForm());
    }

    /** The Florida Blue shape: one address section placed twice, plus the DEA exemption. */
    private static FormDefinition twoAddressForm() {
        return FormDefinition.draft("fd_fl", TENANT, "Florida Blue Recred", "practitioner")
                .withNamedCondition(new FormDefinition.NamedCondition(
                        "specialtyExempt",
                        "Specialty exempt from DEA requirements",
                        new Expression.Leaf("applicantDetails.providerType", Operator.IN, List.of("DC"))))
                .placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                .placeStep(Step.of("practiceLocation", "sd_address", 20))
                .placeStep(Step.of("billingAddress", "sd_address", 30)
                        .withVisibleWhen(new Expression.Not(new Expression.Ref("specialtyExempt"))));
    }

    private String formsPath() {
        return "/api/v1/tenants/" + TENANT + "/forms";
    }

    @Test
    @DisplayName("GET / lists the tenant's forms")
    void listForms() {
        given().when()
                .get(formsPath())
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].name", equalTo("Florida Blue Recred"))
                .body("[0].stepCount", equalTo(3));
    }

    @Test
    @DisplayName("GET /{id} returns steps and named conditions with a usage count")
    void getForm() {
        given().when()
                .get(formsPath() + "/fd_fl")
                .then()
                .statusCode(200)
                .body("steps", hasSize(3))
                .body("steps.key", contains("applicantDetails", "practiceLocation", "billingAddress"))
                // The reason named conditions exist: an author must see that editing one rule
                // touches every step using it.
                .body("namedConditions", hasSize(1))
                .body("namedConditions[0].label", equalTo("Specialty exempt from DEA requirements"))
                .body("namedConditions[0].referenceCount", equalTo(1));
    }

    @Test
    @DisplayName("a condition goes out as grammar JSON, so the frontend evaluator reads what the backend stores")
    void conditionIsGrammarJson() {
        given().when()
                .get(formsPath() + "/fd_fl")
                .then()
                .statusCode(200)
                // Definition side keeps the ref — only the artifact inlines it.
                .body("steps[2].visibleWhen.not.ref", equalTo("specialtyExempt"))
                .body("steps[0].visibleWhen", nullValue());
    }

    @Test
    @DisplayName("GET /{id} for a form that does not exist is 404, not 500")
    void missingFormIs404() {
        given().when()
                .get(formsPath() + "/nope")
                .then()
                .statusCode(404)
                .body("code", equalTo("NOT_FOUND"))
                .body("message", containsString("nope"));
    }

    @Test
    @DisplayName("change-preview on a first publish reports additive and resets nothing")
    void changePreviewFirstPublish() {
        given().when()
                .get(formsPath() + "/fd_fl/change-preview")
                .then()
                .statusCode(200)
                .body("changeClass", equalTo("additive"))
                .body("requiresReset", equalTo(false));
    }

    @Test
    @DisplayName("publishing returns the version and its computed change class")
    void publish() {
        given().contentType("application/json")
                .body("{\"changelog\":\"Initial publish\",\"ticketId\":\"CP-38192\"}")
                .when()
                .post(formsPath() + "/fd_fl/publish")
                .then()
                .statusCode(200)
                .body("version", equalTo(1))
                .body("changeClass", equalTo("additive"))
                .body("publishedBy", equalTo("poc-author"))
                .body("publishedAt", notNullValue())
                .body("stepCount", equalTo(3));
    }

    @Test
    @DisplayName("a blank changelog is rejected by validation before reaching the domain")
    void blankChangelogRejected() {
        given().contentType("application/json")
                .body("{\"changelog\":\"  \"}")
                .when()
                .post(formsPath() + "/fd_fl/publish")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("the compiled artifact is the shape the existing renderer consumes")
    void compiledArtifact() {
        String versionId = given().contentType("application/json")
                .body("{\"changelog\":\"Initial publish\"}")
                .when()
                .post(formsPath() + "/fd_fl/publish")
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        given().when()
                .get(formsPath() + "/fd_fl/versions/" + versionId + "/compiled")
                .then()
                .statusCode(200)
                // One step per step, and placement-scoped answer names — the two claims the
                // model rests on, asserted over the wire.
                .body("steps", hasSize(3))
                .body("steps[1].fields[0].name", equalTo("practiceLocation.line1"))
                .body("steps[2].fields[0].name", equalTo("billingAddress.line1"))
                // Every field carries layout, which the production renderer requires.
                .body("steps[0].fields[0].layout.columns", equalTo(12))
                // The ref is inlined here, unlike on the definition.
                .body("steps[2].condition.not.op", equalTo("in"))
                .body("steps[2].condition.not.ref", nullValue());
    }

    @Test
    @DisplayName("a broken form returns 422 with every problem pinned to its step")
    void brokenFormReturns422() {
        forms.save(FormDefinition.draft("fd_broken", TENANT, "Broken", "practitioner")
                .placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                .placeStep(Step.of("billingAddress", "sd_address", 20)
                        .withVisibleWhen(new Expression.Ref("neverDefined"))));

        given().when()
                .get(formsPath() + "/fd_broken/validate")
                .then()
                .statusCode(422)
                .body("code", equalTo("COMPILATION_FAILED"))
                .body("message", containsString("1 problem"))
                .body("problems", hasSize(1))
                .body("problems[0].code", equalTo("UNRESOLVED_REF"))
                // Pinned to a step so the UI attaches it to a node rather than listing codes.
                .body("problems[0].stepKey", equalTo("billingAddress"))
                // Written for an author, not an engineer.
                .body("problems[0].message", containsString("no longer exists"));
    }

    @Test
    @DisplayName("publishing a broken form is refused, and nothing is written")
    void publishingBrokenFormIsRefused() {
        forms.save(FormDefinition.draft("fd_broken2", TENANT, "Broken", "practitioner")
                .placeStep(Step.of("applicantDetails", "sd_applicant", 10))
                .placeStep(Step.of("billingAddress", "sd_address", 20)
                        .withVisibleWhen(new Expression.Leaf("applicantDetails.providerType", Operator.EQ, "NP"))));

        given().contentType("application/json")
                .body("{\"changelog\":\"should not land\"}")
                .when()
                .post(formsPath() + "/fd_broken2/publish")
                .then()
                .statusCode(422)
                .body("problems[0].code", equalTo("VALUE_NOT_IN_OPTION_SET"));

        given().when()
                .get(formsPath() + "/fd_broken2/versions")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    @DisplayName("history keeps every version — publishing never mutates an earlier one")
    void historyIsAppendOnly() {
        for (int i = 1; i <= 2; i++) {
            given().contentType("application/json")
                    .body("{\"changelog\":\"publish " + i + "\"}")
                    .when()
                    .post(formsPath() + "/fd_fl/publish")
                    .then()
                    .statusCode(200);
        }

        given().when()
                .get(formsPath() + "/fd_fl/versions")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("version", hasItem(1))
                .body("version", hasItem(2));
    }

    @Test
    @DisplayName("change-preview carries notices, so a dropped hard stop is visible before publishing")
    void changePreviewCarriesNotices() {
        forms.save(twoAddressForm()
                .withHardStop(new FormDefinition.HardStop(
                        "excluded",
                        new Expression.Leaf("applicantDetails.providerType", Operator.EQ, "DC"),
                        "Chiropractors are not eligible for this network.",
                        "next")));

        given().when()
                .get(formsPath() + "/fd_fl/change-preview")
                .then()
                // 200, not 422: the form is publishable. A notice that blocked publishing would be
                // a problem, and the two must not be conflated.
                .statusCode(200)
                .body("notices", hasSize(1))
                .body("notices[0].code", equalTo("HARD_STOP_NOT_COMPILED"))
                .body("notices[0].message", containsString("will not block submission"))
                // Form-level, so it has no step to pin to.
                .body("notices[0].stepKey", nullValue());
    }

    @Test
    @DisplayName("change-preview reports no notices for a form that compiles completely")
    void changePreviewHasNoNoticesWhenNothingIsDropped() {
        given().when()
                .get(formsPath() + "/fd_fl/change-preview")
                .then()
                .statusCode(200)
                .body("notices", hasSize(0));
    }

    // ------------------------------------------------------------------
    // PATCH condition — the endpoint the condition builder saves through
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PATCH condition stores the rule and returns the whole updated form")
    void patchConditionStoresRule() {
        given().contentType("application/json")
                .body("{\"visibleWhen\":{\"field\":\"applicantDetails.providerType\",\"op\":\"eq\",\"value\":\"DC\"}}")
                .when()
                .patch(formsPath() + "/fd_fl/steps/practiceLocation/condition")
                .then()
                .statusCode(200)
                .body("steps[1].visibleWhen.field", equalTo("applicantDetails.providerType"))
                .body("steps[1].visibleWhen.op", equalTo("eq"))
                .body("steps[1].visibleWhen.value", equalTo("DC"));
    }

    @Test
    @DisplayName("PATCH condition round-trips the grammar exactly, including a nested not/in")
    void patchConditionRoundTrips() {
        // The shape CP-38192 actually needs. If the codec loses the nesting, the rule silently
        // inverts — which is the worst possible failure for a form gate.
        given().contentType("application/json")
                .body("{\"visibleWhen\":{\"not\":{\"any\":["
                        + "{\"field\":\"applicantDetails.providerType\",\"op\":\"in\",\"value\":[\"DC\",\"DPM\"]}"
                        + "]}}}")
                .when()
                .patch(formsPath() + "/fd_fl/steps/practiceLocation/condition")
                .then()
                .statusCode(200)
                .body("steps[1].visibleWhen.not.any[0].op", equalTo("in"))
                .body("steps[1].visibleWhen.not.any[0].value", contains("DC", "DPM"));
    }

    @Test
    @DisplayName("PATCH condition with a null body clears the rule, making the step unconditional")
    void patchConditionClearsRule() {
        given().contentType("application/json")
                .body("{\"visibleWhen\":null}")
                .when()
                .patch(formsPath() + "/fd_fl/steps/billingAddress/condition")
                .then()
                .statusCode(200)
                .body("steps[2].visibleWhen", nullValue());
    }

    @Test
    @DisplayName("PATCH condition leaves the other steps' rules alone")
    void patchConditionTouchesOneStep() {
        given().contentType("application/json")
                .body("{\"visibleWhen\":{\"field\":\"applicantDetails.npi\",\"op\":\"exists\"}}")
                .when()
                .patch(formsPath() + "/fd_fl/steps/practiceLocation/condition")
                .then()
                .statusCode(200)
                // billingAddress still carries the named condition it started with.
                .body("steps[2].visibleWhen.not.ref", equalTo("specialtyExempt"));
    }

    @Test
    @DisplayName("PATCH condition for an unknown step is 404, not 500")
    void patchConditionUnknownStepIs404() {
        given().contentType("application/json")
                .body("{\"visibleWhen\":null}")
                .when()
                .patch(formsPath() + "/fd_fl/steps/notAStep/condition")
                .then()
                .statusCode(404)
                .body("message", containsString("notAStep"));
    }

    @Test
    @DisplayName("PATCH condition with an unknown operator is 422 with a usable message, not 500")
    void patchConditionUnknownOperatorIs422() {
        given().contentType("application/json")
                .body("{\"visibleWhen\":{\"field\":\"applicantDetails.npi\",\"op\":\"isMostly\"}}")
                .when()
                .patch(formsPath() + "/fd_fl/steps/practiceLocation/condition")
                .then()
                .statusCode(422)
                .body("message", containsString("isMostly"));
    }

    @Test
    @DisplayName("a condition saved over HTTP survives into the compiled artifact")
    void patchedConditionReachesTheArtifact() {
        given().contentType("application/json")
                .body("{\"visibleWhen\":{\"field\":\"applicantDetails.providerType\",\"op\":\"eq\",\"value\":\"DC\"}}")
                .when()
                .patch(formsPath() + "/fd_fl/steps/practiceLocation/condition")
                .then()
                .statusCode(200);

        String versionId = given().contentType("application/json")
                .body("{\"changelog\":\"gate practice location on provider type\"}")
                .when()
                .post(formsPath() + "/fd_fl/publish")
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        // The whole loop, closed: edited in the studio, compiled by the backend, present in the
        // artifact the existing renderer consumes. No code changed to add the rule.
        given().when()
                .get(formsPath() + "/fd_fl/versions/" + versionId + "/compiled")
                .then()
                .statusCode(200)
                .body("steps[1].condition.field", equalTo("applicantDetails.providerType"))
                .body("steps[1].condition.value", equalTo("DC"));
    }
}
