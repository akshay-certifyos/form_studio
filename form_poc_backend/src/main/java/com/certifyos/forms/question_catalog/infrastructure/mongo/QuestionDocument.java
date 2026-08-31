package com.certifyos.forms.question_catalog.infrastructure.mongo;

import com.certifyos.forms.question_catalog.domain.CatalogStatus;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.ResponseType;
import com.certifyos.forms.question_catalog.domain.ValidationRule;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.bson.codecs.pojo.annotations.BsonId;

/** Persistence shape for {@link Question}. Recursive, for grouped inputs. */
@MongoEntity(collection = "question_catalog")
public class QuestionDocument {

    @BsonId
    public String id;

    public String tenantId;
    public String key;
    public String label;
    public String helpText;
    public String responseType;
    public String optionSetKey;
    public String filteredBy;
    public String status;

    public List<ValidationDoc> validations = new ArrayList<>();
    public Map<String, String> platformMapping = new LinkedHashMap<>();

    /**
     * Payer phrasings for the same question. Indexed, because promotion checks every candidate
     * against these — it is the query that keeps the catalog from filling with near-duplicates.
     */
    public List<String> aliases = new ArrayList<>();

    public List<String> tags = new ArrayList<>();
    public List<QuestionDocument> children = new ArrayList<>();

    public static class ValidationDoc {
        public String rule;
        public Map<String, Object> params = new LinkedHashMap<>();
    }

    public static QuestionDocument from(Question question) {
        QuestionDocument doc = new QuestionDocument();
        doc.id = question.id().value();
        doc.tenantId = question.tenantId();
        doc.key = question.key();
        doc.label = question.label();
        doc.helpText = question.helpText();
        doc.responseType = question.responseType().wireName();
        doc.optionSetKey = question.optionSetKey();
        doc.filteredBy = question.filteredBy();
        doc.status = question.status().wireName();
        doc.platformMapping = new LinkedHashMap<>(question.platformMapping());
        doc.aliases = new ArrayList<>(question.aliases());
        doc.tags = new ArrayList<>(question.tags());

        for (ValidationRule rule : question.validations()) {
            ValidationDoc v = new ValidationDoc();
            v.rule = rule.rule();
            v.params = new LinkedHashMap<>(rule.params());
            doc.validations.add(v);
        }
        question.children().forEach(child -> doc.children.add(from(child)));
        return doc;
    }

    public Question toDomain() {
        List<ValidationRule> rules = new ArrayList<>();
        validations.forEach(v -> rules.add(new ValidationRule(v.rule, v.params)));

        List<Question> childQuestions = new ArrayList<>();
        children.forEach(c -> childQuestions.add(c.toDomain()));

        return new Question(
                QuestionId.of(id),
                tenantId,
                key,
                label,
                helpText,
                ResponseType.fromWireName(responseType)
                        .orElseThrow(() -> new IllegalStateException("Unknown responseType stored: " + responseType)),
                optionSetKey,
                rules,
                platformMapping,
                new LinkedHashSet<>(aliases),
                childQuestions,
                filteredBy,
                CatalogStatus.fromWireName(status).orElse(CatalogStatus.PROPOSED),
                new LinkedHashSet<>(tags));
    }
}
