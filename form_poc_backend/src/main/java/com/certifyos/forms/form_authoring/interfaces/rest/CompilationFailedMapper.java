package com.certifyos.forms.form_authoring.interfaces.rest;

import com.certifyos.forms.form_authoring.domain.compile.CompilationFailedException;
import com.certifyos.forms.shared_kernel.interfaces.ApiError;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.List;

/**
 * A form that will not compile.
 *
 * <p>Separate from {@code DomainExceptionMapper} because this failure is a <em>list</em>, not a
 * sentence: the author needs every problem at once, each pinned to the step it belongs to, so the UI
 * can render them against the tree rather than as a stack of unattributed codes. Fixing one problem
 * per publish attempt is the loop this design exists to remove.
 *
 * <p>It also lives in {@code form_authoring} rather than the shared kernel, because
 * {@code CompilationFailedException} belongs to the context that owns the concept — pulling it into
 * the kernel would make the kernel depend on a bounded context.
 */
@Provider
public class CompilationFailedMapper implements ExceptionMapper<CompilationFailedException> {

    @Override
    public Response toResponse(CompilationFailedException exception) {
        List<ApiError.ProblemDetail> problems = exception.report().problems().stream()
                .map(p -> new ApiError.ProblemDetail(
                        p.code().name(), p.message(), p.stepKey(), p.questionKey(), p.path()))
                .toList();

        return Response.status(422)
                .entity(new ApiError(
                        "COMPILATION_FAILED",
                        problems.size() == 1
                                ? "This form has 1 problem that needs fixing before it can be published."
                                : "This form has " + problems.size()
                                        + " problems that need fixing before it can be published.",
                        problems))
                .build();
    }
}
