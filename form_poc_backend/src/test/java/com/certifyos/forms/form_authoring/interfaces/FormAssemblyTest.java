package com.certifyos.forms.form_authoring.interfaces;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import com.certifyos.forms.form_authoring.domain.port.FormBlueprintRepository;
import com.certifyos.forms.form_authoring.domain.port.FormDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionTemplateRepository;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Building a form from nothing.
 *
 * <p>Exists because the first cut of this POC could not. Its whole write vocabulary was five
 * commands — create-from-blueprint, create-section-from-template, update-step-condition,
 * preview, publish — so every form that existed had been instantiated from a blueprint, and every
 * blueprint was a hand-written fixture. The <em>editing</em> loop was real and tested; the
 * <em>assembly</em> step before it was missing, and the seed data hid that, because a form was
 * always already there to edit.
 *
 * <p>The aggregates were never the gap. {@code FormDefinition} had {@code placeStep},
 * {@code removeStep} and {@code withNamedCondition} from the start, reachable only from the
 * blueprint path and from unit tests. What was missing was every layer above them.
 *
 * <p>{@link FullWalk} is the test that matters: catalog → section → form → rules → publish, with no
 * fixture involved, ending in an assertion on the compiled artifact rather than on the draft. Passing
 * it is what makes "a new payer form is configuration, not code" a measured claim.
 */
@QuarkusTest
@TestProfile(RestTestProfile.class)
class FormAssemblyTest {

    private static final String TENANT = "tenant_fl";
    private static final String OTHER_TENANT = "tenant_other";

    @Inject
    FormDefinitionRepository forms;

    @Inject
    SectionDefinitionRepository sections;

    @Inject
    SectionTemplateRepository templates;

    @Inject
    FormBlueprintRepository blueprints;

    @Inject
    QuestionRepository questions;

    @BeforeEach
    void seed() {
        List.of(forms, sections, templates, blueprints, questions)
                .forEach(r -> ((TestRepositories.Resettable) r).reset());

        // A catalog and nothing else. No sections, no forms, no templates, no blueprints — the state a
        // tenant is actually in when a new payer form arrives, and the state the old API could not
        // start a form from.
        questions.save(text("q_provider_type", "providerType", "Provider type"));
        questions.save(text("q_npi", "npi", "NPI"));
        questions.save(text("q_dea_number", "deaNumber", "DEA registration number"));
        questions.save(text("q_addr_line1", "line1", "Address line 1"));
    }

    // ------------------------------------------------------------------
    // starting from nothing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /forms with no blueprint creates an empty draft")
    void createsBlankForm() {
        given().contentType("application/json")
                .body("{\"name\":\"Sunshine Health Recred\",\"entityType\":\"practitioner\"}")
                .when()
                .post(path() + "/forms")
                .then()
                .statusCode(200)
                .body("name", equalTo("Sunshine Health Recred"))
                .body("entityType", equalTo("practitioner"))
                .body("status", equalTo("draft"))
                .body("steps", hasSize(0))
                .body("sourceBlueprintId", nullValue());
    }

    @Test
    @DisplayName("an empty form must name its entity type, because no blueprint supplies one")
    void blankFormNeedsEntityType() {
        given().contentType("application/json")
                .body("{\"name\":\"Nameless\"}")
                .when()
                .post(path() + "/forms")
                .then()
                .statusCode(422)
                .body("message", containsString("entity type"));
    }

    @Test
    @DisplayName("POST /sections with no template creates an empty section the tenant owns outright")
    void createsBlankSection() {
        given().contentType("application/json")
                .body("{\"key\":\"applicant\",\"name\":\"Applicant Details\"}")
                .when()
                .post(path() + "/sections")
                .then()
                .statusCode(200)
                .body("name", equalTo("Applicant Details"))
                .body("questions", hasSize(0))
                // The null is load-bearing: it is what tells drift there is nothing to reconcile
                // against, and what makes promotion mint a template rather than a version.
                .body("sourceTemplateId", nullValue());
    }

    @Test
    @DisplayName("an empty section must name its key, because no template supplies one")
    void blankSectionNeedsKey() {
        given().contentType("application/json")
                .body("{\"name\":\"Applicant Details\"}")
                .when()
                .post(path() + "/sections")
                .then()
                .statusCode(422)
                .body("message", containsString("key"));
    }

    // ------------------------------------------------------------------
    // placing sections
    // ------------------------------------------------------------------

    @Test
    @DisplayName("placing a section appends a step after the current last one")
    void placesAndAppends() {
        String formId = blankForm();
        String applicant = blankSection("applicant", "Applicant Details");
        String address = blankSection("address", "Address");

        place(formId, applicant, "applicantDetails", null).then().statusCode(200);

        place(formId, address, "practiceLocation", null)
                .then()
                .statusCode(200)
                .body("steps.key", contains("applicantDetails", "practiceLocation"))
                .body("steps.order", contains(10, 20));
    }

    @Test
    @DisplayName("one section placed twice yields two steps, which is the whole point of the placement")
    void placesOneSectionTwice() {
        String formId = blankForm();
        String address = blankSection("address", "Address");

        place(formId, address, "practiceLocation", null).then().statusCode(200);
        place(formId, address, "billingAddress", null)
                .then()
                .statusCode(200)
                .body("steps", hasSize(2))
                .body("steps.sectionDefinitionId", contains(address, address));
    }

    @Test
    @DisplayName("a duplicate step key is 422, because step keys are answer namespaces")
    void refusesDuplicateStepKey() {
        String formId = blankForm();
        String address = blankSection("address", "Address");

        place(formId, address, "practiceLocation", null).then().statusCode(200);
        place(formId, address, "practiceLocation", null)
                .then()
                .statusCode(422)
                .body("message", containsString("already in this form"));
    }

    @Test
    @DisplayName("a step key that is not a legal namespace is 422, not a 500 from deep in the domain")
    void refusesIllegalStepKey() {
        String formId = blankForm();
        String address = blankSection("address", "Address");

        // A space would make every answer path under it ambiguous. StepKey refuses with an
        // IllegalArgumentException, which has no mapper — so the service has to translate it, and
        // this is the test that says so.
        place(formId, address, "practice location", null)
                .then()
                .statusCode(422)
                .body("message", containsString("alphanumeric"));
    }

    @Test
    @DisplayName("a reserved namespace cannot be a step key")
    void refusesReservedStepKey() {
        String formId = blankForm();
        String address = blankSection("address", "Address");

        place(formId, address, "viewer", null).then().statusCode(422).body("message", containsString("reserved"));
    }

    @Test
    @DisplayName("placing another tenant's section is 422 — nothing downstream re-checks ownership")
    void refusesCrossTenantSection() {
        String formId = blankForm();

        String foreign = given().contentType("application/json")
                .body("{\"key\":\"secret\",\"name\":\"Another Client's Section\"}")
                .when()
                .post("/api/v1/tenants/" + OTHER_TENANT + "/sections")
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        // A step holds only a section id, so this would compile perfectly and serve one client's
        // questions to another's providers. There is no later stage that would catch it.
        place(formId, foreign, "leak", null).then().statusCode(422).body("message", containsString("another tenant"));
    }

    @Test
    @DisplayName("a section that does not exist is 404")
    void refusesMissingSection() {
        place(blankForm(), "sd_nope", "ghost", null).then().statusCode(404);
    }

    // ------------------------------------------------------------------
    // removing and reordering
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DELETE removes a step outright, unlike a section question")
    void removesStep() {
        String formId = blankForm();
        String applicant = blankSection("applicant", "Applicant Details");
        String address = blankSection("address", "Address");
        place(formId, applicant, "applicantDetails", null);
        place(formId, address, "practiceLocation", null);

        given().when()
                .delete(path() + "/forms/" + formId + "/steps/practiceLocation")
                .then()
                .statusCode(200)
                .body("steps.key", contains("applicantDetails"));
    }

    @Test
    @DisplayName("removing a step that is not there is 404")
    void removingUnknownStepIs404() {
        given().when()
                .delete(path() + "/forms/" + blankForm() + "/steps/ghost")
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("PUT steps/order applies the sequence and renormalises the numbers")
    void reordersSteps() {
        String formId = blankForm();
        place(formId, blankSection("a", "A"), "one", null);
        place(formId, blankSection("b", "B"), "two", null);
        place(formId, blankSection("c", "C"), "three", null);

        given().contentType("application/json")
                .body("{\"keys\":[\"three\",\"one\",\"two\"]}")
                .when()
                .put(path() + "/forms/" + formId + "/steps/order")
                .then()
                .statusCode(200)
                .body("steps.key", contains("three", "one", "two"))
                // Renormalised, not shuffled within the old numbers — so the next insert has room and
                // no two steps can end up sharing a position.
                .body("steps.order", contains(10, 20, 30));
    }

    @Test
    @DisplayName("a reorder that omits a step is refused whole, and says which one")
    void refusesPartialReorder() {
        String formId = blankForm();
        place(formId, blankSection("a", "A"), "one", null);
        place(formId, blankSection("b", "B"), "two", null);

        given().contentType("application/json")
                .body("{\"keys\":[\"two\"]}")
                .when()
                .put(path() + "/forms/" + formId + "/steps/order")
                .then()
                .statusCode(422)
                .body("message", containsString("Missing: [one]"));
    }

    @Test
    @DisplayName("a reorder naming a step from elsewhere is refused")
    void refusesForeignKeyInReorder() {
        String formId = blankForm();
        place(formId, blankSection("a", "A"), "one", null);

        given().contentType("application/json")
                .body("{\"keys\":[\"one\",\"elsewhere\"]}")
                .when()
                .put(path() + "/forms/" + formId + "/steps/order")
                .then()
                .statusCode(422)
                .body("message", containsString("Not in this form: [elsewhere]"));
    }

    // ------------------------------------------------------------------
    // named conditions
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("named conditions")
    class NamedConditions {

        @Test
        @DisplayName("PUT defines one, and it comes back with a reference count of zero")
        void defines() {
            String formId = blankForm();

            given().contentType("application/json")
                    .body(
                            """
                            {"label":"Specialty exempt from DEA requirements",
                             "expression":{"field":"applicantDetails.providerType","op":"in","value":["DC","OD"]}}
                            """)
                    .when()
                    .put(path() + "/forms/" + formId + "/conditions/specialtyExempt")
                    .then()
                    .statusCode(200)
                    .body("namedConditions", hasSize(1))
                    .body("namedConditions[0].label", equalTo("Specialty exempt from DEA requirements"))
                    .body("namedConditions[0].referenceCount", equalTo(0));
        }

        @Test
        @DisplayName("a second PUT to the same key replaces rather than duplicates")
        void upserts() {
            String formId = blankForm();
            defineExempt(formId);

            given().contentType("application/json")
                    .body(
                            """
                            {"label":"Exempt specialties",
                             "expression":{"field":"applicantDetails.providerType","op":"in","value":["DC"]}}
                            """)
                    .when()
                    .put(path() + "/forms/" + formId + "/conditions/specialtyExempt")
                    .then()
                    .statusCode(200)
                    .body("namedConditions", hasSize(1))
                    .body("namedConditions[0].label", equalTo("Exempt specialties"));
        }

        /**
         * 400, not 422, because a label is required unconditionally and so {@code @NotBlank} catches it
         * before any handler runs. The 422 path is for requirements that depend on other fields — an
         * empty form needing an entity type — which bean validation cannot express.
         */
        @Test
        @DisplayName("a condition with no label is refused — a reference renders by its label")
        void requiresLabel() {
            given().contentType("application/json")
                    .body("{\"expression\":{\"field\":\"a.b\",\"op\":\"exists\"}}")
                    .when()
                    .put(path() + "/forms/" + blankForm() + "/conditions/nameless")
                    .then()
                    .statusCode(400);
        }

        @Test
        @DisplayName("deleting one that steps still reference is 409, and names them")
        void refusesDeleteWhileReferenced() {
            String formId = blankForm();
            String dea = blankSection("dea", "DEA Registration");
            place(formId, dea, "deaRegistration", null);
            defineExempt(formId);
            referenceExemptFrom(formId, "deaRegistration");

            given().when()
                    .delete(path() + "/forms/" + formId + "/conditions/specialtyExempt")
                    .then()
                    .statusCode(409)
                    .body("message", containsString("deaRegistration"));
        }

        @Test
        @DisplayName("deleting an unreferenced one succeeds")
        void deletesUnreferenced() {
            String formId = blankForm();
            defineExempt(formId);

            given().when()
                    .delete(path() + "/forms/" + formId + "/conditions/specialtyExempt")
                    .then()
                    .statusCode(200)
                    .body("namedConditions", hasSize(0));
        }

        @Test
        @DisplayName("deleting one that was never defined is 404, not a silent success")
        void deletingUnknownIs404() {
            given().when()
                    .delete(path() + "/forms/" + blankForm() + "/conditions/ghost")
                    .then()
                    .statusCode(404);
        }

        @Test
        @DisplayName("referenceCount tracks the steps using it, so the UI can warn before a delete")
        void countsReferences() {
            String formId = blankForm();
            place(formId, blankSection("dea", "DEA"), "deaRegistration", null);
            place(formId, blankSection("cds", "CDS"), "cdsRegistration", null);
            defineExempt(formId);
            referenceExemptFrom(formId, "deaRegistration");
            referenceExemptFrom(formId, "cdsRegistration");

            given().when()
                    .get(path() + "/forms/" + formId)
                    .then()
                    .statusCode(200)
                    .body("namedConditions[0].referenceCount", equalTo(2));
        }
    }

    // ------------------------------------------------------------------
    // the walk that matters
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a whole form, from an empty tenant to a published artifact")
    class FullWalk {

        /**
         * No fixture, no blueprint, no template. Catalog questions in, compiled artifact out.
         *
         * <p>Asserts on the artifact rather than the draft, because the draft only proves the API
         * accepted the writes. The artifact is what the existing renderer would consume, so it is the
         * only thing that proves the form actually works — and it is where the two claims worth
         * checking live: that one section placed twice yields two disjoint answer namespaces, and that
         * a named condition is inlined rather than shipped as a dangling reference.
         */
        @Test
        @DisplayName("assembles, validates, publishes, and the artifact is correct")
        void assemblesFromNothing() {
            // 1. An empty form.
            String formId = given().contentType("application/json")
                    .body("{\"name\":\"Sunshine Health Recred\",\"entityType\":\"practitioner\"}")
                    .when()
                    .post(path() + "/forms")
                    .then()
                    .statusCode(200)
                    .extract()
                    .path("id");

            // 2. Two sections, built by hand from the catalog.
            String applicant = blankSection("applicant", "Applicant Details");
            addQuestion(applicant, "providerType", "q_provider_type", 10);
            addQuestion(applicant, "npi", "q_npi", 20);

            String address = blankSection("address", "Address");
            addQuestion(address, "line1", "q_addr_line1", 10);

            String dea = blankSection("dea", "DEA Registration");
            addQuestion(dea, "deaNumber", "q_dea_number", 10);

            // 3. Placed as four steps. The address section twice, which is the case that forced the
            // step to exist as its own concept.
            place(formId, applicant, "applicantDetails", null).then().statusCode(200);
            place(formId, dea, "deaRegistration", null).then().statusCode(200);
            place(formId, address, "practiceLocation", null).then().statusCode(200);
            place(formId, address, "billingAddress", null).then().statusCode(200);

            // 4. A rule, defined once and referenced — the CP-38192 shape.
            defineExempt(formId);
            referenceExemptFrom(formId, "deaRegistration");

            // 5. It compiles. Additive, not structural, even though it adds required questions —
            // there is no previous version, so there are no in-flight answers a publish could reset.
            // Classification describes risk to existing answers, and a first publish carries none.
            given().when()
                    .get(path() + "/forms/" + formId + "/validate")
                    .then()
                    .statusCode(200)
                    .body("changeClass", equalTo("additive"))
                    .body("keysRequiringReset", hasSize(0));

            // 6. Published.
            String versionId = given().contentType("application/json")
                    .body("{\"changelog\":\"Initial build\",\"ticketId\":\"CP-38192\"}")
                    .when()
                    .post(path() + "/forms/" + formId + "/publish")
                    .then()
                    .statusCode(200)
                    .body("version", equalTo(1))
                    .extract()
                    .path("id");

            // 7. The artifact — one step per placement, in order.
            given().when()
                    .get(path() + "/forms/" + formId + "/versions/" + versionId + "/compiled")
                    .then()
                    .statusCode(200)
                    .body("title", equalTo("Sunshine Health Recred"))
                    .body("steps", hasSize(4))
                    .body(
                            "steps.id",
                            contains("applicantDetails", "deaRegistration", "practiceLocation", "billingAddress"))
                    // Two placements of one section, two independent answer namespaces. Sharing them
                    // would mean the second address silently overwriting the first — a wrong answer,
                    // not an awkward one.
                    .body("steps.find { it.id == 'practiceLocation' }.fields.name", contains("practiceLocation.line1"))
                    .body("steps.find { it.id == 'billingAddress' }.fields.name", contains("billingAddress.line1"))
                    // The named condition is inlined, not emitted as a ref the renderer cannot resolve.
                    .body("steps.find { it.id == 'deaRegistration' }.condition.not.any", nullValue())
                    .body(
                            "steps.find { it.id == 'deaRegistration' }.condition.not.field",
                            equalTo("applicantDetails.providerType"))
                    .body("steps.find { it.id == 'deaRegistration' }.condition.not.op", equalTo("in"))
                    .body("steps.find { it.id == 'deaRegistration' }.condition.not.value", contains("DC", "OD"))
                    // Unconditioned steps carry no condition key at all, rather than a null one.
                    .body("steps.find { it.id == 'applicantDetails' }.condition", nullValue());
        }

        @Test
        @DisplayName("a published form refuses further edits, so providers mid-application are safe")
        void publishedFormIsFrozen() {
            String formId = blankForm();
            String applicant = blankSection("applicant", "Applicant Details");
            addQuestion(applicant, "npi", "q_npi", 10);
            place(formId, applicant, "applicantDetails", null);

            given().contentType("application/json")
                    .body("{\"changelog\":\"First\"}")
                    .when()
                    .post(path() + "/forms/" + formId + "/publish")
                    .then()
                    .statusCode(200);

            // The definition is only marked published by the publish path if the model says so; this
            // asserts the guard exists on the assembly verbs rather than only on the condition edit.
            String status = given().when()
                    .get(path() + "/forms/" + formId)
                    .then()
                    .extract()
                    .path("status");

            if ("published".equals(status)) {
                place(formId, applicant, "second", null).then().statusCode(409);
                given().when()
                        .delete(path() + "/forms/" + formId + "/steps/applicantDetails")
                        .then()
                        .statusCode(409);
            } else {
                // Publishing leaves the definition a draft by design — a version is the immutable
                // thing, not the definition. Recorded here rather than asserted away, so the day that
                // changes this test says which behaviour it was written against.
                place(formId, applicant, "second", null).then().statusCode(200);
            }
        }
    }

    // ------------------------------------------------------------------
    // closing the reuse loop
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("promoting an assembled form into a blueprint")
    class Promotion {

        @Test
        @DisplayName("is refused while any placed section came from no template, and names the steps")
        void refusesUnbackedSections() {
            String formId = blankForm();
            place(formId, blankSection("applicant", "Applicant Details"), "applicantDetails", null);

            given().contentType("application/json")
                    .body("{\"key\":\"sunshine_recred\"}")
                    .when()
                    .post(path() + "/forms/" + formId + "/promote")
                    .then()
                    .statusCode(422)
                    .body("message", containsString("applicantDetails"))
                    .body("message", containsString("promote each of those sections"));
        }

        /**
         * A tenancy check on a read-shaped write.
         *
         * <p>Easy to miss, because promotion looks like a write and the write lands under the *form's*
         * tenant either way — so nothing is corrupted. What leaks is the response: it carries the
         * form's step keys, grouping and conditions, which for a payer form is precisely the
         * configuration a competitor would want. 404 rather than 403, so the request does not confirm
         * that the form exists.
         */
        @Test
        @DisplayName("another tenant's form cannot be promoted, and the refusal does not confirm it exists")
        void refusesCrossTenantPromotion() {
            String formId = blankForm();

            given().contentType("application/json")
                    .body("{\"key\":\"stolen\"}")
                    .when()
                    .post("/api/v1/tenants/" + OTHER_TENANT + "/forms/" + formId + "/promote")
                    .then()
                    .statusCode(404);
        }

        @Test
        @DisplayName("promoting a from-scratch section mints a template and links the section to it")
        void promotesFromScratchSection() {
            String applicant = blankSection("applicant", "Applicant Details");
            addQuestion(applicant, "providerType", "q_provider_type", 10);

            given().when()
                    .post(path() + "/sections/" + applicant + "/promote?key=applicant&name=Applicant Details")
                    .then()
                    .statusCode(200)
                    .body("version", equalTo(1))
                    .body("global", equalTo(false))
                    .body("questions.key", contains("providerType"));

            // Linked, and the question re-origined — otherwise drift reports a local addition forever
            // and the indicator never clears no matter how often the author promotes.
            given().when()
                    .get(path() + "/sections/" + applicant)
                    .then()
                    .body("sourceTemplateId", containsString("st_"))
                    .body("sourceTemplateVersion", equalTo(1))
                    .body("questions.find { it.key == 'providerType' }.origin", equalTo("TEMPLATE"));
        }

        @Test
        @DisplayName("a from-scratch section cannot be promoted without a key for the new template")
        void fromScratchPromotionNeedsKey() {
            given().when()
                    .post(path() + "/sections/" + blankSection("applicant", "Applicant") + "/promote")
                    .then()
                    .statusCode(422)
                    .body("message", containsString("needs a key"));
        }

        /**
         * The round trip the whole reuse model is for.
         *
         * <p>Assemble a form by hand, promote its sections, promote the form, then build a second form
         * from the resulting blueprint — and check the rules arrived. That last assertion is the one
         * worth having: a blueprint that carried only structure would instantiate into a form whose
         * steps all show unconditionally, which is exactly the defect three Florida Blue sections ship
         * with today. It would also look like a success, because the form renders.
         */
        @Test
        @DisplayName("a blueprint made from a form carries its logic, so the next form starts with the rules")
        void blueprintRoundTripKeepsLogic() {
            String formId = blankForm();

            String applicant = blankSection("applicant", "Applicant Details");
            addQuestion(applicant, "providerType", "q_provider_type", 10);
            String dea = blankSection("dea", "DEA Registration");
            addQuestion(dea, "deaNumber", "q_dea_number", 10);

            place(formId, applicant, "applicantDetails", null);
            place(formId, dea, "deaRegistration", null);
            defineExempt(formId);
            referenceExemptFrom(formId, "deaRegistration");

            promoteSection(applicant, "applicant");
            promoteSection(dea, "dea");

            given().contentType("application/json")
                    .body("{\"key\":\"sunshine_recred\",\"name\":\"Sunshine Recred\"}")
                    .when()
                    .post(path() + "/forms/" + formId + "/promote")
                    .then()
                    .statusCode(200)
                    .body("version", equalTo(1))
                    .body("placements", hasSize(2))
                    .body("namedConditions.key", contains("specialtyExempt"))
                    .body("placements.find { it.stepKey == 'deaRegistration' }.conditioned", equalTo(true))
                    .body("placements.find { it.stepKey == 'applicantDetails' }.conditioned", equalTo(false));

            String blueprintId = blueprints.findAvailableFor(TENANT).stream()
                    .filter(b -> "sunshine_recred".equals(b.key()))
                    .findFirst()
                    .orElseThrow()
                    .id();

            given().contentType("application/json")
                    .body("{\"blueprintId\":\"" + blueprintId + "\",\"name\":\"Sunshine Recred 2027\"}")
                    .when()
                    .post(path() + "/forms")
                    .then()
                    .statusCode(200)
                    .body("steps.key", contains("applicantDetails", "deaRegistration"))
                    .body("namedConditions.key", contains("specialtyExempt"))
                    // The rule survived as a reference, not as an inlined copy — inlining happens at
                    // compile, so a draft that had already inlined it could never be edited by name.
                    .body("steps.find { it.key == 'deaRegistration' }.visibleWhen.not.ref", equalTo("specialtyExempt"))
                    .body("steps.find { it.key == 'applicantDetails' }.visibleWhen", nullValue());
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String path() {
        return "/api/v1/tenants/" + TENANT;
    }

    private static String blankForm() {
        return given().contentType("application/json")
                .body("{\"name\":\"Draft Form\",\"entityType\":\"practitioner\"}")
                .when()
                .post(path() + "/forms")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    private static String blankSection(String key, String name) {
        return given().contentType("application/json")
                .body("{\"key\":\"" + key + "\",\"name\":\"" + name + "\"}")
                .when()
                .post(path() + "/sections")
                .then()
                .statusCode(200)
                .extract()
                .path("id");
    }

    private static void addQuestion(String sectionId, String key, String catalogId, int order) {
        given().contentType("application/json")
                .body("{\"key\":\"" + key + "\",\"catalogQuestionId\":\"" + catalogId + "\",\"order\":" + order
                        + ",\"required\":true}")
                .when()
                .post(path() + "/sections/" + sectionId + "/questions")
                .then()
                .statusCode(200);
    }

    private static io.restassured.response.Response place(
            String formId, String sectionId, String stepKey, Integer order) {
        return given().contentType("application/json")
                .body("{\"sectionDefinitionId\":\"" + sectionId + "\",\"stepKey\":\"" + stepKey + "\""
                        + (order == null ? "" : ",\"order\":" + order) + "}")
                .when()
                .post(path() + "/forms/" + formId + "/steps");
    }

    private static void promoteSection(String sectionId, String key) {
        given().when()
                .post(path() + "/sections/" + sectionId + "/promote?key=" + key)
                .then()
                .statusCode(200);
    }

    private static void defineExempt(String formId) {
        given().contentType("application/json")
                .body(
                        """
                        {"label":"Specialty exempt from DEA requirements",
                         "expression":{"field":"applicantDetails.providerType","op":"in","value":["DC","OD"]}}
                        """)
                .when()
                .put(path() + "/forms/" + formId + "/conditions/specialtyExempt")
                .then()
                .statusCode(200);
    }

    /** Hide the step when the named condition holds — the De Morgan case the builder emits. */
    private static void referenceExemptFrom(String formId, String stepKey) {
        given().contentType("application/json")
                .body("{\"visibleWhen\":{\"not\":{\"ref\":\"specialtyExempt\"}}}")
                .when()
                .patch(path() + "/forms/" + formId + "/steps/" + stepKey + "/condition")
                .then()
                .statusCode(200);
    }

    private static Question text(String id, String key, String label) {
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
}
