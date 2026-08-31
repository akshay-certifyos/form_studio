package com.certifyos.forms.form_authoring.domain.definition;

import com.certifyos.forms.shared_kernel.expression.EvaluationContext;

/**
 * Identity of one step within a form, and — more importantly — the namespace its answers live in.
 *
 * <p>Two steps composing the same section definition hold independent answers because of this:
 * {@code practiceLocation.line1} and {@code billingAddress.line1} are different slots. Without it
 * they are one slot and the second write destroys the first, which is a wrong answer rather than an
 * awkward one.
 */
public record StepKey(String value) {

    public StepKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A step key is required — it is the answer namespace");
        }
        if (!value.matches("[a-zA-Z][a-zA-Z0-9]*")) {
            throw new IllegalArgumentException("Step key must be alphanumeric starting with a letter, got: " + value
                    + ". It becomes part of every answer path, so punctuation would make paths ambiguous.");
        }
        if (EvaluationContext.RESERVED_NAMESPACES.contains(value)) {
            throw new IllegalArgumentException("'" + value
                    + "' is reserved for non-answer context (viewer / entity / tenant) and cannot be a step key.");
        }
    }

    public static StepKey of(String value) {
        return new StepKey(value);
    }

    /** The answer path for one question in this step. */
    public String pathFor(String questionKey) {
        return value + "." + questionKey;
    }

    @Override
    public String toString() {
        return value;
    }
}
