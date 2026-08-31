package com.certifyos.forms.form_authoring.application.command;

/** @param name null keeps the blueprint's own name */
public record CreateFormFromBlueprint(String tenantId, String blueprintId, String name) {

    public CreateFormFromBlueprint {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("A tenant id is required");
        }
        if (blueprintId == null || blueprintId.isBlank()) {
            throw new IllegalArgumentException("A blueprint id is required");
        }
    }
}
