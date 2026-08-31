package com.certifyos.forms.form_authoring.application;

import com.certifyos.forms.form_authoring.application.command.CreateSectionFromTemplate;
import com.certifyos.forms.form_authoring.domain.definition.Layout;
import com.certifyos.forms.form_authoring.domain.definition.Origin;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.port.SectionDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionTemplateRepository;
import com.certifyos.forms.form_authoring.domain.reuse.DriftCalculator;
import com.certifyos.forms.form_authoring.domain.reuse.SectionDrift;
import com.certifyos.forms.form_authoring.domain.reuse.SectionTemplate;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.port.QuestionRepository;
import com.certifyos.forms.shared_kernel.exception.ConflictingState;
import com.certifyos.forms.shared_kernel.exception.InvariantViolated;
import com.certifyos.forms.shared_kernel.exception.NotFound;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Section authoring: instantiate from a template, customise, inspect drift, promote back.
 *
 * <p>This is the loop the design is really proposing. A tenant takes a shared template, switches off
 * what does not apply, adds what does, and — if the change turns out to be general — pushes it back
 * as a new template version. Holds no business rules: instantiation, promotion and drift all live in
 * {@code domain.reuse}.
 */
public class SectionAuthoringService {

    private final SectionDefinitionRepository sections;
    private final SectionTemplateRepository templates;
    private final QuestionRepository questions;

    public SectionAuthoringService(
            SectionDefinitionRepository sections, SectionTemplateRepository templates, QuestionRepository questions) {
        this.sections = sections;
        this.templates = templates;
        this.questions = questions;
    }

    public SectionDefinition handle(CreateSectionFromTemplate command) {
        SectionTemplate template = templates
                .findById(command.sectionTemplateId())
                .orElseThrow(() -> new NotFound("Section template", command.sectionTemplateId()));

        return sections.save(template.instantiate("sd_" + UUID.randomUUID(), command.tenantId(), command.name()));
    }

    /**
     * Adds a question that the template does not have.
     *
     * <p>Recorded as {@link Origin#ADDED}, which is what later lets drift distinguish "I added this"
     * from "the template dropped it" — the two look identical without provenance.
     */
    public SectionDefinition addQuestion(
            String sectionId, String questionKey, String catalogQuestionId, boolean required, int order) {

        SectionDefinition section = require(sectionId);

        if (section.question(questionKey).isPresent()) {
            throw new ConflictingState("Section " + sectionId + " already has a question keyed '" + questionKey
                    + "'. Keys become answer paths, so they must be unique within a section.");
        }

        QuestionId catalogId = QuestionId.of(catalogQuestionId);
        if (questions.findById(catalogId).isEmpty()) {
            // Checked here rather than at compile time so the author hears about it while they are
            // looking at the section, not at publish.
            throw new InvariantViolated(
                    "No catalog question '" + catalogQuestionId + "'. Propose it in the catalog first.");
        }

        List<QuestionInstance> next = new ArrayList<>(section.questions());
        next.add(new QuestionInstance(
                questionKey,
                catalogId,
                Origin.ADDED,
                true,
                order,
                required,
                Layout.FULL,
                null,
                null,
                null,
                null,
                null,
                null));

        return sections.save(withQuestions(section, next));
    }

    /**
     * Switches a question on or off.
     *
     * <p>Never deletes. A template-inherited question that was removed would lose the provenance a
     * later template upgrade needs to reconcile against, so the model disables instead — and the
     * compiler drops disabled questions from the artifact entirely, so the runtime effect is the
     * same as deletion without the information loss.
     */
    public SectionDefinition setQuestionEnabled(String sectionId, String questionKey, boolean enabled) {
        SectionDefinition section = require(sectionId);
        QuestionInstance existing =
                section.question(questionKey).orElseThrow(() -> new NotFound("Question", questionKey));

        List<QuestionInstance> next = section.questions().stream()
                .map(instance -> instance.key().equals(questionKey) ? toggle(existing, enabled) : instance)
                .toList();

        return sections.save(withQuestions(section, next));
    }

    /**
     * Reorders a section's questions to the given key sequence.
     *
     * <p>Takes the whole list, so the invariant lives in the aggregate rather than here — the service
     * only translates the aggregate's refusal into a 422 the client can act on. A partial reorder is
     * not offered at all, because there is no safe version of it: an omitted key keeps its old number
     * and two questions can end up sharing one, which makes the sort unstable rather than wrong in a
     * way anything would notice.
     */
    public SectionDefinition reorderQuestions(String sectionId, List<String> orderedKeys) {
        SectionDefinition section = require(sectionId);
        try {
            return sections.save(section.reorderQuestions(orderedKeys));
        } catch (IllegalArgumentException e) {
            throw new InvariantViolated(e.getMessage());
        }
    }

    public SectionDrift drift(String sectionId) {
        SectionDefinition section = require(sectionId);
        return DriftCalculator.calculate(
                section,
                section.sourceTemplateId() == null
                        ? java.util.Optional.empty()
                        : templates.findById(section.sourceTemplateId()));
    }

    /**
     * Promotes a customised section into the next version of its template.
     *
     * <p>Only sections that came from a template can be promoted in v0. Promoting a from-scratch
     * section would mean minting a brand-new template, which is a different operation with a
     * different question to answer — what should it be called, and should it be global?
     */
    public SectionTemplate promote(String sectionId) {
        SectionDefinition section = require(sectionId);

        if (section.sourceTemplateId() == null) {
            throw new InvariantViolated(
                    "Section " + sectionId + " was authored from scratch, so there is no template to promote it into.");
        }

        SectionTemplate template = templates
                .findById(section.sourceTemplateId())
                .orElseThrow(() -> new NotFound("Section template", section.sourceTemplateId()));

        SectionTemplate promoted = templates.save(template.nextVersionFrom(section));

        // The section is now level with the template it fed, so record that: leaving the old version
        // behind would report drift against changes this section itself contributed.
        //
        // Re-origining matters as much as the version bump. A question that was ADDED here is
        // template-inherited once the template carries it, and leaving it marked ADDED made drift
        // report it as a local addition forever — the author promotes, and the indicator never
        // clears.
        List<QuestionInstance> reconciled = section.questions().stream()
                .map(instance -> promoted.question(instance.key()).isPresent() && instance.origin() == Origin.ADDED
                        ? reorigin(instance, Origin.TEMPLATE)
                        : instance)
                .toList();

        sections.save(new SectionDefinition(
                section.id(),
                section.tenantId(),
                section.key(),
                section.name(),
                section.intro(),
                section.sourceTemplateId(),
                promoted.version(),
                reconciled,
                section.active()));

        return promoted;
    }

    private SectionDefinition require(String sectionId) {
        return sections.findById(sectionId).orElseThrow(() -> new NotFound("Section", sectionId));
    }

    private static QuestionInstance reorigin(QuestionInstance instance, Origin origin) {
        return new QuestionInstance(
                instance.key(),
                instance.catalogQuestionId(),
                origin,
                instance.enabled(),
                instance.order(),
                instance.required(),
                instance.layout(),
                instance.labelOverride(),
                instance.helpTextOverride(),
                instance.visibleWhen(),
                instance.requiredWhen(),
                instance.defaultWhen(),
                instance.validWhen());
    }

    private static QuestionInstance toggle(QuestionInstance instance, boolean enabled) {
        return new QuestionInstance(
                instance.key(),
                instance.catalogQuestionId(),
                instance.origin(),
                enabled,
                instance.order(),
                instance.required(),
                instance.layout(),
                instance.labelOverride(),
                instance.helpTextOverride(),
                instance.visibleWhen(),
                instance.requiredWhen(),
                instance.defaultWhen(),
                instance.validWhen());
    }

    private static SectionDefinition withQuestions(SectionDefinition section, List<QuestionInstance> questions) {
        return new SectionDefinition(
                section.id(),
                section.tenantId(),
                section.key(),
                section.name(),
                section.intro(),
                section.sourceTemplateId(),
                section.sourceTemplateVersion(),
                questions,
                section.active());
    }
}
