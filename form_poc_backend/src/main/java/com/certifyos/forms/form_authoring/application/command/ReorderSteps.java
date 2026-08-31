package com.certifyos.forms.form_authoring.application.command;

import com.certifyos.forms.shared_kernel.exception.InvariantViolated;
import java.util.List;

/**
 * Sets the step order for a whole form.
 *
 * <p>Whole-list, for the reason spelled out on {@code FormDefinition.reorderSteps}: a partial
 * reorder cannot be made safe, because any step left out keeps its old number and can end up
 * sharing one, after which the sort — not the author — decides what order the provider sees.
 *
 * @param orderedKeys every step key in the form, exactly once, in the order wanted
 */
public record ReorderSteps(String formDefinitionId, List<String> orderedKeys) {

    public ReorderSteps {
        if (formDefinitionId == null || formDefinitionId.isBlank()) {
            throw new InvariantViolated("A form definition id is required");
        }
        if (orderedKeys == null || orderedKeys.isEmpty()) {
            throw new InvariantViolated("A reorder needs the full list of step keys");
        }
        orderedKeys = List.copyOf(orderedKeys);
    }
}
