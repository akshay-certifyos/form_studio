package com.certifyos.forms.shared_kernel.expression;

import java.util.List;

/**
 * One recursive grammar with many attachment points (design doc §3 P6).
 *
 * <p>The same node type expresses step visibility, question visibility, hard stops and option
 * filtering — and, when they land, requiredness, defaults and validation. Visibility is the only
 * attachment point evaluated in v0; the rest are reserved so the next four kinds of rule arrive as
 * config rather than as code.
 *
 * <p><b>{@code All}/{@code Any} combine expressions. {@code Some}/{@code Every} quantify over
 * repeat items.</b> These are different operations and are deliberately named differently — the
 * design review flagged that conflating them would be a permanent source of confusion.
 */
public sealed interface Expression
        permits Expression.All,
                Expression.Any,
                Expression.Not,
                Expression.Ref,
                Expression.Some,
                Expression.Every,
                Expression.Leaf {

    /** Boolean AND over expressions. An empty group is vacuously true. */
    record All(List<Expression> operands) implements Expression {
        public All {
            operands = List.copyOf(operands);
        }
    }

    /** Boolean OR over expressions. An empty group is false. */
    record Any(List<Expression> operands) implements Expression {
        public Any {
            operands = List.copyOf(operands);
        }
    }

    /**
     * Negation. Authors never write this directly — the {@code Hide} verb compiles to it, so a
     * multi-row group never confronts anyone with De Morgan.
     */
    record Not(Expression operand) implements Expression {}

    /**
     * Reference to a named condition on the form definition. Defined once, referenced from several
     * steps, and inlined at compile time (P3) so editing one never changes a published version.
     */
    record Ref(String key) implements Expression {}

    /** Existential quantifier over the items of a repeating step. Empty collection is false. */
    record Some(String scope, Expression where) implements Expression {}

    /** Universal quantifier over the items of a repeating step. Empty collection is true. */
    record Every(String scope, Expression where) implements Expression {}

    /**
     * A comparison against a single addressable value.
     *
     * @param path see {@link Path} — an answer, a repeat item field, or non-answer context
     * @param operator from the shared registry
     * @param value absent for arity NONE, a scalar for SINGLE, a list for LIST
     */
    record Leaf(String path, Operator operator, Object value) implements Expression {}
}
