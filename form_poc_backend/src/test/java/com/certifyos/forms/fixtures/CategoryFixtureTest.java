package com.certifyos.forms.fixtures;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.config.FixtureLoader;
import com.certifyos.forms.question_catalog.domain.CatalogStatus;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionCategory;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.ResponseType;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The catalog's taxonomy, against the real fixtures.
 *
 * <p>Referential integrity first, for the same reason {@code ReuseFixtureTest} leads with it: a
 * question carrying {@code categoryKey: "identity"} while no such category exists is a dangling
 * reference that reads as a working feature — the API answers, the field is populated, and the shelf
 * is simply empty. Nothing fails unless something asserts the reference resolves.
 */
class CategoryFixtureTest {

    private static FixtureLoader.Fixtures fixtures;

    @BeforeAll
    static void load() throws Exception {
        fixtures = new FixtureLoader(Path.of("../form_poc_shared/fixtures")).load();
    }

    @Test
    @DisplayName("every question's category resolves to a category that exists")
    void everyCategoryResolves() {
        Set<String> defined = fixtures.questionCategories().stream()
                .map(QuestionCategory::key)
                .collect(Collectors.toSet());

        assertFalse(defined.isEmpty(), "no categories loaded — the rest of this test would pass vacuously");

        List<String> dangling = fixtures.questions().stream()
                .filter(question -> !defined.contains(question.categoryKey()))
                .map(question -> question.key() + " -> " + question.categoryKey())
                .toList();

        assertTrue(dangling.isEmpty(), "questions pointing at categories that do not exist: " + dangling);
    }

    @Test
    @DisplayName("every question has a category — the requirement, checked against real data")
    void noneUncategorised() {
        List<String> missing = fixtures.questions().stream()
                .filter(question ->
                        question.categoryKey() == null || question.categoryKey().isBlank())
                .map(Question::key)
                .toList();

        assertTrue(missing.isEmpty(), "uncategorised: " + missing);
    }

    @Test
    @DisplayName("no category is empty, because an empty shelf is a taxonomy nobody agreed to")
    void everyCategoryIsUsed() {
        Map<String, Long> counts = fixtures.questions().stream()
                .collect(Collectors.groupingBy(Question::categoryKey, Collectors.counting()));

        List<String> unused = fixtures.questionCategories().stream()
                .map(QuestionCategory::key)
                .filter(key -> !counts.containsKey(key))
                .toList();

        // Not a rule the model enforces — a category may legitimately be created before its first
        // question. It is a rule about the *fixtures*: a taxonomy invented ahead of the content is a
        // guess, and this POC's whole argument is that shapes should be promoted from real use.
        assertTrue(unused.isEmpty(), "categories with no questions: " + unused);
    }

    @Test
    @DisplayName("categories carry a deliberate order, not an alphabetical accident")
    void orderedForReading() {
        List<String> byOrder = fixtures.questionCategories().stream()
                .sorted(java.util.Comparator.comparingInt(QuestionCategory::order))
                .map(QuestionCategory::key)
                .toList();

        // Identity first and attestation last is how a credentialing file is assembled. Alphabetical
        // would open with Attestation, which is the last thing anyone fills in.
        assertEquals("identity", byOrder.get(0));
        assertEquals("attestation", byOrder.get(byOrder.size() - 1));
    }

    @Test
    @DisplayName("a question cannot be built without a category")
    void categoryIsRequired() {
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> new Question(
                        QuestionId.of("q_x"),
                        null,
                        "x",
                        "X",
                        null,
                        ResponseType.TEXT,
                        null,
                        List.of(),
                        Map.of(),
                        Set.of(),
                        List.of(),
                        null,
                        CatalogStatus.ACTIVE,
                        Set.of(),
                        null));

        assertTrue(
                thrown.getMessage().contains("category"),
                "the message must say what is missing, not just that something is: " + thrown.getMessage());
    }
}
