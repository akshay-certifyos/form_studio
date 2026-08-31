package com.certifyos.forms.form_authoring.application.command;

import com.certifyos.forms.shared_kernel.exception.InvariantViolated;
import com.certifyos.forms.shared_kernel.expression.Expression;

/**
 * Defines or replaces a rule the form's steps can reference by name.
 *
 * <p>Upsert rather than separate create and update, because from the author's side there is one
 * action — "this is what 'specialty exempt from DEA' means" — and splitting it would only make the
 * client track which it is.
 *
 * <p>Editing one is safe against published versions by construction: named conditions are inlined at
 * compile time, so a version already published holds its own frozen copy and nothing here can reach
 * it. That is the whole reason for the inlining rule.
 *
 * @param label what the rule reads as in the UI. Required, because a referenced condition renders by
 *     its label — an unlabelled one shows up in the builder as a bare key and stops being the
 *     readable shorthand it exists to be.
 */
public record UpsertNamedCondition(String formDefinitionId, String key, String label, Expression expression) {

    public UpsertNamedCondition {
        if (formDefinitionId == null || formDefinitionId.isBlank()) {
            throw new InvariantViolated("A form definition id is required");
        }
        if (key == null || key.isBlank()) {
            throw new InvariantViolated("A condition key is required");
        }
        if (label == null || label.isBlank()) {
            throw new InvariantViolated("A named condition needs a label — it is what the rule reads as in the UI");
        }
        if (expression == null) {
            throw new InvariantViolated("A named condition needs an expression. To remove it, delete it.");
        }
    }
}
