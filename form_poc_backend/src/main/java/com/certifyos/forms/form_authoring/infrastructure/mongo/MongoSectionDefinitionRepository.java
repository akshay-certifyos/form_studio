package com.certifyos.forms.form_authoring.infrastructure.mongo;

import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.port.SectionDefinitionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Mongo-backed {@link SectionDefinitionRepository}. */
@ApplicationScoped
public class MongoSectionDefinitionRepository implements SectionDefinitionRepository {

    private final SectionDefinitionPanacheRepository documents;

    @Inject
    public MongoSectionDefinitionRepository(SectionDefinitionPanacheRepository documents) {
        this.documents = documents;
    }

    @Override
    public Optional<SectionDefinition> findById(String id) {
        return documents.findByIdOptional(id).map(SectionDefinitionDocument::toDomain);
    }

    /** One query for every section a form places, rather than one per step. */
    @Override
    public List<SectionDefinition> findAllById(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return documents.find("_id in ?1", List.copyOf(ids)).stream()
                .map(SectionDefinitionDocument::toDomain)
                .toList();
    }

    @Override
    public List<SectionDefinition> findByTenant(String tenantId) {
        return documents.find("tenantId", tenantId).stream()
                .map(SectionDefinitionDocument::toDomain)
                .toList();
    }

    @Override
    public SectionDefinition save(SectionDefinition definition) {
        documents.persistOrUpdate(SectionDefinitionDocument.from(definition));
        return definition;
    }
}
