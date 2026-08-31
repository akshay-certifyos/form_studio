package com.certifyos.forms.shared_kernel.expression;

import java.util.List;
import java.util.Map;

/**
 * Evaluates an {@link Expression} against an {@link EvaluationContext}.
 *
 * <p>Stateless and side-effect free, so it is safe to share and trivial to test. Its behaviour is
 * pinned by {@code form_poc_shared/conformance} — the same fixtures the TypeScript evaluator runs.
 * Those fixtures are the contract between the two implementations; shared code is not an option
 * because the frontend must evaluate for instant reveal while the backend must evaluate to be the
 * authority.
 */
public final class ExpressionEvaluator {

    private ExpressionEvaluator() {}

    /** A missing expression means "no condition", which is visible — never an error. */
    public static boolean evaluate(Expression expression, EvaluationContext context) {
        if (expression == null) {
            return true;
        }
        return switch (expression) {
            case Expression.All all -> all.operands().stream().allMatch(e -> evaluate(e, context));
            case Expression.Any any -> any.operands().stream().anyMatch(e -> evaluate(e, context));
            case Expression.Not not -> !evaluate(not.operand(), context);
            case Expression.Ref ref -> evaluate(resolveRef(ref, context), context);
            case Expression.Some some -> quantify(some.scope(), some.where(), context, true);
            case Expression.Every every -> quantify(every.scope(), every.where(), context, false);
            case Expression.Leaf leaf -> leaf.operator().test(context.resolve(leaf.path()), leaf.value());
        };
    }

    /**
     * An unresolved reference raises rather than defaulting. Defaulting to true would leave a step
     * permanently visible with no diagnostic anywhere — a silent failure in the exact place a
     * silent failure is most expensive. The analyzer catches this at compile time so it should
     * never reach runtime.
     */
    private static Expression resolveRef(Expression.Ref ref, EvaluationContext context) {
        Expression resolved = context.namedConditions().get(ref.key());
        if (resolved == null) {
            throw new UnresolvedReferenceException(ref.key());
        }
        return resolved;
    }

    /**
     * {@code some} over an empty collection is false; {@code every} over an empty collection is
     * vacuously true. Implementations that get the empty cases wrong are the classic bug, which is
     * why the conformance suite pins all four combinations.
     */
    private static boolean quantify(String scope, Expression where, EvaluationContext context, boolean existential) {
        List<Map<String, Object>> items = context.repeatItems(scope);
        if (items.isEmpty()) {
            return !existential;
        }
        return existential
                ? items.stream().anyMatch(item -> evaluate(where, context.withItem(item)))
                : items.stream().allMatch(item -> evaluate(where, context.withItem(item)));
    }

    /** Thrown when a {@link Expression.Ref} names a condition the form does not define. */
    public static final class UnresolvedReferenceException extends RuntimeException {
        private final String key;

        public UnresolvedReferenceException(String key) {
            super("Unresolved named condition: " + key);
            this.key = key;
        }

        public String key() {
            return key;
        }
    }
}
