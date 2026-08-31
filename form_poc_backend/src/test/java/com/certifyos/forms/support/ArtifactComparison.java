package com.certifyos.forms.support;

import com.certifyos.forms.form_authoring.domain.publishing.CompiledForm;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compares two compiled artifacts and says precisely where they differ.
 *
 * <p>This is the measuring instrument for the round-trip gate: take a form that exists today, import
 * it into the new model, compile it, and compare. If the two artifacts describe the same form, the
 * model can express that form. If they do not, the differences <em>are</em> the finding — the list of
 * things the model cannot yet say.
 *
 * <p><b>Why not {@code ChangeSet.between}.</b> That answers a different question. It classifies the
 * publishing impact of a change — text, additive, or structural — because its job is deciding whether
 * providers lose in-progress answers. It deliberately ignores everything that cannot affect an
 * answer, which for this purpose is most of what matters: a lost hint, a dropped validation, a
 * mangled option label. Reusing it here would report "no structural change" for an artifact that had
 * silently shed half its content.
 *
 * <p><b>What counts as a difference.</b> Everything the renderer reads, compared by value. Two
 * things are deliberately <em>not</em> differences, because neither changes the form a provider
 * sees:
 *
 * <ul>
 *   <li><b>Field and step ordering is compared, but option ordering is not.</b> Step and field order
 *       is visible to the provider and is part of the form. Option order within a select is not
 *       meaningfully authored today — several existing configs list the same option set in different
 *       orders — so treating it as a difference would bury the real findings in noise.
 *   <li><b>Absent versus empty collections.</b> {@code options: null} and {@code options: []} are
 *       the same field. The existing configs use both interchangeably.
 * </ul>
 *
 * <p>Every other asymmetry is reported, including ones that look pedantic. An artifact that differs
 * only in a hint string still means the importer lost a hint, and that is worth knowing before
 * anyone concludes the model round-trips.
 */
public final class ArtifactComparison {

    private final List<String> differences = new ArrayList<>();

    private ArtifactComparison() {}

    public static Result compare(CompiledForm expected, CompiledForm actual) {
        ArtifactComparison comparison = new ArtifactComparison();
        comparison.compareForm(expected, actual);
        return new Result(List.copyOf(comparison.differences));
    }

    /**
     * @param differences one line per difference, each naming the path that differs so a failure
     *     message points at a field rather than at "the artifact"
     */
    public record Result(List<String> differences) {

        public boolean isEquivalent() {
            return differences.isEmpty();
        }

        /** Formatted for an assertion message: the whole list, since fixing one at a time is slow. */
        public String describe() {
            if (isEquivalent()) {
                return "artifacts are equivalent";
            }
            return differences.size() + " difference(s):\n  " + String.join("\n  ", differences);
        }
    }

    // ------------------------------------------------------------------

    private void compareForm(CompiledForm expected, CompiledForm actual) {
        scalar("title", expected.title(), actual.title());
        scalar("instructionsTitle", expected.instructionsTitle(), actual.instructionsTitle());
        scalar(
                "requiresPractitionerSignature",
                expected.requiresPractitionerSignature(),
                actual.requiresPractitionerSignature());

        // Steps are matched by id rather than by position, so a reordering reports as one ordering
        // difference instead of cascading into a difference on every field of every moved step.
        Map<String, CompiledForm.CompiledStep> expectedById = byId(expected.steps(), CompiledForm.CompiledStep::id);
        Map<String, CompiledForm.CompiledStep> actualById = byId(actual.steps(), CompiledForm.CompiledStep::id);

        for (String id : missingFrom(expectedById.keySet(), actualById.keySet())) {
            differences.add("step '" + id + "' is missing");
        }
        for (String id : missingFrom(actualById.keySet(), expectedById.keySet())) {
            differences.add("step '" + id + "' is unexpected");
        }

        List<String> expectedOrder = expected.steps().stream()
                .map(CompiledForm.CompiledStep::id)
                .filter(actualById::containsKey)
                .toList();
        List<String> actualOrder = actual.steps().stream()
                .map(CompiledForm.CompiledStep::id)
                .filter(expectedById::containsKey)
                .toList();
        if (!expectedOrder.equals(actualOrder)) {
            differences.add("step order differs: expected " + expectedOrder + " but was " + actualOrder);
        }

        expectedById.forEach((id, expectedStep) -> {
            CompiledForm.CompiledStep actualStep = actualById.get(id);
            if (actualStep != null) {
                compareStep("step[" + id + "]", expectedStep, actualStep);
            }
        });
    }

    private void compareStep(String path, CompiledForm.CompiledStep expected, CompiledForm.CompiledStep actual) {
        scalar(path + ".title", expected.title(), actual.title());
        scalar(path + ".type", expected.type(), actual.type());
        scalar(path + ".audience", expected.audience(), actual.audience());
        json(path + ".condition", expected.condition(), actual.condition());
        json(path + ".instructionsContent", expected.instructionsContent(), actual.instructionsContent());

        Map<String, CompiledForm.CompiledField> expectedByName =
                byId(expected.fields(), CompiledForm.CompiledField::name);
        Map<String, CompiledForm.CompiledField> actualByName = byId(actual.fields(), CompiledForm.CompiledField::name);

        for (String name : missingFrom(expectedByName.keySet(), actualByName.keySet())) {
            differences.add(path + " is missing field '" + name + "'");
        }
        for (String name : missingFrom(actualByName.keySet(), expectedByName.keySet())) {
            differences.add(path + " has unexpected field '" + name + "'");
        }

        List<String> expectedOrder = expected.fields().stream()
                .map(CompiledForm.CompiledField::name)
                .filter(actualByName::containsKey)
                .toList();
        List<String> actualOrder = actual.fields().stream()
                .map(CompiledForm.CompiledField::name)
                .filter(expectedByName::containsKey)
                .toList();
        if (!expectedOrder.equals(actualOrder)) {
            differences.add(path + " field order differs: expected " + expectedOrder + " but was " + actualOrder);
        }

        expectedByName.forEach((name, expectedField) -> {
            CompiledForm.CompiledField actualField = actualByName.get(name);
            if (actualField != null) {
                compareField(path + ".field[" + name + "]", expectedField, actualField);
            }
        });
    }

    private void compareField(String path, CompiledForm.CompiledField expected, CompiledForm.CompiledField actual) {
        scalar(path + ".label", expected.label(), actual.label());
        scalar(path + ".type", expected.type(), actual.type());
        // Absent and false mean the same thing to the renderer, and the existing configs use both.
        scalar(path + ".required", orFalse(expected.required()), orFalse(actual.required()));
        scalar(path + ".hint", expected.hint(), actual.hint());
        scalar(path + ".dependsOn", expected.dependsOn(), actual.dependsOn());
        scalar(path + ".accept", expected.accept(), actual.accept());
        scalar(path + ".rows", expected.rows(), actual.rows());
        scalar(path + ".validation", expected.validation(), actual.validation());
        scalar(path + ".layout", expected.layout(), actual.layout());
        json(path + ".condition", expected.condition(), actual.condition());

        compareOptions(path, expected.options(), actual.options());
        compareGroupFields(path, expected.groupFields(), actual.groupFields());
    }

    /** Compared as a set: option ordering within a select is not meaningfully authored today. */
    private void compareOptions(
            String path, List<CompiledForm.CompiledOption> expected, List<CompiledForm.CompiledOption> actual) {

        Set<CompiledForm.CompiledOption> expectedSet = new LinkedHashSet<>(orEmpty(expected));
        Set<CompiledForm.CompiledOption> actualSet = new LinkedHashSet<>(orEmpty(actual));

        expectedSet.stream()
                .filter(option -> !actualSet.contains(option))
                .forEach(option -> differences.add(path + " is missing option " + describe(option)));
        actualSet.stream()
                .filter(option -> !expectedSet.contains(option))
                .forEach(option -> differences.add(path + " has unexpected option " + describe(option)));
    }

    private void compareGroupFields(
            String path, List<CompiledForm.CompiledField> expected, List<CompiledForm.CompiledField> actual) {

        List<CompiledForm.CompiledField> expectedFields = orEmpty(expected);
        List<CompiledForm.CompiledField> actualFields = orEmpty(actual);

        if (expectedFields.size() != actualFields.size()) {
            differences.add(path + " has " + actualFields.size() + " sub-field(s), expected " + expectedFields.size());
            return;
        }
        for (int i = 0; i < expectedFields.size(); i++) {
            compareField(path + ".groupFields[" + i + "]", expectedFields.get(i), actualFields.get(i));
        }
    }

    // ------------------------------------------------------------------

    private void scalar(String path, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            differences.add(path + ": expected " + render(expected) + " but was " + render(actual));
        }
    }

    /**
     * Conditions are compared as parsed JSON, not as text.
     *
     * <p>Key order and whitespace are not part of an expression's meaning, and an importer that
     * emitted the same rule with its keys in a different order would otherwise look like a failure.
     */
    private void json(String path, JsonNode expected, JsonNode actual) {
        if (!Objects.equals(expected, actual)) {
            differences.add(path + ": expected " + render(expected) + " but was " + render(actual));
        }
    }

    private static Boolean orFalse(Boolean value) {
        return value != null && value;
    }

    private static <T> List<T> orEmpty(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static String describe(CompiledForm.CompiledOption option) {
        return "'" + option.value() + "'"
                + (option.filterValue() == null ? "" : " (filtered by " + option.filterValue() + ")");
    }

    private static String render(Object value) {
        return value == null ? "absent" : "'" + value + "'";
    }

    private static <T> Map<String, T> byId(List<T> items, java.util.function.Function<T, String> id) {
        Map<String, T> byId = new LinkedHashMap<>();
        for (T item : items) {
            byId.put(id.apply(item), item);
        }
        return byId;
    }

    private static List<String> missingFrom(Set<String> source, Set<String> target) {
        return source.stream().filter(key -> !target.contains(key)).toList();
    }
}
