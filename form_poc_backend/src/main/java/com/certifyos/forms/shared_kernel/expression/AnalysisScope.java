package com.certifyos.forms.shared_kernel.expression;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the analyzer needs to know about the form surrounding an expression.
 *
 * <p>Deliberately a plain record rather than a reference to the form_authoring domain — the shared
 * kernel must not depend on a bounded context. The compiler builds one of these from the form
 * definition and hands it over.
 *
 * @param pathOrdinal every answer path in the form, mapped to the index of the step that owns it.
 *     Ordinals are what make forward references detectable: a condition may only look backwards.
 * @param allowedValues answer paths backed by an option set, mapped to that set's values. Lets the
 *     analyzer catch a condition testing for a value the question can never hold.
 * @param repeatScopes step keys that are actually repeating, so a quantifier over a non-repeating
 *     step is caught rather than silently evaluating false forever.
 * @param namedConditions form-level conditions available to {@link Expression.Ref}.
 */
public record AnalysisScope(
        Map<String, Integer> pathOrdinal,
        Map<String, Set<Object>> allowedValues,
        Set<String> repeatScopes,
        Map<String, Expression> namedConditions) {

    public AnalysisScope {
        pathOrdinal = pathOrdinal == null ? Map.of() : Map.copyOf(pathOrdinal);
        allowedValues = allowedValues == null ? Map.of() : Map.copyOf(allowedValues);
        repeatScopes = repeatScopes == null ? Set.of() : Set.copyOf(repeatScopes);
        namedConditions = namedConditions == null ? Map.of() : Map.copyOf(namedConditions);
    }

    public static AnalysisScope empty() {
        return new AnalysisScope(Map.of(), Map.of(), Set.of(), Map.of());
    }

    /** True for {@code viewer.*}, {@code entity.*}, {@code tenant.*} — not answers, so not ordered. */
    public static boolean isContextPath(String path) {
        if (path == null) {
            return false;
        }
        int dot = path.indexOf('.');
        return dot > 0 && EvaluationContext.RESERVED_NAMESPACES.contains(path.substring(0, dot));
    }

    public static boolean isItemPath(String path) {
        return path != null && path.startsWith("@item.");
    }

    public Optional<Integer> ordinalOf(String path) {
        return Optional.ofNullable(pathOrdinal.get(path));
    }

    public boolean knowsPath(String path) {
        return pathOrdinal.containsKey(path);
    }

    /** Values a question may hold, when it is backed by an option set. */
    public Optional<Set<Object>> valuesFor(String path) {
        return Optional.ofNullable(allowedValues.get(path));
    }
}
