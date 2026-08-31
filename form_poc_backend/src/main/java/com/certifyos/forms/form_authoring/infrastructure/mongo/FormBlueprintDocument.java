package com.certifyos.forms.form_authoring.infrastructure.mongo;

import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.reuse.FormBlueprint;
import com.certifyos.forms.form_authoring.domain.reuse.SectionTemplate;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.util.ArrayList;
import java.util.List;
import org.bson.codecs.pojo.annotations.BsonId;

/** Persistence shape for {@link FormBlueprint}. */
@MongoEntity(collection = "form_blueprints")
public class FormBlueprintDocument {

    @BsonId
    public String id;

    public String tenantId;
    public String key;
    public String name;
    public int version = 1;
    public String entityType;
    public String status = "active";

    public RecognitionHintsDoc recognitionHints;
    public List<PlacementDoc> placements = new ArrayList<>();

    public static class RecognitionHintsDoc {
        public List<String> requiredSectionTemplates = new ArrayList<>();
        public List<String> keywords = new ArrayList<>();
    }

    public static class PlacementDoc {
        public String stepKey;
        public String sectionTemplateId;
        public int order;
        public String group;
        /** Null inherits the template's default. */
        public SectionTemplateDocument.RepeatingDoc repeating;
    }

    public static FormBlueprintDocument from(FormBlueprint blueprint) {
        FormBlueprintDocument doc = new FormBlueprintDocument();
        doc.id = blueprint.id();
        doc.tenantId = blueprint.tenantId();
        doc.key = blueprint.key();
        doc.name = blueprint.name();
        doc.version = blueprint.version();
        doc.entityType = blueprint.entityType();
        doc.status = blueprint.status().name().toLowerCase(java.util.Locale.ROOT);

        RecognitionHintsDoc hints = new RecognitionHintsDoc();
        if (blueprint.recognitionHints() != null) {
            hints.requiredSectionTemplates =
                    List.copyOf(blueprint.recognitionHints().requiredSectionTemplates());
            hints.keywords = List.copyOf(blueprint.recognitionHints().keywords());
        }
        doc.recognitionHints = hints;

        doc.placements = blueprint.placements().stream()
                .map(placement -> {
                    PlacementDoc p = new PlacementDoc();
                    p.stepKey = placement.stepKey();
                    p.sectionTemplateId = placement.sectionTemplateId();
                    p.order = placement.order();
                    p.group = placement.group();
                    if (placement.repeating() != null) {
                        SectionTemplateDocument.RepeatingDoc repeating = new SectionTemplateDocument.RepeatingDoc();
                        repeating.min = placement.repeating().min();
                        repeating.max = placement.repeating().max();
                        repeating.itemLabel = placement.repeating().itemLabel();
                        p.repeating = repeating;
                    }
                    return p;
                })
                .toList();

        return doc;
    }

    public FormBlueprint toDomain() {
        List<FormBlueprint.BlueprintPlacement> domainPlacements = placements.stream()
                .map(p -> new FormBlueprint.BlueprintPlacement(
                        p.stepKey,
                        p.sectionTemplateId,
                        p.order,
                        p.group,
                        p.repeating == null
                                ? null
                                : new Step.Repeating(p.repeating.min, p.repeating.max, p.repeating.itemLabel)))
                .toList();

        return new FormBlueprint(
                id,
                tenantId,
                key,
                name,
                version,
                entityType,
                recognitionHints == null
                        ? FormBlueprint.RecognitionHints.none()
                        : new FormBlueprint.RecognitionHints(
                                recognitionHints.requiredSectionTemplates, recognitionHints.keywords),
                domainPlacements,
                "deprecated".equalsIgnoreCase(status)
                        ? SectionTemplate.TemplateStatus.DEPRECATED
                        : SectionTemplate.TemplateStatus.ACTIVE);
    }
}
