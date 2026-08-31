package com.certifyos.forms.form_authoring.domain.definition;

import com.certifyos.forms.shared_kernel.expression.Expression;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A tenant's form, as authored. Aggregate root.
 *
 * <p>A flat, ordered list of {@link Step}s — one grouping level, because the runtime artifact has
 * exactly one ({@code steps}). A step compiles 1:1 to a step, so a step's {@code visibleWhen} maps
 * straight onto the artifact's {@code condition} with nothing lost.
 *
 * <p>Mutable while it is a draft. Publishing compiles it into an immutable {@code FormVersion};
 * this record is never itself the published thing.
 *
 * @param namedConditions rules defined once and referenced from several steps. Inlined at compile
 *     time (P3), so editing one never changes a version that is already published.
 */
public record FormDefinition(
        String id,
        String tenantId,
        String formTemplateId,
        String name,
        String entityType,
        String sourceBlueprintId,
        Integer sourceBlueprintVersion,
        Map<String, NamedCondition> namedConditions,
        List<Step> steps,
        List<HardStop> hardStops,
        DefinitionStatus status) {

    public FormDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Form name is required");
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
        hardStops = hardStops == null ? List.of() : List.copyOf(hardStops);
        namedConditions = namedConditions == null ? Map.of() : Map.copyOf(namedConditions);
        status = status == null ? DefinitionStatus.DRAFT : status;

        // The invariant the design review surfaced. Two steps sharing a key share an answer
        // namespace, so the second silently destroys the first's answers.
        Set<String> seen = new LinkedHashSet<>();
        for (Step s : steps) {
            if (!seen.add(s.key().value())) {
                throw new IllegalArgumentException("Duplicate step key '" + s.key()
                        + "'. Step keys are answer namespaces — two steps sharing one would overwrite "
                        + "each other's answers.");
            }
        }
    }

    /** A rule defined once, referenced from several steps. */
    public record NamedCondition(String key, String label, Expression expression) {
        public NamedCondition {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("A named condition needs a key");
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException(
                        "A named condition needs a label — it is what the rule reads as in the UI");
            }
        }
    }

    /**
     * Blocks submission when a disqualifying answer is given.
     *
     * @param evaluateOn "next" mirrors the PRD: a hard stop fires on advancing, not mid-typing, so
     *     the provider sees it with context rather than as they answer.
     */
    public record HardStop(String key, Expression when, String message, String evaluateOn) {
        public HardStop {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("A hard stop must say why, or the provider is simply stuck");
            }
            evaluateOn = evaluateOn == null ? "next" : evaluateOn;
        }
    }

    public enum DefinitionStatus {
        DRAFT,
        READY,
        PUBLISHED
    }

    public static FormDefinition draft(String id, String tenantId, String name, String entityType) {
        return new FormDefinition(
                id,
                tenantId,
                null,
                name,
                entityType,
                null,
                null,
                Map.of(),
                List.of(),
                List.of(),
                DefinitionStatus.DRAFT);
    }

    // ------------------------------------------------------------------
    // queries
    // ------------------------------------------------------------------

    /** Steps that survive compilation, in order. */
    public List<Step> orderedSteps() {
        return steps.stream()
                .filter(Step::enabled)
                .sorted(Comparator.comparingInt(Step::order))
                .toList();
    }

    public Optional<Step> step(String stepKey) {
        return steps.stream().filter(s -> s.key().value().equals(stepKey)).findFirst();
    }

    /** Position of each step among the enabled ones — what makes forward references detectable. */
    public Map<String, Integer> stepOrdinals() {
        Map<String, Integer> ordinals = new LinkedHashMap<>();
        List<Step> ordered = orderedSteps();
        for (int i = 0; i < ordered.size(); i++) {
            ordinals.put(ordered.get(i).key().value(), i);
        }
        return ordinals;
    }

    public Map<String, Expression> namedConditionExpressions() {
        Map<String, Expression> out = new LinkedHashMap<>();
        namedConditions.forEach((k, v) -> out.put(k, v.expression()));
        return out;
    }

    // ------------------------------------------------------------------
    // authoring
    // ------------------------------------------------------------------

    /**
     * Places a section in the form.
     *
     * <p>The same section definition may be placed more than once — that is the whole point of the
     * step being a separate concept — so only the key is checked for uniqueness.
     */
    public FormDefinition placeStep(Step step) {
        if (step(step.key().value()).isPresent()) {
            throw new IllegalArgumentException("A step named '" + step.key() + "' is already in this form");
        }
        List<Step> next = new ArrayList<>(steps);
        next.add(step);
        return copyWithSteps(next);
    }

    public FormDefinition replaceStep(Step step) {
        if (step(step.key().value()).isEmpty()) {
            throw new IllegalArgumentException("No step named '" + step.key() + "' in this form");
        }
        return copyWithSteps(
                steps.stream().map(s -> s.key().equals(step.key()) ? step : s).toList());
    }

    public FormDefinition removeStep(String stepKey) {
        if (step(stepKey).isEmpty()) {
            throw new IllegalArgumentException("No step named '" + stepKey + "' in this form");
        }
        return copyWithSteps(
                steps.stream().filter(s -> !s.key().value().equals(stepKey)).toList());
    }

    /**
     * Adds a disqualifying rule.
     *
     * <p>Note that v0 of the compiler stores this and does not emit it — {@code FormCompiler} raises
     * a {@code HARD_STOP_NOT_COMPILED} notice so an author is told rather than left believing it
     * works. The data is kept faithfully so a later version can compile it without a migration.
     */
    public FormDefinition withHardStop(HardStop stop) {
        List<HardStop> next = new ArrayList<>(hardStops);
        next.add(stop);
        return new FormDefinition(
                id,
                tenantId,
                formTemplateId,
                name,
                entityType,
                sourceBlueprintId,
                sourceBlueprintVersion,
                namedConditions,
                steps,
                next,
                status);
    }

    public FormDefinition withNamedCondition(NamedCondition condition) {
        Map<String, NamedCondition> next = new LinkedHashMap<>(namedConditions);
        next.put(condition.key(), condition);
        return new FormDefinition(
                id,
                tenantId,
                formTemplateId,
                name,
                entityType,
                sourceBlueprintId,
                sourceBlueprintVersion,
                next,
                steps,
                hardStops,
                status);
    }

    /**
     * Removing a named condition that steps still reference would leave those steps unresolvable.
     * The analyzer would catch it at publish, but failing here tells the author immediately.
     */
    public FormDefinition removeNamedCondition(String conditionKey) {
        List<String> users = steps.stream()
                .filter(s -> referencesCondition(s.visibleWhen(), conditionKey))
                .map(s -> s.key().value())
                .toList();
        if (!users.isEmpty()) {
            throw new IllegalStateException("'" + conditionKey + "' is still used by: " + String.join(", ", users)
                    + ". Remove it from those steps first.");
        }
        Map<String, NamedCondition> next = new LinkedHashMap<>(namedConditions);
        next.remove(conditionKey);
        return new FormDefinition(
                id,
                tenantId,
                formTemplateId,
                name,
                entityType,
                sourceBlueprintId,
                sourceBlueprintVersion,
                next,
                steps,
                hardStops,
                status);
    }

    private boolean referencesCondition(Expression expr, String conditionKey) {
        if (expr == null) {
            return false;
        }
        return switch (expr) {
            case Expression.Ref ref -> ref.key().equals(conditionKey);
            case Expression.Not not -> referencesCondition(not.operand(), conditionKey);
            case Expression.All all -> all.operands().stream().anyMatch(e -> referencesCondition(e, conditionKey));
            case Expression.Any any -> any.operands().stream().anyMatch(e -> referencesCondition(e, conditionKey));
            case Expression.Some some -> referencesCondition(some.where(), conditionKey);
            case Expression.Every every -> referencesCondition(every.where(), conditionKey);
            case Expression.Leaf ignored -> false;
        };
    }

    private FormDefinition copyWithSteps(List<Step> next) {
        return new FormDefinition(
                id,
                tenantId,
                formTemplateId,
                name,
                entityType,
                sourceBlueprintId,
                sourceBlueprintVersion,
                namedConditions,
                next,
                hardStops,
                status);
    }
}
