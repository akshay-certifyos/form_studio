package com.certifyos.forms.shared_kernel.exception;

/**
 * The request was well-formed but would leave an aggregate in an illegal state — a duplicate step
 * key, a template question someone tried to delete. Maps to 422.
 */
public final class InvariantViolated extends DomainException {

    public InvariantViolated(String message) {
        super(message);
    }
}
