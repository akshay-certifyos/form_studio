package com.certifyos.forms.form_authoring.application.command;

import com.certifyos.forms.shared_kernel.expression.Expression;

/**
 * Replaces the rule that decides whether one step appears.
 *
 * <p>{@code visibleWhen} is nullable, and null is meaningful: it means "always visible". That is
 * distinct from an empty {@code all}, which is also always-true but would then be published as a
 * condition and show up in every subsequent diff as a change nobody made.
 */
public record UpdateStepCondition(String formDefinitionId, String stepKey, Expression visibleWhen) {

    public UpdateStepCondition {
        if (formDefinitionId == null || formDefinitionId.isBlank()) {
            throw new IllegalArgumentException("A form definition id is required");
        }
        if (stepKey == null || stepKey.isBlank()) {
            throw new IllegalArgumentException("A step key is required");
        }
    }
}
