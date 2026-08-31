package com.certifyos.forms.form_authoring.infrastructure.mongo;

import com.certifyos.forms.form_authoring.domain.port.SectionTemplateRepository;
import com.certifyos.forms.form_authoring.domain.reuse.SectionTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Mongo-backed {@link SectionTemplateRepository}. */
@ApplicationScoped
public class MongoSectionTemplateRepository implements SectionTemplateRepository {

    private final SectionTemplatePanacheRepository documents;

    @Inject
    public MongoSectionTemplateRepository(SectionTemplatePanacheRepository documents) {
        this.documents = documents;
    }

    @Override
    public Optional<SectionTemplate> findById(String id) {
        return documents.findByIdOptional(id).map(SectionTemplateDocument::toDomain);
    }

    /** One query for every template a blueprint places, rather than one per placement. */
    @Override
    public List<SectionTemplate> findAllById(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return documents.find("_id in ?1", List.copyOf(ids)).stream()
                .map(SectionTemplateDocument::toDomain)
                .toList();
    }

    /**
     * Global templates plus the tenant's own.
     *
     * <p>{@code tenantId is null} is the global case and is part of the predicate rather than a
     * second query, so a tenant browsing templates sees one list in one round trip.
     */
    @Override
    public List<SectionTemplate> findAvailableFor(String tenantId) {
        return documents.find("status = ?1 and (tenantId is null or tenantId = ?2)", "active", tenantId).stream()
                .map(SectionTemplateDocument::toDomain)
                .toList();
    }

    @Override
    public SectionTemplate save(SectionTemplate template) {
        documents.persistOrUpdate(SectionTemplateDocument.from(template));
        return template;
    }
}
