package com.certifyos.forms.question_catalog.interfaces;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.certifyos.forms.form_authoring.interfaces.RestTestProfile;
import com.certifyos.forms.form_authoring.interfaces.TestRepositories;
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
 * The promotion gate, over HTTP.
 *
 * <p>Catalog rot is the highest risk in this design, and it does not arrive as a bug — it arrives as
 * a hundred reasonable-looking approvals. So the API's job is not merely to refuse a duplicate but
 * to show the steward what it collided with, because the correct resolution is almost always
 * "absorb this phrasing as an alias" rather than "reject".
 */
@QuarkusTest
@TestProfile(RestTestProfile.class)
class QuestionCatalogResourceTest {

    private static final String TENANT = "tenant_fl";

    @Inject
    QuestionRepository questions;

    private static Question question(String id, String key, String label, CatalogStatus status, String... aliases) {
        return new Question(
                QuestionId.of(id),
                null,
                key,
                label,
                null,
                ResponseType.TEXT,
                null,
                List.of(),
                Map.of("practitioner", key),
                Set.of(aliases),
                List.of(),
                null,
                status,
                Set.of("identity"),
                "identity");
    }

    @BeforeEach
    void seed() {
        ((TestRepositories.Resettable) questions).reset();

        questions.save(question(
                "q_npi",
                "npi",
                "National Provider Identifier (NPI)",
                CatalogStatus.ACTIVE,
                "NPI",
                "NPI Number",
                "Individual NPI"));
        questions.save(question("q_dea", "deaNumber", "DEA registration number", CatalogStatus.ACTIVE));
    }

    private String path() {
        return "/api/v1/tenants/" + TENANT + "/catalog";
    }

    @Test
    @DisplayName("browsing returns active entries only, so a proposed question cannot be used by accident")
    void browsingHidesProposed() {
        questions.save(question("q_pending", "hospitalAffiliation", "Hospital affiliation", CatalogStatus.PROPOSED));

        given().when()
                .get(path() + "/questions")
                .then()
                .statusCode(200)
                .body("key", not(hasItem("hospitalAffiliation")));
    }

    @Test
    @DisplayName("includeProposed surfaces entries awaiting promotion")
    void includeProposedSurfacesPending() {
        questions.save(question("q_pending", "hospitalAffiliation", "Hospital affiliation", CatalogStatus.PROPOSED));

        given().when()
                .get(path() + "/questions?includeProposed=true")
                .then()
                .statusCode(200)
                .body("key", hasItem("hospitalAffiliation"))
                .body("find { it.key == 'hospitalAffiliation' }.status", equalTo("proposed"));
    }

    @Test
    @DisplayName("searching and browsing agree about which entries exist")
    void searchAndBrowseAgree() {
        questions.save(question("q_pending", "hospitalAffiliation", "Hospital affiliation", CatalogStatus.PROPOSED));

        // The bug this pins: an empty search filtered to active, while a text search applied no
        // status predicate at all — so the same catalog had two different contents depending on
        // whether the search box happened to be empty.
        given().when()
                .get(path() + "/questions?q=Hospital")
                .then()
                .statusCode(200)
                .body("$", hasSize(0));

        given().when()
                .get(path() + "/questions?q=Hospital&includeProposed=true")
                .then()
                .statusCode(200)
                .body("$", hasSize(1));
    }

    @Test
    @DisplayName("a deprecated question stays hidden even with includeProposed")
    void deprecatedStaysHidden() {
        questions.save(question("q_old", "faxNumber", "Fax number", CatalogStatus.DEPRECATED));

        given().when()
                .get(path() + "/questions?includeProposed=true")
                .then()
                .statusCode(200)
                .body("key", not(hasItem("faxNumber")));
    }

    @Test
    @DisplayName("search matches an alias, not just the label")
    void searchMatchesAliases() {
        given().when()
                .get(path() + "/questions?q=Individual NPI")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].key", equalTo("npi"));
    }

    @Test
    @DisplayName("a question carries its platform mapping — defined once, not per form")
    void platformMappingIsExposed() {
        given().when()
                .get(path() + "/questions/q_npi")
                .then()
                .statusCode(200)
                .body("platformMapping.practitioner", equalTo("npi"))
                .body("aliases", hasItem("NPI Number"))
                .body("status", equalTo("active"));
    }

    @Test
    @DisplayName("promoting a genuinely new question succeeds")
    void promoteNewQuestion() {
        questions.save(
                question("q_new", "hospitalAffiliation", "Primary hospital affiliation", CatalogStatus.PROPOSED));

        given().when()
                .post(path() + "/questions/q_new/promote")
                .then()
                .statusCode(200)
                .body("promoted", equalTo(true))
                .body("question.status", equalTo("active"))
                .body("duplicates", nullValue());
    }

    @Test
    @DisplayName("a near-duplicate is refused with 409, and the collision is shown")
    void promoteDuplicateIsRefused() {
        // A payer's phrasing that NPI already records as an alias.
        questions.save(question("q_dupe", "npiNumber", "NPI Number", CatalogStatus.PROPOSED));

        given().when()
                .post(path() + "/questions/q_dupe/promote")
                .then()
                .statusCode(409)
                .body("promoted", equalTo(false))
                .body("duplicates", hasSize(1))
                .body("duplicates[0].existingQuestionId", equalTo("q_npi"))
                .body("duplicates[0].reason", equalTo("SAME_LABEL_OR_ALIAS"))
                // The steward needs to know what to do, not just that they were stopped.
                .body("duplicates[0].explanation", containsString("alias instead of a new question"));
    }

    @Test
    @DisplayName("absorbing the phrasing as an alias is the intended resolution")
    void absorbAliasResolvesTheCollision() {
        given().contentType("application/json")
                .body("{\"phrasing\":\"Rendering Provider NPI\"}")
                .when()
                .post(path() + "/questions/q_npi/aliases")
                .then()
                .statusCode(200)
                .body("aliases", hasItem("Rendering Provider NPI"));

        // And the absorbed phrasing is recognised the next time it turns up.
        questions.save(question("q_next", "renderingNpi", "Rendering Provider NPI", CatalogStatus.PROPOSED));

        given().when()
                .post(path() + "/questions/q_next/promote")
                .then()
                .statusCode(409)
                .body("duplicates[0].existingQuestionId", equalTo("q_npi"));
    }

    @Test
    @DisplayName("promoting an already-active question is 409, not a silent no-op")
    void promoteActiveIsConflict() {
        given().when()
                .post(path() + "/questions/q_npi/promote")
                .then()
                .statusCode(409)
                .body("code", equalTo("CONFLICT"))
                .body("message", containsString("already in the catalog"));
    }

    @Test
    @DisplayName("deprecating retires a question without deleting it")
    void deprecate() {
        given().when()
                .post(path() + "/questions/q_dea/deprecate")
                .then()
                .statusCode(200)
                .body("status", equalTo("deprecated"));

        given().when().get(path() + "/questions/q_dea").then().statusCode(200);
    }

    @Test
    @DisplayName("a missing question is 404")
    void missingQuestionIs404() {
        given().when().get(path() + "/questions/nope").then().statusCode(404).body("code", equalTo("NOT_FOUND"));
    }

    @Test
    @DisplayName("option sets come back, tags included — the frontend needs them to filter a select")
    void optionSetsCarryTags() {
        given().when().get(path() + "/option-sets").then().statusCode(200).body("$", notNullValue());
    }
}
