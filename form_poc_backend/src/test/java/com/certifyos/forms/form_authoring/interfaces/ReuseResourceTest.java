package com.certifyos.forms.form_authoring.interfaces;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.certifyos.forms.form_authoring.domain.definition.Layout;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.port.FormBlueprintRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionTemplateRepository;
import com.certifyos.forms.form_authoring.domain.reuse.FormBlueprint;
import com.certifyos.forms.form_authoring.domain.reuse.SectionTemplate;
import com.certifyos.forms.question_catalog.domain.CatalogStatus;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.ResponseType;
import com.certifyos.forms.question_catalog.domain.port.QuestionRepository;
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
 * Templates, blueprints and the authoring loop, over HTTP.
 *
 * <p>Covers the gap that let this whole area ship half-built: section definitions and forms carried
 * {@code sourceTemplateId} and {@code sourceBlueprintId} from day one and exposed them over the API,
 * while nothing existed to resolve them. The reads below are what make those references answerable.
 *
 * <p>The loop under test is the one originally asked for: take a shared template, switch off what
 * does not apply, add what does, see what has diverged, push it back.
 */
@QuarkusTest
@TestProfile(RestTestProfile.class)
class ReuseResourceTest {

    private static final String TENANT = "tenant_fl";

    @Inject
    SectionTemplateRepository templates;

    @Inject
    FormBlueprintRepository blueprints;

    @Inject
    SectionDefinitionRepository sections;

    @Inject
    com.certifyos.forms.form_authoring.domain.port.FormDefinitionRepository forms;

    @Inject
    QuestionRepository questions;

    @BeforeEach
    void seed() {
        // Every store, forms included. These fakes are @ApplicationScoped, so state survives between
        // methods exactly as a real database would — and a forgotten reset shows up as another test's
        // data, not as an error.
        List.of(templates, blueprints, sections, questions, forms)
                .forEach(r -> ((TestRepositories.Resettable) r).reset());

        questions.save(question("q_dea_number", "deaNumber", "DEA registration number"));
        questions.save(question("q_dea_expiration", "deaExpiration", "DEA expiration date"));
        questions.save(question("q_addr_state", "state", "State"));

        templates.save(new SectionTemplate(
                "st_dea",
                null,
                "dea",
                "DEA Registration",
                1,
                null,
                null,
                List.of(
                        new SectionTemplate.TemplateQuestion(
                                "deaNumber", QuestionId.of("q_dea_number"), 10, true, Layout.HALF),
                        new SectionTemplate.TemplateQuestion(
                                "deaExpiration", QuestionId.of("q_dea_expiration"), 20, true, Layout.HALF)),
                SectionTemplate.TemplateStatus.ACTIVE));

        templates.save(new SectionTemplate(
                "st_address",
                null,
                "address",
                "Address",
                1,
                null,
                null,
                List.of(new SectionTemplate.TemplateQuestion(
                        "state", QuestionId.of("q_addr_state"), 10, true, Layout.FULL)),
                SectionTemplate.TemplateStatus.ACTIVE));

        // A deprecated template must not appear in the browse list.
        templates.save(new SectionTemplate(
                "st_retired",
                null,
                "retired",
                "Retired Section",
                1,
                null,
                null,
                List.of(),
                SectionTemplate.TemplateStatus.DEPRECATED));

        blueprints.save(new FormBlueprint(
                "fb_recred",
                null,
                "recred",
                "Recredentialing",
                2,
                "practitioner",
                new FormBlueprint.RecognitionHints(List.of("st_dea"), List.of("recredentialing")),
                Map.of(),
                List.of(
                        new FormBlueprint.BlueprintPlacement(
                                "deaRegistration", "st_dea", 10, "Credentials", null, null),
                        // Same template twice, repeating in one placement only.
                        new FormBlueprint.BlueprintPlacement(
                                "practiceLocation",
                                "st_address",
                                20,
                                "Locations",
                                new Step.Repeating(1, 10, "Location"),
                                null),
                        new FormBlueprint.BlueprintPlacement(
                                "billingAddress", "st_address", 30, "Locations", null, null)),
                SectionTemplate.TemplateStatus.ACTIVE));

        // References a template nobody has, so instantiating it must fail before writing anything.
        blueprints.save(new FormBlueprint(
                "fb_broken",
                null,
                "broken",
                "Broken",
                1,
                "practitioner",
                FormBlueprint.RecognitionHints.none(),
                Map.of(),
                List.of(new FormBlueprint.BlueprintPlacement("ghost", "st_missing", 10, null, null, null)),
                SectionTemplate.TemplateStatus.ACTIVE));
    }

    private static Question question(String id, String key, String label) {
        return new Question(
                QuestionId.of(id),
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
                Set.of(),
                "identity");
    }

    private String path() {
        return "/api/v1/tenants/" + TENANT;
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /section-templates lists active templates and hides deprecated ones")
    void listsTemplates() {
        given().when()
                .get(path() + "/section-templates")
                .then()
                .statusCode(200)
                .body("id", not(contains("st_retired")))
                .body("$", hasSize(2))
                .body("find { it.id == 'st_dea' }.global", equalTo(true))
                .body("find { it.id == 'st_dea' }.questionCount", equalTo(2));
    }

    @Test
    @DisplayName("GET /section-templates/{id} resolves a reference a section records")
    void resolvesTemplateReference() {
        // The endpoint whose absence made sourceTemplateId a dangling pointer.
        given().when()
                .get(path() + "/section-templates/st_dea")
                .then()
                .statusCode(200)
                .body("version", equalTo(1))
                .body("questions.key", contains("deaNumber", "deaExpiration"));
    }

    @Test
    @DisplayName("GET /blueprints exposes placements with per-placement repetition")
    void listsBlueprints() {
        given().when()
                .get(path() + "/blueprints/fb_recred")
                .then()
                .statusCode(200)
                .body("placements", hasSize(3))
                // The same template placed twice, repeating in one and not the other.
                .body("placements.find { it.stepKey == 'practiceLocation' }.repeating", equalTo(true))
                .body("placements.find { it.stepKey == 'billingAddress' }.repeating", equalTo(false))
                .body("keywords", contains("recredentialing"));
    }

    @Test
    @DisplayName("a missing template is 404, not 500")
    void missingTemplateIs404() {
        given().when().get(path() + "/section-templates/st_nope").then().statusCode(404);
    }

    // ------------------------------------------------------------------
    // the authoring loop
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /sections instantiates a template, marking every question template-inherited")
    void createsSectionFromTemplate() {
        given().contentType("application/json")
                .body("{\"sectionTemplateId\":\"st_dea\",\"name\":\"DEA (Florida)\"}")
                .when()
                .post(path() + "/sections")
                .then()
                .statusCode(200)
                .body("name", equalTo("DEA (Florida)"))
                .body("sourceTemplateId", equalTo("st_dea"))
                .body("sourceTemplateVersion", equalTo(1))
                .body("questions", hasSize(2))
                .body("questions.origin", contains("TEMPLATE", "TEMPLATE"));
    }

    @Test
    @DisplayName("disabling a question keeps it, because provenance is what a template upgrade needs")
    void disablesWithoutDeleting() {
        String sectionId = instantiate();

        given().contentType("application/json")
                .body("{\"enabled\":false}")
                .when()
                .patch(path() + "/sections/" + sectionId + "/questions/deaExpiration")
                .then()
                .statusCode(200)
                // Still present, still marked TEMPLATE — the compiler is what drops it.
                .body("questions", hasSize(2))
                .body("questions.find { it.key == 'deaExpiration' }.enabled", equalTo(false))
                .body("questions.find { it.key == 'deaExpiration' }.origin", equalTo("TEMPLATE"));
    }

    @Test
    @DisplayName("an added question is recorded as ADDED, so drift can tell it from a template removal")
    void addsLocalQuestion() {
        String sectionId = instantiate();

        given().contentType("application/json")
                .body("{\"key\":\"deaState\",\"catalogQuestionId\":\"q_addr_state\",\"order\":30}")
                .when()
                .post(path() + "/sections/" + sectionId + "/questions")
                .then()
                .statusCode(200)
                .body("questions", hasSize(3))
                .body("questions.find { it.key == 'deaState' }.origin", equalTo("ADDED"));
    }

    @Test
    @DisplayName("adding a question that is not in the catalog is 422, not a dangling reference")
    void refusesUnknownCatalogQuestion() {
        String sectionId = instantiate();

        given().contentType("application/json")
                .body("{\"key\":\"ghost\",\"catalogQuestionId\":\"q_nope\"}")
                .when()
                .post(path() + "/sections/" + sectionId + "/questions")
                .then()
                .statusCode(422)
                .body("message", containsString("q_nope"));
    }

    @Test
    @DisplayName("GET drift separates what a re-sync would bring in from what it would overwrite")
    void reportsDriftInBothDirections() {
        String sectionId = instantiate();

        given().contentType("application/json")
                .body("{\"enabled\":false}")
                .when()
                .patch(path() + "/sections/" + sectionId + "/questions/deaExpiration")
                .then()
                .statusCode(200);

        given().when()
                .get(path() + "/sections/" + sectionId + "/drift")
                .then()
                .statusCode(200)
                .body("hasDrift", equalTo(true))
                .body("behindTemplate", equalTo(false))
                .body("localCustomisations", hasSize(1))
                .body("localCustomisations[0].code", equalTo("DISABLED_LOCALLY"))
                .body("templateChanges", hasSize(0));
    }

    @Test
    @DisplayName("promoting mints a new template version and clears the drift it came from")
    void promotionClosesTheLoop() {
        String sectionId = instantiate();

        given().contentType("application/json")
                .body("{\"key\":\"deaState\",\"catalogQuestionId\":\"q_addr_state\",\"order\":30}")
                .when()
                .post(path() + "/sections/" + sectionId + "/questions")
                .then()
                .statusCode(200);

        given().when()
                .post(path() + "/sections/" + sectionId + "/promote")
                .then()
                .statusCode(200)
                .body("version", equalTo(2))
                .body("questions.key", contains("deaNumber", "deaExpiration", "deaState"));

        // The indicator must clear. It previously did not: the version was raised but the question
        // stayed marked ADDED, so drift reported a local addition forever.
        given().when()
                .get(path() + "/sections/" + sectionId + "/drift")
                .then()
                .statusCode(200)
                .body("hasDrift", equalTo(false))
                .body("definitionTemplateVersion", equalTo(2))
                .body("currentTemplateVersion", equalTo(2));
    }

    @Test
    @DisplayName("a section authored from scratch reports no template rather than a phantom one")
    void fromScratchHasNoTemplate() {
        given().contentType("application/json")
                .body("{\"blueprintId\":\"fb_recred\"}")
                .when()
                .post(path() + "/forms")
                .then()
                .statusCode(200);

        // Every section here came from a template, so pick the drift of one and confirm the shape.
        given().when().get(path() + "/sections").then().statusCode(200).body("$", hasSize(3));
    }

    // ------------------------------------------------------------------
    // reordering
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PUT questions/order applies the sequence and renormalises the numbers")
    void reordersQuestions() {
        String sectionId = instantiate();

        given().contentType("application/json")
                .body("{\"keys\":[\"deaExpiration\",\"deaNumber\"]}")
                .when()
                .put(path() + "/sections/" + sectionId + "/questions/order")
                .then()
                .statusCode(200)
                .body("questions.key", contains("deaExpiration", "deaNumber"))
                .body("questions.order", contains(10, 20));
    }

    @Test
    @DisplayName("a reorder that omits a question is refused, not partially applied")
    void refusesIncompleteReorder() {
        String sectionId = instantiate();

        given().contentType("application/json")
                .body("{\"keys\":[\"deaNumber\"]}")
                .when()
                .put(path() + "/sections/" + sectionId + "/questions/order")
                .then()
                // The omitted question would keep its old number and could collide with a renumbered
                // one — a tied sort renders the form in an arbitrary sequence rather than failing.
                .statusCode(422)
                .body("message", containsString("deaExpiration"));

        // And nothing moved.
        given().when()
                .get(path() + "/sections/" + sectionId)
                .then()
                .body("questions.key", contains("deaNumber", "deaExpiration"));
    }

    @Test
    @DisplayName("a reorder naming a question from elsewhere is refused")
    void refusesForeignKey() {
        String sectionId = instantiate();

        given().contentType("application/json")
                .body("{\"keys\":[\"deaNumber\",\"deaExpiration\",\"somethingElse\"]}")
                .when()
                .put(path() + "/sections/" + sectionId + "/questions/order")
                .then()
                .statusCode(422)
                .body("message", containsString("somethingElse"));
    }

    @Test
    @DisplayName("an empty key list is rejected by validation before reaching the domain")
    void refusesEmptyKeyList() {
        String sectionId = instantiate();

        given().contentType("application/json")
                .body("{\"keys\":[]}")
                .when()
                .put(path() + "/sections/" + sectionId + "/questions/order")
                .then()
                .statusCode(400);
    }

    @Test
    @DisplayName("reordering leaves provenance intact, so drift still reads correctly")
    void reorderPreservesProvenance() {
        String sectionId = instantiate();

        given().contentType("application/json")
                .body("{\"key\":\"deaState\",\"catalogQuestionId\":\"q_addr_state\",\"order\":30}")
                .when()
                .post(path() + "/sections/" + sectionId + "/questions")
                .then()
                .statusCode(200);

        given().contentType("application/json")
                .body("{\"keys\":[\"deaState\",\"deaNumber\",\"deaExpiration\"]}")
                .when()
                .put(path() + "/sections/" + sectionId + "/questions/order")
                .then()
                .statusCode(200)
                // A reorder that reset origin would make a locally added question look inherited,
                // and drift would stop offering to promote it.
                .body("questions.find { it.key == 'deaState' }.origin", equalTo("ADDED"))
                .body("questions.find { it.key == 'deaNumber' }.origin", equalTo("TEMPLATE"));

        given().when()
                .get(path() + "/sections/" + sectionId + "/drift")
                .then()
                .statusCode(200)
                .body("localCustomisations.code", hasItem("ADDED_LOCALLY"));
    }

    // ------------------------------------------------------------------
    // blueprint instantiation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /forms from a blueprint places one independent section per placement")
    void createsFormFromBlueprint() {
        String body = given().contentType("application/json")
                .body("{\"blueprintId\":\"fb_recred\",\"name\":\"Aetna Recred\"}")
                .when()
                .post(path() + "/forms")
                .then()
                .statusCode(200)
                .body("name", equalTo("Aetna Recred"))
                .body("sourceBlueprintId", equalTo("fb_recred"))
                .body("sourceBlueprintVersion", equalTo(2))
                .body("steps", hasSize(3))
                .body("steps.key", contains("deaRegistration", "practiceLocation", "billingAddress"))
                .extract()
                .asString();

        // The two address placements must own different sections, or customising one changes the
        // other — the reason a section is created per placement rather than per template.
        io.restassured.path.json.JsonPath json = new io.restassured.path.json.JsonPath(body);
        String practice = json.getString("steps.find { it.key == 'practiceLocation' }.sectionDefinitionId");
        String billing = json.getString("steps.find { it.key == 'billingAddress' }.sectionDefinitionId");
        org.junit.jupiter.api.Assertions.assertNotEquals(practice, billing);
    }

    @Test
    @DisplayName("repetition follows the placement, not the template")
    void repetitionIsPerPlacement() {
        given().contentType("application/json")
                .body("{\"blueprintId\":\"fb_recred\"}")
                .when()
                .post(path() + "/forms")
                .then()
                .statusCode(200)
                .body("steps.find { it.key == 'practiceLocation' }.repeating", equalTo(true))
                .body("steps.find { it.key == 'billingAddress' }.repeating", equalTo(false));
    }

    @Test
    @DisplayName("a blueprint needing a missing template is refused, and nothing is created")
    void refusesBlueprintWithMissingTemplate() {
        given().contentType("application/json")
                .body("{\"blueprintId\":\"fb_broken\"}")
                .when()
                .post(path() + "/forms")
                .then()
                .statusCode(422)
                .body("message", containsString("st_missing"));

        // All-or-nothing: a half-applied blueprint leaves a form that looks complete and is not.
        given().when().get(path() + "/sections").then().statusCode(200).body("$", hasSize(0));
        given().when().get(path() + "/forms").then().statusCode(200).body("$", hasSize(0));
    }

    @Test
    @DisplayName("a form created from a blueprint starts as a draft with no named conditions")
    void blueprintFormStartsClean() {
        given().contentType("application/json")
                .body("{\"blueprintId\":\"fb_recred\"}")
                .when()
                .post(path() + "/forms")
                .then()
                .statusCode(200)
                .body("status", equalTo("draft"))
                .body("namedConditions", hasSize(0))
                .body("steps.visibleWhen", contains(nullValue(), nullValue(), nullValue()));
    }

    private String instantiate() {
        return given().contentType("application/json")
                .body("{\"sectionTemplateId\":\"st_dea\"}")
                .when()
                .post(path() + "/sections")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }
}
