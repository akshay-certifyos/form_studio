package com.certifyos.forms.shared_kernel.expression;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The operator registry. This single table drives the parser's accepted set, the evaluator's
 * dispatch, and — mirrored in {@code packages/form-expression/registry.ts} — the condition
 * builder's operator dropdown and value-input shape.
 *
 * <p><b>Adding an operator is one entry here plus one entry in the TypeScript registry.</b> No UI
 * change, no rendering-engine change, no release gated on a Jira story. That is the whole point:
 * CP-38192 exists because adding {@code in} to a condition today is a code change.
 *
 * <p>{@code IN} asks whether the answer is one of a list. {@code CONTAINS} asks whether an
 * array-or-string answer includes a value. They are kept distinct so that neither operator's
 * meaning depends on the shape of the answer it is applied to.
 */
public enum Operator {
    EQ("eq", Arity.SINGLE),
    NEQ("neq", Arity.SINGLE),
    IN("in", Arity.LIST),
    NIN("nin", Arity.LIST),
    CONTAINS("contains", Arity.SINGLE),
    GT("gt", Arity.SINGLE),
    GTE("gte", Arity.SINGLE),
    LT("lt", Arity.SINGLE),
    LTE("lte", Arity.SINGLE),
    EXISTS("exists", Arity.NONE),
    EMPTY("empty", Arity.NONE),
    MATCHES("matches", Arity.SINGLE);

    /** How many operands the value slot carries — this is what picks the builder's value input. */
    public enum Arity {
        NONE,
        SINGLE,
        LIST
    }

    private final String wireName;
    private final Arity arity;

    Operator(String wireName, Arity arity) {
        this.wireName = wireName;
        this.arity = arity;
    }

    public String wireName() {
        return wireName;
    }

    public Arity arity() {
        return arity;
    }

    public static Optional<Operator> fromWireName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (Operator op : values()) {
            if (op.wireName.equals(name)) {
                return Optional.of(op);
            }
        }
        return Optional.empty();
    }

    /**
     * Applies this operator. {@code actual} is whatever the path resolved to, which is frequently
     * {@code null} — an unanswered question is the normal case, not an error.
     */
    public boolean test(Object actual, Object operand) {
        return switch (this) {
            case EXISTS -> isPresent(actual);
            case EMPTY -> !isPresent(actual);
            case EQ -> isPresent(actual) && valuesEqual(actual, operand);
                // Asymmetric with EQ on purpose: an unanswered field IS "not equal to MD".
                // Authors wanting "answered and different" compose NEQ with EXISTS.
            case NEQ -> !isPresent(actual) || !valuesEqual(actual, operand);
            case IN -> isPresent(actual) && asList(operand).stream().anyMatch(v -> valuesEqual(actual, v));
            case NIN -> !isPresent(actual) || asList(operand).stream().noneMatch(v -> valuesEqual(actual, v));
            case CONTAINS -> contains(actual, operand);
            case GT -> compare(actual, operand).map(c -> c > 0).orElse(false);
            case GTE -> compare(actual, operand).map(c -> c >= 0).orElse(false);
            case LT -> compare(actual, operand).map(c -> c < 0).orElse(false);
            case LTE -> compare(actual, operand).map(c -> c <= 0).orElse(false);
            case MATCHES -> matches(actual, operand);
        };
    }

    private static boolean isPresent(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof CharSequence cs) {
            return !cs.toString().isEmpty();
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        return true;
    }

    /**
     * Answers arrive from JSON as strings far more often than as numbers, so {@code "10"} and
     * {@code 10} must be the same answer. Falls back to string comparison otherwise.
     */
    private static boolean valuesEqual(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return String.valueOf(a).equals(String.valueOf(b));
        }
        Optional<BigDecimal> na = asNumber(a);
        Optional<BigDecimal> nb = asNumber(b);
        if (na.isPresent() && nb.isPresent()) {
            return na.get().compareTo(nb.get()) == 0;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static boolean contains(Object actual, Object operand) {
        if (!isPresent(actual)) {
            return false;
        }
        if (actual instanceof Collection<?> c) {
            return c.stream().anyMatch(v -> valuesEqual(v, operand));
        }
        return String.valueOf(actual).contains(String.valueOf(operand));
    }

    /**
     * Numeric when both sides parse as numbers, lexicographic otherwise — which is exactly right
     * for ISO-8601 dates and means the grammar needs no date type.
     */
    private static Optional<Integer> compare(Object actual, Object operand) {
        if (!isPresent(actual) || operand == null) {
            return Optional.empty();
        }
        Optional<BigDecimal> na = asNumber(actual);
        Optional<BigDecimal> nb = asNumber(operand);
        if (na.isPresent() && nb.isPresent()) {
            return Optional.of(na.get().compareTo(nb.get()));
        }
        return Optional.of(String.valueOf(actual).compareTo(String.valueOf(operand)));
    }

    private static boolean matches(Object actual, Object operand) {
        if (!isPresent(actual) || operand == null) {
            return false;
        }
        try {
            return Pattern.compile(String.valueOf(operand))
                    .matcher(String.valueOf(actual))
                    .find();
        } catch (PatternSyntaxException e) {
            // A malformed pattern is an authoring error the analyzer reports at compile time.
            // At runtime it must not take the form down.
            return false;
        }
    }

    private static Optional<BigDecimal> asNumber(Object v) {
        if (v instanceof Number n) {
            return Optional.of(new BigDecimal(n.toString()));
        }
        if (v instanceof CharSequence cs) {
            try {
                return Optional.of(new BigDecimal(cs.toString().trim()));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static List<?> asList(Object operand) {
        if (operand instanceof Collection<?> c) {
            return List.copyOf(c);
        }
        return operand == null ? List.of() : List.of(operand);
    }
}
