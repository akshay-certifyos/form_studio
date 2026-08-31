package com.certifyos.forms.form_authoring.infrastructure.mongo;

import com.certifyos.forms.form_authoring.domain.definition.Layout;
import com.certifyos.forms.form_authoring.domain.definition.Origin;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.shared_kernel.expression.ExpressionCodec;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.bson.codecs.pojo.annotations.BsonId;

/** Persistence shape for {@link SectionDefinition}. Same separation rationale as the form document. */
@MongoEntity(collection = "section_definitions")
public class SectionDefinitionDocument {

    @BsonId
    public String id;

    public String tenantId;
    public String key;
    public String name;
    public String intro;
    public String sourceTemplateId;
    public Integer sourceTemplateVersion;
    public boolean active = true;

    /**
     * Computed on the domain side and stored anyway.
     *
     * <p>Redundant by design: it makes "which sections need a providerType question?" a query rather
     * than a full load-and-recompute across every section a tenant owns. Recomputed on every save,
     * so it cannot drift.
     */
    public List<String> externalRefs = new ArrayList<>();

    public List<QuestionInstanceDoc> questions = new ArrayList<>();

    public static class QuestionInstanceDoc {
        public String key;
        public String catalogQuestionId;
        public String origin;
        public boolean enabled = true;
        public int order;
        public boolean required;
        public Integer layoutColumns;
        public String labelOverride;
        public String helpTextOverride;
        public Document visibleWhen;
        public Document requiredWhen;
        public Document defaultWhen;
        public Document validWhen;
    }

    public static SectionDefinitionDocument from(SectionDefinition section) {
        SectionDefinitionDocument doc = new SectionDefinitionDocument();
        doc.id = section.id();
        doc.tenantId = section.tenantId();
        doc.key = section.key();
        doc.name = section.name();
        doc.intro = section.intro();
        doc.sourceTemplateId = section.sourceTemplateId();
        doc.sourceTemplateVersion = section.sourceTemplateVersion();
        doc.active = section.active();
        doc.externalRefs = new ArrayList<>(section.externalRefs());

        for (QuestionInstance q : section.questions()) {
            QuestionInstanceDoc d = new QuestionInstanceDoc();
            d.key = q.key();
            d.catalogQuestionId = q.catalogQuestionId().value();
            d.origin = q.origin().name();
            d.enabled = q.enabled();
            d.order = q.order();
            d.required = q.required();
            d.layoutColumns = q.layout().columns();
            d.labelOverride = q.labelOverride();
            d.helpTextOverride = q.helpTextOverride();
            d.visibleWhen = BsonJson.toDocument(ExpressionCodec.write(q.visibleWhen()));
            d.requiredWhen = BsonJson.toDocument(ExpressionCodec.write(q.requiredWhen()));
            d.defaultWhen = BsonJson.toDocument(ExpressionCodec.write(q.defaultWhen()));
            d.validWhen = BsonJson.toDocument(ExpressionCodec.write(q.validWhen()));
            doc.questions.add(d);
        }
        return doc;
    }

    public SectionDefinition toDomain() {
        List<QuestionInstance> instances = new ArrayList<>();
        for (QuestionInstanceDoc d : questions) {
            instances.add(new QuestionInstance(
                    d.key,
                    QuestionId.of(d.catalogQuestionId),
                    d.origin == null ? Origin.ADDED : Origin.valueOf(d.origin),
                    d.enabled,
                    d.order,
                    d.required,
                    d.layoutColumns == null ? Layout.FULL : new Layout(d.layoutColumns),
                    d.labelOverride,
                    d.helpTextOverride,
                    ExpressionCodec.read(BsonJson.toNode(d.visibleWhen)),
                    ExpressionCodec.read(BsonJson.toNode(d.requiredWhen)),
                    ExpressionCodec.read(BsonJson.toNode(d.defaultWhen)),
                    ExpressionCodec.read(BsonJson.toNode(d.validWhen))));
        }
        return new SectionDefinition(
                id, tenantId, key, name, intro, sourceTemplateId, sourceTemplateVersion, instances, active);
    }
}
