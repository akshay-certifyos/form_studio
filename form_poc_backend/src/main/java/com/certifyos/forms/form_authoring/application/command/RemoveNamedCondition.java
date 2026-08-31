package com.certifyos.forms.form_authoring.application.command;

import com.certifyos.forms.shared_kernel.exception.InvariantViolated;

/**
 * Deletes a named condition.
 *
 * <p>Refused while any step still references it. The analyzer would catch the dangling {@code ref} at
 * publish, but the author would meet it a screen away from the cause, so the aggregate refuses here
 * and names the steps to fix first.
 */
public record RemoveNamedCondition(String formDefinitionId, String key) {

    public RemoveNamedCondition {
        if (formDefinitionId == null || formDefinitionId.isBlank()) {
            throw new InvariantViolated("A form definition id is required");
        }
        if (key == null || key.isBlank()) {
            throw new InvariantViolated("A condition key is required");
        }
    }
}
