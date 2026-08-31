package com.certifyos.forms.form_authoring.domain.reuse;

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
     */
    public record BlueprintPlacement(
            String stepKey,
            String sectionTemplateId,
            int order,
            String group,
            com.certifyos.forms.form_authoring.domain.definition.Step.Repeating repeating) {

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
