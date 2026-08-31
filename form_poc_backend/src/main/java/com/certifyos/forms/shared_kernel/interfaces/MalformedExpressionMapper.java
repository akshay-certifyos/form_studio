package com.certifyos.forms.shared_kernel.interfaces;

import com.certifyos.forms.shared_kernel.expression.ExpressionParser.MalformedExpressionException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * An expression that does not match the grammar.
 *
 * <p>It gets its own mapper for the same reason {@code CompilationFailedMapper} does: it cannot join
 * the {@code DomainException} hierarchy. That hierarchy is sealed, and without a module descriptor a
 * sealed class permits only same-package subclasses — so a parser exception in
 * {@code shared_kernel.expression} could only be admitted by moving the grammar into the exception
 * package, which would be the tail wagging the dog.
 *
 * <p>Without this mapper the failure is a 500. That is worth stating plainly, because it is the
 * third time this exact shape has bitten: an exception outside the mapped hierarchy is invisible
 * until a request actually makes it throw, and 422 versus 500 is the difference between the studio
 * showing "Unknown operator: isMostly" and showing nothing at all.
 */
@Provider
public class MalformedExpressionMapper implements ExceptionMapper<MalformedExpressionException> {

    @Override
    public Response toResponse(MalformedExpressionException exception) {
        // 422, not 400: the JSON parsed fine, it just is not a legal expression.
        return Response.status(422)
                .entity(ApiError.of("MALFORMED_EXPRESSION", exception.getMessage()))
                .build();
    }
}
