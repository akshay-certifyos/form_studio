package com.certifyos.forms.form_authoring.interfaces.rest.dto;

import com.certifyos.forms.form_authoring.domain.reuse.FormBlueprint;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** A reusable form shape, as the studio needs it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormBlueprintView(
        String id,
        String key,
        String name,
        int version,
        String entityType,
        boolean global,
        List<String> keywords,
        List<PlacementView> placements) {

    public record PlacementView(String stepKey, String sectionTemplateId, int order, String group, boolean repeating) {}

    public static FormBlueprintView of(FormBlueprint blueprint) {
        return new FormBlueprintView(
                blueprint.id(),
                blueprint.key(),
                blueprint.name(),
                blueprint.version(),
                blueprint.entityType(),
                blueprint.tenantId() == null,
                blueprint.recognitionHints().keywords(),
                blueprint.orderedPlacements().stream()
                        .map(p -> new PlacementView(
                                p.stepKey(), p.sectionTemplateId(), p.order(), p.group(), p.repeating() != null))
                        .toList());
    }
}
