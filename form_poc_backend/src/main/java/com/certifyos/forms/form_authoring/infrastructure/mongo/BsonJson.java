package com.certifyos.forms.form_authoring.infrastructure.mongo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.bson.Document;

/**
 * Converts between Jackson's tree model and BSON.
 *
 * <p>Exists because a {@code JsonNode} cannot be a field on a document type. The Mongo POJO codec
 * reflects over fields and has no idea what an {@code ObjectNode} is, so it writes something
 * unintended and then fails on the way back with {@code Can not set ... JsonNode field ... to
 * java.util.ArrayList}. Every condition in the system went through such a field, so <em>no stored
 * form could be read back</em> — and none of the 415 tests saw it, because they all use in-memory
 * repositories that hand the same object graph back and never encode anything.
 *
 * <p>{@link Document} rather than a JSON string, deliberately. A string would round-trip just as
 * safely and be less code, but it would make every condition an opaque blob: you could no longer
 * read a rule in {@code mongosh}, and the claim that conditions are data rather than code would stop
 * being true of the database itself.
 *
 * <p>Conversion goes through Jackson's {@code convertValue} rather than
 * {@code Document.parse(node.toString())}, because {@code toJson}/{@code parse} round-trips numbers
 * through extended JSON ({@code {"$numberLong": "5"}}) and would reintroduce exactly the
 * int-versus-long drift that already caused one spurious STRUCTURAL diff.
 */
final class BsonJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> AS_MAP = new TypeReference<>() {};

    private BsonJson() {}

    /** Null in, null out — an absent condition is meaningful and must not become an empty object. */
    static Document toDocument(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return new Document(MAPPER.convertValue(node, AS_MAP));
    }

    static JsonNode toNode(Document document) {
        if (document == null) {
            return null;
        }
        return MAPPER.valueToTree(document);
    }
}
