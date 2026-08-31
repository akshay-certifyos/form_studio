package com.certifyos.forms.shared_kernel.exception;

/**
 * Errors a caller can act on, as a closed set.
 *
 * <p>An abstract class rather than an interface so {@code DomainExceptionMapper} can be typed on it
 * directly. That matters: an earlier version typed the mapper on {@code RuntimeException} and
 * rethrew anything that was not a domain error — but rethrowing inside a mapper does not delegate to
 * default handling, it produces a 500. A malformed content-type came back as an opaque server error
 * instead of a 415.
 *
 * <p>Sealed so the mapper switches exhaustively: add a case here without deciding its status code
 * and the build fails, rather than the new failure quietly becoming a 500.
 *
 * <p>Note what is not in this hierarchy: {@code CompilationFailedException}. It carries a structured
 * report rather than a message, and it belongs to {@code form_authoring}, which owns the concept —
 * pulling it in here would make the shared kernel depend on a bounded context. It gets its own
 * mapper.
 */
public abstract sealed class DomainException extends RuntimeException
        permits NotFound, InvariantViolated, ConflictingState {

    protected DomainException(String message) {
        super(message);
    }

    public String message() {
        return getMessage();
    }
}
