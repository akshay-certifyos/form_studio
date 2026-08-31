package com.certifyos.forms.form_authoring.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Layout;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.definition.StepKey;
import com.certifyos.forms.form_authoring.domain.reuse.FormBlueprint;
import com.certifyos.forms.form_authoring.domain.reuse.SectionTemplate;
import com.certifyos.forms.form_authoring.infrastructure.mongo.FormBlueprintDocument;
import com.certifyos.forms.form_authoring.infrastructure.mongo.FormDefinitionDocument;
import com.certifyos.forms.form_authoring.infrastructure.mongo.SectionTemplateDocument;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.shared_kernel.expression.Expression;
import com.certifyos.forms.shared_kernel.expression.Operator;
import com.mongodb.MongoClientSettings;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonDocumentWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The document types, encoded and decoded by the actual BSON codec.
 *
 * <p>{@code FormDefinitionDocumentTest} already round-trips the whole aggregate through
 * {@code from()} and {@code toDomain()} — and it passed throughout, while <em>no stored form could
 * be read back at all</em>. The reason is that it never encoded anything: it handed the same Java
 * object graph straight back, so the one layer that was broken was the one layer not exercised.
 *
 * <p>The bug: every condition was held in a {@code JsonNode} field. The POJO codec reflects over
 * fields and has no notion of an {@code ObjectNode}, so it wrote something unintended and failed on
 * read with {@code Can not set ... JsonNode field ... to java.util.ArrayList}. Fifteen tests covered
 * the mappers and 415 covered the service; none could see it, because in-memory repositories skip
 * serialisation entirely. Only starting the app against a real Mongo surfaced it.
 *
 * <p>Hence this test. It needs no server — a codec registry encodes into a {@link BsonDocument} in
 * memory — so the gap is closed at unit-test cost rather than requiring Docker in CI.
 */
class MongoCodecRoundTripTest {

    /**
     * The same registry configuration the Mongo client uses, so this exercises the real encoder
     * rather than an approximation of it.
     */
    private static final CodecRegistry REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(
                    PojoCodecProvider.builder().automatic(true).build()));

    private static <T> T throughBson(T value, Class<T> type) {
        Codec<T> codec = REGISTRY.get(type);

        BsonDocument encoded = new BsonDocument();
        codec.encode(
                new BsonDocumentWriter(encoded), value, EncoderContext.builder().build());

        return codec.decode(
                new BsonDocumentReader(encoded), DecoderContext.builder().build());
    }

    private static FormDefinition withEveryExpressionSlot() {
        Expression inList = new Expression.Leaf("applicantDetails.specialty", Operator.IN, List.of("DC", "OD"));
        Expression compound = new Expression.All(List.of(
                new Expression.Leaf("billingSetup.hasCaqhId", Operator.EQ, "Yes"),
                new Expression.Not(new Expression.Ref("specialtyExempt"))));

        return new FormDefinition(
                "fd_codec",
                "tenant_fl_blue",
                null,
                "Codec probe",
                "practitioner",
                null,
                null,
                Map.of(
                        "specialtyExempt",
                        new FormDefinition.NamedCondition(
                                "specialtyExempt", "Specialty exempt from DEA requirements", inList)),
                List.of(
                        Step.of("applicantDetails", "sd_applicant", 1),
                        new Step(
                                StepKey.of("billingAddress"),
                                "sd_address",
                                2,
                                true,
                                "Billing Address",
                                "Locations",
                                null,
                                compound,
                                new Expression.Leaf("viewer.role", Operator.EQ, "admin"))),
                List.of(new FormDefinition.HardStop(
                        "excluded",
                        new Expression.Every("licenses", new Expression.Leaf("@item.status", Operator.EQ, "active")),
                        "At least one license is not active.",
                        "next")),
                FormDefinition.DefinitionStatus.DRAFT);
    }

    @Test
    @DisplayName("a definition survives a real BSON encode/decode with every expression intact")
    void survivesBsonRoundTrip() {
        FormDefinition original = withEveryExpressionSlot();

        FormDefinitionDocument decoded =
                throughBson(FormDefinitionDocument.from(original), FormDefinitionDocument.class);

        // Value equality over the whole aggregate: a slot lost in BSON shows up here without anyone
        // having to remember to assert on it.
        assertEquals(original, decoded.toDomain());
    }

    @Test
    @DisplayName("a list operand comes back a list, not a scalar — this is the shape that failed")
    void listOperandSurvives() {
        FormDefinitionDocument decoded =
                throughBson(FormDefinitionDocument.from(withEveryExpressionSlot()), FormDefinitionDocument.class);

        Expression expression =
                decoded.toDomain().namedConditions().get("specialtyExempt").expression();

        assertTrue(expression instanceof Expression.Leaf);
        assertEquals(List.of("DC", "OD"), ((Expression.Leaf) expression).value());
    }

    @Test
    @DisplayName("a nested not/ref inside an all survives, so a gate cannot silently invert")
    void nestedStructureSurvives() {
        FormDefinitionDocument decoded =
                throughBson(FormDefinitionDocument.from(withEveryExpressionSlot()), FormDefinitionDocument.class);

        Expression billing =
                decoded.toDomain().step("billingAddress").orElseThrow().visibleWhen();

        assertTrue(billing instanceof Expression.All);
        List<Expression> parts = ((Expression.All) billing).operands();
        assertEquals(2, parts.size());
        assertTrue(parts.get(1) instanceof Expression.Not);
    }

    @Test
    @DisplayName("a quantifier on a hard stop survives — the slot that first threw")
    void quantifierSurvives() {
        FormDefinitionDocument decoded =
                throughBson(FormDefinitionDocument.from(withEveryExpressionSlot()), FormDefinitionDocument.class);

        Expression when = decoded.toDomain().hardStops().get(0).when();

        assertTrue(when instanceof Expression.Every);
        assertEquals("licenses", ((Expression.Every) when).scope());
    }

    @Test
    @DisplayName("a section template survives a real BSON encode/decode")
    void sectionTemplateSurvivesBson() {
        SectionTemplate template = new SectionTemplate(
                "st_licensure",
                null,
                "licensure",
                "Licensure",
                3,
                "List all active state licenses.",
                new Step.Repeating(1, 20, "License"),
                List.of(
                        new SectionTemplate.TemplateQuestion(
                                "licenseState", QuestionId.of("q_license_state"), 10, true, Layout.HALF),
                        new SectionTemplate.TemplateQuestion(
                                "licenseNumber", QuestionId.of("q_license_number"), 20, false, Layout.FULL)),
                SectionTemplate.TemplateStatus.ACTIVE);

        SectionTemplateDocument decoded =
                throughBson(SectionTemplateDocument.from(template), SectionTemplateDocument.class);

        // Whole-aggregate equality: a field lost in BSON fails here without anyone remembering to
        // assert on it. A null tenantId must also survive — it is what marks a template global.
        assertEquals(template, decoded.toDomain());
        assertNull(decoded.toDomain().tenantId());
    }

    @Test
    @DisplayName("a form blueprint survives a real BSON encode/decode, recognition hints included")
    void formBlueprintSurvivesBson() {
        FormBlueprint blueprint = new FormBlueprint(
                "fb_practitioner_recred",
                null,
                "practitioner_recred",
                "Practitioner Recredentialing Application",
                2,
                "practitioner",
                new FormBlueprint.RecognitionHints(
                        List.of("st_licensure", "st_dea"), List.of("recredentialing", "reattestation")),
                List.of(
                        new FormBlueprint.BlueprintPlacement("licensure", "st_licensure", 10, "Credentials", null),
                        // The same template placed twice, repeating in one placement and not the
                        // other — the case that forced both stepKey and repeating onto the placement,
                        // and the one a round trip must not collapse.
                        new FormBlueprint.BlueprintPlacement(
                                "practiceLocation",
                                "st_address",
                                20,
                                "Locations",
                                new Step.Repeating(1, 10, "Location")),
                        new FormBlueprint.BlueprintPlacement("billingAddress", "st_address", 30, "Locations", null)),
                SectionTemplate.TemplateStatus.ACTIVE);

        FormBlueprintDocument decoded = throughBson(FormBlueprintDocument.from(blueprint), FormBlueprintDocument.class);

        assertEquals(blueprint, decoded.toDomain());
        assertEquals(3, decoded.toDomain().placements().size());
        assertEquals(2, decoded.toDomain().requiredTemplateIds().size(), "st_address deduplicated");

        // Repetition must survive per placement, or one address step silently stops repeating.
        List<FormBlueprint.BlueprintPlacement> placements = decoded.toDomain().orderedPlacements();
        assertNotNull(placements.get(1).repeating());
        assertEquals("Location", placements.get(1).repeating().itemLabel());
        assertNull(placements.get(2).repeating(), "billing address does not repeat");
    }

    @Test
    @DisplayName("an absent condition stays absent rather than becoming an empty object")
    void nullConditionStaysNull() {
        FormDefinitionDocument decoded =
                throughBson(FormDefinitionDocument.from(withEveryExpressionSlot()), FormDefinitionDocument.class);

        Step first = decoded.toDomain().step("applicantDetails").orElseThrow();

        // An empty object would parse as a malformed expression rather than as "always visible".
        assertNull(first.visibleWhen());
        assertNotNull(decoded.toDomain().step("billingAddress").orElseThrow().visibleWhen());
    }
}
