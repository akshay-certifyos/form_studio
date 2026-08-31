package com.certifyos.forms.form_authoring.domain.reuse;

import com.certifyos.forms.form_authoring.domain.definition.Layout;
import com.certifyos.forms.form_authoring.domain.definition.Origin;
import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A reusable section shape. Aggregate root, per §5.3.
 *
 * <p><b>Copy-on-use, not reference.</b> Instantiating a template produces an independent
 * {@link SectionDefinition} that records which template version it came from and nothing more. A
 * tenant can then disable, reorder and override freely without a template edit reaching in and
 * changing a form somebody is mid-way through filling. The recorded version is what makes drift
 * computable later — see {@link DriftCalculator}.
 *
 * <p>Templates are versioned and immutable per version: promoting a definition mints version n+1
 * rather than editing n, so a definition's recorded version always describes something that actually
 * existed.
 *
 * @param tenantId null for a globally available template; set for a tenant's own
 * @param version monotonic; a definition records the version it was instantiated from
 */
public record SectionTemplate(
        String id,
        String tenantId,
        String key,
        String name,
        int version,
        String intro,
        Step.Repeating repeating,
        List<TemplateQuestion> questions,
        TemplateStatus status) {

    public SectionTemplate {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Template key is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Template name is required — it becomes the step title");
        }
        if (version < 1) {
            throw new IllegalArgumentException("Template version starts at 1, got: " + version);
        }
        questions = questions == null ? List.of() : List.copyOf(questions);
        status = status == null ? TemplateStatus.ACTIVE : status;

        // Same invariant as a section definition, and for the same reason: these keys become answer
        // paths, so a duplicate would have two questions writing to one answer.
        Set<String> seen = new LinkedHashSet<>();
        for (TemplateQuestion q : questions) {
            if (!seen.add(q.key())) {
                throw new IllegalArgumentException(
                        "Duplicate question key '" + q.key() + "' in template '" + key + "'");
            }
        }
    }

    /**
     * One question in a template.
     *
     * <p>Carries {@code required} and {@code layout} as well as the catalog reference. §5.3's example
     * JSON shows only key, catalog id and order — but a template that cannot say "this one is
     * mandatory" forces every derived section to re-decide it by hand, and makes a change in
     * requiredness invisible to drift. The example is illustrative; this is the working shape.
     */
    public record TemplateQuestion(
            String key, QuestionId catalogQuestionId, int order, boolean required, Layout layout) {

        public TemplateQuestion {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("A template question needs a key");
            }
            if (catalogQuestionId == null) {
                throw new IllegalArgumentException("Template question '" + key + "' must reference a catalog entry");
            }
            layout = layout == null ? Layout.FULL : layout;
        }

        public static TemplateQuestion of(String key, QuestionId catalogQuestionId, int order) {
            return new TemplateQuestion(key, catalogQuestionId, order, false, Layout.FULL);
        }
    }

    public enum TemplateStatus {
        ACTIVE,
        DEPRECATED
    }

    public List<TemplateQuestion> orderedQuestions() {
        return questions.stream()
                .sorted(Comparator.comparingInt(TemplateQuestion::order))
                .toList();
    }

    public Optional<TemplateQuestion> question(String questionKey) {
        return questions.stream().filter(q -> q.key().equals(questionKey)).findFirst();
    }

    /**
     * Instantiates this template as a tenant's own section.
     *
     * <p>Every question arrives with {@link Origin#TEMPLATE}, which is what later distinguishes
     * "the template gave me this and I switched it off" from "I added this myself" — the distinction
     * a template upgrade has to reconcile.
     */
    public SectionDefinition instantiate(String sectionId, String tenantId, String name) {
        List<QuestionInstance> instances = orderedQuestions().stream()
                .map(q -> new QuestionInstance(
                        q.key(),
                        q.catalogQuestionId(),
                        Origin.TEMPLATE,
                        true,
                        q.order(),
                        q.required(),
                        q.layout(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null))
                .toList();

        return new SectionDefinition(
                sectionId,
                tenantId,
                key,
                name == null || name.isBlank() ? this.name : name,
                intro,
                id,
                version,
                instances,
                true);
    }

    /**
     * Mints the next version of this template from a section a tenant has evolved.
     *
     * <p>Locally-added questions are promoted alongside the inherited ones — that is the point of
     * promoting. Disabled questions are dropped: an author who switched something off and then
     * promoted is saying the template should not carry it either.
     */
    public SectionTemplate nextVersionFrom(SectionDefinition definition) {
        List<TemplateQuestion> promoted = new ArrayList<>();
        for (QuestionInstance instance : definition.enabledQuestions()) {
            promoted.add(new TemplateQuestion(
                    instance.key(),
                    instance.catalogQuestionId(),
                    instance.order(),
                    instance.required(),
                    instance.layout()));
        }

        return new SectionTemplate(
                id, tenantId, key, name, version + 1, definition.intro(), repeating, promoted, status);
    }

    public static SectionTemplate global(String id, String key, String name, TemplateQuestion... questions) {
        return new SectionTemplate(id, null, key, name, 1, null, null, List.of(questions), TemplateStatus.ACTIVE);
    }
}
