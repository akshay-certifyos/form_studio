package com.certifyos.forms.shared_kernel.interfaces;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The error body every failure returns.
 *
 * @param code stable and machine-readable, for logs and clients
 * @param message written for the person on the other end of the screen
 * @param problems populated only for a compilation failure, where "what is wrong" is a list rather
 *     than a sentence and each entry needs pinning to a specific node in the authoring UI
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, List<ProblemDetail> problems) {

    public ApiError {
        problems = problems == null ? null : List.copyOf(problems);
    }

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }

    /**
     * @param stepKey which step to attach this to, so the UI shows it on the offending node rather
     *     than in a list the author has to map back to the form themselves
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProblemDetail(String code, String message, String stepKey, String questionKey, String path) {}
}
