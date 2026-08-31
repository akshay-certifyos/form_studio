package com.certifyos.forms.form_authoring.application.command;

import com.certifyos.forms.shared_kernel.exception.InvariantViolated;

/**
 * Turns a form somebody assembled by hand into a reusable shape.
 *
 * <p>The form-level twin of {@code POST /sections/{id}/promote}, and the step that makes the reuse
 * loop a loop rather than a one-way import: assemble the first payer form from the catalog, then let
 * the second one start from its shape instead of from nothing.
 *
 * <p>Requires every placed section to be template-backed. That reads as a restriction and is really
 * an ordering: promote the sections, then promote the form. A blueprint references templates, so
 * there is nothing else it could point at.
 *
 * @param key stable identifier for the shape, distinct from the form's own id
 */
public record CreateBlueprintFromForm(String tenantId, String formDefinitionId, String key, String name) {

    public CreateBlueprintFromForm {
        if (tenantId == null || tenantId.isBlank()) {
            throw new InvariantViolated("A tenant id is required");
        }
        if (formDefinitionId == null || formDefinitionId.isBlank()) {
            throw new InvariantViolated("A form definition id is required");
        }
        if (key == null || key.isBlank()) {
            throw new InvariantViolated("A blueprint key is required");
        }
    }
}
