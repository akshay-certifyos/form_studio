package com.certifyos.forms.form_authoring.domain.publishing;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The difference between two compiled artifacts, and what it costs providers.
 *
 * <p>This replaces the hand-ticked {@code isTextOnlyUpdate} checkbox that currently decides whether
 * publishing wipes every in-progress answer. Two things change as a result: the classification is
 * derived rather than asserted, and {@link #changedKeys} makes the reset surgical — a provider
 * loses the answers to the three questions that actually changed, not all forty.
 *
 * <p>Note what is <em>not</em> here: a count of affected applications. That is a real computation
 * against stored answers, and it belongs to whoever owns the answers. Reporting a flat number from
 * here would be the demo version of it.
 */
public record ChangeSet(
        ChangeClass changeClass,
        Set<String> addedKeys,
        Set<String> removedKeys,
        Set<String> changedKeys,
        List<String> notes) {

    public ChangeSet {
        addedKeys = addedKeys == null ? Set.of() : Set.copyOf(addedKeys);
        removedKeys = removedKeys == null ? Set.of() : Set.copyOf(removedKeys);
        changedKeys = changedKeys == null ? Set.of() : Set.copyOf(changedKeys);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** The first publish of a form — nothing exists to disturb. */
    public static ChangeSet firstPublish() {
        return new ChangeSet(ChangeClass.ADDITIVE, Set.of(), Set.of(), Set.of(), List.of("First publish."));
    }

    /**
     * Diffs two artifacts.
     *
     * @param previous null on first publish
     */
    /**
     * Steps whose field sequence differs, comparing only the fields both versions have.
     *
     * <p>Relative rather than absolute: adding a question shifts every later field along without
     * reordering anything, and reporting that as a reorder would fire on almost every additive
     * change. Same reasoning as {@code DriftCalculator}'s reorder check.
     */
    private static List<String> fieldOrderChanges(CompiledForm previous, CompiledForm next) {
        Map<String, List<String>> before = fieldNamesByStep(previous);
        Map<String, List<String>> after = fieldNamesByStep(next);

        List<String> reordered = new java.util.ArrayList<>();
        after.forEach((stepId, afterNames) -> {
            List<String> beforeNames = before.get(stepId);
            if (beforeNames == null) {
                return;
            }
            List<String> shared =
                    beforeNames.stream().filter(afterNames::contains).toList();
            List<String> sharedAfter =
                    afterNames.stream().filter(beforeNames::contains).toList();
            if (!shared.equals(sharedAfter)) {
                reordered.add(stepId);
            }
        });
        return reordered;
    }

    private static Map<String, List<String>> fieldNamesByStep(CompiledForm form) {
        Map<String, List<String>> byStep = new LinkedHashMap<>();
        form.steps()
                .forEach(step -> byStep.put(
                        step.id(),
                        step.fields().stream()
                                .map(CompiledForm.CompiledField::name)
                                .toList()));
        return byStep;
    }

    public static ChangeSet between(CompiledForm previous, CompiledForm next) {
        if (previous == null) {
            return firstPublish();
        }

        Map<String, CompiledForm.CompiledField> before = fieldsByName(previous);
        Map<String, CompiledForm.CompiledField> after = fieldsByName(next);

        Set<String> added = new LinkedHashSet<>(after.keySet());
        added.removeAll(before.keySet());

        Set<String> removed = new LinkedHashSet<>(before.keySet());
        removed.removeAll(after.keySet());

        Set<String> changed = new LinkedHashSet<>();
        Set<String> textOnly = new LinkedHashSet<>();

        for (String name : after.keySet()) {
            CompiledForm.CompiledField b = before.get(name);
            if (b == null) {
                continue;
            }
            CompiledForm.CompiledField a = after.get(name);
            if (structurallyDiffers(b, a)) {
                changed.add(name);
            } else if (textDiffers(b, a)) {
                textOnly.add(name);
            }
        }

        Set<String> stepsChanged = stepConditionChanges(previous, next);
        changed.addAll(stepsChanged);

        // Field order, which nothing above can see.
        //
        // Everything else here is keyed by field name, so a reorder produced added/removed/changed
        // all empty and published as "No changes." — while the sequence a provider is asked to fill
        // had visibly changed. Not classified as structural: answers are keyed by name, so reordering
        // invalidates nothing. It is a presentation change, which is exactly what TEXT means here.
        List<String> reorderedSteps = fieldOrderChanges(previous, next);

        List<String> notes = new java.util.ArrayList<>();
        ChangeClass classification;

        if (!changed.isEmpty() || !removed.isEmpty()) {
            classification = ChangeClass.STRUCTURAL;
            if (!removed.isEmpty()) {
                notes.add(removed.size() + " question(s) removed.");
            }
            if (!changed.isEmpty()) {
                notes.add(changed.size() + " question(s) changed in a way that invalidates existing answers.");
            }
        } else if (!added.isEmpty()) {
            // Added questions cannot invalidate an existing answer — but a newly REQUIRED one
            // blocks a provider who had already passed that step.
            boolean anyRequired = added.stream().map(after::get).anyMatch(f -> Boolean.TRUE.equals(f.required()));
            classification = anyRequired ? ChangeClass.STRUCTURAL : ChangeClass.ADDITIVE;
            notes.add(added.size() + " question(s) added" + (anyRequired ? ", at least one required." : "."));
        } else if (!textOnly.isEmpty() || !reorderedSteps.isEmpty()) {
            classification = ChangeClass.TEXT;
            if (!textOnly.isEmpty()) {
                notes.add(textOnly.size() + " label or help text change(s). No answers affected.");
            }
            if (!reorderedSteps.isEmpty()) {
                notes.add("Question order changed in " + reorderedSteps.size() + " step(s): " + reorderedSteps
                        + ". No answers affected.");
            }
        } else {
            classification = ChangeClass.TEXT;
            notes.add("No changes.");
        }

        return new ChangeSet(classification, added, removed, changed, notes);
    }

    /** Answers a provider loses on publish — the removed and the invalidated, never the untouched. */
    public Set<String> keysRequiringReset() {
        Set<String> out = new LinkedHashSet<>(changedKeys);
        out.addAll(removedKeys);
        return out;
    }

    public boolean requiresReset() {
        return changeClass == ChangeClass.STRUCTURAL && !keysRequiringReset().isEmpty();
    }

    // ------------------------------------------------------------------

    private static Map<String, CompiledForm.CompiledField> fieldsByName(CompiledForm form) {
        Map<String, CompiledForm.CompiledField> out = new LinkedHashMap<>();
        form.steps().forEach(step -> step.fields().forEach(field -> out.put(field.name(), field)));
        return out;
    }

    /** Changes that can invalidate an answer already given. */
    private static boolean structurallyDiffers(CompiledForm.CompiledField a, CompiledForm.CompiledField b) {
        return !Objects.equals(a.type(), b.type())
                || !Objects.equals(a.required(), b.required())
                || !Objects.equals(a.validation(), b.validation())
                || !Objects.equals(a.condition(), b.condition())
                || !Objects.equals(a.dependsOn(), b.dependsOn())
                || optionValuesDiffer(a, b);
    }

    /**
     * Only option <em>values</em> matter — a stored answer references a value. Relabelling
     * "Cardiology" to "Cardiology (Adult)" leaves every existing answer valid.
     */
    private static boolean optionValuesDiffer(CompiledForm.CompiledField a, CompiledForm.CompiledField b) {
        return !Objects.equals(optionValues(a), optionValues(b));
    }

    private static Set<String> optionValues(CompiledForm.CompiledField field) {
        if (field.options() == null) {
            return Set.of();
        }
        return field.options().stream()
                .map(CompiledForm.CompiledOption::value)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** Cosmetic changes — safe to publish over a provider mid-application. */
    private static boolean textDiffers(CompiledForm.CompiledField a, CompiledForm.CompiledField b) {
        return !Objects.equals(a.label(), b.label())
                || !Objects.equals(a.hint(), b.hint())
                || !Objects.equals(a.layout(), b.layout())
                || optionLabelsDiffer(a, b);
    }

    private static boolean optionLabelsDiffer(CompiledForm.CompiledField a, CompiledForm.CompiledField b) {
        return !Objects.equals(optionLabels(a), optionLabels(b));
    }

    private static Map<String, String> optionLabels(CompiledForm.CompiledField field) {
        if (field.options() == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        field.options().forEach(o -> out.put(o.value(), o.label()));
        return out;
    }

    /**
     * A step whose visibility rule changed can hide a step a provider already filled in, so every
     * answer inside it is at risk.
     */
    private static Set<String> stepConditionChanges(CompiledForm previous, CompiledForm next) {
        Map<String, CompiledForm.CompiledStep> before = new LinkedHashMap<>();
        previous.steps().forEach(s -> before.put(s.id(), s));

        Set<String> affected = new LinkedHashSet<>();
        for (CompiledForm.CompiledStep step : next.steps()) {
            CompiledForm.CompiledStep old = before.get(step.id());
            if (old != null && !Objects.equals(old.condition(), step.condition())) {
                step.fields().forEach(f -> affected.add(f.name()));
            }
        }
        return affected;
    }
}
