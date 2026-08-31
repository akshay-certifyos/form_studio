package com.certifyos.forms.form_authoring.domain.definition;

import com.certifyos.forms.shared_kernel.expression.AnalysisScope;
import com.certifyos.forms.shared_kernel.expression.ExpressionAnalyzer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A tenant-owned, reusable block of questions. Aggregate root.
 *
 * <p>Owns <b>content only</b> — which questions, in what order, with what local rules. Where it
 * appears in a form, when it appears, and what namespace its answers live in are all owned by the
 * {@link Step} that places it. That split is what lets one address section be placed twice in a
 * form as Practice Location and Billing Address.
 *
 * <p>Created from a global template by copy-on-use: {@link #sourceTemplateId} and {@link
 * #sourceTemplateVersion} record the provenance so drift stays computable, but Certify editing the
 * template never mutates a tenant's copy.
 */
public record SectionDefinition(
        String id,
        String tenantId,
        String key,
        String name,
        String intro,
        String sourceTemplateId,
        Integer sourceTemplateVersion,
        List<QuestionInstance> questions,
        boolean active) {

    public SectionDefinition {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Section key is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Section name is required — it is the step title an author reads");
        }
        questions = questions == null ? List.of() : List.copyOf(questions);

        Set<String> seen = new LinkedHashSet<>();
        for (QuestionInstance q : questions) {
            if (!seen.add(q.key())) {
                throw new IllegalArgumentException("Duplicate question key '" + q.key() + "' in section '" + key
                        + "' — keys become answer paths, so they must be unique within a section");
            }
        }
    }

    /**
     * Reorders the questions to the given key sequence.
     *
     * <p>Takes the <b>whole list</b> rather than one question and a new position, because a partial
     * reorder cannot be made safe: any question left out keeps its old number and can collide with a
     * renumbered one, and two questions sharing an order make the sort unstable — the form then
     * renders in a different sequence depending on iteration order, which surfaces as "it looked
     * different after reload" rather than as an error.
     *
     * <p>Numbers are renormalised to 10, 20, 30… on every call. Spacing is kept because templates and
     * fixtures use it and it leaves room to read; correctness does not depend on it, since the whole
     * list is rewritten each time.
     *
     * <p><b>Disabled questions participate.</b> They hold a position so that re-enabling one puts it
     * back where it was rather than at the end.
     *
     * @param orderedKeys every question key in this section, exactly once
     * @throws IllegalArgumentException if the keys are not exactly this section's keys
     */
    public SectionDefinition reorderQuestions(List<String> orderedKeys) {
        Set<String> submitted = new LinkedHashSet<>(orderedKeys);
        if (submitted.size() != orderedKeys.size()) {
            throw new IllegalArgumentException("Duplicate keys in the requested order: " + orderedKeys);
        }

        Set<String> own = questions.stream()
                .map(QuestionInstance::key)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!submitted.equals(own)) {
            Set<String> missing = new LinkedHashSet<>(own);
            missing.removeAll(submitted);
            Set<String> unknown = new LinkedHashSet<>(submitted);
            unknown.removeAll(own);
            throw new IllegalArgumentException("A reorder must list every question in the section exactly once."
                    + (missing.isEmpty() ? "" : " Missing: " + missing + ".")
                    + (unknown.isEmpty() ? "" : " Not in this section: " + unknown + "."));
        }

        Map<String, QuestionInstance> byKey = new LinkedHashMap<>();
        questions.forEach(q -> byKey.put(q.key(), q));

        List<QuestionInstance> reordered = new java.util.ArrayList<>();
        int order = 10;
        for (String key : orderedKeys) {
            reordered.add(byKey.get(key).withOrder(order));
            order += 10;
        }

        return new SectionDefinition(
                id, tenantId, key, name, intro, sourceTemplateId, sourceTemplateVersion, reordered, active);
    }

    /** Questions that survive compilation. Disabled ones never reach the artifact. */
    public List<QuestionInstance> enabledQuestions() {
        return questions.stream()
                .filter(QuestionInstance::enabled)
                .sorted(java.util.Comparator.comparingInt(QuestionInstance::order))
                .toList();
    }

    public Optional<QuestionInstance> question(String questionKey) {
        return questions.stream().filter(q -> q.key().equals(questionKey)).findFirst();
    }

    /**
     * Question keys this section's own conditions read from <em>outside</em> itself.
     *
     * <p><b>Computed, never authored.</b> This is the section's contract — like a function
     * signature. Placing it in a form that lacks these questions fails at compile time with a clear
     * message, instead of the section silently never rendering, which is the failure mode that
     * makes conditional forms miserable to debug.
     */
    public Set<String> externalRefs() {
        Set<String> own = new LinkedHashSet<>();
        questions.forEach(q -> own.add(q.key()));

        Set<String> external = new LinkedHashSet<>();
        AnalysisScope scope = AnalysisScope.empty();
        for (QuestionInstance q : questions) {
            for (String path : ExpressionAnalyzer.referencedPaths(q.visibleWhen(), scope)) {
                // Inside a section definition, a condition names a sibling by bare key; anything
                // dotted is already a cross-step reference and belongs to the placement.
                if (!own.contains(path)) {
                    external.add(path);
                }
            }
        }
        return external;
    }

    /** True when nothing outside this section is needed to render it. */
    public boolean isSelfContained() {
        return externalRefs().isEmpty();
    }

    // ------------------------------------------------------------------
    // authoring
    // ------------------------------------------------------------------

    public SectionDefinition addQuestion(QuestionInstance question) {
        if (question(question.key()).isPresent()) {
            throw new IllegalArgumentException("Question '" + question.key() + "' is already in section '" + key + "'");
        }
        List<QuestionInstance> next = new ArrayList<>(questions);
        next.add(question);
        return copyWith(next);
    }

    /** Disables a question. Template-sourced questions are never removed — see {@link Origin}. */
    public SectionDefinition disableQuestion(String questionKey) {
        return mapQuestion(questionKey, QuestionInstance::disable);
    }

    public SectionDefinition enableQuestion(String questionKey) {
        return mapQuestion(questionKey, QuestionInstance::enable);
    }

    /**
     * Removes a question outright. Only legal for locally added ones — removing a template-sourced
     * question would discard the provenance a template upgrade needs.
     */
    public SectionDefinition removeQuestion(String questionKey) {
        QuestionInstance target = question(questionKey)
                .orElseThrow(() -> new IllegalArgumentException("No question '" + questionKey + "' in " + key));
        if (target.isFromTemplate()) {
            throw new IllegalStateException("'" + questionKey
                    + "' came from a template. Disable it instead of removing it, so the link to the template "
                    + "survives and drift stays computable.");
        }
        return copyWith(
                questions.stream().filter(q -> !q.key().equals(questionKey)).toList());
    }

    private SectionDefinition mapQuestion(String questionKey, java.util.function.UnaryOperator<QuestionInstance> fn) {
        if (question(questionKey).isEmpty()) {
            throw new IllegalArgumentException("No question '" + questionKey + "' in section '" + key + "'");
        }
        return copyWith(questions.stream()
                .map(q -> q.key().equals(questionKey) ? fn.apply(q) : q)
                .toList());
    }

    private SectionDefinition copyWith(List<QuestionInstance> next) {
        return new SectionDefinition(
                id, tenantId, key, name, intro, sourceTemplateId, sourceTemplateVersion, next, active);
    }
}
