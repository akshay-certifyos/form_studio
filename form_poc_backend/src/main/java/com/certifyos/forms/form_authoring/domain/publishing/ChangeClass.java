package com.certifyos.forms.form_authoring.domain.publishing;

/**
 * How disruptive publishing a version is to providers mid-application.
 *
 * <p>Today this is a hand-ticked {@code isTextOnlyUpdate} checkbox, and when it is false the
 * publish wipes {@code answers} and {@code stepProgress} for <em>every</em> in-progress application
 * on that template. A person's judgement call decides whether providers lose their work.
 *
 * <p>Here it is <b>computed</b> by diffing two compiled artifacts, and paired with the exact set of
 * changed keys so the reset can be surgical instead of total.
 */
public enum ChangeClass {
    /** Labels, help text, intros. Nothing an answer depends on. No reset. */
    TEXT,

    /** New optional questions or steps only. Existing answers stay valid. No reset. */
    ADDITIVE,

    /** Anything else. Only the answers to changed questions are dropped. */
    STRUCTURAL;
}
