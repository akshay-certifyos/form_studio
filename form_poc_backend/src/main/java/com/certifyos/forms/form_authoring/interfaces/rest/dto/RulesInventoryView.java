package com.certifyos.forms.form_authoring.interfaces.rest.dto;

import com.certifyos.forms.form_authoring.application.RulesInventory;
import com.certifyos.forms.shared_kernel.expression.ExpressionCodec;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Every rule a tenant has.
 *
 * <p>Expressions go out as the same JSON the grammar uses, so the studio renders them with the same
 * {@code describe()} the condition builder uses rather than a second prose generator that could
 * disagree with the first. Two descriptions of one rule is how a UI ends up telling an author
 * something the evaluator does not believe.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RulesInventoryView(
        List<StepRuleView> steps,
        List<NamedRuleView> namedConditions,
        List<QuestionRuleView> questions,
        /** Answer path to question label, so the client renders a rule readably without a second request. */
        Map<String, String> labels,
        SummaryView summary) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepRuleView(
            String formId,
            String formName,
            String formStatus,
            String stepKey,
            String stepTitle,
            String sectionId,
            int order,
            boolean enabled,
            /** Absent means the step is always visible — deliberately listed, not filtered out. */
            JsonNode visibleWhen,
            List<String> reads,
            List<String> references,
            List<String> operators) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NamedRuleView(
            String formId,
            String formName,
            String key,
            String label,
            JsonNode expression,
            List<String> usedBySteps,
            List<String> reads,
            List<String> operators) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionRuleView(
            String sectionId,
            String sectionName,
            String questionKey,
            JsonNode visibleWhen,
            List<String> reads,
            List<String> operators,
            List<String> placedInSteps) {}

    public record SummaryView(
            int forms,
            int steps,
            int conditionedSteps,
            int namedConditions,
            int unusedNamedConditions,
            int questionConditions,
            Map<String, Integer> operatorUsage) {}

    public static RulesInventoryView of(RulesInventory.Inventory inventory) {
        return new RulesInventoryView(
                inventory.steps().stream()
                        .map(rule -> new StepRuleView(
                                rule.formId(),
                                rule.formName(),
                                rule.formStatus(),
                                rule.stepKey(),
                                rule.stepTitle(),
                                rule.sectionId(),
                                rule.order(),
                                rule.enabled(),
                                ExpressionCodec.write(rule.visibleWhen()),
                                rule.reads(),
                                rule.references(),
                                rule.operators()))
                        .toList(),
                inventory.namedConditions().stream()
                        .map(rule -> new NamedRuleView(
                                rule.formId(),
                                rule.formName(),
                                rule.key(),
                                rule.label(),
                                ExpressionCodec.write(rule.expression()),
                                rule.usedBySteps(),
                                rule.reads(),
                                rule.operators()))
                        .toList(),
                inventory.questions().stream()
                        .map(rule -> new QuestionRuleView(
                                rule.sectionId(),
                                rule.sectionName(),
                                rule.questionKey(),
                                ExpressionCodec.write(rule.visibleWhen()),
                                rule.reads(),
                                rule.operators(),
                                rule.placedInSteps()))
                        .toList(),
                inventory.labels(),
                new SummaryView(
                        inventory.summary().forms(),
                        inventory.summary().steps(),
                        inventory.summary().conditionedSteps(),
                        inventory.summary().namedConditions(),
                        inventory.summary().unusedNamedConditions(),
                        inventory.summary().questionConditions(),
                        inventory.summary().operatorUsage()));
    }
}
