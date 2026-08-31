package com.certifyos.forms.form_authoring.application.command;

/** Compile and diff without persisting, so an author sees the blast radius before committing. */
public record PreviewChangeSet(String tenantId, String formDefinitionId) {}
