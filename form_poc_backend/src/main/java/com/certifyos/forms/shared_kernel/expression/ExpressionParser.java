package com.certifyos.forms.shared_kernel.expression;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * JSON to {@link Expression}.
 *
 * <p>Rejects unknown operators and malformed nodes with a typed error rather than degrading — a
 * condition that silently parses to "always visible" is the failure mode this whole project
 * exists to remove.
 */
public final class ExpressionParser {

    private ExpressionParser() {}

    public static Expression parse(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isObject()) {
            throw new MalformedExpressionException("Expression must be an object, got: " + node.getNodeType());
        }

        if (node.has("all")) {
            return new Expression.All(parseOperands(node.get("all"), "all"));
        }
        if (node.has("any")) {
            return new Expression.Any(parseOperands(node.get("any"), "any"));
        }
        if (node.has("not")) {
            return new Expression.Not(parse(node.get("not")));
        }
        if (node.has("ref")) {
            JsonNode ref = node.get("ref");
            if (!ref.isTextual() || ref.asText().isBlank()) {
                throw new MalformedExpressionException("ref must be a non-empty string");
            }
            return new Expression.Ref(ref.asText());
        }
        if (node.has("some")) {
            return new Expression.Some(quantifierScope(node, "some"), parseWhere(node));
        }
        if (node.has("every")) {
            return new Expression.Every(quantifierScope(node, "every"), parseWhere(node));
        }
        if (node.has("field")) {
            return parseLeaf(node);
        }

        throw new MalformedExpressionException("Expression matches no grammar production. Expected one of "
                + "all / any / not / ref / some / every / field, got keys: " + fieldNames(node));
    }

    private static List<Expression> parseOperands(JsonNode arrayNode, String keyword) {
        if (!arrayNode.isArray()) {
            throw new MalformedExpressionException(keyword + " must be an array");
        }
        List<Expression> operands = new ArrayList<>();
        arrayNode.forEach(child -> operands.add(parse(child)));
        return operands;
    }

    private static String quantifierScope(JsonNode node, String keyword) {
        JsonNode scope = node.get(keyword);
        if (!scope.isTextual() || scope.asText().isBlank()) {
            throw new MalformedExpressionException(keyword + " must name a repeating step");
        }
        return scope.asText();
    }

    private static Expression parseWhere(JsonNode node) {
        if (!node.has("where")) {
            throw new MalformedExpressionException("A quantifier requires a where clause");
        }
        return parse(node.get("where"));
    }

    private static Expression parseLeaf(JsonNode node) {
        JsonNode field = node.get("field");
        if (!field.isTextual() || field.asText().isBlank()) {
            throw new MalformedExpressionException("field must be a non-empty string");
        }
        String opName = node.path("op").asText(null);
        Operator operator = Operator.fromWireName(opName)
                .orElseThrow(() -> new MalformedExpressionException("Unknown operator: " + opName));

        Object value = node.has("value") ? toJavaValue(node.get("value")) : null;

        if (operator.arity() == Operator.Arity.LIST && value != null && !(value instanceof List)) {
            throw new MalformedExpressionException("Operator '" + opName + "' takes a list value, got: "
                    + node.get("value").getNodeType());
        }
        if (operator.arity() == Operator.Arity.NONE && value != null) {
            throw new MalformedExpressionException("Operator '" + opName + "' takes no value");
        }
        if (operator.arity() != Operator.Arity.NONE && value == null) {
            throw new MalformedExpressionException("Operator '" + opName + "' requires a value");
        }

        return new Expression.Leaf(field.asText(), operator, value);
    }

    /** Jackson node to plain Java, so the evaluator never touches Jackson types. */
    static Object toJavaValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            // numberValue() keeps the type Jackson chose (Integer for an int-sized value, Long
            // beyond that). Widening everything to long would make a saved-and-reloaded definition
            // differ from a freshly compiled one, and the change-diff compares artifacts by value —
            // so an untouched field would read as changed.
            return node.numberValue();
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            node.forEach(child -> out.add(toJavaValue(child)));
            return out;
        }
        if (node.isObject()) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                out.put(entry.getKey(), toJavaValue(entry.getValue()));
            }
            return out;
        }
        return node.asText();
    }

    private static String fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return String.join(", ", names);
    }

    /** Thrown when JSON does not match the grammar. Surfaced by the compiler, never swallowed. */
    public static final class MalformedExpressionException extends RuntimeException {
        public MalformedExpressionException(String message) {
            super(message);
        }
    }
}
