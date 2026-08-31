package com.certifyos.forms.shared_kernel.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The codec must be a true inverse.
 *
 * <p>Three callers convert expressions: the compiler emitting an artifact, Mongo persisting a
 * definition, and the fixtures. If write and read disagree even slightly, a condition round-tripped
 * through the database would reach the renderer differently from one compiled directly — a bug that
 * only appears after a save and reload, which is the worst place to find it.
 *
 * <p>Rather than invent cases, this re-reads the whole conformance suite: every expression the
 * grammar is contractually required to support must survive a round trip unchanged.
 */
class ExpressionCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path sharedDir() {
        String configured = System.getProperty("form-poc.shared-dir");
        return configured != null ? Path.of(configured) : Path.of("..", "form_poc_shared");
    }

    /** Every {@code expr} in the conformance suite, so coverage tracks the contract automatically. */
    static Stream<Fixture> expressions() throws IOException {
        List<Fixture> all = new ArrayList<>();
        try (var files = Files.list(sharedDir().resolve("conformance"))) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                JsonNode root = MAPPER.readTree(file.toFile());
                String suite = root.path("suite").asText();
                for (JsonNode testCase : root.path("cases")) {
                    JsonNode expr = testCase.get("expr");
                    if (expr != null && !expr.isNull()) {
                        all.add(new Fixture(suite + "/" + testCase.path("id").asText(), expr));
                    }
                }
            }
        }
        return all.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expressions")
    @DisplayName("read then write reproduces the original JSON")
    void roundTrips(Fixture fixture) {
        Expression parsed = ExpressionCodec.read(fixture.json());
        JsonNode written = ExpressionCodec.write(parsed);

        assertEquals(
                fixture.json(),
                written,
                () -> fixture.id() + " did not survive a round trip\n  in:  " + fixture.json() + "\n  out: " + written);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("expressions")
    @DisplayName("a second round trip is stable — no drift on repeated saves")
    void isIdempotent(Fixture fixture) {
        JsonNode once = ExpressionCodec.write(ExpressionCodec.read(fixture.json()));
        JsonNode twice = ExpressionCodec.write(ExpressionCodec.read(once));
        assertEquals(once, twice, () -> fixture.id() + " drifts on a second save");
    }

    @Test
    @DisplayName("a null expression stays absent rather than becoming an empty object")
    void nullStaysNull() {
        assertNull(ExpressionCodec.write(null));
        assertNull(ExpressionCodec.read(null));
    }

    record Fixture(String id, JsonNode json) {
        @Override
        public String toString() {
            return id;
        }
    }
}
