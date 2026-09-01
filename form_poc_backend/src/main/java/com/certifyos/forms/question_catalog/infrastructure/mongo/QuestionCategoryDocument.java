package com.certifyos.forms.question_catalog.infrastructure.mongo;

import com.certifyos.forms.question_catalog.domain.QuestionCategory;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

/**
 * Persistence shape for {@link QuestionCategory}.
 *
 * <p>The key is the document id. Unlike questions and option sets there is no surrogate id, because a
 * category has no identity apart from its key — questions reference it by key, and a second id would
 * be a second thing to keep in step for no gain.
 */
@MongoEntity(collection = "question_categories")
public class QuestionCategoryDocument {

    @BsonId
    public String key;

    public String label;
    public String description;
    public int order;

    public static QuestionCategoryDocument from(QuestionCategory category) {
        QuestionCategoryDocument doc = new QuestionCategoryDocument();
        doc.key = category.key();
        doc.label = category.label();
        doc.description = category.description();
        doc.order = category.order();
        return doc;
    }

    public QuestionCategory toDomain() {
        return new QuestionCategory(key, label, description, order);
    }
}
