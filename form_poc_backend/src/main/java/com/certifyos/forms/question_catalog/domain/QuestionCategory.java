package com.certifyos.forms.question_catalog.domain;

/**
 * What a question is <em>about</em>. Aggregate root.
 *
 * <p>Stored and referenced by key, rather than a Java enum. Both are closed vocabularies, and design
 * principle P4 asks for closed vocabularies <em>not expressed as code</em> — an enum would mean a
 * release to add "Behavioral health", which is precisely the cost this design exists to remove. This
 * is the {@link OptionSet} pattern: you cannot invent a category by typing, and you can add one by
 * configuring.
 *
 * <p><b>A category is intrinsic to the question, not to where it is used.</b> "Do you have a CAQH ID?"
 * belongs to Identity &amp; identifiers even though the Florida Blue form asks it inside Billing Setup;
 * the section decides where a question appears, the category says what it is. Conflating the two is
 * tempting and wrong — it would give the same question a different category in every form, at which
 * point the taxonomy carries no information.
 *
 * <p><b>Global, never per tenant.</b> A taxonomy that varies by tenant cannot group a shared catalog:
 * the whole value of browsing by category is that every tenant sees the same shelves.
 *
 * @param order where this sits when categories are listed. Explicit rather than alphabetical, because
 *     the useful sequence follows how a credentialing file is assembled — identity first, attestation
 *     last — and alphabetical would open with Attestation.
 */
public record QuestionCategory(String key, String label, String description, int order) {

    public QuestionCategory {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A category needs a key — it is what a question references");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("A category needs a label — it is the heading an author reads");
        }
    }
}
