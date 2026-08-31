package com.certifyos.forms.form_authoring.application;

import com.certifyos.forms.form_authoring.application.command.CreateBlankForm;
import com.certifyos.forms.form_authoring.application.command.CreateBlueprintFromForm;
import com.certifyos.forms.form_authoring.application.command.CreateFormFromBlueprint;
import com.certifyos.forms.form_authoring.application.command.PlaceSection;
import com.certifyos.forms.form_authoring.application.command.RemoveNamedCondition;
import com.certifyos.forms.form_authoring.application.command.RemoveStep;
import com.certifyos.forms.form_authoring.application.command.ReorderSteps;
import com.certifyos.forms.form_authoring.application.command.UpdateStepCondition;
import com.certifyos.forms.form_authoring.application.command.UpsertNamedCondition;
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
                    // The blueprint's rule, not null. Instantiating a shape and then discarding
                    // every condition it carried would hand the author a form whose steps all show
                    // unconditionally — the exact defect three Florida Blue sections ship with
                    // today, reintroduced by the tool meant to fix it.
                    placement.visibleWhen(),
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
                blueprint.namedConditions(),
                steps,
                List.of(),
                FormDefinition.DefinitionStatus.DRAFT));
    }

    public FormDefinition handle(UpdateStepCondition command) {
        FormDefinition definition = requireDraft(command.formDefinitionId());
        Step step = definition.step(command.stepKey()).orElseThrow(() -> new NotFound("Step", command.stepKey()));

        return definitions.save(definition.replaceStep(step.withVisibleWhen(command.visibleWhen())));
    }

    // ------------------------------------------------------------------
    // assembly
    // ------------------------------------------------------------------

    /**
     * Starts an empty form.
     *
     * <p>Deliberately not "create a form and give it a first step". An empty form is a legitimate
     * state — it is where every form built for a payer nobody has onboarded before begins — and it
     * compiles to an artifact with no steps rather than to an error, so the studio can show it
     * immediately instead of holding the author on a modal until they have decided the first section.
     */
    public FormDefinition handle(CreateBlankForm command) {
        return definitions.save(FormDefinition.draft(
                "fd_" + UUID.randomUUID(), command.tenantId(), command.name(), command.entityType()));
    }

    /**
     * Places a section into a form as a new step.
     *
     * <p>Checks the section exists <em>and</em> belongs to this tenant. The second half matters more
     * than it looks: a step holds only a section id, so a form pointing at another tenant's section
     * would compile perfectly and serve one client's questions to another's providers. There is no
     * later stage that would catch it, because nothing downstream re-checks ownership.
     */
    public FormDefinition handle(PlaceSection command) {
        FormDefinition definition = requireDraft(command.formDefinitionId());

        SectionDefinition section = sections.findById(command.sectionDefinitionId())
                .orElseThrow(() -> new NotFound("Section", command.sectionDefinitionId()));

        // Objects.equals rather than a direct call: nothing in the aggregates forbids a null tenant,
        // so a direct .equals would turn a tenancy check into a 500 — the one failure mode a
        // tenancy check must not have.
        if (!java.util.Objects.equals(definition.tenantId(), section.tenantId())) {
            throw new InvariantViolated("Section " + section.id() + " belongs to another tenant.");
        }

        int order = command.order() != null
                ? command.order()
                : definition.steps().stream().mapToInt(Step::order).max().orElse(0) + 10;

        try {
            return definitions.save(definition.placeStep(new Step(
                    StepKey.of(command.stepKey()),
                    section.id(),
                    order,
                    true,
                    command.titleOverride(),
                    command.group(),
                    command.repeating(),
                    null,
                    null)));
        } catch (IllegalArgumentException e) {
            // Covers both an invalid step key and a duplicate one. Both are the caller's to fix and
            // both already carry a message that says how, so re-wording here would only lose detail.
            throw new InvariantViolated(e.getMessage());
        }
    }

    public FormDefinition handle(RemoveStep command) {
        FormDefinition definition = requireDraft(command.formDefinitionId());
        try {
            return definitions.save(definition.removeStep(command.stepKey()));
        } catch (IllegalArgumentException e) {
            throw new NotFound("Step", command.stepKey());
        }
    }

    public FormDefinition handle(ReorderSteps command) {
        FormDefinition definition = requireDraft(command.formDefinitionId());
        try {
            return definitions.save(definition.reorderSteps(command.orderedKeys()));
        } catch (IllegalArgumentException e) {
            throw new InvariantViolated(e.getMessage());
        }
    }

    public FormDefinition handle(UpsertNamedCondition command) {
        FormDefinition definition = requireDraft(command.formDefinitionId());
        return definitions.save(definition.withNamedCondition(
                new FormDefinition.NamedCondition(command.key(), command.label(), command.expression())));
    }

    public FormDefinition handle(RemoveNamedCondition command) {
        FormDefinition definition = requireDraft(command.formDefinitionId());
        if (!definition.namedConditions().containsKey(command.key())) {
            throw new NotFound("Named condition", command.key());
        }
        try {
            return definitions.save(definition.removeNamedCondition(command.key()));
        } catch (IllegalStateException e) {
            // Still referenced by steps. A conflict rather than an invariant violation: the request
            // is well-formed and would be legal once those steps stop using it.
            throw new ConflictingState(e.getMessage());
        }
    }

    /**
     * Promotes an assembled form into a reusable blueprint.
     *
     * <p>Resolves each placed section's source template first, because that is what a blueprint
     * points at. Sections authored from scratch have none, and the aggregate refuses rather than
     * quietly dropping those steps — so the author is told to promote the sections first, which is
     * the actual dependency rather than a rule invented for this endpoint.
     */
    public FormBlueprint handle(CreateBlueprintFromForm command) {
        FormDefinition definition = definitions
                .findById(command.formDefinitionId())
                .orElseThrow(() -> new NotFound("Form definition", command.formDefinitionId()));

        // 404 rather than 403, deliberately: a tenant asking about another tenant's form should not
        // learn that it exists. Checked here and not only in PlaceSection, because the response
        // carries the form's whole shape — step keys, grouping, conditions — and that is exactly what
        // a competitor's configuration would be.
        if (!java.util.Objects.equals(definition.tenantId(), command.tenantId())) {
            throw new NotFound("Form definition", command.formDefinitionId());
        }

        List<String> sectionIds = definition.orderedSteps().stream()
                .map(Step::sectionDefinitionId)
                .distinct()
                .toList();

        Map<String, String> templateBySection = new LinkedHashMap<>();
        for (SectionDefinition section : sections.findAllById(sectionIds)) {
            if (section.sourceTemplateId() != null) {
                templateBySection.put(section.id(), section.sourceTemplateId());
            }
        }

        try {
            return blueprints.save(FormBlueprint.fromForm(
                    "bp_" + UUID.randomUUID(), command.key(), command.name(), definition, templateBySection));
        } catch (IllegalStateException e) {
            throw new InvariantViolated(e.getMessage());
        }
    }

    /**
     * Loads a form and refuses if it is published.
     *
     * <p>Every assembly verb goes through this. A published definition is the record of what was
     * published — editing it would rewrite history for providers who are part-way through answering
     * it, so the way forward is a new draft rather than a mutation.
     */
    private FormDefinition requireDraft(String formId) {
        FormDefinition definition =
                definitions.findById(formId).orElseThrow(() -> new NotFound("Form definition", formId));

        if (definition.status() == FormDefinition.DefinitionStatus.PUBLISHED) {
            throw new ConflictingState("Form " + definition.id()
                    + " is published. Editing it would change a form providers have already answered.");
        }
        return definition;
    }
}
