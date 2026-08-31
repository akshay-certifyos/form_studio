package com.certifyos.forms.form_authoring.domain.publishing;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import java.time.Instant;

/**
 * A published, immutable form. Aggregate root.
 *
 * <p>Immutability is not stylistic here — a provider signs an attestation against a specific
 * rendering, so reproducing exactly what they saw is a compliance requirement. {@link
 * #definitionSnapshot} records the definition that produced this artifact, so the version is
 * reproducible even after the definition has moved on.
 *
 * <p>There is deliberately no {@code archive()} or {@code deactivate()}. The active version is
 * <b>derived</b> — the highest version number with a {@link #publishedAt} — which means publishing
 * is a single insert, needs no transaction, and cannot leave two versions both claiming to be live.
 */
public record FormVersion(
        String id,
        String tenantId,
        String formDefinitionId,
        int version,
        CompiledForm artifact,
        FormDefinition definitionSnapshot,
        ChangeSet changeSet,
        String changelog,
        String ticketId,
        Instant publishedAt,
        String publishedBy) {

    public FormVersion {
        if (formDefinitionId == null || formDefinitionId.isBlank()) {
            throw new IllegalArgumentException("A version must belong to a form definition");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Version numbers start at 1, got: " + version);
        }
        if (artifact == null) {
            throw new IllegalArgumentException("A version without an artifact is not publishable");
        }
        if (publishedAt == null) {
            throw new IllegalArgumentException(
                    "A FormVersion only exists once published — there is no draft version record");
        }
    }

    /**
     * Publishes a compiled form as the next version.
     *
     * <p>The clock is passed in rather than read, so a version is reproducible in a test and the
     * aggregate stays a pure function of its inputs.
     */
    public static FormVersion publish(
            String id,
            FormDefinition definition,
            CompiledForm artifact,
            ChangeSet changeSet,
            int previousVersion,
            String changelog,
            String ticketId,
            String publishedBy,
            Instant now) {

        return new FormVersion(
                id,
                definition.tenantId(),
                definition.id(),
                previousVersion + 1,
                artifact,
                definition,
                changeSet,
                changelog,
                ticketId,
                now,
                publishedBy);
    }

    /**
     * Whether publishing this version disturbs providers who are part-way through the form.
     * Computed from the diff, never asserted by whoever clicked publish.
     */
    public ChangeClass changeClass() {
        return changeSet == null ? ChangeClass.STRUCTURAL : changeSet.changeClass();
    }
}
