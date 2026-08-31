package com.certifyos.forms.shared_kernel.exception;

/**
 * The target is not in a state that allows this — promoting an already-active question, editing a
 * published version. Maps to 409.
 */
public final class ConflictingState extends DomainException {

    public ConflictingState(String message) {
        super(message);
    }
}
