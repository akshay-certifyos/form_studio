package com.certifyos.forms.shared_kernel.exception;

/** The thing asked for does not exist. Maps to 404. */
public final class NotFound extends DomainException {

    public NotFound(String what, String id) {
        super(what + " not found: " + id);
    }

    public NotFound(String message) {
        super(message);
    }
}
