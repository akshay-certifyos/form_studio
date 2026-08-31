package com.certifyos.forms.form_authoring.application.command;

import com.certifyos.forms.shared_kernel.exception.InvariantViolated;

/**
 * Takes a step out of a form.
 *
 * <p>A real removal, unlike a section question, which is only ever disabled. The asymmetry is
 * deliberate: a question's origin is the provenance a template upgrade reconciles against, so
 * destroying it loses information nothing can recover. A step has no such role — the section it
 * placed still exists and can be placed again — so there is nothing to preserve by keeping a husk.
 *
 * <p>What this does not do is check whether other steps' conditions read from the one being removed.
 * The analyzer reports that at {@code /validate} with every other problem, rather than blocking an
 * edit an author is part-way through.
 */
public record RemoveStep(String formDefinitionId, String stepKey) {

    public RemoveStep {
        if (formDefinitionId == null || formDefinitionId.isBlank()) {
            throw new InvariantViolated("A form definition id is required");
        }
        if (stepKey == null || stepKey.isBlank()) {
            throw new InvariantViolated("A step key is required");
        }
    }
}
