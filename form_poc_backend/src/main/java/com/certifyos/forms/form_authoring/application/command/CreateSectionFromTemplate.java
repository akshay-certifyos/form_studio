package com.certifyos.forms.form_authoring.application.command;

/** @param name null keeps the template's own name */
public record CreateSectionFromTemplate(String tenantId, String sectionTemplateId, String name) {

    public CreateSectionFromTemplate {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("A tenant id is required");
        }
        if (sectionTemplateId == null || sectionTemplateId.isBlank()) {
            throw new IllegalArgumentException("A section template id is required");
        }
    }
}
