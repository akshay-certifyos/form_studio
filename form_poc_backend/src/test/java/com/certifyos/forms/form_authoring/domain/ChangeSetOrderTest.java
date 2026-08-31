package com.certifyos.forms.form_authoring.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.form_authoring.domain.publishing.ChangeClass;
import com.certifyos.forms.form_authoring.domain.publishing.ChangeSet;
import com.certifyos.forms.form_authoring.domain.publishing.CompiledForm;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Change classification for a reorder.
 *
 * <p>Every other comparison in {@code ChangeSet} keys fields by name, so field <em>order</em> was
 * invisible to it: reordering the questions in a step produced added, removed and changed all empty
 * and published as {@code TEXT} with the note <b>"No changes."</b> — while the sequence a provider is
 * asked to fill had visibly changed. The classification was right and the note was a lie.
 *
 * <p>Still {@code TEXT}, deliberately. Answers are keyed by name, so reordering invalidates nothing
 * and must not trigger a reset. What it must do is say so.
 */
class ChangeSetOrderTest {

    private static CompiledForm.CompiledField field(String name) {
        return new CompiledForm.CompiledField(
                name, name, "text", null, null, null, null, null, null, null, null, null, null);
    }

    private static CompiledForm form(List<String> stepOneFields, List<String> stepTwoFields) {
        return new CompiledForm(
                "Recred",
                null,
                List.of(
                        new CompiledForm.CompiledStep(
                                "applicant",
                                "Applicant",
                                null,
                                stepOneFields.stream()
                                        .map(ChangeSetOrderTest::field)
                                        .toList(),
                                null,
                                null,
                                null),
                        new CompiledForm.CompiledStep(
                                "address",
                                "Address",
                                null,
                                stepTwoFields.stream()
                                        .map(ChangeSetOrderTest::field)
                                        .toList(),
                                null,
                                null,
                                null)),
                null);
    }

    @Test
    @DisplayName("a reorder is reported rather than passing as no change")
    void reorderIsReported() {
        CompiledForm before = form(List.of("a.one", "a.two", "a.three"), List.of("b.one"));
        CompiledForm after = form(List.of("a.three", "a.one", "a.two"), List.of("b.one"));

        ChangeSet changes = ChangeSet.between(before, after);

        assertFalse(
                String.join(" ", changes.notes()).contains("No changes"),
                () -> "a reorder must not publish as 'No changes': " + changes.notes());
        assertTrue(String.join(" ", changes.notes()).contains("Question order changed"), () -> changes.notes()
                .toString());
        assertTrue(String.join(" ", changes.notes()).contains("applicant"), "names the step");
    }

    @Test
    @DisplayName("a reorder does not invalidate any answer")
    void reorderDoesNotRequireReset() {
        CompiledForm before = form(List.of("a.one", "a.two"), List.of("b.one"));
        CompiledForm after = form(List.of("a.two", "a.one"), List.of("b.one"));

        ChangeSet changes = ChangeSet.between(before, after);

        // Answers are keyed by name. Classifying this as structural would wipe every in-progress
        // application because someone moved a question up.
        assertEquals(ChangeClass.TEXT, changes.changeClass());
        assertFalse(changes.requiresReset());
        assertEquals(List.of(), List.copyOf(changes.changedKeys()));
    }

    @Test
    @DisplayName("an identical artifact still reports no changes")
    void identicalIsStillNoChanges() {
        CompiledForm artifact = form(List.of("a.one", "a.two"), List.of("b.one"));

        ChangeSet changes = ChangeSet.between(artifact, artifact);

        assertTrue(String.join(" ", changes.notes()).contains("No changes"), () -> changes.notes()
                .toString());
    }

    @Test
    @DisplayName("adding a question is not a reorder, so the note stays meaningful")
    void additionIsNotAReorder() {
        CompiledForm before = form(List.of("a.one", "a.two"), List.of("b.one"));
        CompiledForm after = form(List.of("a.one", "a.two", "a.three"), List.of("b.one"));

        ChangeSet changes = ChangeSet.between(before, after);

        // Inserting shifts later positions without reordering anything. Reporting it as a reorder
        // would fire on nearly every additive change and the note would stop meaning anything.
        assertEquals(ChangeClass.ADDITIVE, changes.changeClass());
        assertFalse(String.join(" ", changes.notes()).contains("Question order changed"));
    }

    @Test
    @DisplayName("a removal is structural on its own terms, not reported as a reorder")
    void removalIsNotAReorder() {
        CompiledForm before = form(List.of("a.one", "a.two", "a.three"), List.of("b.one"));
        CompiledForm after = form(List.of("a.one", "a.three"), List.of("b.one"));

        ChangeSet changes = ChangeSet.between(before, after);

        assertEquals(ChangeClass.STRUCTURAL, changes.changeClass());
        assertFalse(String.join(" ", changes.notes()).contains("Question order changed"));
    }

    @Test
    @DisplayName("reorders in two steps are both named")
    void reportsEveryReorderedStep() {
        CompiledForm before = form(List.of("a.one", "a.two"), List.of("b.one", "b.two"));
        CompiledForm after = form(List.of("a.two", "a.one"), List.of("b.two", "b.one"));

        ChangeSet changes = ChangeSet.between(before, after);
        String notes = String.join(" ", changes.notes());

        assertTrue(notes.contains("2 step(s)"), notes);
        assertTrue(notes.contains("applicant") && notes.contains("address"), notes);
    }
}
