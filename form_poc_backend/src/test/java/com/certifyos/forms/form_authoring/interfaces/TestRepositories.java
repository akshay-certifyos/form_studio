package com.certifyos.forms.form_authoring.interfaces;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.port.FormBlueprintRepository;
import com.certifyos.forms.form_authoring.domain.port.FormDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.FormVersionRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionTemplateRepository;
import com.certifyos.forms.form_authoring.domain.publishing.FormVersion;
import com.certifyos.forms.form_authoring.domain.reuse.FormBlueprint;
import com.certifyos.forms.form_authoring.domain.reuse.SectionTemplate;
import com.certifyos.forms.question_catalog.domain.CatalogStatus;
import com.certifyos.forms.question_catalog.domain.OptionSet;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.port.OptionSetRepository;
import com.certifyos.forms.question_catalog.domain.port.QuestionRepository;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * CDI alternatives that replace the Mongo repositories in HTTP tests.
 *
 * <p>Each honours its query arguments rather than returning everything — a fake more permissive
 * than the real store hides exactly the bugs it exists to surface, which is a mistake this project
 * already made once with the catalog port.
 */
public final class TestRepositories {

    private TestRepositories() {}

    /**
     * Lets a test clear state between methods.
     *
     * <p>Needed because these are {@code @ApplicationScoped} — one instance for the whole Quarkus
     * run, so without this a form saved by one test is visible to the next. That surfaced as four
     * failures whose messages looked like production bugs (a version numbered 2 on a first publish,
     * a change class of {@code text} instead of {@code additive}) but were entirely leaked state.
     *
     * <p>Real Mongo has the same hazard, so the fake keeping it rather than hiding it is correct —
     * a fake that is cleaner than the real store teaches the wrong lesson.
     */
    public interface Resettable {
        void reset();
    }

    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class Forms implements FormDefinitionRepository, Resettable {
        private final Map<String, FormDefinition> store = new LinkedHashMap<>();

        @Override
        public void reset() {
            store.clear();
        }

        @Override
        public Optional<FormDefinition> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<FormDefinition> findByTenant(String tenantId) {
            return store.values().stream()
                    .filter(f -> Objects.equals(tenantId, f.tenantId()))
                    .toList();
        }

        @Override
        public FormDefinition save(FormDefinition definition) {
            store.put(definition.id(), definition);
            return definition;
        }
    }

    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class Sections implements SectionDefinitionRepository, Resettable {
        private final Map<String, SectionDefinition> store = new LinkedHashMap<>();

        @Override
        public void reset() {
            store.clear();
        }

        @Override
        public Optional<SectionDefinition> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<SectionDefinition> findAllById(Collection<String> ids) {
            return ids.stream().map(store::get).filter(Objects::nonNull).toList();
        }

        @Override
        public List<SectionDefinition> findByTenant(String tenantId) {
            return store.values().stream()
                    .filter(s -> Objects.equals(tenantId, s.tenantId()))
                    .toList();
        }

        @Override
        public SectionDefinition save(SectionDefinition definition) {
            store.put(definition.id(), definition);
            return definition;
        }
    }

    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class Templates implements SectionTemplateRepository, Resettable {
        private final Map<String, SectionTemplate> store = new LinkedHashMap<>();

        @Override
        public void reset() {
            store.clear();
        }

        @Override
        public Optional<SectionTemplate> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<SectionTemplate> findAllById(Collection<String> ids) {
            return ids.stream().map(store::get).filter(Objects::nonNull).toList();
        }

        @Override
        public List<SectionTemplate> findAvailableFor(String tenantId) {
            // Mirrors the Mongo predicate exactly: active, global or this tenant's. A fake that
            // skipped either filter would report templates production would not return.
            return store.values().stream()
                    .filter(t -> t.status() == SectionTemplate.TemplateStatus.ACTIVE)
                    .filter(t -> t.tenantId() == null || t.tenantId().equals(tenantId))
                    .toList();
        }

        @Override
        public SectionTemplate save(SectionTemplate template) {
            store.put(template.id(), template);
            return template;
        }
    }

    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class Blueprints implements FormBlueprintRepository, Resettable {
        private final Map<String, FormBlueprint> store = new LinkedHashMap<>();

        @Override
        public void reset() {
            store.clear();
        }

        @Override
        public Optional<FormBlueprint> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<FormBlueprint> findAvailableFor(String tenantId) {
            return store.values().stream()
                    .filter(b -> b.status() == SectionTemplate.TemplateStatus.ACTIVE)
                    .filter(b -> b.tenantId() == null || b.tenantId().equals(tenantId))
                    .toList();
        }

        @Override
        public FormBlueprint save(FormBlueprint blueprint) {
            store.put(blueprint.id(), blueprint);
            return blueprint;
        }
    }

    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class Versions implements FormVersionRepository, Resettable {
        private final Map<String, FormVersion> store = new LinkedHashMap<>();

        @Override
        public void reset() {
            store.clear();
        }

        @Override
        public Optional<FormVersion> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        /** Derived, exactly as the Mongo implementation is — highest version wins. */
        @Override
        public Optional<FormVersion> findActive(String formDefinitionId) {
            return store.values().stream()
                    .filter(v -> v.formDefinitionId().equals(formDefinitionId))
                    .max(Comparator.comparingInt(FormVersion::version));
        }

        @Override
        public List<FormVersion> findHistory(String formDefinitionId) {
            return store.values().stream()
                    .filter(v -> v.formDefinitionId().equals(formDefinitionId))
                    .sorted(Comparator.comparingInt(FormVersion::version).reversed())
                    .toList();
        }

        @Override
        public FormVersion save(FormVersion version) {
            store.put(version.id(), version);
            return version;
        }
    }

    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class Questions implements QuestionRepository, Resettable {
        private final Map<String, Question> store = new LinkedHashMap<>();

        @Override
        public void reset() {
            store.clear();
        }

        @Override
        public Optional<Question> findById(QuestionId id) {
            return Optional.ofNullable(store.get(id.value()));
        }

        @Override
        public List<Question> findAllById(Collection<QuestionId> ids) {
            return ids.stream()
                    .map(id -> store.get(id.value()))
                    .filter(Objects::nonNull)
                    .toList();
        }

        @Override
        public List<Question> findActiveFor(String tenantId) {
            // Filters on status, as the Mongo implementation does. It previously did not, which made
            // the fake report a larger catalog than the real one — the same shape of infidelity that
            // once let a repository bug through because the fake ignored its arguments.
            return visibleFor(tenantId, false);
        }

        private List<Question> visibleFor(String tenantId, boolean includeProposed) {
            return store.values().stream()
                    .filter(q -> q.tenantId() == null || Objects.equals(tenantId, q.tenantId()))
                    .filter(q -> q.status() == CatalogStatus.ACTIVE
                            || (includeProposed && q.status() == CatalogStatus.PROPOSED))
                    .toList();
        }

        @Override
        public List<Question> search(String tenantId, String text, boolean includeProposed) {
            List<Question> candidates = visibleFor(tenantId, includeProposed);
            if (text == null || text.isBlank()) {
                return candidates;
            }
            String needle = text.toLowerCase(java.util.Locale.ROOT);
            return candidates.stream()
                    .filter(q -> q.searchableNames().stream().anyMatch(n -> n.contains(needle)))
                    .toList();
        }

        @Override
        public Question save(Question question) {
            store.put(question.id().value(), question);
            return question;
        }
    }

    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class OptionSets implements OptionSetRepository, Resettable {
        private final Map<String, OptionSet> store = new LinkedHashMap<>();

        @Override
        public void reset() {
            store.clear();
        }

        @Override
        public Optional<OptionSet> findByKey(String tenantId, String key) {
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public List<OptionSet> findAllByKey(String tenantId, Collection<String> keys) {
            return keys.stream().map(store::get).filter(Objects::nonNull).toList();
        }

        @Override
        public List<OptionSet> findAllFor(String tenantId) {
            return List.copyOf(store.values());
        }

        @Override
        public OptionSet save(OptionSet optionSet) {
            store.put(optionSet.key(), optionSet);
            return optionSet;
        }
    }
}
