package com.certifyos.forms.shared_kernel.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs every case in {@code form_poc_shared/conformance} against the Java evaluator.
 *
 * <p><b>These same fixtures are run by the TypeScript evaluator in
 * {@code packages/form-expression}.</b> Two implementations are unavoidable — the frontend must
 * evaluate for instant reveal (PRD §4.5) while the backend must evaluate to be the authority for
 * validation and hard stops. What is avoidable is the two drifting, and this suite is the only
 * thing preventing that. A change here is a change to the contract, not a change to a test.
 */
class ConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path sharedDir() {
        String configured = System.getProperty("form-poc.shared-dir");
        return configured != null ? Path.of(configured) : Path.of("..", "form_poc_shared");
    }

    static Stream<Case> cases() throws IOException {
        Path dir = sharedDir().resolve("conformance");
        List<Case> all = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonNode root = MAPPER.readTree(file.toFile());
                String suite = root.path("suite").asText(file.getFileName().toString());
                for (JsonNode c : root.path("cases")) {
                    all.add(new Case(suite, c.path("id").asText(), c));
                }
            }
        }
        return all.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void conforms(Case testCase) {
        JsonNode node = testCase.node();
        Expression expr = ExpressionParser.parse(node.get("expr"));
        EvaluationContext ctx = contextFrom(node.get("context"));

        if (node.has("expectedError")) {
            String code = node.get("expectedError").asText();
            if ("UNRESOLVED_REF".equals(code)) {
                assertThrows(
                        ExpressionEvaluator.UnresolvedReferenceException.class,
                        () -> ExpressionEvaluator.evaluate(expr, ctx),
                        testCase + " should have raised " + code);
                return;
            }
            throw new AssertionError("Unhandled expectedError code in fixture: " + code);
        }

        boolean expected = node.get("expected").asBoolean();
        boolean actual = ExpressionEvaluator.evaluate(expr, ctx);
        assertEquals(
                expected, actual, () -> testCase + " — " + node.path("note").asText(""));
    }

    @Test
    @DisplayName("the fixture set is actually loaded — a silent empty suite would pass vacuously")
    void fixturesAreLoaded() throws IOException {
        List<Case> all = cases().toList();
        assertTrue(all.size() >= 80, "expected the full conformance suite, found " + all.size());
        assertTrue(
                all.stream().anyMatch(c -> c.suite().equals("quantifiers")),
                "quantifier suite missing — some/every would be untested");
        assertFalse(all.stream().anyMatch(c -> c.id().isBlank()), "every case needs an id for failure attribution");
    }

    // ---------------------------------------------------------------------
    // fixture context -> EvaluationContext
    // ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static EvaluationContext contextFrom(JsonNode ctx) {
        if (ctx == null || ctx.isNull()) {
            return EvaluationContext.ofAnswers(Map.of());
        }
        Map<String, Object> answers = (Map<String, Object>) objectOf(ctx.get("answers"));
        Map<String, Object> viewer = (Map<String, Object>) objectOf(ctx.get("viewer"));
        Map<String, Object> entity = (Map<String, Object>) objectOf(ctx.get("entity"));
        Map<String, Object> tenant = (Map<String, Object>) objectOf(ctx.get("tenant"));

        Map<String, List<Map<String, Object>>> repeats = new LinkedHashMap<>();
        JsonNode repeatsNode = ctx.get("repeats");
        if (repeatsNode != null && repeatsNode.isObject()) {
            repeatsNode.fields().forEachRemaining(entry -> {
                List<Map<String, Object>> items = new ArrayList<>();
                entry.getValue().forEach(item -> items.add((Map<String, Object>) objectOf(item)));
                repeats.put(entry.getKey(), items);
            });
        }

        Map<String, Expression> named = new LinkedHashMap<>();
        JsonNode namedNode = ctx.get("namedConditions");
        if (namedNode != null && namedNode.isObject()) {
            namedNode
                    .fields()
                    .forEachRemaining(entry -> named.put(entry.getKey(), ExpressionParser.parse(entry.getValue())));
        }

        return new EvaluationContext(answers, repeats, viewer, entity, tenant, named);
    }

    private static Object objectOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return ExpressionParser.toJavaValue(node);
    }

    /** Named so parameterized failures identify the fixture rather than an index. */
    record Case(String suite, String id, JsonNode node) {
        @Override
        public String toString() {
            return suite + "/" + id;
        }
    }
}
