package com.certifyos.forms.form_authoring.domain.reuse;

import com.certifyos.forms.form_authoring.domain.definition.Origin;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Compares a section definition against the template it came from. Domain service.
 *
 * <p>Pure function, no I/O — both sides arrive as arguments, so this is testable without a database
 * and cannot accidentally read a template version other than the one it was handed.
 *
 * <p>The comparison is deliberately asymmetric, because {@link Origin} makes two superficially
 * similar situations mean opposite things. A question present in the template and absent here is the
 * template moving ahead. A question present here and absent from the template is either a local
 * addition (origin {@code ADDED}) or something the template has dropped (origin {@code TEMPLATE}) —
 * and telling those apart is the entire reason provenance is recorded rather than inferred.
 */
public final class DriftCalculator {

    private DriftCalculator() {}

    /**
     * @param currentTemplate the latest version of the template, or empty when it no longer exists
     */
    public static SectionDrift calculate(SectionDefinition definition, Optional<SectionTemplate> currentTemplate) {

        if (definition.sourceTemplateId() == null) {
            // Authored from scratch. Nothing to drift from, and reporting "no drift" against a
            // template it never had would imply a relationship that does not exist.
            return SectionDrift.notFromTemplate(definition.id());
        }

        if (currentTemplate.isEmpty()) {
            return new SectionDrift(
                    definition.id(),
                    definition.sourceTemplateId(),
                    definition.sourceTemplateVersion(),
                    null,
                    false,
                    List.of(new SectionDrift.Finding(
                            SectionDrift.Code.REMOVED_FROM_TEMPLATE,
                            null,
                            "The template this section came from no longer exists, so it can no longer be"
                                    + " re-synced. The section itself is unaffected.")));
        }

        SectionTemplate template = currentTemplate.get();
        List<SectionDrift.Finding> findings = new ArrayList<>();

        // --- what re-syncing would bring in -------------------------------------------------
        for (SectionTemplate.TemplateQuestion templateQuestion : template.orderedQuestions()) {
            Optional<QuestionInstance> here = definition.question(templateQuestion.key());

            if (here.isEmpty()) {
                findings.add(new SectionDrift.Finding(
                        SectionDrift.Code.ADDED_IN_TEMPLATE,
                        templateQuestion.key(),
                        "The template has added this question since this section was created."));
                continue;
            }

            QuestionInstance instance = here.get();
            if (instance.required() != templateQuestion.required() && instance.origin() == Origin.TEMPLATE) {
                // Reported as a template change when the local side is untouched, and as an override
                // below when it is not — the same difference, read from opposite ends.
                findings.add(new SectionDrift.Finding(
                        SectionDrift.Code.REQUIREDNESS_CHANGED_IN_TEMPLATE,
                        templateQuestion.key(),
                        templateQuestion.required()
                                ? "The template now requires this question; here it is optional."
                                : "The template no longer requires this question; here it is still required."));
            }
        }

        // --- what re-syncing would overwrite ------------------------------------------------
        for (QuestionInstance instance : definition.questions()) {
            Optional<SectionTemplate.TemplateQuestion> inTemplate = template.question(instance.key());

            if (instance.origin() == Origin.ADDED) {
                findings.add(new SectionDrift.Finding(
                        SectionDrift.Code.ADDED_LOCALLY,
                        instance.key(),
                        "Added here, not in the template. Promote the section to keep it."));
                continue;
            }

            if (inTemplate.isEmpty()) {
                // Only worth reporting while this section still *emits* the question. A disabled
                // question the template has also dropped is the settled end state of "switch it off,
                // then promote" — neither side will emit it, so there is nothing to reconcile.
                // Reporting it anyway told the author the template had dropped something, moments
                // after they were the one who dropped it.
                if (instance.enabled()) {
                    findings.add(new SectionDrift.Finding(
                            SectionDrift.Code.REMOVED_FROM_TEMPLATE,
                            instance.key(),
                            "The template has dropped this question; it is still here and still active."));
                }
                continue;
            }

            if (!instance.enabled()) {
                findings.add(new SectionDrift.Finding(
                        SectionDrift.Code.DISABLED_LOCALLY,
                        instance.key(),
                        "Switched off here. It is compiled out entirely, and a re-sync would bring it back."));
            }

            if (instance.labelOverride() != null || instance.helpTextOverride() != null) {
                findings.add(new SectionDrift.Finding(
                        SectionDrift.Code.OVERRIDDEN_LOCALLY,
                        instance.key(),
                        "Reworded here. A re-sync would restore the template's wording."));
            }
        }

        if (isReordered(definition, template)) {
            findings.add(new SectionDrift.Finding(
                    SectionDrift.Code.REORDERED_LOCALLY, null, "Template questions appear in a different order here."));
        }

        Integer recordedVersion = definition.sourceTemplateVersion();
        boolean behind = recordedVersion != null && recordedVersion < template.version();

        return new SectionDrift(
                definition.id(), definition.sourceTemplateId(), recordedVersion, template.version(), behind, findings);
    }

    /**
     * Compares the relative order of questions the two sides share.
     *
     * <p>Relative rather than absolute: inserting a local question between two template ones shifts
     * every later {@code order} value without reordering anything, and reporting that as a reorder
     * would make the indicator fire on almost every customised section.
     */
    private static boolean isReordered(SectionDefinition definition, SectionTemplate template) {
        List<String> templateOrder = template.orderedQuestions().stream()
                .map(SectionTemplate.TemplateQuestion::key)
                .filter(key -> definition.question(key).isPresent())
                .toList();

        List<String> definitionOrder = definition.questions().stream()
                .sorted(java.util.Comparator.comparingInt(QuestionInstance::order))
                .map(QuestionInstance::key)
                .filter(key -> template.question(key).isPresent())
                .toList();

        return !templateOrder.equals(definitionOrder);
    }
}
