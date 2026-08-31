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
        List<NamedConditionView> namedConditions,
        List<PlacementView> placements) {

    /**
     * {@code conditioned} rather than the expression itself: the blueprint list is a picker, and what
     * an author needs there is whether the shape brings logic with it, not what the logic says. The
     * rules become inspectable the moment the blueprint is instantiated, on the form that results.
     */
    public record PlacementView(
            String stepKey,
            String sectionTemplateId,
            int order,
            String group,
            boolean repeating,
            boolean conditioned) {}

    public record NamedConditionView(String key, String label) {}

    public static FormBlueprintView of(FormBlueprint blueprint) {
        return new FormBlueprintView(
                blueprint.id(),
                blueprint.key(),
                blueprint.name(),
                blueprint.version(),
                blueprint.entityType(),
                blueprint.tenantId() == null,
                blueprint.recognitionHints().keywords(),
                blueprint.namedConditions().values().stream()
                        .map(c -> new NamedConditionView(c.key(), c.label()))
                        .toList(),
                blueprint.orderedPlacements().stream()
                        .map(p -> new PlacementView(
                                p.stepKey(),
                                p.sectionTemplateId(),
                                p.order(),
                                p.group(),
                                p.repeating() != null,
                                p.visibleWhen() != null))
                        .toList());
    }
}
