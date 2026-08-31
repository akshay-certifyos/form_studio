package com.certifyos.forms.form_authoring.infrastructure.mongo;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.port.FormDefinitionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

/**
 * Mongo-backed {@link FormDefinitionRepository}.
 *
 * <p>Panache is confined to {@link FormDefinitionPanacheRepository}; this class maps at the
 * boundary, so a change to the stored shape never reaches the aggregate.
 *
 * <p>No {@code @Transactional}: an aggregate is one document, and a single-document write is already
 * atomic in Mongo. Needing a transaction here would mean the aggregate boundary was drawn wrong.
 */
@ApplicationScoped
public class MongoFormDefinitionRepository implements FormDefinitionRepository {

    private final FormDefinitionPanacheRepository documents;

    @Inject
    public MongoFormDefinitionRepository(FormDefinitionPanacheRepository documents) {
        this.documents = documents;
    }

    @Override
    public Optional<FormDefinition> findById(String id) {
        return documents.findByIdOptional(id).map(FormDefinitionDocument::toDomain);
    }

    @Override
    public List<FormDefinition> findByTenant(String tenantId) {
        return documents.find("tenantId", tenantId).stream()
                .map(FormDefinitionDocument::toDomain)
                .toList();
    }

    @Override
    public FormDefinition save(FormDefinition definition) {
        documents.persistOrUpdate(FormDefinitionDocument.from(definition));
        return definition;
    }
}
