package com.certifyos.forms.form_authoring.application;

import com.certifyos.forms.form_authoring.application.command.CreateBlankSection;
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

    /**
     * Starts an empty section the tenant owns outright.
     *
     * <p>No source template, and the null is load-bearing — see {@code SectionDefinition.blank}. This
     * is the path for the part of a payer form that is genuinely specific to it, which every real
     * form examined had some of.
     */
    public SectionDefinition handle(CreateBlankSection command) {
        return sections.save(
                SectionDefinition.blank("sd_" + UUID.randomUUID(), command.tenantId(), command.key(), command.name()));
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
     * Makes a section reusable.
     *
     * <p>One verb, two paths, because from the author's side there is one intention — "other forms
     * should be able to start from this". Which path applies is a fact about the section, not a
     * decision to put to the author:
     *
     * <ul>
     *   <li>Came from a template → mints version n+1 of that template.
     *   <li>Authored from scratch → mints a brand-new template at version 1, and links the section to
     *       it so drift becomes computable from here on.
     * </ul>
     *
     * <p>The second path needs a {@code key}, since nothing exists to inherit one from. It was
     * originally refused outright with a note that it "would mean minting a brand-new template,
     * which is a different operation" — true, and it is the operation a from-scratch form needs
     * before it can become a blueprint, so it is now built rather than deferred.
     *
     * @param key required only when the section came from no template; ignored otherwise
     * @param name null keeps the section's own name
     */
    public SectionTemplate promote(String sectionId, String key, String name) {
        SectionDefinition section = require(sectionId);

        if (section.sourceTemplateId() == null) {
            return mintTemplate(section, key, name);
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

    /** Convenience for the template-backed path, which needs neither key nor name. */
    public SectionTemplate promote(String sectionId) {
        return promote(sectionId, null, null);
    }

    /**
     * Mints a first-version template from a section that came from none, and links the section to it.
     *
     * <p>The link is the point. Without it the section would report no drift forever — not because it
     * is in sync, but because there is nothing recorded to compare against — and the author would
     * have no way to tell "level with the template" from "never connected to one".
     */
    private SectionTemplate mintTemplate(SectionDefinition section, String key, String name) {
        if (key == null || key.isBlank()) {
            throw new InvariantViolated("Section " + section.id()
                    + " came from no template, so promoting it creates one. That needs a key to identify the "
                    + "new template by.");
        }

        SectionTemplate minted =
                templates.save(SectionTemplate.fromSection("st_" + UUID.randomUUID(), key, name, section));

        // Every enabled question is now the template's, so re-origin them for the same reason the
        // upgrade path does: a question left marked ADDED reports as a local addition forever, and
        // the drift indicator never clears no matter how many times the author promotes.
        List<QuestionInstance> reconciled = section.questions().stream()
                .map(instance -> minted.question(instance.key()).isPresent() && instance.origin() == Origin.ADDED
                        ? reorigin(instance, Origin.TEMPLATE)
                        : instance)
                .toList();

        sections.save(new SectionDefinition(
                section.id(),
                section.tenantId(),
                section.key(),
                section.name(),
                section.intro(),
                minted.id(),
                minted.version(),
                reconciled,
                section.active()));

        return minted;
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
