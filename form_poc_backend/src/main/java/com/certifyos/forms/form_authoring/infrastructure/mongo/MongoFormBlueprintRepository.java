package com.certifyos.forms.form_authoring.infrastructure.mongo;

import com.certifyos.forms.form_authoring.domain.port.FormBlueprintRepository;
import com.certifyos.forms.form_authoring.domain.reuse.FormBlueprint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

/** Mongo-backed {@link FormBlueprintRepository}. */
@ApplicationScoped
public class MongoFormBlueprintRepository implements FormBlueprintRepository {

    private final FormBlueprintPanacheRepository documents;

    @Inject
    public MongoFormBlueprintRepository(FormBlueprintPanacheRepository documents) {
        this.documents = documents;
    }

    @Override
    public Optional<FormBlueprint> findById(String id) {
        return documents.findByIdOptional(id).map(FormBlueprintDocument::toDomain);
    }

    @Override
    public List<FormBlueprint> findAvailableFor(String tenantId) {
        return documents.find("status = ?1 and (tenantId is null or tenantId = ?2)", "active", tenantId).stream()
                .map(FormBlueprintDocument::toDomain)
                .toList();
    }

    @Override
    public FormBlueprint save(FormBlueprint blueprint) {
        documents.persistOrUpdate(FormBlueprintDocument.from(blueprint));
        return blueprint;
    }
}
