package com.certifyos.forms.form_authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.form_authoring.application.FormAuthoringService;
import com.certifyos.forms.form_authoring.application.command.UpdateStepCondition;
import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.shared_kernel.exception.ConflictingState;
import com.certifyos.forms.shared_kernel.exception.InvariantViolated;
import com.certifyos.forms.shared_kernel.exception.NotFound;
import com.certifyos.forms.shared_kernel.expression.Expression;
import com.certifyos.forms.shared_kernel.expression.Operator;
import com.certifyos.forms.support.InMemoryRepositories;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Editing a step's condition.
 *
 * <p>This is the endpoint that turns the POC's claim into something a credentialing analyst can
 * actually do: change "hide the DEA section for these specialties" without an engineer, a ticket and
 * a release. So the tests cover the two directions that matter — the rule persists, and the rule can
 * be removed — plus the one thing that must never happen, which is editing a definition someone has
 * already published and answered against.
 */
class FormAuthoringServiceTest {

    private static final Expression IS_CHIRO = new Expression.Leaf("applicant.providerType", Operator.EQ, "DC");

    private InMemoryRepositories.Forms forms;
    private InMemoryRepositories.Templates templates;
    private InMemoryRepositories.Blueprints blueprints;
    private InMemoryRepositories.Sections sections;
    private FormAuthoringService service;

    @BeforeEach
    void setUp() {
        forms = new InMemoryRepositories.Forms();
        templates = new InMemoryRepositories.Templates();
        blueprints = new InMemoryRepositories.Blueprints();
        sections = new InMemoryRepositories.Sections();
        service = new FormAuthoringService(forms, blueprints, templates, sections);
    }

    private FormDefinition draftWithTwoSteps() {
        return forms.save(new FormDefinition(
                "fd_1",
                "t_1",
                null,
                "Recred",
                "PRACTITIONER",
                null,
                null,
                Map.of(),
                List.of(Step.of("applicant", "sd_applicant", 1), Step.of("billingAddress", "sd_address", 2)),
                List.of(),
                FormDefinition.DefinitionStatus.DRAFT));
    }

    @Test
    @DisplayName("sets a condition on a step that had none")
    void setsCondition() {
        draftWithTwoSteps();

        FormDefinition updated = service.handle(new UpdateStepCondition("fd_1", "billingAddress", IS_CHIRO));

        Expression stored = updated.step("billingAddress").orElseThrow().visibleWhen();
        assertEquals(IS_CHIRO, stored);
    }

    @Test
    @DisplayName("persists through the repository rather than only returning the new state")
    void persists() {
        draftWithTwoSteps();

        service.handle(new UpdateStepCondition("fd_1", "billingAddress", IS_CHIRO));

        // Read back through the port: an in-place mutation of a returned record would pass the
        // previous test and lose the edit on the next request.
        FormDefinition reloaded = forms.require("fd_1");
        assertEquals(IS_CHIRO, reloaded.step("billingAddress").orElseThrow().visibleWhen());
    }

    @Test
    @DisplayName("clears the condition when given null, making the step unconditional")
    void clearsCondition() {
        draftWithTwoSteps();
        service.handle(new UpdateStepCondition("fd_1", "billingAddress", IS_CHIRO));

        FormDefinition updated = service.handle(new UpdateStepCondition("fd_1", "billingAddress", null));

        // Null, not an empty `all`. Both are always-true, but an empty `all` would be published as a
        // condition and then appear in every later diff as a change nobody made.
        assertNull(updated.step("billingAddress").orElseThrow().visibleWhen());
    }

    @Test
    @DisplayName("leaves every other step untouched")
    void touchesOnlyTheNamedStep() {
        draftWithTwoSteps();

        FormDefinition updated = service.handle(new UpdateStepCondition("fd_1", "billingAddress", IS_CHIRO));

        assertNull(updated.step("applicant").orElseThrow().visibleWhen());
        assertEquals(2, updated.steps().size());
    }

    @Test
    @DisplayName("preserves the step's other attributes — order, section and key are not authored here")
    void preservesStepIdentity() {
        draftWithTwoSteps();

        FormDefinition updated = service.handle(new UpdateStepCondition("fd_1", "billingAddress", IS_CHIRO));
        Step step = updated.step("billingAddress").orElseThrow();

        assertEquals(2, step.order());
        assertEquals("sd_address", step.sectionDefinitionId());
        assertTrue(step.enabled());
    }

    @Test
    @DisplayName("refuses to edit a published definition, which providers have already answered")
    void refusesPublished() {
        forms.save(new FormDefinition(
                "fd_pub",
                "t_1",
                null,
                "Recred",
                "PRACTITIONER",
                null,
                null,
                Map.of(),
                List.of(Step.of("applicant", "sd_applicant", 1)),
                List.of(),
                FormDefinition.DefinitionStatus.PUBLISHED));

        ConflictingState thrown = assertThrows(
                ConflictingState.class, () -> service.handle(new UpdateStepCondition("fd_pub", "applicant", IS_CHIRO)));

        assertTrue(thrown.getMessage().contains("published"));
    }

    @Test
    @DisplayName("reports a missing form as not found rather than as a server error")
    void missingForm() {
        assertThrows(NotFound.class, () -> service.handle(new UpdateStepCondition("nope", "applicant", IS_CHIRO)));
    }

    @Test
    @DisplayName("reports a missing step as not found, naming the step")
    void missingStep() {
        draftWithTwoSteps();

        NotFound thrown = assertThrows(
                NotFound.class, () -> service.handle(new UpdateStepCondition("fd_1", "notAStep", IS_CHIRO)));

        assertTrue(thrown.getMessage().contains("notAStep"));
    }

    @Test
    @DisplayName("accepts a condition the analyzer would reject, because authoring passes through invalid states")
    void permissiveOnPurpose() {
        draftWithTwoSteps();

        // A step conditioned on its own answers is illegal and the compiler says so. Rejecting it
        // here would make reordering impossible: for one save, a condition legitimately points the
        // wrong way. /validate is where the authoritative answer comes from.
        Expression selfReferencing = new Expression.Leaf("billingAddress.line1", Operator.EXISTS, null);

        FormDefinition updated = service.handle(new UpdateStepCondition("fd_1", "billingAddress", selfReferencing));

        assertNotNull(updated.step("billingAddress").orElseThrow().visibleWhen());
    }

    @Test
    @DisplayName("rejects a blank step key at the command boundary, before any load")
    void rejectsBlankStepKey() {
        // InvariantViolated, not IllegalArgumentException: a command is built from a request body, so
        // its guards are validating caller input and must reach the client as a 422. Only
        // InvariantViolated has a mapper — an IllegalArgumentException here would surface as a 500
        // for a request the caller could have fixed.
        assertThrows(InvariantViolated.class, () -> new UpdateStepCondition("fd_1", "  ", IS_CHIRO));
    }
}
