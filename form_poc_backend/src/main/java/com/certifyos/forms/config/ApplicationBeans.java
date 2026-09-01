package com.certifyos.forms.config;

import com.certifyos.forms.form_authoring.application.FormAuthoringService;
import com.certifyos.forms.form_authoring.application.FormPublishingService;
import com.certifyos.forms.form_authoring.application.RulesInventory;
import com.certifyos.forms.form_authoring.application.SectionAuthoringService;
import com.certifyos.forms.form_authoring.domain.port.DomainEventPublisher;
import com.certifyos.forms.form_authoring.domain.port.FormBlueprintRepository;
import com.certifyos.forms.form_authoring.domain.port.FormDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.FormVersionRepository;
import com.certifyos.forms.form_authoring.domain.port.QuestionCatalogPort;
import com.certifyos.forms.form_authoring.domain.port.SectionDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionTemplateRepository;
import com.certifyos.forms.question_catalog.application.CatalogService;
import com.certifyos.forms.question_catalog.domain.port.OptionSetRepository;
import com.certifyos.forms.question_catalog.domain.port.QuestionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

/**
 * Wires the application services.
 *
 * <p>The services take plain constructor arguments rather than field injection, which is what makes
 * them testable with in-memory doubles and no container. The cost is this file; the benefit is that
 * 350-odd tests run in two seconds without starting Quarkus.
 */
public class ApplicationBeans {

    /**
     * Injected rather than read, so a published version is reproducible in a test — a version's
     * timestamp is part of what makes it an audit record.
     */
    @Produces
    @ApplicationScoped
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Produces
    @ApplicationScoped
    public FormAuthoringService formAuthoringService(
            FormDefinitionRepository definitions,
            FormBlueprintRepository blueprints,
            SectionTemplateRepository templates,
            SectionDefinitionRepository sections) {
        return new FormAuthoringService(definitions, blueprints, templates, sections);
    }

    /**
     * A projection over three reads: forms, sections, and the catalog.
     *
     * <p>The catalog earns its place by turning {@code applicantDetails.specialty} into "Primary
     * specialty". A rule an author cannot read is a rule they will not check, so on a screen whose
     * entire purpose is inspection the label is not decoration.
     */
    @Produces
    @ApplicationScoped
    public RulesInventory rulesInventory(
            FormDefinitionRepository definitions, SectionDefinitionRepository sections, QuestionRepository questions) {
        return new RulesInventory(definitions, sections, questions);
    }

    @Produces
    @ApplicationScoped
    public SectionAuthoringService sectionAuthoringService(
            SectionDefinitionRepository sections, SectionTemplateRepository templates, QuestionRepository questions) {
        return new SectionAuthoringService(sections, templates, questions);
    }

    @Produces
    @ApplicationScoped
    public FormPublishingService formPublishingService(
            FormDefinitionRepository definitions,
            SectionDefinitionRepository sections,
            FormVersionRepository versions,
            QuestionCatalogPort catalog,
            DomainEventPublisher events,
            Clock clock) {
        return new FormPublishingService(definitions, sections, versions, catalog, events, clock);
    }

    @Produces
    @ApplicationScoped
    public CatalogService catalogService(QuestionRepository questions, OptionSetRepository optionSets) {
        return new CatalogService(questions, optionSets);
    }
}
