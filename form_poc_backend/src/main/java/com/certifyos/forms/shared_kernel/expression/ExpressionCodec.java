package com.certifyos.forms.shared_kernel.expression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * Expression to JSON and back.
 *
 * <p>One codec rather than two, because three separate callers need the same conversion — the
 * compiler emitting an artifact, Mongo persisting a definition, and the conformance tests reading
 * fixtures. Two of those drifting apart would mean a condition that round-trips through the database
 * differently from how it reaches the renderer, which is the kind of bug that only shows up in
 * production.
 *
 * <p>{@link #write} is the exact inverse of {@link ExpressionParser#parse}; the round-trip is pinned
 * by test.
 */
public final class ExpressionCodec {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private ExpressionCodec() {}

    public static Expression read(JsonNode node) {
        return ExpressionParser.parse(node);
    }

    /** @return null for a null expression, so "no condition" stays absent rather than becoming {} */
    public static JsonNode write(Expression expression) {
        if (expression == null) {
            return null;
        }
        return switch (expression) {
            case Expression.All all -> group("all", all.operands());
            case Expression.Any any -> group("any", any.operands());
            case Expression.Not not -> {
                ObjectNode node = JSON.objectNode();
                node.set("not", write(not.operand()));
                yield node;
            }
            case Expression.Ref ref -> {
                ObjectNode node = JSON.objectNode();
                node.put("ref", ref.key());
                yield node;
            }
            case Expression.Some some -> quantifier("some", some.scope(), some.where());
            case Expression.Every every -> quantifier("every", every.scope(), every.where());
            case Expression.Leaf leaf -> {
                ObjectNode node = JSON.objectNode();
                node.put("field", leaf.path());
                node.put("op", leaf.operator().wireName());
                if (leaf.value() != null) {
                    node.set("value", valueToJson(leaf.value()));
                }
                yield node;
            }
        };
    }

    private static ObjectNode group(String keyword, List<Expression> operands) {
        ObjectNode node = JSON.objectNode();
        ArrayNode array = node.putArray(keyword);
        operands.forEach(operand -> array.add(write(operand)));
        return node;
    }

    private static ObjectNode quantifier(String keyword, String scope, Expression where) {
        ObjectNode node = JSON.objectNode();
        node.put(keyword, scope);
        node.set("where", write(where));
        return node;
    }

    private static JsonNode valueToJson(Object value) {
        if (value instanceof List<?> list) {
            ArrayNode array = JSON.arrayNode();
            list.forEach(item -> array.add(valueToJson(item)));
            return array;
        }
        if (value instanceof Boolean b) {
            return JSON.booleanNode(b);
        }
        if (value instanceof Integer i) {
            return JSON.numberNode(i);
        }
        if (value instanceof Long l) {
            return JSON.numberNode(l);
        }
        if (value instanceof Double d) {
            return JSON.numberNode(d);
        }
        if (value instanceof java.math.BigDecimal bd) {
            return JSON.numberNode(bd);
        }
        if (value instanceof Number n) {
            return JSON.numberNode(n.doubleValue());
        }
        return JSON.textNode(String.valueOf(value));
    }
}
