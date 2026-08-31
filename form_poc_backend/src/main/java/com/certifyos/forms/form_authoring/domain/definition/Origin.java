package com.certifyos.forms.form_authoring.domain.definition;

/**
 * Where a question instance came from.
 *
 * <p>This is what makes template drift computable — "the template has 12 questions, you have
 * disabled 2 and added 1". It is also why a template-sourced question is <em>disabled</em> rather
 * than deleted: deleting it loses the provenance a template upgrade needs to reconcile against.
 */
public enum Origin {
    /** Inherited from the section template this definition was created from. */
    TEMPLATE,
    /** Added locally by the tenant after creation. */
    ADDED
}
