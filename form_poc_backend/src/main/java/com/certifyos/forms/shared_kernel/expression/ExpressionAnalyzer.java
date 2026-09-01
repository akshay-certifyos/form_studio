package com.certifyos.forms.shared_kernel.expression;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Static analysis of an expression, run at compile time.
 *
 * <p>This is where the design's claim that "no dependency graph needs storing" is cashed out: every
 * check below — dangling references, forward references, cycles, values outside their option set —
 * is a single pass over the expression tree building a transient set. Compilation is what makes a
 * persistent graph unnecessary, because at compile time the whole resolved form is in memory and
 * afterwards the artifact is frozen.
 *
 * <p><b>Returns findings rather than throwing.</b> An author fixing one problem at a time, with the
 * next one only revealed after another publish attempt, is a miserable loop — the compiler collects
 * everything into one report instead.
 */
public final class ExpressionAnalyzer {

    private ExpressionAnalyzer() {}

    /** Why an expression will not compile. Codes are for logs; messages are for authors. */
    public enum Code {
        UNRESOLVED_REF,
        NAMED_CONDITION_CYCLE,
        DANGLING_PATH,
        FORWARD_REFERENCE,
        SELF_REFERENCE,
        VALUE_NOT_IN_OPTION_SET,
        UNKNOWN_REPEAT_SCOPE,
        ITEM_PATH_OUTSIDE_QUANTIFIER
    }

    /**
     * @param code machine-readable, for tests and logs
     * @param message written for a credentialing ops person, not an engineer
     * @param path the offending path or key, so the UI can pin it to a node
     */
    public record Finding(Code code, String message, String path) {}

    /**
     * Where a condition is attached, which changes what counts as a legal reference.
     *
     * <p>A <b>step</b> cannot decide whether to appear from an answer inside itself — the provider
     * has not reached it yet. A <b>question</b> absolutely can depend on a sibling in the same step:
     * "show the certification number once they say they are board certified" is the single most
     * common conditional pattern in a credentialing form.
     */
    public enum Level {
        STEP,
        QUESTION
    }

    /**
     * @param expression the condition to analyze
     * @param ownerOrdinal index of the step this condition belongs to; conditions may only
     *     reference questions in earlier steps. Pass {@link Integer#MAX_VALUE} for form-level
     *     conditions with no position of their own.
     */
    public static List<Finding> analyze(Expression expression, int ownerOrdinal, AnalysisScope scope) {
        return analyze(expression, ownerOrdinal, scope, Level.STEP);
    }

    public static List<Finding> analyze(Expression expression, int ownerOrdinal, AnalysisScope scope, Level level) {
        List<Finding> findings = new ArrayList<>();
        walk(expression, ownerOrdinal, scope, findings, false, new ArrayDeque<>(), level);
        return List.copyOf(findings);
    }

    /** All answer paths an expression reads, following refs. Used to build the dependency view. */
    public static Set<String> referencedPaths(Expression expression, AnalysisScope scope) {
        Set<String> paths = new LinkedHashSet<>();
        collectPaths(expression, scope, paths, new ArrayDeque<>());
        return paths;
    }

    /**
     * Named conditions an expression references, directly or through another ref.
     *
     * <p>Does <em>not</em> follow refs into their definitions to collect further refs — a ref chain
     * is legal but the analyzer already rejects a cycle, so this reports what the expression itself
     * names. That is what a rules inventory wants: "this step uses specialtyExempt", not the
     * transitive closure of everything specialtyExempt happens to be built from.
     */
    public static Set<String> referencedConditions(Expression expression) {
        Set<String> keys = new LinkedHashSet<>();
        collectRefs(expression, keys);
        return keys;
    }

    /**
     * Operators an expression uses, in first-seen order.
     *
     * <p>Exists for the rules inventory, and it earns its place by answering a question that is
     * otherwise guesswork: which operators are actually in use across a tenant. That decides what a
     * migration has to support, and it is the difference between "the grammar has twelve operators"
     * and "eleven of them have never been used".
     */
    public static Set<Operator> operatorsUsed(Expression expression) {
        Set<Operator> operators = new LinkedHashSet<>();
        collectOperators(expression, operators);
        return operators;
    }

    private static void collectRefs(Expression expr, Set<String> keys) {
        if (expr == null) {
            return;
        }
        switch (expr) {
            case Expression.Ref ref -> keys.add(ref.key());
            case Expression.Not not -> collectRefs(not.operand(), keys);
            case Expression.All all -> all.operands().forEach(e -> collectRefs(e, keys));
            case Expression.Any any -> any.operands().forEach(e -> collectRefs(e, keys));
            case Expression.Some some -> collectRefs(some.where(), keys);
            case Expression.Every every -> collectRefs(every.where(), keys);
            case Expression.Leaf ignored -> {}
        }
    }

    private static void collectOperators(Expression expr, Set<Operator> operators) {
        if (expr == null) {
            return;
        }
        switch (expr) {
            case Expression.Leaf leaf -> operators.add(leaf.operator());
            case Expression.Not not -> collectOperators(not.operand(), operators);
            case Expression.All all -> all.operands().forEach(e -> collectOperators(e, operators));
            case Expression.Any any -> any.operands().forEach(e -> collectOperators(e, operators));
            case Expression.Some some -> collectOperators(some.where(), operators);
            case Expression.Every every -> collectOperators(every.where(), operators);
                // A ref's operators belong to the named condition, which the inventory lists in its own
                // right. Counting them here would double-count every shared rule.
            case Expression.Ref ignored -> {}
        }
    }

    // ------------------------------------------------------------------
    // walk
    // ------------------------------------------------------------------

    private static void walk(
            Expression expr,
            int ownerOrdinal,
            AnalysisScope scope,
            List<Finding> findings,
            boolean insideQuantifier,
            Deque<String> refStack,
            Level level) {

        if (expr == null) {
            return;
        }
        switch (expr) {
            case Expression.All all -> all.operands()
                    .forEach(e -> walk(e, ownerOrdinal, scope, findings, insideQuantifier, refStack, level));
            case Expression.Any any -> any.operands()
                    .forEach(e -> walk(e, ownerOrdinal, scope, findings, insideQuantifier, refStack, level));
            case Expression.Not not -> walk(
                    not.operand(), ownerOrdinal, scope, findings, insideQuantifier, refStack, level);
            case Expression.Ref ref -> walkRef(ref, ownerOrdinal, scope, findings, insideQuantifier, refStack, level);
            case Expression.Some some -> walkQuantifier(
                    some.scope(), some.where(), ownerOrdinal, scope, findings, refStack, level);
            case Expression.Every every -> walkQuantifier(
                    every.scope(), every.where(), ownerOrdinal, scope, findings, refStack, level);
            case Expression.Leaf leaf -> walkLeaf(leaf, ownerOrdinal, scope, findings, insideQuantifier, level);
        }
    }

    private static void walkRef(
            Expression.Ref ref,
            int ownerOrdinal,
            AnalysisScope scope,
            List<Finding> findings,
            boolean insideQuantifier,
            Deque<String> refStack,
            Level level) {

        if (refStack.contains(ref.key())) {
            findings.add(new Finding(
                    Code.NAMED_CONDITION_CYCLE,
                    "The condition \"" + ref.key() + "\" refers back to itself, so it can never be evaluated.",
                    ref.key()));
            return;
        }
        Expression resolved = scope.namedConditions().get(ref.key());
        if (resolved == null) {
            findings.add(new Finding(
                    Code.UNRESOLVED_REF,
                    "This rule uses a saved condition called \"" + ref.key() + "\", which no longer exists.",
                    ref.key()));
            return;
        }
        refStack.push(ref.key());
        walk(resolved, ownerOrdinal, scope, findings, insideQuantifier, refStack, level);
        refStack.pop();
    }

    private static void walkQuantifier(
            String repeatScope,
            Expression where,
            int ownerOrdinal,
            AnalysisScope scope,
            List<Finding> findings,
            Deque<String> refStack,
            Level level) {

        if (!scope.repeatScopes().contains(repeatScope)) {
            findings.add(new Finding(
                    Code.UNKNOWN_REPEAT_SCOPE,
                    "This rule asks about each \"" + repeatScope
                            + "\", but that step does not exist or does not repeat.",
                    repeatScope));
        }
        walk(where, ownerOrdinal, scope, findings, true, refStack, level);
    }

    private static void walkLeaf(
            Expression.Leaf leaf,
            int ownerOrdinal,
            AnalysisScope scope,
            List<Finding> findings,
            boolean insideQuantifier,
            Level level) {

        String path = leaf.path();

        if (AnalysisScope.isItemPath(path)) {
            if (!insideQuantifier) {
                findings.add(new Finding(
                        Code.ITEM_PATH_OUTSIDE_QUANTIFIER,
                        "\"" + path + "\" refers to one entry of a repeating step, but this rule is not "
                                + "asking about each entry.",
                        path));
            }
            return;
        }

        // viewer / entity / tenant are not answers, so they have no position and cannot dangle.
        if (AnalysisScope.isContextPath(path)) {
            return;
        }

        if (!scope.knowsPath(path)) {
            findings.add(new Finding(
                    Code.DANGLING_PATH, "This rule depends on a question that is not in this form any more.", path));
            return;
        }

        int referencedOrdinal = scope.ordinalOf(path).orElse(Integer.MIN_VALUE);
        if (referencedOrdinal == ownerOrdinal) {
            // Legal for a question — "show the certification number once they say they are board
            // certified" is the commonest conditional pattern there is. Illegal for a step, which
            // would be deciding whether to appear from an answer nobody can have given yet.
            if (level == Level.STEP) {
                findings.add(new Finding(
                        Code.SELF_REFERENCE,
                        "A step cannot decide whether to appear based on an answer inside itself.",
                        path));
            }
        } else if (referencedOrdinal > ownerOrdinal) {
            findings.add(new Finding(
                    Code.FORWARD_REFERENCE,
                    "This rule depends on a question that comes later in the form, so it can never be "
                            + "answered in time.",
                    path));
        }

        checkValueAgainstOptionSet(leaf, scope, findings);
    }

    /**
     * Catches the condition that can never fire because it tests for a value the question does not
     * offer — typically a payer's option list changing under a rule that was written against the
     * old one.
     */
    private static void checkValueAgainstOptionSet(Expression.Leaf leaf, AnalysisScope scope, List<Finding> findings) {

        if (leaf.operator().arity() == Operator.Arity.NONE || leaf.value() == null) {
            return;
        }
        // A regex is matched against the value, not drawn from the option set.
        if (leaf.operator() == Operator.MATCHES) {
            return;
        }
        scope.valuesFor(leaf.path()).ifPresent(allowed -> {
            List<Object> candidates = leaf.value() instanceof List<?> list ? List.copyOf(list) : List.of(leaf.value());
            for (Object candidate : candidates) {
                boolean known = allowed.stream().anyMatch(a -> String.valueOf(a).equals(String.valueOf(candidate)));
                if (!known) {
                    findings.add(new Finding(
                            Code.VALUE_NOT_IN_OPTION_SET,
                            "\"" + candidate + "\" is not one of the answers this question offers, so this "
                                    + "rule can never match.",
                            leaf.path()));
                }
            }
        });
    }

    // ------------------------------------------------------------------
    // path collection
    // ------------------------------------------------------------------

    private static void collectPaths(Expression expr, AnalysisScope scope, Set<String> out, Deque<String> refStack) {
        if (expr == null) {
            return;
        }
        switch (expr) {
            case Expression.All all -> all.operands().forEach(e -> collectPaths(e, scope, out, refStack));
            case Expression.Any any -> any.operands().forEach(e -> collectPaths(e, scope, out, refStack));
            case Expression.Not not -> collectPaths(not.operand(), scope, out, refStack);
            case Expression.Some some -> collectPaths(some.where(), scope, out, refStack);
            case Expression.Every every -> collectPaths(every.where(), scope, out, refStack);
            case Expression.Ref ref -> {
                if (!refStack.contains(ref.key())) {
                    refStack.push(ref.key());
                    collectPaths(scope.namedConditions().get(ref.key()), scope, out, refStack);
                    refStack.pop();
                }
            }
            case Expression.Leaf leaf -> {
                if (!AnalysisScope.isItemPath(leaf.path()) && !AnalysisScope.isContextPath(leaf.path())) {
                    out.add(leaf.path());
                }
            }
        }
    }
}
