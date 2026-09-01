package com.certifyos.forms.form_authoring.application;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.port.FormDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionDefinitionRepository;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.port.QuestionRepository;
import com.certifyos.forms.shared_kernel.expression.AnalysisScope;
import com.certifyos.forms.shared_kernel.expression.Expression;
import com.certifyos.forms.shared_kernel.expression.ExpressionAnalyzer;
import com.certifyos.forms.shared_kernel.expression.Operator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every rule a tenant has, in one read.
 *
 * <p>A projection, not an aggregate: it hydrates forms and sections and derives from them, and it
 * holds no state of its own. That is why it sits in the application layer rather than the domain —
 * there is no invariant here to protect, only a question to answer.
 *
 * <p>Reads the catalog too, for one reason only: a rule that prints {@code applicantDetails.specialty}
 * is decodable, and one that prints "Primary specialty" is readable. One batched lookup for the whole
 * tenant, so the screen never joins a catalog request onto a rules request to render a sentence.
 *
 * <p>The question is one the studio could not answer at all before. Conditions were visible one step
 * at a time, inside the editor of the form that owned them, so "which rules exist" and "what reads
 * from this question" required opening every form in turn. For a tenant with nine payer forms that
 * is not a view, it is an afternoon.
 *
 * <p><b>Unconditioned steps are included deliberately.</b> Every step appears, with a null condition
 * when it has none, because the most consequential thing about a rule set is often the rule that is
 * missing — three Florida Blue sections ship unconditioned in production today, which is a defect
 * that looks exactly like a working form. An inventory that listed only the rules that exist could
 * not show it.
 */
public class RulesInventory {

    private final FormDefinitionRepository forms;
    private final SectionDefinitionRepository sections;
    private final QuestionRepository questions;

    public RulesInventory(
            FormDefinitionRepository forms, SectionDefinitionRepository sections, QuestionRepository questions) {
        this.forms = forms;
        this.sections = sections;
        this.questions = questions;
    }

    /**
     * One step's placement and the rule deciding whether it appears.
     *
     * @param visibleWhen null when the step is unconditional — see the class note on why those are
     *     listed rather than filtered out
     * @param reads answer paths the rule depends on, following refs into their definitions. This is
     *     the reverse index that answers "what breaks if I remove this question".
     * @param references named conditions the rule names directly
     */
    public record StepRule(
            String formId,
            String formName,
            String formStatus,
            String stepKey,
            String stepTitle,
            String sectionId,
            int order,
            boolean enabled,
            Expression visibleWhen,
            List<String> reads,
            List<String> references,
            List<String> operators) {}

    /** @param usedBySteps step keys whose conditions name this rule. Empty means nothing uses it. */
    public record NamedRule(
            String formId,
            String formName,
            String key,
            String label,
            Expression expression,
            List<String> usedBySteps,
            List<String> reads,
            List<String> operators) {}

    /**
     * A question-level rule.
     *
     * @param placedInSteps every step that places the owning section, because a question rule is
     *     written once on the section and takes effect in each placement. One rule, several places
     *     it bites — which is invisible from the section editor alone.
     */
    public record QuestionRule(
            String sectionId,
            String sectionName,
            String questionKey,
            Expression visibleWhen,
            List<String> reads,
            List<String> operators,
            List<String> placedInSteps) {}

    /**
     * @param operatorUsage how many rules use each operator. Sorted by name so the shape is stable
     *     between calls; a histogram that reorders itself reads as data changing when it has not.
     */
    public record Summary(
            int forms,
            int steps,
            int conditionedSteps,
            int namedConditions,
            int unusedNamedConditions,
            int questionConditions,
            Map<String, Integer> operatorUsage) {}

    /**
     * @param labels answer path to the question's label, so a rule can be read rather than decoded.
     *     Returned as a map rather than inlined into each rule because the same path appears in many
     *     rules — that is the point of a shared question — and inlining would repeat every label once
     *     per rule that mentions it.
     */
    public record Inventory(
            List<StepRule> steps,
            List<NamedRule> namedConditions,
            List<QuestionRule> questions,
            Map<String, String> labels,
            Summary summary) {}

    public Inventory of(String tenantId) {
        List<FormDefinition> tenantForms = forms.findByTenant(tenantId);
        List<SectionDefinition> tenantSections = sections.findByTenant(tenantId);

        Map<String, SectionDefinition> sectionById = new LinkedHashMap<>();
        tenantSections.forEach(section -> sectionById.put(section.id(), section));

        List<StepRule> stepRules = new ArrayList<>();
        List<NamedRule> namedRules = new ArrayList<>();
        Map<String, Integer> operatorUsage = new TreeMap<>();

        for (FormDefinition form : tenantForms) {
            // Resolves refs so `reads` is the real dependency set rather than stopping at the ref.
            // Without this, a step gated entirely through a named condition would report reading
            // nothing, which is the opposite of the truth.
            AnalysisScope scope = scopeFor(form);

            for (Step step : sortedSteps(form)) {
                SectionDefinition section = sectionById.get(step.sectionDefinitionId());
                Set<Operator> operators = ExpressionAnalyzer.operatorsUsed(step.visibleWhen());
                operators.forEach(op -> operatorUsage.merge(op.wireName(), 1, Integer::sum));

                stepRules.add(new StepRule(
                        form.id(),
                        form.name(),
                        form.status().name().toLowerCase(java.util.Locale.ROOT),
                        step.key().value(),
                        step.titleOverride() != null
                                ? step.titleOverride()
                                : section != null ? section.name() : step.key().value(),
                        step.sectionDefinitionId(),
                        step.order(),
                        step.enabled(),
                        step.visibleWhen(),
                        List.copyOf(ExpressionAnalyzer.referencedPaths(step.visibleWhen(), scope)),
                        List.copyOf(ExpressionAnalyzer.referencedConditions(step.visibleWhen())),
                        wireNames(operators)));
            }

            for (FormDefinition.NamedCondition condition :
                    form.namedConditions().values()) {
                List<String> usedBy = form.steps().stream()
                        .filter(step -> ExpressionAnalyzer.referencedConditions(step.visibleWhen())
                                .contains(condition.key()))
                        .map(step -> step.key().value())
                        .toList();

                Set<Operator> operators = ExpressionAnalyzer.operatorsUsed(condition.expression());
                operators.forEach(op -> operatorUsage.merge(op.wireName(), 1, Integer::sum));

                namedRules.add(new NamedRule(
                        form.id(),
                        form.name(),
                        condition.key(),
                        condition.label(),
                        condition.expression(),
                        usedBy,
                        List.copyOf(ExpressionAnalyzer.referencedPaths(condition.expression(), scope)),
                        wireNames(operators)));
            }
        }

        // Which steps place each section, so a question rule can say where it takes effect.
        Map<String, List<String>> placementsBySection = new LinkedHashMap<>();
        for (FormDefinition form : tenantForms) {
            for (Step step : form.steps()) {
                placementsBySection
                        .computeIfAbsent(step.sectionDefinitionId(), key -> new ArrayList<>())
                        .add(form.name() + " → " + step.key().value());
            }
        }

        List<QuestionRule> questionRules = new ArrayList<>();
        for (SectionDefinition section : tenantSections) {
            for (QuestionInstance question : section.questions()) {
                if (question.visibleWhen() == null) {
                    continue;
                }
                Set<Operator> operators = ExpressionAnalyzer.operatorsUsed(question.visibleWhen());
                operators.forEach(op -> operatorUsage.merge(op.wireName(), 1, Integer::sum));

                questionRules.add(new QuestionRule(
                        section.id(),
                        section.name(),
                        question.key(),
                        question.visibleWhen(),
                        // Bare scope: a question rule names its siblings by unqualified key, and
                        // there is no single form to resolve them against — the same section may be
                        // placed in several.
                        List.copyOf(ExpressionAnalyzer.referencedPaths(question.visibleWhen(), AnalysisScope.empty())),
                        wireNames(operators),
                        placementsBySection.getOrDefault(section.id(), List.of())));
            }
        }

        Summary summary = new Summary(
                tenantForms.size(),
                stepRules.size(),
                (int) stepRules.stream()
                        .filter(rule -> rule.visibleWhen() != null)
                        .count(),
                namedRules.size(),
                (int) namedRules.stream()
                        .filter(rule -> rule.usedBySteps().isEmpty())
                        .count(),
                questionRules.size(),
                Map.copyOf(operatorUsage));

        return new Inventory(
                List.copyOf(stepRules),
                List.copyOf(namedRules),
                List.copyOf(questionRules),
                labelsFor(tenantForms, sectionById),
                summary);
    }

    /**
     * Answer path to question label, for every question every form places.
     *
     * <p>Keyed by the placement-scoped path rather than the question key, because that is what a rule
     * actually names — and because the same catalog question placed in two steps has two paths and,
     * legitimately, may carry a different override in each.
     *
     * <p>An override wins over the catalog label. That order matters: an override exists precisely
     * because a payer words the question differently, and showing the canonical label in a rule about
     * a form that asks it differently would be quietly misleading.
     *
     * <p>One batched catalog read for the whole tenant. Resolved here rather than in the client so the
     * rules screen does not have to join a catalog lookup onto a rules lookup to print a sentence.
     */
    private Map<String, String> labelsFor(
            List<FormDefinition> tenantForms, Map<String, SectionDefinition> sectionById) {

        Set<QuestionId> needed = new LinkedHashSet<>();
        for (FormDefinition form : tenantForms) {
            for (Step step : form.steps()) {
                SectionDefinition section = sectionById.get(step.sectionDefinitionId());
                if (section != null) {
                    section.questions().forEach(question -> needed.add(question.catalogQuestionId()));
                }
            }
        }

        Map<QuestionId, String> catalogLabels = new LinkedHashMap<>();
        questions.findAllById(needed).forEach(question -> catalogLabels.put(question.id(), question.label()));

        Map<String, String> labels = new LinkedHashMap<>();
        for (FormDefinition form : tenantForms) {
            for (Step step : form.steps()) {
                SectionDefinition section = sectionById.get(step.sectionDefinitionId());
                if (section == null) {
                    continue;
                }
                for (QuestionInstance question : section.questions()) {
                    String label = question.labelOverride() != null
                            ? question.labelOverride()
                            : catalogLabels.get(question.catalogQuestionId());
                    if (label != null) {
                        labels.put(step.key().pathFor(question.key()), label);
                    }
                }
            }
        }
        return Map.copyOf(labels);
    }

    /**
     * Ordinals are not needed here, only ref resolution and value sets, so this deliberately does not
     * reuse the compiler's scope construction — that one exists to detect forward references and
     * carries the ordinal bookkeeping to do it.
     */
    private static AnalysisScope scopeFor(FormDefinition form) {
        return new AnalysisScope(Map.of(), Map.of(), Set.of(), form.namedConditionExpressions());
    }

    private static List<Step> sortedSteps(FormDefinition form) {
        return form.steps().stream()
                .sorted(java.util.Comparator.comparingInt(Step::order))
                .toList();
    }

    private static List<String> wireNames(Set<Operator> operators) {
        Set<String> names = new LinkedHashSet<>();
        operators.forEach(op -> names.add(op.wireName()));
        return List.copyOf(names);
    }
}
