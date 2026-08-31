package com.certifyos.forms.boot;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.certifyos.forms.form_authoring.application.FormPublishingService;
import com.certifyos.forms.form_authoring.domain.port.DomainEventPublisher;
import com.certifyos.forms.form_authoring.domain.port.FormDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.FormVersionRepository;
import com.certifyos.forms.form_authoring.domain.port.QuestionCatalogPort;
import com.certifyos.forms.form_authoring.domain.port.SectionDefinitionRepository;
import com.certifyos.forms.question_catalog.domain.port.OptionSetRepository;
import com.certifyos.forms.question_catalog.domain.port.QuestionRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the application actually starts.
 *
 * <p>Worth its own test because every other test here is plain JUnit with hand-constructed objects.
 * Those verify the domain but say nothing about whether Quarkus can wire it: a missing producer, an
 * ambiguous bean, a Panache repository that cannot be proxied, or two implementations of one port
 * would all pass 363 unit tests and then fail on the first {@code make dev}.
 *
 * <p>Injecting every port by its <em>interface</em> is the point — it fails if a port has no
 * implementation, and equally if it has two and CDI cannot choose.
 */
@QuarkusTest
class ApplicationBootTest {

    @Inject
    FormPublishingService publishing;

    @Inject
    FormDefinitionRepository formDefinitions;

    @Inject
    SectionDefinitionRepository sectionDefinitions;

    @Inject
    FormVersionRepository formVersions;

    @Inject
    QuestionRepository questions;

    @Inject
    OptionSetRepository optionSets;

    @Inject
    QuestionCatalogPort catalogPort;

    @Inject
    DomainEventPublisher events;

    @Test
    @DisplayName("the container starts and every port resolves to exactly one implementation")
    void everyPortIsWired() {
        assertNotNull(publishing, "FormPublishingService — check the ApplicationBeans producer");
        assertNotNull(formDefinitions);
        assertNotNull(sectionDefinitions);
        assertNotNull(formVersions);
        assertNotNull(questions);
        assertNotNull(optionSets);
        assertNotNull(catalogPort, "the cross-context seam");
        assertNotNull(events, "the event boundary");
    }
}
