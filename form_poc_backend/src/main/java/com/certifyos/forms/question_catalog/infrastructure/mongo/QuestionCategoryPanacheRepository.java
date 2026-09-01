package com.certifyos.forms.question_catalog.infrastructure.mongo;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/** Raw Panache access to {@code question_categories}. */
@ApplicationScoped
public class QuestionCategoryPanacheRepository
        implements PanacheMongoRepositoryBase<QuestionCategoryDocument, String> {}
