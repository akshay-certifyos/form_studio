package com.certifyos.forms.config;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Layout;
import com.certifyos.forms.form_authoring.domain.definition.Origin;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.definition.StepKey;
import com.certifyos.forms.form_authoring.domain.reuse.FormBlueprint;
import com.certifyos.forms.form_authoring.domain.reuse.SectionTemplate;
import com.certifyos.forms.question_catalog.domain.CatalogStatus;
import com.certifyos.forms.question_catalog.domain.OptionSet;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.ResponseType;
import com.certifyos.forms.question_catalog.domain.ValidationRule;
import com.certifyos.forms.shared_kernel.expression.Expression;
import com.certifyos.forms.shared_kernel.expression.ExpressionCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@code form_poc_shared/fixtures} into domain objects.
 *
 * <p>Kept as a pure reader with no persistence and no CDI, so it can be used by the seed runner
 * <em>and</em> by tests. That matters more than it looks: a test that compiles the real fixture is
 * the closest thing this POC has to the deferred round-trip gate, and it only works if the same code
 * path parses the same files.
 */
public final class FixtureLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path fixturesDir;

    public FixtureLoader(Path fixturesDir) {
        this.fixturesDir = fixturesDir;
    }

    /** Everything the fixtures describe, already validated by the domain's own constructors. */
    public record Fixtures(
            List<Question> questions,
            List<OptionSet> optionSets,
            List<SectionTemplate> sectionTemplates,
            List<FormBlueprint> formBlueprints,
            List<SectionDefinition> sections,
            List<FormDefinition> forms) {}

    public Fixtures load() throws IOException {
        return new Fixtures(
                loadQuestions(),
                loadOptionSets(),
                loadSectionTemplates(),
                loadFormBlueprints(),
                loadSections(),
                loadForms());
    }

    public boolean available() {
        return Files.isDirectory(fixturesDir);
    }

    // ------------------------------------------------------------------

    private List<Question> loadQuestions() throws IOException {
        List<Question> out = new ArrayList<>();
        for (JsonNode node : read("questions.json").path("questions")) {
            out.add(question(node));
        }
        return out;
    }

    private Question question(JsonNode node) {
        List<ValidationRule> validations = new ArrayList<>();
        for (JsonNode v : node.path("validations")) {
            Map<String, Object> params = new LinkedHashMap<>();
            v.path("params").fields().forEachRemaining(e -> params.put(e.getKey(), plain(e.getValue())));
            validations.add(new ValidationRule(v.path("rule").asText(), params));
        }

        Map<String, String> mapping = new LinkedHashMap<>();
        node.path("platformMapping")
                .fields()
                .forEachRemaining(e -> mapping.put(e.getKey(), e.getValue().asText()));

        return new Question(
                QuestionId.of(node.path("id").asText()),
                node.path("tenantId").isNull() ? null : node.path("tenantId").asText(null),
                node.path("key").asText(),
                node.path("label").asText(),
                node.path("helpText").asText(null),
                ResponseType.fromWireName(node.path("responseType").asText())
                        .orElseThrow(() -> new IllegalStateException("Unknown responseType in fixtures: "
                                + node.path("responseType").asText())),
                node.path("optionSetKey").asText(null),
                validations,
                mapping,
                textSet(node.path("aliases")),
                List.of(),
                node.path("filteredBy").asText(null),
                CatalogStatus.fromWireName(node.path("status").asText("active")).orElse(CatalogStatus.PROPOSED),
                textSet(node.path("tags")));
    }

    private List<OptionSet> loadOptionSets() throws IOException {
        List<OptionSet> out = new ArrayList<>();
        for (JsonNode node : read("option-sets.json").path("optionSets")) {
            List<OptionSet.Option> options = new ArrayList<>();
            for (JsonNode o : node.path("options")) {
                Map<String, List<String>> tags = new LinkedHashMap<>();
                o.path("tags").fields().forEachRemaining(e -> {
                    List<String> values = new ArrayList<>();
                    e.getValue().forEach(v -> values.add(v.asText()));
                    tags.put(e.getKey(), values);
                });
                options.add(new OptionSet.Option(
                        o.path("value").asText(), o.path("label").asText(), tags));
            }
            out.add(new OptionSet(
                    node.path("id").asText(),
                    node.path("tenantId").isNull()
                            ? null
                            : node.path("tenantId").asText(null),
                    node.path("key").asText(),
                    node.path("name").asText(),
                    options,
                    node.path("active").asBoolean(true)));
        }
        return out;
    }

    /**
     * Section templates.
     *
     * <p>Optional: the file may be absent, and the loader returns an empty list rather than failing.
     * Sections then simply carry a {@code sourceTemplateId} that resolves to nothing, which the drift
     * endpoint reports honestly instead of erroring.
     */
    private List<SectionTemplate> loadSectionTemplates() throws IOException {
        List<SectionTemplate> out = new ArrayList<>();
        for (JsonNode node : readOptional("section-templates.json").path("sectionTemplates")) {
            List<SectionTemplate.TemplateQuestion> questions = new ArrayList<>();
            for (JsonNode q : node.path("questions")) {
                questions.add(new SectionTemplate.TemplateQuestion(
                        q.path("key").asText(),
                        QuestionId.of(q.path("catalogQuestionId").asText()),
                        q.path("order").asInt(),
                        q.path("required").asBoolean(false),
                        q.has("layoutColumns")
                                ? new Layout(q.path("layoutColumns").asInt())
                                : Layout.FULL));
            }

            out.add(new SectionTemplate(
                    node.path("_id").asText(),
                    node.hasNonNull("tenantId") ? node.path("tenantId").asText() : null,
                    node.path("key").asText(),
                    node.path("name").asText(),
                    node.path("version").asInt(1),
                    node.hasNonNull("intro") ? node.path("intro").asText() : null,
                    repeating(node.path("repeating")),
                    questions,
                    "deprecated".equalsIgnoreCase(node.path("status").asText("active"))
                            ? SectionTemplate.TemplateStatus.DEPRECATED
                            : SectionTemplate.TemplateStatus.ACTIVE));
        }
        return out;
    }

    private List<FormBlueprint> loadFormBlueprints() throws IOException {
        List<FormBlueprint> out = new ArrayList<>();
        for (JsonNode node : readOptional("form-blueprints.json").path("formBlueprints")) {
            List<FormBlueprint.BlueprintPlacement> placements = new ArrayList<>();
            for (JsonNode p : node.path("placements")) {
                placements.add(new FormBlueprint.BlueprintPlacement(
                        p.path("stepKey").asText(),
                        p.path("sectionTemplateId").asText(),
                        p.path("order").asInt(),
                        p.hasNonNull("group") ? p.path("group").asText() : null,
                        repeating(p.path("repeating"))));
            }

            JsonNode hints = node.path("recognitionHints");
            out.add(new FormBlueprint(
                    node.path("_id").asText(),
                    node.hasNonNull("tenantId") ? node.path("tenantId").asText() : null,
                    node.path("key").asText(),
                    node.path("name").asText(),
                    node.path("version").asInt(1),
                    node.path("entityType").asText("practitioner"),
                    new FormBlueprint.RecognitionHints(
                            strings(hints.path("requiredSectionTemplates")), strings(hints.path("keywords"))),
                    placements,
                    "deprecated".equalsIgnoreCase(node.path("status").asText("active"))
                            ? SectionTemplate.TemplateStatus.DEPRECATED
                            : SectionTemplate.TemplateStatus.ACTIVE));
        }
        return out;
    }

    private static Step.Repeating repeating(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new Step.Repeating(
                node.path("min").asInt(0),
                node.path("max").asInt(1),
                node.path("itemLabel").asText("Item"));
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private List<SectionDefinition> loadSections() throws IOException {
        List<SectionDefinition> out = new ArrayList<>();
        for (JsonNode node : read("section-definitions.json").path("sections")) {
            List<QuestionInstance> questions = new ArrayList<>();
            for (JsonNode q : node.path("questions")) {
                questions.add(new QuestionInstance(
                        q.path("key").asText(),
                        QuestionId.of(q.path("catalogQuestionId").asText()),
                        Origin.valueOf(q.path("origin").asText("ADDED")),
                        q.path("enabled").asBoolean(true),
                        q.path("order").asInt(),
                        q.path("required").asBoolean(false),
                        q.has("layoutColumns")
                                ? new Layout(q.path("layoutColumns").asInt())
                                : Layout.FULL,
                        q.path("labelOverride").asText(null),
                        q.path("helpTextOverride").asText(null),
                        expression(q.get("visibleWhen")),
                        expression(q.get("requiredWhen")),
                        expression(q.get("defaultWhen")),
                        expression(q.get("validWhen"))));
            }
            out.add(new SectionDefinition(
                    node.path("id").asText(),
                    node.path("tenantId").asText(null),
                    node.path("key").asText(),
                    node.path("name").asText(),
                    node.path("intro").asText(null),
                    node.path("sourceTemplateId").isNull()
                            ? null
                            : node.path("sourceTemplateId").asText(null),
                    node.path("sourceTemplateVersion").isNull()
                            ? null
                            : node.path("sourceTemplateVersion").asInt(),
                    questions,
                    node.path("active").asBoolean(true)));
        }
        return out;
    }

    private List<FormDefinition> loadForms() throws IOException {
        List<FormDefinition> out = new ArrayList<>();
        for (JsonNode node : read("form-definitions.json").path("forms")) {
            Map<String, FormDefinition.NamedCondition> conditions = new LinkedHashMap<>();
            for (JsonNode c : node.path("namedConditions")) {
                String key = c.path("key").asText();
                conditions.put(
                        key,
                        new FormDefinition.NamedCondition(
                                key, c.path("label").asText(), expression(c.get("expression"))));
            }

            List<Step> steps = new ArrayList<>();
            for (JsonNode s : node.path("steps")) {
                Step.Repeating repeating = null;
                if (s.has("repeating") && !s.path("repeating").isNull()) {
                    JsonNode r = s.path("repeating");
                    repeating = new Step.Repeating(
                            r.path("min").asInt(),
                            r.path("max").asInt(),
                            r.path("itemLabel").asText());
                }
                steps.add(new Step(
                        StepKey.of(s.path("key").asText()),
                        s.path("sectionDefinitionId").asText(),
                        s.path("order").asInt(),
                        s.path("enabled").asBoolean(true),
                        s.path("titleOverride").isNull()
                                ? null
                                : s.path("titleOverride").asText(null),
                        s.path("group").asText(null),
                        repeating,
                        expression(s.get("visibleWhen")),
                        expression(s.get("audienceWhen"))));
            }

            List<FormDefinition.HardStop> hardStops = new ArrayList<>();
            for (JsonNode h : node.path("hardStops")) {
                hardStops.add(new FormDefinition.HardStop(
                        h.path("key").asText(),
                        expression(h.get("when")),
                        h.path("message").asText(),
                        h.path("evaluateOn").asText("next")));
            }

            out.add(new FormDefinition(
                    node.path("id").asText(),
                    node.path("tenantId").asText(),
                    node.path("formTemplateId").asText(null),
                    node.path("name").asText(),
                    node.path("entityType").asText(),
                    node.path("sourceBlueprintId").asText(null),
                    node.has("sourceBlueprintVersion")
                            ? node.path("sourceBlueprintVersion").asInt()
                            : null,
                    conditions,
                    steps,
                    hardStops,
                    FormDefinition.DefinitionStatus.valueOf(node.path("status").asText("DRAFT"))));
        }
        return out;
    }

    // ------------------------------------------------------------------

    /**
     * Reads a fixture file that may not be there.
     *
     * <p>Used for the two files added after the original set. A fixture directory from before them
     * should still seed the rest rather than failing wholesale — the seeder catches and logs, so a
     * hard read here would turn a missing optional file into an empty database.
     */
    private JsonNode readOptional(String fileName) throws IOException {
        Path file = fixturesDir.resolve(fileName);
        return Files.exists(file) ? MAPPER.readTree(file.toFile()) : MAPPER.createObjectNode();
    }

    private JsonNode read(String fileName) throws IOException {
        Path file = fixturesDir.resolve(fileName);
        if (!Files.exists(file)) {
            throw new IOException("Fixture not found: " + file.toAbsolutePath());
        }
        return MAPPER.readTree(file.toFile());
    }

    /** Uses the same codec as persistence and the compiler, so fixtures cannot parse differently. */
    private static Expression expression(JsonNode node) {
        return ExpressionCodec.read(node);
    }

    private static Set<String> textSet(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        array.forEach(v -> out.add(v.asText()));
        return out;
    }

    private static Object plain(JsonNode node) {
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.asText();
    }
}
