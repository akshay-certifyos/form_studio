package com.certifyos.forms.form_authoring.infrastructure.mongo;

import com.certifyos.forms.form_authoring.domain.publishing.ChangeClass;
import com.certifyos.forms.form_authoring.domain.publishing.ChangeSet;
import com.certifyos.forms.form_authoring.domain.publishing.CompiledForm;
import com.certifyos.forms.form_authoring.domain.publishing.FormVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.mongodb.panache.common.MongoEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.bson.Document;
import org.bson.codecs.pojo.annotations.BsonId;

/**
 * Persistence shape for {@link FormVersion}.
 *
 * <p>Two fields are stored as raw JSON rather than mapped field-by-field, and for opposite reasons.
 *
 * <p>{@link #artifact} is JSON <em>by definition</em> — it is the blob the renderer consumes, and
 * mapping it into typed documents and back would risk normalising away exactly the byte-level
 * fidelity that makes an artifact reproducible and a diff meaningful.
 *
 * <p>{@link #definitionSnapshot} reuses {@link FormDefinitionDocument}, so the snapshot cannot drift
 * from how a live definition is stored — one mapper, one shape, already round-trip tested.
 */
@MongoEntity(collection = "form_versions")
public class FormVersionDocument {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BsonId
    public String id;

    public String tenantId;
    public String formDefinitionId;
    public int version;

    public Document artifact;
    public FormDefinitionDocument definitionSnapshot;

    public String changeClass;
    public List<String> addedKeys = new ArrayList<>();
    public List<String> removedKeys = new ArrayList<>();
    public List<String> changedKeys = new ArrayList<>();
    public List<String> notes = new ArrayList<>();

    public String changelog;
    public String ticketId;
    public Instant publishedAt;
    public String publishedBy;

    public static FormVersionDocument from(FormVersion version) {
        FormVersionDocument doc = new FormVersionDocument();
        doc.id = version.id();
        doc.tenantId = version.tenantId();
        doc.formDefinitionId = version.formDefinitionId();
        doc.version = version.version();
        doc.artifact = BsonJson.toDocument(MAPPER.valueToTree(version.artifact()));
        doc.definitionSnapshot =
                version.definitionSnapshot() == null ? null : FormDefinitionDocument.from(version.definitionSnapshot());
        doc.changelog = version.changelog();
        doc.ticketId = version.ticketId();
        doc.publishedAt = version.publishedAt();
        doc.publishedBy = version.publishedBy();

        ChangeSet changeSet = version.changeSet();
        if (changeSet != null) {
            doc.changeClass = changeSet.changeClass().name();
            doc.addedKeys = new ArrayList<>(changeSet.addedKeys());
            doc.removedKeys = new ArrayList<>(changeSet.removedKeys());
            doc.changedKeys = new ArrayList<>(changeSet.changedKeys());
            doc.notes = new ArrayList<>(changeSet.notes());
        }
        return doc;
    }

    public FormVersion toDomain() {
        CompiledForm compiled;
        try {
            compiled = MAPPER.treeToValue(BsonJson.toNode(artifact), CompiledForm.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Stored artifact for version " + id + " is not readable", e);
        }

        ChangeSet changeSet = changeClass == null
                ? null
                : new ChangeSet(
                        ChangeClass.valueOf(changeClass),
                        new LinkedHashSet<>(addedKeys),
                        new LinkedHashSet<>(removedKeys),
                        new LinkedHashSet<>(changedKeys),
                        notes);

        return new FormVersion(
                id,
                tenantId,
                formDefinitionId,
                version,
                compiled,
                definitionSnapshot == null ? null : definitionSnapshot.toDomain(),
                changeSet,
                changelog,
                ticketId,
                publishedAt,
                publishedBy);
    }
}
