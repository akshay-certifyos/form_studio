package com.certifyos.forms.question_catalog.domain;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guards catalog hygiene — the highest risk in this design.
 *
 * <p>Every payer phrases the same question differently. "NPI", "NPI Number", "National Provider
 * Identifier", "Individual NPI" are one question wearing four labels. If each becomes its own
 * catalog entry, reuse drops to zero and the catalog becomes a worse version of what it replaced.
 *
 * <p>So promotion is gated: a proposed question is checked against every active entry's label
 * <em>and aliases</em> before it can join the catalog. The intended outcome of a match is usually
 * not "reject" but "add this phrasing as an alias of the existing question".
 *
 * <p>Matching is deliberately conservative — normalise, then compare — rather than fuzzy. A false
 * positive costs a steward ten seconds; a false negative silently rots the catalog, so the bias is
 * towards flagging.
 */
public final class DuplicateDetector {

    /** Words that carry no meaning when comparing question labels. */
    private static final Set<String> NOISE =
            Set.of("the", "a", "an", "your", "please", "enter", "provide", "number", "no", "num", "id", "identifier");

    private DuplicateDetector() {}

    /**
     * @param candidate the question being promoted
     * @param existing every active question in the same scope
     * @return matches worth a steward's attention, most similar first
     */
    public static List<Match> findDuplicates(Question candidate, List<Question> existing) {
        return existing.stream()
                .filter(q -> q.status() == CatalogStatus.ACTIVE)
                .filter(q -> !q.id().equals(candidate.id()))
                .map(q -> match(candidate, q))
                .filter(m -> m != null)
                .sorted((a, b) -> Double.compare(b.confidence(), a.confidence()))
                .toList();
    }

    private static Match match(Question candidate, Question existing) {
        // An exact key collision is unambiguous.
        if (candidate.key().equalsIgnoreCase(existing.key())) {
            return new Match(existing, 1.0, Reason.SAME_KEY);
        }
        // The candidate's label is already recorded as an alias of an existing question — the
        // single most common case, and exactly what aliases exist to absorb.
        for (String name : candidate.searchableNames()) {
            if (existing.searchableNames().contains(name)) {
                return new Match(existing, 1.0, Reason.SAME_LABEL_OR_ALIAS);
            }
        }
        // Same significant words in any order: "NPI Number" vs "Number, NPI".
        Set<String> a = significantWords(candidate.label());
        Set<String> b = significantWords(existing.label());
        if (!a.isEmpty() && a.equals(b)) {
            return new Match(existing, 0.9, Reason.SAME_SIGNIFICANT_WORDS);
        }
        return null;
    }

    /** Lower-case, strip punctuation, drop noise words. */
    static Set<String> significantWords(String label) {
        return java.util.Arrays.stream(label.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9\\s]", " ")
                        .split("\\s+"))
                .filter(w -> !w.isBlank())
                .filter(w -> !NOISE.contains(w))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public enum Reason {
        SAME_KEY,
        SAME_LABEL_OR_ALIAS,
        SAME_SIGNIFICANT_WORDS
    }

    /**
     * @param confidence 1.0 for an exact match, lower for a heuristic one
     */
    public record Match(Question existing, double confidence, Reason reason) {

        /** What a steward should be shown. */
        public String explanation() {
            return switch (reason) {
                case SAME_KEY -> "An active question already uses the key \"" + existing.key() + "\".";
                case SAME_LABEL_OR_ALIAS -> "\"" + existing.label()
                        + "\" already covers this wording. Add it as an alias instead of a new question.";
                case SAME_SIGNIFICANT_WORDS -> "\"" + existing.label()
                        + "\" uses the same significant words. Check whether these are the same question.";
            };
        }
    }
}
