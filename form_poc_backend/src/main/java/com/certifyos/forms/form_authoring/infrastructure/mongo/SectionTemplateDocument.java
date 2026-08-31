package com.certifyos.forms.form_authoring.infrastructure.mongo;

import com.certifyos.forms.form_authoring.domain.definition.Layout;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.reuse.SectionTemplate;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.util.ArrayList;
import java.util.List;
import org.bson.codecs.pojo.annotations.BsonId;

/** Persistence shape for {@link SectionTemplate}. Same separation rationale as the other documents. */
@MongoEntity(collection = "section_templates")
public class SectionTemplateDocument {

    @BsonId
    public String id;

    /** Null for a globally available template. */
    public String tenantId;

    public String key;
    public String name;
    public int version = 1;
    public String intro;
    public String status = "active";

    public RepeatingDoc repeating;
    public List<TemplateQuestionDoc> questions = new ArrayList<>();

    public static class RepeatingDoc {
        public int min;
        public int max;
        public String itemLabel;
    }

    public static class TemplateQuestionDoc {
        public String key;
        public String catalogQuestionId;
        public int order;
        public boolean required;
        public Integer layoutColumns;
    }

    public static SectionTemplateDocument from(SectionTemplate template) {
        SectionTemplateDocument doc = new SectionTemplateDocument();
        doc.id = template.id();
        doc.tenantId = template.tenantId();
        doc.key = template.key();
        doc.name = template.name();
        doc.version = template.version();
        doc.intro = template.intro();
        doc.status = template.status().name().toLowerCase(java.util.Locale.ROOT);

        if (template.repeating() != null) {
            RepeatingDoc repeating = new RepeatingDoc();
            repeating.min = template.repeating().min();
            repeating.max = template.repeating().max();
            repeating.itemLabel = template.repeating().itemLabel();
            doc.repeating = repeating;
        }

        doc.questions = template.questions().stream()
                .map(question -> {
                    TemplateQuestionDoc q = new TemplateQuestionDoc();
                    q.key = question.key();
                    q.catalogQuestionId = question.catalogQuestionId().value();
                    q.order = question.order();
                    q.required = question.required();
                    q.layoutColumns = question.layout().columns();
                    return q;
                })
                .toList();

        return doc;
    }

    public SectionTemplate toDomain() {
        List<SectionTemplate.TemplateQuestion> domainQuestions = questions.stream()
                .map(q -> new SectionTemplate.TemplateQuestion(
                        q.key,
                        QuestionId.of(q.catalogQuestionId),
                        q.order,
                        q.required,
                        q.layoutColumns == null ? Layout.FULL : new Layout(q.layoutColumns)))
                .toList();

        return new SectionTemplate(
                id,
                tenantId,
                key,
                name,
                version,
                intro,
                repeating == null ? null : new Step.Repeating(repeating.min, repeating.max, repeating.itemLabel),
                domainQuestions,
                "deprecated".equalsIgnoreCase(status)
                        ? SectionTemplate.TemplateStatus.DEPRECATED
                        : SectionTemplate.TemplateStatus.ACTIVE);
    }
}
