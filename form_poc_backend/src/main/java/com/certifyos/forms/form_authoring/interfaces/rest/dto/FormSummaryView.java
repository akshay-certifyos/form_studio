package com.certifyos.forms.form_authoring.interfaces.rest.dto;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Row in the forms list. A view, never the aggregate — see the controller rules in the design doc. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormSummaryView(
        String id, String name, String entityType, String status, int stepCount, String sourceBlueprintId) {

    public static FormSummaryView of(FormDefinition definition) {
        return new FormSummaryView(
                definition.id(),
                definition.name(),
                definition.entityType(),
                definition.status().name().toLowerCase(java.util.Locale.ROOT),
                definition.orderedSteps().size(),
                definition.sourceBlueprintId());
    }
}
