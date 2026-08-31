package com.certifyos.forms.form_authoring.domain.compile;

/**
 * Thrown when a form cannot compile.
 *
 * <p>Carries the whole {@link CompilationReport} rather than a message, so the API can return a
 * structured 422 the authoring UI renders inline against the offending nodes — not a string an
 * author has to interpret.
 */
public class CompilationFailedException extends RuntimeException {

    private final transient CompilationReport report;

    public CompilationFailedException(CompilationReport report) {
        super("Form does not compile:\n" + report.summary());
        this.report = report;
    }

    public CompilationReport report() {
        return report;
    }
}
