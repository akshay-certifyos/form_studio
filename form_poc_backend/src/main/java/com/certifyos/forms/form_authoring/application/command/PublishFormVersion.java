package com.certifyos.forms.form_authoring.application.command;

/**
 * Publish the current draft of a form.
 *
 * @param requestedBy the actor, for the audit trail on the version
 */
public record PublishFormVersion(
        String tenantId, String formDefinitionId, String changelog, String ticketId, String requestedBy) {}
