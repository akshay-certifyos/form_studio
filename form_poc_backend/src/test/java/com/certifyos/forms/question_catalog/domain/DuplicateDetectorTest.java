package com.certifyos.forms.question_catalog.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Catalog hygiene is risk #1 in the design.
 *
 * <p>If near-duplicates accumulate, reuse drops to zero and the catalog becomes a worse version of
 * the hand-written form configs it replaced. These tests pin the cases that actually occur: the
 * same question arriving under a different payer's phrasing.
 */
class DuplicateDetectorTest {

    private static Question question(String id, String key, String label, Set<String> aliases, CatalogStatus status) {
        return new Question(
                QuestionId.of(id),
                null,
                key,
                label,
                null,
                ResponseType.TEXT,
                null,
                List.of(),
                Map.of(),
                aliases,
                List.of(),
                null,
                status,
                Set.of());
    }

    private static Question active(String id, String key, String label, String... aliases) {
        return question(id, key, label, Set.of(aliases), CatalogStatus.ACTIVE);
    }

    private static Question proposed(String id, String key, String label) {
        return question(id, key, label, Set.of(), CatalogStatus.PROPOSED);
    }

    private static final Question NPI =
            active("q_npi", "npi", "National Provider Identifier (NPI)", "NPI", "NPI Number", "Individual NPI");

    @Nested
    @DisplayName("the cases that actually rot a catalog")
    class RealWorld {

        @Test
        @DisplayName("a payer's phrasing already recorded as an alias is caught")
        void aliasMatch() {
            var candidate = proposed("q_new", "npiNumber", "NPI Number");
            var matches = DuplicateDetector.findDuplicates(candidate, List.of(NPI));

            assertEquals(1, matches.size());
            assertEquals(
                    DuplicateDetector.Reason.SAME_LABEL_OR_ALIAS, matches.get(0).reason());
            assertTrue(matches.get(0).explanation().contains("alias"));
        }

        @Test
        @DisplayName("the same words in a different order is caught")
        void significantWordsMatch() {
            var candidate = proposed("q_new", "providerNpi", "Provider NPI");
            var existing = active("q_npi2", "npi", "NPI Provider");

            var matches = DuplicateDetector.findDuplicates(candidate, List.of(existing));
            assertEquals(
                    DuplicateDetector.Reason.SAME_SIGNIFICANT_WORDS,
                    matches.get(0).reason());
        }

        @Test
        @DisplayName("noise words do not hide a duplicate")
        void noiseWordsIgnored() {
            // "Please enter your NPI number" and "NPI" reduce to the same significant word.
            var candidate = proposed("q_new", "npiEntry", "Please enter your NPI number");
            var existing = active("q_npi3", "npi", "NPI");

            var matches = DuplicateDetector.findDuplicates(candidate, List.of(existing));
            assertFalse(matches.isEmpty(), "noise words should not defeat the comparison");
        }

        @Test
        @DisplayName("a key collision is unambiguous")
        void keyCollision() {
            var candidate = proposed("q_new", "npi", "Something else entirely");
            var matches = DuplicateDetector.findDuplicates(candidate, List.of(NPI));

            assertEquals(DuplicateDetector.Reason.SAME_KEY, matches.get(0).reason());
            assertEquals(1.0, matches.get(0).confidence());
        }
    }

    @Nested
    @DisplayName("what must NOT be flagged")
    class NoFalsePositives {

        @Test
        @DisplayName("a genuinely different question passes")
        void distinctQuestion() {
            var candidate = proposed("q_new", "deaNumber", "DEA registration number");
            assertTrue(DuplicateDetector.findDuplicates(candidate, List.of(NPI)).isEmpty());
        }

        @Test
        @DisplayName("only active entries are compared — a deprecated one must not block a replacement")
        void deprecatedIgnored() {
            var deprecated = question("q_old", "npiOld", "NPI", Set.of(), CatalogStatus.DEPRECATED);
            var candidate = proposed("q_new", "npi2", "NPI");

            assertTrue(DuplicateDetector.findDuplicates(candidate, List.of(deprecated))
                    .isEmpty());
        }

        @Test
        @DisplayName("a question is never a duplicate of itself")
        void selfIsNotADuplicate() {
            assertTrue(DuplicateDetector.findDuplicates(NPI, List.of(NPI)).isEmpty());
        }
    }

    @Nested
    @DisplayName("the promotion state machine")
    class Promotion {

        @Test
        @DisplayName("proposed becomes active")
        void promoteProposed() {
            assertEquals(CatalogStatus.ACTIVE, proposed("q", "k", "L").promote().status());
        }

        @Test
        @DisplayName("promoting twice is an error, not a silent no-op")
        void promoteActiveFails() {
            assertThrows(IllegalStateException.class, () -> NPI.promote());
        }

        @Test
        @DisplayName("a deprecated question is not revived — history stays readable")
        void deprecatedCannotBePromoted() {
            var deprecated = NPI.deprecate();
            assertThrows(IllegalStateException.class, deprecated::promote);
        }

        @Test
        @DisplayName("absorbing a payer's phrasing as an alias is the intended resolution")
        void aliasAbsorbsPhrasing() {
            var updated = NPI.withAlias("Rendering Provider NPI");
            assertTrue(updated.aliases().contains("Rendering Provider NPI"));

            var candidate = proposed("q_new", "renderingNpi", "Rendering Provider NPI");
            assertFalse(
                    DuplicateDetector.findDuplicates(candidate, List.of(updated))
                            .isEmpty(),
                    "once absorbed, the phrasing must be recognised next time it appears");
        }
    }

    @Nested
    @DisplayName("question invariants")
    class Invariants {

        @Test
        @DisplayName("a select question without an option set is rejected at construction")
        void selectNeedsOptionSet() {
            var e = assertThrows(
                    IllegalArgumentException.class,
                    () -> new Question(
                            QuestionId.of("q"),
                            null,
                            "specialty",
                            "Specialty",
                            null,
                            ResponseType.SINGLE_SELECT,
                            null,
                            List.of(),
                            Map.of(),
                            Set.of(),
                            List.of(),
                            null,
                            CatalogStatus.ACTIVE,
                            Set.of()));
            assertTrue(e.getMessage().contains("option set"));
        }

        @Test
        @DisplayName("a blank label is rejected — it is what an author reads")
        void labelRequired() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new Question(
                            QuestionId.of("q"),
                            null,
                            "k",
                            "  ",
                            null,
                            ResponseType.TEXT,
                            null,
                            List.of(),
                            Map.of(),
                            Set.of(),
                            List.of(),
                            null,
                            CatalogStatus.ACTIVE,
                            Set.of()));
        }

        @Test
        @DisplayName("an unknown validation rule is rejected — rules are a closed vocabulary")
        void unknownValidationRule() {
            var e = assertThrows(
                    IllegalArgumentException.class, () -> new ValidationRule("luhnCheckButFancier", Map.of()));
            assertTrue(e.getMessage().contains("closed vocabulary"));
        }
    }

    @Nested
    @DisplayName("option sets")
    class OptionSets {

        private static final OptionSet SPECIALTIES = new OptionSet(
                "os_specialties",
                null,
                "specialties",
                "Specialties",
                List.of(
                        new OptionSet.Option("Cardiology", "Cardiology", Map.of("providerType", List.of("MD", "DO"))),
                        new OptionSet.Option("DC", "Chiropractic", Map.of("providerType", List.of("DC")))),
                true);

        @Test
        @DisplayName("labels resolve, so condition prose never prints stored codes")
        void labelLookup() {
            assertEquals("Chiropractic", SPECIALTIES.labelFor("DC").orElseThrow());
        }

        @Test
        @DisplayName("tags narrow the list — PRD 4.3 without a hard-coded rule")
        void tagFiltering() {
            assertEquals(1, SPECIALTIES.filteredBy("providerType", "DC").size());
            assertEquals(1, SPECIALTIES.filteredBy("providerType", "MD").size());
        }

        @Test
        @DisplayName("duplicate values are rejected — conditions reference values, so they must be unique")
        void duplicateValuesRejected() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OptionSet(
                            "os",
                            null,
                            "dupes",
                            "Dupes",
                            List.of(
                                    new OptionSet.Option("MD", "Physician", Map.of()),
                                    new OptionSet.Option("MD", "Doctor of Medicine", Map.of())),
                            true));
        }
    }
}
