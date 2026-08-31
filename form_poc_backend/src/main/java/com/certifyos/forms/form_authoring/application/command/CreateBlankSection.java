package com.certifyos.forms.form_authoring.application.command;

import com.certifyos.forms.shared_kernel.exception.InvariantViolated;

/**
 * Starts a section with no questions.
 *
 * <p>Where {@link CreateSectionFromTemplate} copies a shape somebody already generalised, this is
 * for the part of a payer form that is genuinely specific — and every real form examined had some.
 * Questions are then added from the catalog, so the section is bespoke while its content is not.
 *
 * @param key the section's own key. Distinct from the step key that will place it: this one names
 *     the shape, the step key names the answer namespace, and one section can be placed twice.
 */
public record CreateBlankSection(String tenantId, String key, String name) {

    public CreateBlankSection {
        if (tenantId == null || tenantId.isBlank()) {
            throw new InvariantViolated("A tenant id is required");
        }
        if (key == null || key.isBlank()) {
            throw new InvariantViolated("A section key is required");
        }
        if (name == null || name.isBlank()) {
            throw new InvariantViolated("A section name is required — it is the step title an author reads");
        }
    }
}
