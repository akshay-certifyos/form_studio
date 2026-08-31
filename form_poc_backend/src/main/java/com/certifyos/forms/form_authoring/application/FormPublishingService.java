package com.certifyos.forms.form_authoring.application;

import com.certifyos.forms.form_authoring.application.command.PreviewChangeSet;
import com.certifyos.forms.form_authoring.application.command.PublishFormVersion;
import com.certifyos.forms.form_authoring.domain.compile.CatalogSnapshot;
import com.certifyos.forms.form_authoring.domain.compile.CompilationFailedException;
import com.certifyos.forms.form_authoring.domain.compile.CompilationReport;
import com.certifyos.forms.form_authoring.domain.compile.FormCompiler;
import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.event.FormVersionPublished;
import com.certifyos.forms.form_authoring.domain.port.DomainEventPublisher;
import com.certifyos.forms.form_authoring.domain.port.FormDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.FormVersionRepository;
import com.certifyos.forms.form_authoring.domain.port.QuestionCatalogPort;
import com.certifyos.forms.form_authoring.domain.port.SectionDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.publishing.ChangeSet;
import com.certifyos.forms.form_authoring.domain.publishing.CompiledForm;
import com.certifyos.forms.form_authoring.domain.publishing.FormVersion;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates publishing. Holds no business rules.
 *
 * <p>Load the aggregate, resolve what the compiler needs, call the domain, persist, emit an event.
 * Every decision — how to compile, how to classify a change, what a version is allowed to be — sits
 * in {@code domain}. If a method here grew an {@code if} that a credentialing analyst would
 * recognise as a policy, it would be in the wrong layer.
 */
public class FormPublishingService {

    private final FormDefinitionRepository definitions;
    private final SectionDefinitionRepository sections;
    private final FormVersionRepository versions;
    private final QuestionCatalogPort catalog;
    private final DomainEventPublisher events;
    private final Clock clock;

    public FormPublishingService(
            FormDefinitionRepository definitions,
            SectionDefinitionRepository sections,
            FormVersionRepository versions,
            QuestionCatalogPort catalog,
            DomainEventPublisher events,
            Clock clock) {
        this.definitions = definitions;
        this.sections = sections;
        this.versions = versions;
        this.catalog = catalog;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Compiles, diffs, persists an immutable version, and announces it.
     *
     * @throws CompilationFailedException carrying every problem, so the author fixes them in one
     *     pass rather than one per publish attempt
     */
    public FormVersion handle(PublishFormVersion command) {
        FormDefinition definition = definitions.require(command.formDefinitionId());
        Resolution resolution = resolve(definition);

        CompiledForm artifact = FormCompiler.compile(definition, resolution.sections(), resolution.catalog());

        Optional<FormVersion> active = versions.findActive(definition.id());
        ChangeSet changeSet =
                ChangeSet.between(active.map(FormVersion::artifact).orElse(null), artifact);

        Instant now = clock.instant();
        FormVersion published = FormVersion.publish(
                UUID.randomUUID().toString(),
                definition,
                artifact,
                changeSet,
                active.map(FormVersion::version).orElse(0),
                command.changelog(),
                command.ticketId(),
                command.requestedBy(),
                now);

        FormVersion saved = versions.save(published);

        // Publishing states what happened. It does not reach into anyone's answers.
        events.publish(new FormVersionPublished(
                saved.id(),
                saved.formDefinitionId(),
                saved.tenantId(),
                saved.version(),
                changeSet.changeClass(),
                changeSet.keysRequiringReset(),
                now));

        return saved;
    }

    /**
     * Compile and diff without persisting.
     *
     * <p>This is what turns publishing from a leap into a decision: the author sees the change
     * class and the exact keys at risk <em>before</em> committing, rather than discovering
     * afterwards that a checkbox wiped everyone's work.
     */
    public Preview handle(PreviewChangeSet command) {
        FormDefinition definition = definitions.require(command.formDefinitionId());
        Resolution resolution = resolve(definition);

        FormCompiler.Result result = FormCompiler.analyze(definition, resolution.sections(), resolution.catalog());
        if (!result.report().isClean()) {
            return new Preview(null, result.report());
        }

        ChangeSet changeSet = ChangeSet.between(
                versions.findActive(definition.id()).map(FormVersion::artifact).orElse(null), result.artifact());

        // The compiler's own report, not an empty one. It used to be discarded here on the grounds
        // that a clean form has nothing to report — which stopped being true once the report also
        // carried notices, and dropped them on exactly the path where they matter: the form is
        // publishable, so nothing else will ever mention the hard stop that will not fire.
        return new Preview(changeSet, result.report());
    }

    /** @param changeSet null when the form does not compile */
    public record Preview(ChangeSet changeSet, CompilationReport report) {
        public boolean compiles() {
            return report.isClean();
        }
    }

    // ------------------------------------------------------------------

    /** Everything the compiler needs, fetched in two batched calls rather than N. */
    private Resolution resolve(FormDefinition definition) {
        List<Step> steps = definition.orderedSteps();

        Set<String> sectionIds =
                steps.stream().map(Step::sectionDefinitionId).collect(java.util.stream.Collectors.toSet());

        Map<String, SectionDefinition> sectionsById = new LinkedHashMap<>();
        sections.findAllById(sectionIds).forEach(s -> sectionsById.put(s.id(), s));

        Set<QuestionId> questionIds = new LinkedHashSet<>();
        for (SectionDefinition section : sectionsById.values()) {
            for (QuestionInstance instance : section.enabledQuestions()) {
                questionIds.add(instance.catalogQuestionId());
            }
        }

        // The port resolves the option sets these questions reference — see QuestionCatalogPort.
        CatalogSnapshot snapshot = catalog.resolve(questionIds);
        return new Resolution(sectionsById, snapshot);
    }

    private record Resolution(Map<String, SectionDefinition> sections, CatalogSnapshot catalog) {}
}
