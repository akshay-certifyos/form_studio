package com.certifyos.forms.shared_kernel.interfaces;

import com.certifyos.forms.shared_kernel.exception.ConflictingState;
import com.certifyos.forms.shared_kernel.exception.DomainException;
import com.certifyos.forms.shared_kernel.exception.InvariantViolated;
import com.certifyos.forms.shared_kernel.exception.NotFound;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Turns a domain failure into a response, once, centrally.
 *
 * <p>This is what lets every controller be free of {@code try/catch}. The existing service repeats
 * {@code ErrorHandlerUtil.handleDalError(e, "Failed to …")} in every method of every resource; the
 * repetition is not just noise — it means each call site decides its own status code, and they
 * drift.
 *
 * <p>The switch is <b>exhaustive over a sealed interface</b>, so adding a domain exception without
 * deciding its status code fails the build rather than silently becoming a 500.
 */
@Provider
public class DomainExceptionMapper implements ExceptionMapper<DomainException> {

    @Override
    public Response toResponse(DomainException exception) {
        return switch (exception) {
            case NotFound e -> status(Response.Status.NOT_FOUND, "NOT_FOUND", e.message());
            case ConflictingState e -> status(Response.Status.CONFLICT, "CONFLICT", e.message());
            case InvariantViolated e -> status(422, "INVARIANT_VIOLATED", e.message());
        };
    }

    private static Response status(Response.Status status, String code, String message) {
        return Response.status(status).entity(ApiError.of(code, message)).build();
    }

    private static Response status(int status, String code, String message) {
        return Response.status(status).entity(ApiError.of(code, message)).build();
    }
}
