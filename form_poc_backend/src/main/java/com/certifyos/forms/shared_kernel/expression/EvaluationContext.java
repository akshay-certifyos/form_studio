package com.certifyos.forms.shared_kernel.expression;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Everything an expression may read.
 *
 * <p>Answers are not a sufficient variable space: production already gates steps by viewer role
 * via {@code FormConfig.audience}, which is not an answer. Deciding that now costs a field name;
 * retrofitting it later would mean rewriting every stored expression.
 *
 * @param answers keyed by placement-scoped path, {@code <stepKey>.<questionKey>}. Two steps
 *     composing the same section definition therefore hold independent answers — without this,
 *     Practice Location and Billing Address share one {@code line1} slot and the second write
 *     destroys the first.
 * @param repeats items of each repeating step, keyed by {@code stepKey}; each item is keyed by
 *     bare question key and addressed through {@code @item.}
 * @param viewer who is looking — {@code role}, etc.
 * @param entity the practitioner or facility profile
 * @param tenant tenant configuration
 * @param namedConditions form-level reusable conditions, resolved by {@link Expression.Ref}
 */
public record EvaluationContext(
        Map<String, Object> answers,
        Map<String, List<Map<String, Object>>> repeats,
        Map<String, Object> viewer,
        Map<String, Object> entity,
        Map<String, Object> tenant,
        Map<String, Expression> namedConditions) {

    /** Namespaces that can never be a {@code stepKey}. Enforced by the analyzer. */
    public static final List<String> RESERVED_NAMESPACES = List.of("viewer", "entity", "tenant");

    public EvaluationContext {
        answers = answers == null ? Map.of() : Map.copyOf(answers);
        repeats = repeats == null ? Map.of() : Map.copyOf(repeats);
        viewer = viewer == null ? Map.of() : Map.copyOf(viewer);
        entity = entity == null ? Map.of() : Map.copyOf(entity);
        tenant = tenant == null ? Map.of() : Map.copyOf(tenant);
        namedConditions = namedConditions == null ? Map.of() : Map.copyOf(namedConditions);
    }

    public static EvaluationContext ofAnswers(Map<String, Object> answers) {
        return new EvaluationContext(answers, null, null, null, null, null);
    }

    /**
     * Resolves a path. A two-segment path is <em>always</em> an answer, even when its first
     * segment collides with a reserved namespace — the namespaces win only for their own prefix,
     * and the analyzer forbids a step being named after one, so the collision cannot arise in a
     * form that compiles.
     */
    public Object resolve(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int dot = path.indexOf('.');
        if (dot < 0) {
            return answers.get(path);
        }
        String head = path.substring(0, dot);
        String tail = path.substring(dot + 1);

        return switch (head) {
            case "viewer" -> descend(viewer, tail);
            case "entity" -> descend(entity, tail);
            case "tenant" -> descend(tenant, tail);
            default -> answers.get(path);
        };
    }

    /** Walks a dotted path into a nested map, e.g. {@code entity.address.state}. */
    private static Object descend(Map<String, Object> root, String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    public List<Map<String, Object>> repeatItems(String scope) {
        return repeats.getOrDefault(scope, Collections.emptyList());
    }

    /** A copy with {@code @item.*} bound to one repetition, for use inside a quantifier. */
    public EvaluationContext withItem(Map<String, Object> item) {
        Map<String, Object> merged = new java.util.HashMap<>(answers);
        if (item != null) {
            item.forEach((k, v) -> merged.put("@item." + k, v));
        }
        return new EvaluationContext(merged, repeats, viewer, entity, tenant, namedConditions);
    }
}
