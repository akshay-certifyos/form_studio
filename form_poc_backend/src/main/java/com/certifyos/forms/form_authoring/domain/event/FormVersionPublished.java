package com.certifyos.forms.form_authoring.domain.event;

import com.certifyos.forms.form_authoring.domain.publishing.ChangeClass;
import java.time.Instant;
import java.util.Set;

/**
 * A form version went live.
 *
 * <p><b>This is deliberately an event and not a method call.</b> Publishing a structural change
 * means in-progress applications lose answers — the single most destructive operation in the
 * system. Today {@code publishDraft} reaches straight into the application store and wipes
 * {@code answers} in a loop with failures swallowed.
 *
 * <p>Here, publishing only states what happened. Whoever owns the answers decides what "reset"
 * means for their own aggregates, and {@link #changedKeys} lets them be surgical rather than total.
 *
 * <p>No subscriber exists in v0 — response capture is out of scope. The event is here so that the
 * dangerous operation is behind a boundary from the start, rather than being retrofitted behind one
 * after something has already reached across it.
 */
public record FormVersionPublished(
        String formVersionId,
        String formDefinitionId,
        String tenantId,
        int version,
        ChangeClass changeClass,
        Set<String> changedKeys,
        Instant publishedAt) {

    public FormVersionPublished {
        changedKeys = changedKeys == null ? Set.of() : Set.copyOf(changedKeys);
    }

    /** True when a subscriber needs to do something about in-progress work. */
    public boolean requiresAnswerReset() {
        return changeClass == ChangeClass.STRUCTURAL && !changedKeys.isEmpty();
    }
}
