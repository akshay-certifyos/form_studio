package com.certifyos.forms.form_authoring.interfaces.rest.dto;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.shared_kernel.expression.ExpressionCodec;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A form as the authoring UI needs it.
 *
 * <p>Conditions go out as the same JSON the grammar uses, so the frontend's evaluator reads exactly
 * what the backend stores — that shared shape is what the conformance suite is a contract over.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormDetailView(
        String id,
        String name,
        String entityType,
        String status,
        String sourceBlueprintId,
        Integer sourceBlueprintVersion,
        List<NamedConditionView> namedConditions,
        List<StepView> steps) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NamedConditionView(String key, String label, JsonNode expression, int referenceCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepView(
            String key,
            String sectionDefinitionId,
            String title,
            String group,
            int order,
            boolean enabled,
            boolean repeating,
            JsonNode visibleWhen) {}

    public static FormDetailView of(FormDefinition definition) {
        List<NamedConditionView> conditions = definition.namedConditions().values().stream()
                .map(c -> new NamedConditionView(
                        c.key(),
                        c.label(),
                        ExpressionCodec.write(c.expression()),
                        countReferences(definition, c.key())))
                .toList();

        List<StepView> steps = definition.steps().stream()
                .sorted(java.util.Comparator.comparingInt(Step::order))
                .map(s -> new StepView(
                        s.key().value(),
                        s.sectionDefinitionId(),
                        s.titleOverride(),
                        s.group(),
                        s.order(),
                        s.enabled(),
                        s.isRepeating(),
                        ExpressionCodec.write(s.visibleWhen())))
                .toList();

        return new FormDetailView(
                definition.id(),
                definition.name(),
                definition.entityType(),
                definition.status().name().toLowerCase(java.util.Locale.ROOT),
                definition.sourceBlueprintId(),
                definition.sourceBlueprintVersion(),
                conditions,
                steps);
    }

    /**
     * How many steps use a named condition.
     *
     * <p>Surfaced because the whole reason named conditions exist is that the same rule gates
     * several steps — an author needs to see that editing one touches four places, not one.
     */
    private static int countReferences(FormDefinition definition, String conditionKey) {
        return (int) definition.steps().stream()
                .filter(s -> ExpressionCodec.write(s.visibleWhen()) != null
                        && ExpressionCodec.write(s.visibleWhen()).toString().contains("\"" + conditionKey + "\""))
                .count();
    }
}
