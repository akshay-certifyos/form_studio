package com.certifyos.forms.form_authoring.application.command;

import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.shared_kernel.exception.InvariantViolated;

/**
 * Places a section into a form as a new step.
 *
 * <p>The verb the first cut of this POC was missing, and the reason a form could only ever be
 * instantiated whole. Without it the model's central claim — a section is placed, and the placement
 * owns where, when and under what namespace — was expressible in the domain and unreachable through
 * the API.
 *
 * @param stepKey the answer namespace. Deliberately the caller's choice rather than derived from the
 *     section: placing one address section twice must yield {@code practiceLocation.*} and {@code
 *     billingAddress.*}, and only the author knows which is which.
 * @param order null appends after the current last step, which is what an author adding sections in
 *     sequence means. An explicit value inserts at that position without renumbering anything else.
 * @param repeating null takes the section as non-repeating
 */
public record PlaceSection(
        String formDefinitionId,
        String sectionDefinitionId,
        String stepKey,
        Integer order,
        String group,
        String titleOverride,
        Step.Repeating repeating) {

    public PlaceSection {
        if (formDefinitionId == null || formDefinitionId.isBlank()) {
            throw new InvariantViolated("A form definition id is required");
        }
        if (sectionDefinitionId == null || sectionDefinitionId.isBlank()) {
            throw new InvariantViolated("A section definition id is required");
        }
        if (stepKey == null || stepKey.isBlank()) {
            throw new InvariantViolated("A step key is required — it is the answer namespace");
        }
    }
}
