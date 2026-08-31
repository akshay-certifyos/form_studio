package com.certifyos.forms.question_catalog.infrastructure.mongo;

import com.certifyos.forms.question_catalog.domain.OptionSet;
import com.certifyos.forms.question_catalog.domain.port.OptionSetRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Mongo-backed {@link OptionSetRepository}. */
@ApplicationScoped
public class MongoOptionSetRepository implements OptionSetRepository {

    private final OptionSetPanacheRepository documents;

    @Inject
    public MongoOptionSetRepository(OptionSetPanacheRepository documents) {
        this.documents = documents;
    }

    @Override
    public Optional<OptionSet> findByKey(String tenantId, String key) {
        return documents
                .find("key = ?1 and (tenantId is null or tenantId = ?2)", key, tenantId)
                .firstResultOptional()
                .map(OptionSetDocument::toDomain);
    }

    @Override
    public List<OptionSet> findAllByKey(String tenantId, Collection<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        return documents.find("key in ?1 and (tenantId is null or tenantId = ?2)", List.copyOf(keys), tenantId).stream()
                .map(OptionSetDocument::toDomain)
                .toList();
    }

    @Override
    public List<OptionSet> findAllFor(String tenantId) {
        return documents.find("tenantId is null or tenantId = ?1", tenantId).stream()
                .map(OptionSetDocument::toDomain)
                .toList();
    }

    @Override
    public OptionSet save(OptionSet optionSet) {
        documents.persistOrUpdate(OptionSetDocument.from(optionSet));
        return optionSet;
    }
}
