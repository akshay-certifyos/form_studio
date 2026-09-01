package com.certifyos.forms.question_catalog.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A reusable question definition. Aggregate root of {@code question_catalog}.
 *
 * <p>This is a <b>type</b>, not an appearance. Its appearance in a form is a {@code
 * QuestionInstance} inside a step, which carries the ordering, overrides and answer path. The same
 * word meaning two different things in the two contexts is precisely why they are separate bounded
 * contexts.
 *
 * <p>The catalog is the asset that compounds — and the thing most likely to rot. Six months of
 * accepting every proposed question and it holds "NPI", "NPI Number", "National Provider
 * Identifier" and "Individual NPI" as four entries, at which point reuse is zero. {@link #aliases}
 * and {@link CatalogStatus#PROPOSED} exist to prevent that.
 */
public record Question(
        QuestionId id,
        String tenantId,
        String key,
        String label,
        String helpText,
        ResponseType responseType,
        String optionSetKey,
        List<ValidationRule> validations,
        /** Target field per entity type in the credentialing platform — defined once, globally. */
        Map<String, String> platformMapping,
        /**
         * Payer-specific phrasings for the same question. These are where "NPI Number" lands
         * instead of becoming a near-duplicate catalog entry, and they are what the importer and
         * the duplicate detector match against.
         */
        Set<String> aliases,
        /** Child fields, for {@link ResponseType#TEXT_GROUP} and {@link ResponseType#GROUP}. */
        List<Question> children,
        /**
         * Question key whose answer narrows this one's option list, e.g. specialty filtered by
         * provider type. Resolved within the same step.
         */
        String filteredBy,
        CatalogStatus status,
        Set<String> tags,
        /**
         * What this question is about. Required — a question outside the taxonomy is a question nobody
         * browsing the catalog will find, which is how a catalog of 34 entries becomes unusable at 300.
         *
         * <p>A key into {@link QuestionCategory}, not a free-text label. Free text is the same failure
         * this record's {@link #aliases} exists to prevent, one level up: "Identity", "identity" and
         * "Personal info" as three shelves holding the same thing. Referential integrity is checked
         * where the reference is made — a record cannot see a repository — and by a fixture test, for
         * the same reason the template references have one.
         */
        String categoryKey) {

    public Question {
        if (id == null) {
            throw new IllegalArgumentException("Question id is required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Question key is required");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Question label is required — it is what an author reads");
        }
        if (responseType == null) {
            throw new IllegalArgumentException("Question responseType is required");
        }
        if (categoryKey == null || categoryKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Question '" + key + "' needs a category — every question belongs to one, and an "
                            + "uncategorised question is invisible to anyone browsing the catalog");
        }
        if (responseType.isSelect() && (optionSetKey == null || optionSetKey.isBlank())) {
            throw new IllegalArgumentException(
                    "A select question needs an option set: " + key + " (" + responseType.wireName() + ")");
        }
        validations = validations == null ? List.of() : List.copyOf(validations);
        platformMapping = platformMapping == null ? Map.of() : Map.copyOf(platformMapping);
        aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
        children = children == null ? List.of() : List.copyOf(children);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        status = status == null ? CatalogStatus.PROPOSED : status;
    }

    /** Certify-owned questions are shared by every tenant. */
    public boolean isGlobal() {
        return tenantId == null || tenantId.isBlank();
    }

    /**
     * Promotes a proposed question into the active catalog. Callers must have run the duplicate
     * check first — the aggregate cannot see its siblings, so it enforces the state machine and
     * {@code DuplicateDetector} enforces uniqueness.
     */
    public Question promote() {
        if (status == CatalogStatus.ACTIVE) {
            throw new IllegalStateException("Question is already active: " + key);
        }
        if (status == CatalogStatus.DEPRECATED) {
            throw new IllegalStateException(
                    "Deprecated questions are not revived — create a new one so history stays readable: " + key);
        }
        return withStatus(CatalogStatus.ACTIVE);
    }

    public Question deprecate() {
        if (status == CatalogStatus.DEPRECATED) {
            return this;
        }
        return withStatus(CatalogStatus.DEPRECATED);
    }

    /** Records a payer's phrasing without minting a near-duplicate entry. */
    public Question withAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return this;
        }
        Set<String> next = new LinkedHashSet<>(aliases);
        next.add(alias.trim());
        return new Question(
                id,
                tenantId,
                key,
                label,
                helpText,
                responseType,
                optionSetKey,
                validations,
                platformMapping,
                next,
                children,
                filteredBy,
                status,
                tags,
                categoryKey);
    }

    private Question withStatus(CatalogStatus next) {
        return new Question(
                id,
                tenantId,
                key,
                label,
                helpText,
                responseType,
                optionSetKey,
                validations,
                platformMapping,
                aliases,
                children,
                filteredBy,
                next,
                tags,
                categoryKey);
    }

    /** Label plus every alias, lower-cased — what the duplicate detector compares. */
    public Set<String> searchableNames() {
        Set<String> names = new LinkedHashSet<>();
        names.add(label.toLowerCase(Locale.ROOT).trim());
        aliases.forEach(a -> names.add(a.toLowerCase(Locale.ROOT).trim()));
        return names;
    }
}
