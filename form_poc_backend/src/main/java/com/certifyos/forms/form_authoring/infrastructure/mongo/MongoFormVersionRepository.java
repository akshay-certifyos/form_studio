package com.certifyos.forms.form_authoring.infrastructure.mongo;

import com.certifyos.forms.form_authoring.domain.port.FormVersionRepository;
import com.certifyos.forms.form_authoring.domain.publishing.FormVersion;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

/**
 * Mongo-backed {@link FormVersionRepository}.
 *
 * <p>{@code findActive} is a query, not a flag lookup: the live version is the highest one for a
 * definition. Nothing is ever marked inactive, so publishing is one insert and two versions can
 * never both claim to be live.
 */
@ApplicationScoped
public class MongoFormVersionRepository implements FormVersionRepository {

    private final FormVersionPanacheRepository documents;

    @Inject
    public MongoFormVersionRepository(FormVersionPanacheRepository documents) {
        this.documents = documents;
    }

    @Override
    public Optional<FormVersion> findById(String id) {
        return documents.findByIdOptional(id).map(FormVersionDocument::toDomain);
    }

    @Override
    public Optional<FormVersion> findActive(String formDefinitionId) {
        return documents
                .find("formDefinitionId", Sort.descending("version"), formDefinitionId)
                .firstResultOptional()
                .map(FormVersionDocument::toDomain);
    }

    @Override
    public List<FormVersion> findHistory(String formDefinitionId) {
        return documents.find("formDefinitionId", Sort.descending("version"), formDefinitionId).stream()
                .map(FormVersionDocument::toDomain)
                .toList();
    }

    @Override
    public FormVersion save(FormVersion version) {
        documents.persist(FormVersionDocument.from(version));
        return version;
    }
}
