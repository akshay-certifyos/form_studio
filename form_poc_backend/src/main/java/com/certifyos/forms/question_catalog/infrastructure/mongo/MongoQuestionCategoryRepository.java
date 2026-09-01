package com.certifyos.forms.question_catalog.infrastructure.mongo;

import com.certifyos.forms.question_catalog.domain.QuestionCategory;
import com.certifyos.forms.question_catalog.domain.port.QuestionCategoryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;

/** Mongo-backed {@link QuestionCategoryRepository}. */
@ApplicationScoped
public class MongoQuestionCategoryRepository implements QuestionCategoryRepository {

    private final QuestionCategoryPanacheRepository documents;

    @Inject
    public MongoQuestionCategoryRepository(QuestionCategoryPanacheRepository documents) {
        this.documents = documents;
    }

    @Override
    public Optional<QuestionCategory> findByKey(String key) {
        return documents.findByIdOptional(key).map(QuestionCategoryDocument::toDomain);
    }

    /** Sorted here rather than left to the caller: `order` exists to be honoured, not negotiated. */
    @Override
    public List<QuestionCategory> findAll() {
        return documents.findAll().stream()
                .map(QuestionCategoryDocument::toDomain)
                .sorted(java.util.Comparator.comparingInt(QuestionCategory::order))
                .toList();
    }

    @Override
    public QuestionCategory save(QuestionCategory category) {
        documents.persistOrUpdate(QuestionCategoryDocument.from(category));
        return category;
    }
}
