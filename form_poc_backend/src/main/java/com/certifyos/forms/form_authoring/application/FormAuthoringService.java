package com.certifyos.forms.form_authoring.application;

import com.certifyos.forms.form_authoring.application.command.CreateFormFromBlueprint;
import com.certifyos.forms.form_authoring.application.command.UpdateStepCondition;
import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.definition.StepKey;
import com.certifyos.forms.form_authoring.domain.port.FormBlueprintRepository;
import com.certifyos.forms.form_authoring.domain.port.FormDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionTemplateRepository;
import com.certifyos.forms.form_authoring.domain.reuse.FormBlueprint;
import com.certifyos.forms.form_authoring.domain.reuse.SectionTemplate;
import com.certifyos.forms.shared_kernel.exception.ConflictingState;
import com.certifyos.forms.shared_kernel.exception.InvariantViolated;
import com.certifyos.forms.shared_kernel.exception.NotFound;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Edits to a draft form. Holds no business rules.
 *
 * <p>Separate from {@link FormPublishingService} because the two have different risk profiles:
 * authoring mutates a draft and is cheap to undo, publishing creates an immutable artifact that
 * providers answer against. Keeping them apart makes it obvious which endpoints can lose work.
 *
 * <p>Deliberately permissive: a save applies the aggregate's own invariants but does <em>not</em>
 * run the expression analyzer. Authoring passes through states that are temporarily invalid — you
 * reorder two steps, and for one save a condition points forward. Rejecting that would make the
 * form uneditable. Analysis is the compiler's job, and {@code /validate} is how the studio asks for
 * it, so the authoritative answer arrives before publishing rather than at save time.
 */
public class FormAuthoringService {

    private final FormDefinitionRepository definitions;
    private final FormBlueprintRepository blueprints;
    private final SectionTemplateRepository templates;
    private final SectionDefinitionRepository sections;

    public FormAuthoringService(
            FormDefinitionRepository definitions,
            FormBlueprintRepository blueprints,
            SectionTemplateRepository templates,
            SectionDefinitionRepository sections) {
        this.definitions = definitions;
        this.blueprints = blueprints;
        this.templates = templates;
        this.sections = sections;
    }

    /**
     * Creates a tenant's form from a shared blueprint.
     *
     * <p>The blueprint names section <em>templates</em>, so this instantiates each one into a section
     * definition the tenant owns, then places those sections as steps. Copy-on-use throughout: after
     * this returns, nothing the tenant has is reachable from a later edit to the blueprint or its
     * templates.
     *
     * <p>Note the two-pass shape. Every template is resolved and checked <em>before</em> anything is
     * written, because a blueprint half-applied because one template had been deprecated leaves a
     * tenant with a form that looks complete and is not — and no error to explain it.
     */
    public FormDefinition handle(CreateFormFromBlueprint command) {
        FormBlueprint blueprint = blueprints
                .findById(command.blueprintId())
                .orElseThrow(() -> new NotFound("Form blueprint", command.blueprintId()));

        Map<String, SectionTemplate> available = new LinkedHashMap<>();
        templates.findAllById(blueprint.requiredTemplateIds()).forEach(t -> available.put(t.id(), t));

        List<String> missing = blueprint.missingTemplates(available);
        if (!missing.isEmpty()) {
            throw new InvariantViolated("Blueprint " + blueprint.key() + " needs section template(s) " + missing
                    + ", which are not available. Nothing was created.");
        }

        String formId = "fd_" + UUID.randomUUID();
        List<Step> steps = new java.util.ArrayList<>();

        for (FormBlueprint.BlueprintPlacement placement : blueprint.orderedPlacements()) {
            SectionTemplate template = available.get(placement.sectionTemplateId());

            // One section definition per placement, not per template. The same template placed twice
            // must yield two independent sections, or the two steps would share content and the
            // author could not customise one without the other.
            SectionDefinition section =
                    sections.save(template.instantiate("sd_" + UUID.randomUUID(), command.tenantId(), null));

            steps.add(new Step(
                    StepKey.of(placement.stepKey()),
                    section.id(),
                    placement.order(),
                    true,
                    null,
                    placement.group(),
                    placement.repeating() != null ? placement.repeating() : template.repeating(),
                    null,
                    null));
        }

        return definitions.save(new FormDefinition(
                formId,
                command.tenantId(),
                null,
                command.name() == null || command.name().isBlank() ? blueprint.name() : command.name(),
                blueprint.entityType(),
                blueprint.id(),
                blueprint.version(),
                Map.of(),
                steps,
                List.of(),
                FormDefinition.DefinitionStatus.DRAFT));
    }

    public FormDefinition handle(UpdateStepCondition command) {
        FormDefinition definition = definitions
                .findById(command.formDefinitionId())
                .orElseThrow(() -> new NotFound("Form definition", command.formDefinitionId()));

        if (definition.status() == FormDefinition.DefinitionStatus.PUBLISHED) {
            // A published definition is the record of what was published. Editing it would rewrite
            // history for providers mid-application; a new draft is the way forward.
            throw new ConflictingState("Form " + definition.id()
                    + " is published. Editing it would change a form providers have already answered.");
        }

        Step step = definition.step(command.stepKey()).orElseThrow(() -> new NotFound("Step", command.stepKey()));

        return definitions.save(definition.replaceStep(step.withVisibleWhen(command.visibleWhen())));
    }
}
