package com.certifyos.forms.support;

import com.certifyos.forms.form_authoring.domain.compile.CatalogSnapshot;
import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.port.DomainEventPublisher;
import com.certifyos.forms.form_authoring.domain.port.FormBlueprintRepository;
import com.certifyos.forms.form_authoring.domain.port.FormDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.FormVersionRepository;
import com.certifyos.forms.form_authoring.domain.port.QuestionCatalogPort;
import com.certifyos.forms.form_authoring.domain.port.SectionDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionTemplateRepository;
import com.certifyos.forms.form_authoring.domain.publishing.FormVersion;
import com.certifyos.forms.form_authoring.domain.reuse.FormBlueprint;
import com.certifyos.forms.form_authoring.domain.reuse.SectionTemplate;
import com.certifyos.forms.question_catalog.domain.OptionSet;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.port.QuestionRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory doubles for every port.
 *
 * <p>These are why {@code make test} needs no Docker. They are also a check on the ports
 * themselves: an interface that cannot be faked in twenty lines is usually leaking persistence
 * concerns into the domain.
 */
public final class InMemoryRepositories {

    private InMemoryRepositories() {}

    public static final class Forms implements FormDefinitionRepository {
        private final Map<String, FormDefinition> store = new LinkedHashMap<>();

        @Override
        public Optional<FormDefinition> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<FormDefinition> findByTenant(String tenantId) {
            return store.values().stream()
                    .filter(f -> tenantId.equals(f.tenantId()))
                    .toList();
        }

        @Override
        public FormDefinition save(FormDefinition definition) {
            store.put(definition.id(), definition);
            return definition;
        }
    }

    public static final class Sections implements SectionDefinitionRepository {
        private final Map<String, SectionDefinition> store = new LinkedHashMap<>();

        public Sections with(SectionDefinition... definitions) {
            for (SectionDefinition d : definitions) {
                store.put(d.id(), d);
            }
            return this;
        }

        @Override
        public Optional<SectionDefinition> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<SectionDefinition> findAllById(Collection<String> ids) {
            return ids.stream()
                    .map(store::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }

        @Override
        public List<SectionDefinition> findByTenant(String tenantId) {
            return store.values().stream()
                    .filter(s -> tenantId.equals(s.tenantId()))
                    .toList();
        }

        @Override
        public SectionDefinition save(SectionDefinition definition) {
            store.put(definition.id(), definition);
            return definition;
        }
    }

    /** Templates a tenant can instantiate. Global entries have a null tenantId, as in Mongo. */
    public static final class Templates implements SectionTemplateRepository {

        private final Map<String, SectionTemplate> store = new LinkedHashMap<>();

        public Templates with(SectionTemplate... templates) {
            for (SectionTemplate template : templates) {
                store.put(template.id(), template);
            }
            return this;
        }

        @Override
        public Optional<SectionTemplate> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<SectionTemplate> findAllById(Collection<String> ids) {
            return ids.stream()
                    .map(store::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }

        @Override
        public List<SectionTemplate> findAvailableFor(String tenantId) {
            // Mirrors the Mongo predicate: active, global or this tenant's. A fake that ignored
            // status or tenancy would report a larger catalogue than production has.
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

    public static final class Blueprints implements FormBlueprintRepository {

        private final Map<String, FormBlueprint> store = new LinkedHashMap<>();

        public Blueprints with(FormBlueprint... blueprints) {
            for (FormBlueprint blueprint : blueprints) {
                store.put(blueprint.id(), blueprint);
            }
            return this;
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

    /**
     * Note the absence of an archive operation, mirroring the real port: the active version is
     * derived as the highest published one, so there is no second write to get wrong.
     */
    public static final class Versions implements FormVersionRepository {
        private final Map<String, FormVersion> store = new LinkedHashMap<>();

        @Override
        public Optional<FormVersion> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

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

    public static final class Catalog implements QuestionCatalogPort {
        private final List<Question> questions = new ArrayList<>();
        private final List<OptionSet> optionSets = new ArrayList<>();

        public Catalog with(Question... entries) {
            questions.addAll(List.of(entries));
            return this;
        }

        public Catalog with(OptionSet... sets) {
            optionSets.addAll(List.of(sets));
            return this;
        }

        /**
         * Honours the requested ids rather than returning everything.
         *
         * <p>An earlier version ignored its arguments, which masked a real defect: the caller was
         * passing an empty option-set collection, and a faithful adapter would have returned no
         * options at all. A fake that is more permissive than the real thing hides exactly the bugs
         * it is supposed to surface.
         */
        @Override
        public CatalogSnapshot resolve(Collection<QuestionId> questionIds) {
            List<Question> matched =
                    questions.stream().filter(q -> questionIds.contains(q.id())).toList();
            java.util.Set<String> keysNeeded = matched.stream()
                    .map(Question::optionSetKey)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            List<OptionSet> matchedSets = optionSets.stream()
                    .filter(s -> keysNeeded.contains(s.key()))
                    .toList();
            return CatalogSnapshot.of(matched, matchedSets);
        }
    }

    /**
     * The catalog as an aggregate repository, distinct from {@link Catalog} above.
     *
     * <p>Both exist because both ports do: {@code QuestionCatalogPort} is how {@code form_authoring}
     * reaches the catalog across a context boundary, and it returns a compile-time snapshot;
     * {@code QuestionRepository} is the catalog's own repository and returns aggregates. Collapsing
     * them in a test double would hide the boundary the design is proposing.
     */
    public static final class Questions implements QuestionRepository {
        private final Map<QuestionId, Question> store = new LinkedHashMap<>();

        public Questions with(Question... entries) {
            for (Question entry : entries) {
                store.put(entry.id(), entry);
            }
            return this;
        }

        @Override
        public Optional<Question> findById(QuestionId id) {
            return Optional.ofNullable(store.get(id));
        }

        /**
         * Honours the requested ids rather than returning everything — the same discipline as
         * {@link Catalog#resolve}, and for the same reason: a fake more permissive than the real
         * thing hides the bugs it exists to surface.
         */
        @Override
        public List<Question> findAllById(Collection<QuestionId> ids) {
            return ids.stream()
                    .map(store::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }

        @Override
        public List<Question> findActiveFor(String tenantId) {
            return store.values().stream()
                    .filter(q -> q.status() == com.certifyos.forms.question_catalog.domain.CatalogStatus.ACTIVE)
                    .filter(q -> q.tenantId() == null || q.tenantId().equals(tenantId))
                    .toList();
        }

        @Override
        public List<Question> search(String tenantId, String text, boolean includeProposed) {
            String needle = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
            return store.values().stream()
                    .filter(q -> includeProposed
                            || q.status() == com.certifyos.forms.question_catalog.domain.CatalogStatus.ACTIVE)
                    .filter(q -> needle.isEmpty()
                            || q.label().toLowerCase(java.util.Locale.ROOT).contains(needle)
                            || q.key().toLowerCase(java.util.Locale.ROOT).contains(needle))
                    .toList();
        }

        @Override
        public Question save(Question question) {
            store.put(question.id(), question);
            return question;
        }
    }

    /** Records what was published so a test can assert on the boundary rather than a side effect. */
    public static final class Events implements DomainEventPublisher {
        private final List<Object> published = new ArrayList<>();

        @Override
        public void publish(Object event) {
            published.add(event);
        }

        public List<Object> published() {
            return List.copyOf(published);
        }

        @SuppressWarnings("unchecked")
        public <T> List<T> ofType(Class<T> type) {
            return published.stream().filter(type::isInstance).map(e -> (T) e).toList();
        }
    }
}
