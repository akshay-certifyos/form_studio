package com.certifyos.forms.form_authoring.domain.reuse;

import java.util.List;

/**
 * How far a section definition has moved from the template it came from.
 *
 * <p>Answers two questions an author actually has, which are not the same question:
 *
 * <ul>
 *   <li><b>What would I gain by re-syncing?</b> — {@link Code#ADDED_IN_TEMPLATE},
 *       {@link Code#REMOVED_FROM_TEMPLATE}, {@link Code#REQUIREDNESS_CHANGED_IN_TEMPLATE}
 *   <li><b>What would re-syncing destroy?</b> — {@link Code#DISABLED_LOCALLY},
 *       {@link Code#ADDED_LOCALLY}, {@link Code#OVERRIDDEN_LOCALLY}, {@link Code#REORDERED_LOCALLY}
 * </ul>
 *
 * <p>Showing only the first set is how a "sync with template" button quietly deletes a tenant's
 * customisations. Both halves are computed and reported together, and nothing here performs a
 * resync — this is a report, not an action.
 *
 * @param behindTemplate the template has moved on since this section was created. False does not
 *     mean "no drift": a tenant can diverge from a template that never changed.
 */
public record SectionDrift(
        String sectionDefinitionId,
        String sourceTemplateId,
        Integer definitionTemplateVersion,
        Integer currentTemplateVersion,
        boolean behindTemplate,
        List<Finding> findings) {

    public SectionDrift {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** @param detail written for an author, naming the consequence rather than the field */
    public record Finding(Code code, String questionKey, String detail) {}

    public enum Code {
        /** The template gained a question this section does not have. */
        ADDED_IN_TEMPLATE,
        /** The template dropped a question this section still carries. */
        REMOVED_FROM_TEMPLATE,
        /** The template changed whether a question is mandatory. */
        REQUIREDNESS_CHANGED_IN_TEMPLATE,
        /** A template question switched off here. Provenance kept, so a resync can reconcile it. */
        DISABLED_LOCALLY,
        /** A question added here that the template has never had. */
        ADDED_LOCALLY,
        /** A template question given a different label, help text or requiredness here. */
        OVERRIDDEN_LOCALLY,
        /** Template questions appear here in a different order. */
        REORDERED_LOCALLY
    }

    /** No template to compare against — a section authored from scratch cannot drift. */
    public static SectionDrift notFromTemplate(String sectionDefinitionId) {
        return new SectionDrift(sectionDefinitionId, null, null, null, false, List.of());
    }

    public boolean hasDrift() {
        return !findings.isEmpty();
    }

    /** Findings a resync would overwrite. What makes the button dangerous. */
    public List<Finding> localCustomisations() {
        return findings.stream().filter(f -> isLocal(f.code())).toList();
    }

    /** Findings a resync would bring in. What makes the button worth pressing. */
    public List<Finding> templateChanges() {
        return findings.stream().filter(f -> !isLocal(f.code())).toList();
    }

    private static boolean isLocal(Code code) {
        return switch (code) {
            case DISABLED_LOCALLY, ADDED_LOCALLY, OVERRIDDEN_LOCALLY, REORDERED_LOCALLY -> true;
            case ADDED_IN_TEMPLATE, REMOVED_FROM_TEMPLATE, REQUIREDNESS_CHANGED_IN_TEMPLATE -> false;
        };
    }
}
