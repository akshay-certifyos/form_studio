package com.certifyos.forms.question_catalog.domain;

import java.util.Optional;

/**
 * Lifecycle of a catalog entry.
 *
 * <p>{@link #PROPOSED} is the guard against catalog rot. Anything extracted from a payer's form —
 * by the importer, or later by PDF ingestion — lands here and needs a steward to promote it after a
 * duplicate check. Without that gate the catalog fills with near-duplicates and reuse collapses,
 * which is the highest risk in this design.
 */
public enum CatalogStatus {
    PROPOSED("proposed"),
    ACTIVE("active"),
    DEPRECATED("deprecated");

    private final String wireName;

    CatalogStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<CatalogStatus> fromWireName(String name) {
        for (CatalogStatus s : values()) {
            if (s.wireName.equals(name)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
