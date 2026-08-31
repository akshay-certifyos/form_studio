package com.certifyos.forms.form_authoring.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.form_authoring.domain.compile.CatalogSnapshot;
import com.certifyos.forms.form_authoring.domain.compile.CompilationReport;
import com.certifyos.forms.form_authoring.domain.compile.FormCompiler;
import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Layout;
import com.certifyos.forms.form_authoring.domain.definition.Origin;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.definition.StepKey;
import com.certifyos.forms.question_catalog.domain.CatalogStatus;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.ResponseType;
import com.certifyos.forms.shared_kernel.expression.Expression;
import com.certifyos.forms.shared_kernel.expression.Operator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Features v0 stores but does not compile.
 *
 * <p>Three of them — hard stops, grouped questions, a step's audience rule — were dropped without a
 * word. That is the most dangerous shape a gap can take: an author configures a disqualifying hard
 * stop, publishing reports success, and the resulting form never blocks anyone. Nothing in 420 tests
 * disagreed, because no test asserted on the absence of something nobody had claimed was present.
 *
 * <p>So these tests assert two things at once: the feature is still <em>not</em> compiled (v0 scope
 * is unchanged), and the author is <em>told</em>. If a later version starts emitting one of them, the
 * corresponding test fails and has to be rewritten deliberately — which is the point.
 */
class CompilerNoticeTest {

    private static final QuestionId LICENSE = QuestionId.of("q_license");
    private static final QuestionId ADDRESS = QuestionId.of("q_address");

    private static Question plain(QuestionId id, String key, String label) {
        return new Question(
                id,
                null,
                key,
                label,
                null,
                ResponseType.TEXT,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                List.of(),
                null,
                CatalogStatus.ACTIVE,
                Set.of());
    }

    /** A question with sub-inputs — the "grouped input" shape production uses for addresses. */
    private static Question grouped(QuestionId id, String key, String label, int childCount) {
        List<Question> children = java.util.stream.IntStream.range(0, childCount)
                .mapToObj(i -> plain(QuestionId.of(id.value() + "_child" + i), key + "Child" + i, label + " part " + i))
                .toList();
        return new Question(
                id,
                null,
                key,
                label,
                null,
                ResponseType.GROUP,
                null,
                List.of(),
                Map.of(),
                Set.of(),
                children,
                null,
                CatalogStatus.ACTIVE,
                Set.of());
    }

    private static QuestionInstance instance(QuestionId id, String key, int order) {
        return new QuestionInstance(
                key, id, Origin.TEMPLATE, true, order, true, new Layout(12), null, null, null, null, null, null);
    }

    private static SectionDefinition section(String id, QuestionInstance... questions) {
        return new SectionDefinition(id, "tenant_1", id, id, null, null, null, List.of(questions), true);
    }

    private static FormCompiler.Result compile(
            FormDefinition definition, SectionDefinition section, Question... catalog) {
        return FormCompiler.analyze(
                definition, Map.of(section.id(), section), CatalogSnapshot.of(List.of(catalog), List.of()));
    }

    private static FormDefinition form(List<Step> steps, List<FormDefinition.HardStop> hardStops) {
        return new FormDefinition(
                "fd_1",
                "tenant_1",
                null,
                "Notices",
                "practitioner",
                null,
                null,
                Map.of(),
                steps,
                hardStops,
                FormDefinition.DefinitionStatus.DRAFT);
    }

    @Test
    @DisplayName("a hard stop compiles to nothing, and says so")
    void hardStopNotCompiled() {
        SectionDefinition section = section("sd_1", instance(LICENSE, "licenseNumber", 1));
        FormDefinition definition = form(
                List.of(Step.of("licensure", "sd_1", 1)),
                List.of(new FormDefinition.HardStop(
                        "excluded",
                        new Expression.Leaf("licensure.licenseNumber", Operator.EMPTY, null),
                        "You cannot continue without an active license.",
                        "next")));

        FormCompiler.Result result = compile(definition, section, plain(LICENSE, "licenseNumber", "License number"));

        // Still not compiled — v0 scope is deliberately unchanged.
        assertTrue(result.report().isClean(), "a hard stop must not block publishing");
        assertEquals(1, result.report().notices().size());

        CompilationReport.Notice notice = result.report().notices().get(0);
        assertEquals(CompilationReport.Notice.Code.HARD_STOP_NOT_COMPILED, notice.code());
        // The message must name the consequence, so an author does not read it as a technical aside.
        assertTrue(notice.message().contains("will not block submission"), notice.message());
        assertTrue(notice.message().contains("You cannot continue without an active license."));
    }

    @Test
    @DisplayName("a form with no hard stops produces no notices at all")
    void noNoticesWhenNothingIsDropped() {
        SectionDefinition section = section("sd_1", instance(LICENSE, "licenseNumber", 1));
        FormDefinition definition = form(List.of(Step.of("licensure", "sd_1", 1)), List.of());

        FormCompiler.Result result = compile(definition, section, plain(LICENSE, "licenseNumber", "License number"));

        // Notices must stay rare enough to read. A screen that always shows three of them is ignored.
        assertFalse(result.report().hasNotices(), result.report().notices().toString());
    }

    @Test
    @DisplayName("a step's audience rule compiles to nothing, and says the step will be shown to everyone")
    void audienceRuleNotCompiled() {
        SectionDefinition section = section("sd_1", instance(LICENSE, "licenseNumber", 1));
        Step restricted = new Step(
                StepKey.of("licensure"),
                "sd_1",
                1,
                true,
                null,
                null,
                null,
                null,
                new Expression.Leaf("viewer.role", Operator.EQ, "admin"));
        FormDefinition definition = form(List.of(restricted), List.of());

        FormCompiler.Result result = compile(definition, section, plain(LICENSE, "licenseNumber", "License number"));

        assertTrue(result.report().isClean());
        assertEquals(1, result.report().notices().size());
        CompilationReport.Notice notice = result.report().notices().get(0);
        assertEquals(CompilationReport.Notice.Code.AUDIENCE_RULE_NOT_COMPILED, notice.code());
        assertEquals("licensure", notice.stepKey(), "must be pinnable to the step in the tree");
        assertTrue(notice.message().contains("everyone"), notice.message());
    }

    @Test
    @DisplayName("a grouped question emits only its parent, and names how many inputs are missing")
    void groupedQuestionNotCompiled() {
        SectionDefinition section = section("sd_1", instance(ADDRESS, "address", 1));
        FormDefinition definition = form(List.of(Step.of("practice", "sd_1", 1)), List.of());

        FormCompiler.Result result = compile(definition, section, grouped(ADDRESS, "address", "Practice address", 3));

        assertTrue(result.report().isClean());
        assertEquals(1, result.report().notices().size());
        CompilationReport.Notice notice = result.report().notices().get(0);
        assertEquals(CompilationReport.Notice.Code.GROUPED_QUESTION_NOT_COMPILED, notice.code());
        assertEquals("practice", notice.stepKey());
        assertEquals("address", notice.questionKey(), "must be pinnable to the question, not just the step");
        assertTrue(notice.message().contains("3 sub-question"), notice.message());

        // And the parent really is all that reaches the artifact.
        assertEquals(1, result.artifact().steps().get(0).fields().size());
    }

    @Test
    @DisplayName("notices are pinnable per step, so the UI can attach them to tree nodes")
    void noticesAreAddressable() {
        SectionDefinition section = section("sd_1", instance(ADDRESS, "address", 1));
        Step restricted = new Step(
                StepKey.of("practice"),
                "sd_1",
                1,
                true,
                null,
                null,
                null,
                null,
                new Expression.Leaf("viewer.role", Operator.EQ, "admin"));
        FormDefinition definition = form(
                List.of(restricted),
                List.of(new FormDefinition.HardStop(
                        "excluded",
                        new Expression.Leaf("practice.address", Operator.EMPTY, null),
                        "Blocked.",
                        "next")));

        FormCompiler.Result result = compile(definition, section, grouped(ADDRESS, "address", "Practice address", 2));

        assertEquals(3, result.report().notices().size());
        assertEquals(2, result.report().noticesForStep("practice").size(), "audience + grouped question");
        // The hard stop is form-level and must not be pinned to an arbitrary step.
        assertTrue(result.report().notices().stream()
                .anyMatch(
                        n -> n.stepKey() == null && n.code() == CompilationReport.Notice.Code.HARD_STOP_NOT_COMPILED));
    }
}
