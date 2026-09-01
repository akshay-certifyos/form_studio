package com.certifyos.forms.form_authoring.interfaces.rest.dto;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Row in the forms list. A view, never the aggregate — see the controller rules in the design doc.
 *
 * @param activeVersion the highest published version, or null when the form has never been published.
 *     Null and zero are not the same thing here: a form with no published version is not live at all,
 *     and rendering that as "0" would read as a version that exists.
 * @param sourceBlueprintVersion the blueprint version this form was instantiated from. Recorded at
 *     creation and never updated, so a later blueprint version does not change what this form says it
 *     came from — that is the whole point of copy-on-use.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormSummaryView(
        String id,
        String name,
        String entityType,
        String status,
        int stepCount,
        String sourceBlueprintId,
        Integer sourceBlueprintVersion,
        Integer activeVersion) {

    public static FormSummaryView of(FormDefinition definition, Integer activeVersion) {
        return new FormSummaryView(
                definition.id(),
                definition.name(),
                definition.entityType(),
                definition.status().name().toLowerCase(java.util.Locale.ROOT),
                definition.orderedSteps().size(),
                definition.sourceBlueprintId(),
                definition.sourceBlueprintVersion(),
                activeVersion);
    }
}
