package com.certifyos.forms.form_authoring.infrastructure.mongo;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.definition.StepKey;
import com.certifyos.forms.shared_kernel.expression.ExpressionCodec;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.codecs.pojo.annotations.BsonId;

/**
 * Persistence shape for {@link FormDefinition}.
 *
 * <p>Deliberately a separate type. The domain records carry no Panache, BSON or Jackson
 * annotations — enforced by {@code LayeringTest} — because annotating aggregates is the shortcut
 * that makes a DDD proposal hollow: the model then exists to satisfy a driver rather than to
 * express the business.
 *
 * <p>The cost is one mapper per aggregate. The benefit is that a storage change never reaches the
 * domain, and the domain can be reasoned about without knowing Mongo exists.
 *
 * <p>Expressions are stored as the same JSON the artifact uses, via {@link ExpressionCodec}, so a
 * condition cannot round-trip through the database differently from how it reaches the renderer.
 */
@MongoEntity(collection = "form_definitions")
public class FormDefinitionDocument {

    @BsonId
    public String id;

    public String tenantId;
    public String formTemplateId;
    public String name;
    public String entityType;
    public String sourceBlueprintId;
    public Integer sourceBlueprintVersion;
    public String status;

    public List<NamedConditionDoc> namedConditions = new ArrayList<>();
    public List<StepDoc> steps = new ArrayList<>();
    public List<HardStopDoc> hardStops = new ArrayList<>();

    public static class NamedConditionDoc {
        public String key;
        public String label;
        public Document expression;
    }

    public static class StepDoc {
        public String key;
        public String sectionDefinitionId;
        public int order;
        public boolean enabled = true;
        public String titleOverride;
        public String group;
        public RepeatingDoc repeating;
        public Document visibleWhen;
        public Document audienceWhen;
    }

    public static class RepeatingDoc {
        public int min;
        public int max;
        public String itemLabel;
    }

    public static class HardStopDoc {
        public String key;
        public Document when;
        public String message;
        public String evaluateOn;
    }

    // ------------------------------------------------------------------
    // mapping
    // ------------------------------------------------------------------

    public static FormDefinitionDocument from(FormDefinition definition) {
        FormDefinitionDocument doc = new FormDefinitionDocument();
        doc.id = definition.id();
        doc.tenantId = definition.tenantId();
        doc.formTemplateId = definition.formTemplateId();
        doc.name = definition.name();
        doc.entityType = definition.entityType();
        doc.sourceBlueprintId = definition.sourceBlueprintId();
        doc.sourceBlueprintVersion = definition.sourceBlueprintVersion();
        doc.status = definition.status().name();

        definition.namedConditions().forEach((key, condition) -> {
            NamedConditionDoc nc = new NamedConditionDoc();
            nc.key = condition.key();
            nc.label = condition.label();
            nc.expression = BsonJson.toDocument(ExpressionCodec.write(condition.expression()));
            doc.namedConditions.add(nc);
        });

        for (Step step : definition.steps()) {
            StepDoc s = new StepDoc();
            s.key = step.key().value();
            s.sectionDefinitionId = step.sectionDefinitionId();
            s.order = step.order();
            s.enabled = step.enabled();
            s.titleOverride = step.titleOverride();
            s.group = step.group();
            s.visibleWhen = BsonJson.toDocument(ExpressionCodec.write(step.visibleWhen()));
            s.audienceWhen = BsonJson.toDocument(ExpressionCodec.write(step.audienceWhen()));
            if (step.repeating() != null) {
                RepeatingDoc r = new RepeatingDoc();
                r.min = step.repeating().min();
                r.max = step.repeating().max();
                r.itemLabel = step.repeating().itemLabel();
                s.repeating = r;
            }
            doc.steps.add(s);
        }

        for (FormDefinition.HardStop stop : definition.hardStops()) {
            HardStopDoc h = new HardStopDoc();
            h.key = stop.key();
            h.when = BsonJson.toDocument(ExpressionCodec.write(stop.when()));
            h.message = stop.message();
            h.evaluateOn = stop.evaluateOn();
            doc.hardStops.add(h);
        }

        return doc;
    }

    public FormDefinition toDomain() {
        Map<String, FormDefinition.NamedCondition> conditions = new LinkedHashMap<>();
        for (NamedConditionDoc nc : namedConditions) {
            conditions.put(
                    nc.key,
                    new FormDefinition.NamedCondition(
                            nc.key, nc.label, ExpressionCodec.read(BsonJson.toNode(nc.expression))));
        }

        List<Step> domainSteps = new ArrayList<>();
        for (StepDoc s : steps) {
            Step.Repeating repeating = s.repeating == null
                    ? null
                    : new Step.Repeating(s.repeating.min, s.repeating.max, s.repeating.itemLabel);
            domainSteps.add(new Step(
                    StepKey.of(s.key),
                    s.sectionDefinitionId,
                    s.order,
                    s.enabled,
                    s.titleOverride,
                    s.group,
                    repeating,
                    ExpressionCodec.read(BsonJson.toNode(s.visibleWhen)),
                    ExpressionCodec.read(BsonJson.toNode(s.audienceWhen))));
        }

        List<FormDefinition.HardStop> domainStops = new ArrayList<>();
        for (HardStopDoc h : hardStops) {
            domainStops.add(new FormDefinition.HardStop(
                    h.key, ExpressionCodec.read(BsonJson.toNode(h.when)), h.message, h.evaluateOn));
        }

        return new FormDefinition(
                id,
                tenantId,
                formTemplateId,
                name,
                entityType,
                sourceBlueprintId,
                sourceBlueprintVersion,
                conditions,
                domainSteps,
                domainStops,
                status == null
                        ? FormDefinition.DefinitionStatus.DRAFT
                        : FormDefinition.DefinitionStatus.valueOf(status));
    }
}
