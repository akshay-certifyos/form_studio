package com.certifyos.forms.form_authoring.domain.compile;

import com.certifyos.forms.shared_kernel.expression.ExpressionAnalyzer;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything wrong with a form, collected in one pass.
 *
 * <p>Deliberately not fail-fast. An author who fixes one problem, publishes, and is then shown the
 * next one is in a miserable loop — and the premise of this project is that a credentialing ops
 * person resolves these unaided.
 *
 * <p>Every problem carries the step it belongs to so the UI can pin it to a tree node rather than
 * presenting a list of codes.
 *
 * <p><b>Problems and notices are separate, and the distinction is not cosmetic.</b> A problem means
 * the form is wrong and must not be published. A notice means the form is fine, but v0 of the
 * compiler will not carry some part of it into the artifact — a hard stop, a grouped question, an
 * audience rule. Those three are stored faithfully and were being dropped in silence, so an author
 * could set a disqualifying hard stop, publish successfully, and ship a form that never blocks
 * anyone. Modelling them as two lists rather than one severity field means {@link #isClean()} keeps
 * its exact meaning and no future code can accidentally let a notice block a publish, or a problem
 * fail to.
 */
public record CompilationReport(List<Problem> problems, List<Notice> notices) {

    public CompilationReport {
        problems = problems == null ? List.of() : List.copyOf(problems);
        notices = notices == null ? List.of() : List.copyOf(notices);
    }

    /** Kept so the many call sites that only produce problems stay unchanged. */
    public CompilationReport(List<Problem> problems) {
        this(problems, List.of());
    }

    /**
     * @param stepKey where to pin this in the UI; null for a form-level problem
     * @param message written for an author, not an engineer
     */
    public record Problem(
            String stepKey, String questionKey, ExpressionAnalyzer.Code code, String message, String path) {

        public static Problem at(String stepKey, ExpressionAnalyzer.Finding finding) {
            return new Problem(stepKey, null, finding.code(), finding.message(), finding.path());
        }

        public static Problem at(String stepKey, String questionKey, ExpressionAnalyzer.Finding finding) {
            return new Problem(stepKey, questionKey, finding.code(), finding.message(), finding.path());
        }
    }

    /**
     * Something the author authored that this version of the compiler does not emit.
     *
     * <p>Does not block publishing — the feature is stored and a later version can compile it — but
     * it must be said out loud, because the alternative is a form that quietly does less than it
     * appears to.
     *
     * @param message written for an author: what will not happen, not which field was skipped
     */
    public record Notice(String stepKey, String questionKey, Code code, String message) {

        public enum Code {
            /** A disqualifying rule is stored but never evaluated, so nothing blocks submission. */
            HARD_STOP_NOT_COMPILED,
            /** A step restricted to certain viewer roles will be shown to everyone. */
            AUDIENCE_RULE_NOT_COMPILED,
            /** A question with child inputs emits only the parent; the children are absent. */
            GROUPED_QUESTION_NOT_COMPILED
        }
    }

    public static CompilationReport empty() {
        return new CompilationReport(List.of(), List.of());
    }

    public boolean hasNotices() {
        return !notices.isEmpty();
    }

    public boolean isClean() {
        return problems.isEmpty();
    }

    public List<Problem> forStep(String stepKey) {
        return problems.stream().filter(p -> stepKey.equals(p.stepKey())).toList();
    }

    public CompilationReport plus(Problem problem) {
        List<Problem> next = new ArrayList<>(problems);
        next.add(problem);
        return new CompilationReport(next, notices);
    }

    public List<Notice> noticesForStep(String stepKey) {
        return notices.stream().filter(n -> stepKey.equals(n.stepKey())).toList();
    }

    /** One line per problem, for logs and test failures. */
    public String summary() {
        if (isClean()) {
            return "no problems";
        }
        return problems.stream()
                .map(p -> (p.stepKey() == null ? "form" : p.stepKey())
                        + (p.questionKey() == null ? "" : "." + p.questionKey())
                        + ": " + p.code() + " (" + p.path() + ")")
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
