package com.certifyos.forms.form_authoring.domain.reuse;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.shared_kernel.expression.Expression;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A reusable form shape. Aggregate root, per §5.5.
 *
 * <p>Holds an ordered list of section <em>templates</em> to place, not sections. Instantiating a
 * blueprint therefore means instantiating each template in turn — the blueprint decides the shape of
 * a form, the templates decide the content of each part, and neither reaches into a tenant's
 * definition after the fact.
 *
 * <p>Carries <b>logic as well as shape</b> — each placement's {@code visibleWhen} and the form-level
 * {@code namedConditions} it may reference. Without those, instantiating a blueprint would produce a
 * form whose steps all show unconditionally, which is precisely the defect this whole design exists
 * to remove: three Florida Blue sections ship unconditioned today for want of somewhere to put the
 * rule. A reusable shape that drops the rules is not reusable, it is a scaffold.
 *
 * <p>{@code recognitionHints} is inert here. It exists as the hook PDF ingestion (§11) would use to
 * guess which blueprint an uploaded form resembles, and is carried so that adding ingestion later is
 * not a schema change.
 */
public record FormBlueprint(
        String id,
        String tenantId,
        String key,
        String name,
        int version,
        String entityType,
        RecognitionHints recognitionHints,
        Map<String, FormDefinition.NamedCondition> namedConditions,
        List<BlueprintPlacement> placements,
        SectionTemplate.TemplateStatus status) {

    public FormBlueprint {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Blueprint key is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Blueprint name is required");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Blueprint version starts at 1, got: " + version);
        }
        placements = placements == null ? List.of() : List.copyOf(placements);
        namedConditions = namedConditions == null ? Map.of() : Map.copyOf(namedConditions);
        status = status == null ? SectionTemplate.TemplateStatus.ACTIVE : status;

        // A blueprint placing one template twice is legitimate — Practice Location and Billing
        // Address are the same template — so uniqueness is on the step key, not the template id.
        Set<String> seen = new LinkedHashSet<>();
        for (BlueprintPlacement placement : placements) {
            if (!seen.add(placement.stepKey())) {
                throw new IllegalArgumentException("Blueprint '" + key + "' places two steps named '"
                        + placement.stepKey() + "' — step keys are answer namespaces");
            }
        }
    }

    /**
     * One section template placed in the form.
     *
     * @param stepKey the answer namespace this placement will own. §5.5's example omits it, which
     *     works only while no blueprint places the same template twice — and the very first real
     *     form examined does exactly that. Naming it here means a blueprint, not a later author,
     *     decides the answer paths.
     * @param group presentational only; compiles to nothing
     * @param repeating null inherits the template's default; set to override it for this placement
     * @param visibleWhen the rule deciding whether this step appears, carried through instantiation.
     *     Safe to reuse verbatim because it is written against step-scoped answer paths and the
     *     blueprint fixes the step keys — so a condition reading {@code applicant.providerType} means
     *     the same thing in every form built from this blueprint. May be a {@code ref} into {@link
     *     FormBlueprint#namedConditions}.
     */
    public record BlueprintPlacement(
            String stepKey,
            String sectionTemplateId,
            int order,
            String group,
            Step.Repeating repeating,
            Expression visibleWhen) {

        /**
         * Repetition belongs to the placement, with the template supplying a default.
         *
         * <p>Null means "inherit the template's". It has to be expressible per placement because the
         * first real form examined places one address template twice — Practice Location repeats up
         * to ten times, Billing Address does not. Reading repetition off the template alone made
         * both repeat, which the fixture caught.
         */
        public BlueprintPlacement {
            if (stepKey == null || stepKey.isBlank()) {
                throw new IllegalArgumentException("A blueprint placement needs a step key");
            }
            if (sectionTemplateId == null || sectionTemplateId.isBlank()) {
                throw new IllegalArgumentException("Placement '" + stepKey + "' must reference a section template");
            }
        }
    }

    /** @param requiredSectionTemplates templates a form must contain to be recognised as this shape */
    public record RecognitionHints(List<String> requiredSectionTemplates, List<String> keywords) {

        public RecognitionHints {
            requiredSectionTemplates =
                    requiredSectionTemplates == null ? List.of() : List.copyOf(requiredSectionTemplates);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }

        public static RecognitionHints none() {
            return new RecognitionHints(List.of(), List.of());
        }
    }

    /**
     * Mints a blueprint from a form a tenant has assembled by hand.
     *
     * <p>The form-level counterpart of {@link SectionTemplate#fromSection}, and the step that closes
     * the reuse loop: build one payer form from the catalog, discover the shape generalises, and make
     * the next form start from it instead of from nothing.
     *
     * <p><b>Every placed section must already be template-backed.</b> A blueprint references section
     * <em>templates</em>, not sections — that is what keeps instantiation copy-on-use — so a form
     * holding a from-scratch section has nothing for the blueprint to point at. Rather than silently
     * dropping that step, or inventing a template behind the author's back, this refuses and names
     * the steps involved: promote those sections first, then promote the form. The order is not
     * arbitrary, it is the dependency.
     *
     * <p>Disabled steps are dropped, consistently with how promotion treats disabled questions. The
     * conditions travel with them — each step's {@code visibleWhen} and every named condition on the
     * form — because a blueprint that kept only the shape would instantiate into a form with all its
     * logic stripped, and the author would have to re-author every rule they just finished writing.
     *
     * @param templateIdBySectionId source template of each section the form places, resolved by the
     *     caller. Absent entries are what trigger the refusal above.
     */
    public static FormBlueprint fromForm(
            String id, String key, String name, FormDefinition form, Map<String, String> templateIdBySectionId) {

        List<Step> placed = form.orderedSteps();

        List<String> unbacked = placed.stream()
                .filter(step -> templateIdBySectionId.get(step.sectionDefinitionId()) == null)
                .map(step -> step.key().value())
                .toList();
        if (!unbacked.isEmpty()) {
            throw new IllegalStateException("These steps hold sections that came from no template: " + unbacked
                    + ". A blueprint places templates, so promote each of those sections into a template first.");
        }

        List<BlueprintPlacement> placements = new java.util.ArrayList<>();
        int order = 10;
        for (Step step : placed) {
            placements.add(new BlueprintPlacement(
                    step.key().value(),
                    templateIdBySectionId.get(step.sectionDefinitionId()),
                    order,
                    step.group(),
                    step.repeating(),
                    step.visibleWhen()));
            order += 10;
        }

        return new FormBlueprint(
                id,
                form.tenantId(),
                key,
                name == null || name.isBlank() ? form.name() : name,
                1,
                form.entityType(),
                RecognitionHints.none(),
                // Every named condition, not only the referenced ones. A blueprint carrying a rule
                // whose `ref` resolves to nothing would instantiate into a form that cannot compile,
                // and the author would meet the failure one step removed from the cause.
                form.namedConditions(),
                placements,
                SectionTemplate.TemplateStatus.ACTIVE);
    }

    public List<BlueprintPlacement> orderedPlacements() {
        return placements.stream()
                .sorted(Comparator.comparingInt(BlueprintPlacement::order))
                .toList();
    }

    /** Template ids this blueprint needs, in placement order and deduplicated. */
    public List<String> requiredTemplateIds() {
        return orderedPlacements().stream()
                .map(BlueprintPlacement::sectionTemplateId)
                .distinct()
                .toList();
    }

    /**
     * Which templates are missing from a candidate set.
     *
     * <p>Checked before instantiating rather than during: a blueprint half-applied because a
     * template had been deprecated would leave a tenant with a form that looks complete and is not.
     */
    public List<String> missingTemplates(Map<String, SectionTemplate> available) {
        return requiredTemplateIds().stream()
                .filter(templateId -> !available.containsKey(templateId))
                .toList();
    }
}
