package com.certifyos.forms.form_authoring.application.command;

import com.certifyos.forms.shared_kernel.exception.InvariantViolated;

/** @param name null keeps the blueprint's own name */
public record CreateFormFromBlueprint(String tenantId, String blueprintId, String name) {

    public CreateFormFromBlueprint {
        if (tenantId == null || tenantId.isBlank()) {
            throw new InvariantViolated("A tenant id is required");
        }
        if (blueprintId == null || blueprintId.isBlank()) {
            throw new InvariantViolated("A blueprint id is required");
        }
    }
}
