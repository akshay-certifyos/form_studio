package com.certifyos.forms.form_authoring.domain.definition;

import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.shared_kernel.expression.Expression;

/**
 * One appearance of a catalog question inside a section.
 *
 * <p>The catalog holds the <b>type</b> — label, response type, validations, platform mapping. This
 * holds everything specific to this appearance: order, whether it is required <em>here</em>, its
 * width, and its own visibility rule.
 *
 * <p>The reserved attachment points are the design's P6 made concrete. Only {@link #visibleWhen} is
 * evaluated in v0, but {@link #requiredWhen}, {@link #defaultWhen} and {@link #validWhen} exist in
 * the shape from day one. Shipping visibility-only and adding the others as bespoke mechanisms
 * later is how a config system turns back into a rule engine.
 *
 * <p><b>Scheduled change — answer-key override.</b> Specified as the next iteration in
 * {@code docs/form-config-poc.md} §12, which carries the shape, the three call sites it touches and
 * a definition of done. Deferred rather than dropped: it lands with the transformer, and neither is
 * useful alone. The reasoning, in short:
 *
 * <p>The answer path here
 * is always {@code stepKey.key}, which is right for forms authored in this model: two placements of
 * one section need disjoint namespaces, and that was the design review's central finding. It is
 * <em>wrong</em> for forms that already exist. Production uses a single flat global namespace
 * ({@code npi}, {@code homeState}, {@code attestationDate}), so bringing an existing form in would
 * rename every one of its answer keys and orphan every in-flight application — 57 keys for the
 * Premera config alone.
 *
 * <p>The fix is one nullable field on this record: an explicit answer key, set once when a form is
 * transformed in, preserved verbatim thereafter, and left unset for anything authored fresh. Not
 * built yet because nothing can populate it without the transformer, and an override nothing sets
 * would imply a capability that does not exist — the same failure mode that left
 * {@code sourceTemplateId} a dangling pointer for most of this POC's life. This is the only audit
 * finding that changes the model rather than the compiler; see also
 * {@code docs/form-config-poc-expressiveness-audit.md} §1.1.
 *
 * @param key local to the step; the answer path is {@code stepKey.key}
 * @param catalogQuestionId the type this instantiates
 * @param origin template-inherited or locally added — drives drift
 * @param enabled false compiles the question out entirely. Distinct from a condition, which leaves
 *     it in the artifact and hides it at runtime.
 */
public record QuestionInstance(
        String key,
        QuestionId catalogQuestionId,
        Origin origin,
        boolean enabled,
        int order,
        boolean required,
        Layout layout,
        String labelOverride,
        String helpTextOverride,
        Expression visibleWhen,
        Expression requiredWhen,
        Expression defaultWhen,
        Expression validWhen) {

    /** Everything else held constant — a reorder must not disturb provenance or overrides. */
    public QuestionInstance withOrder(int newOrder) {
        return new QuestionInstance(
                key,
                catalogQuestionId,
                origin,
                enabled,
                newOrder,
                required,
                layout,
                labelOverride,
                helpTextOverride,
                visibleWhen,
                requiredWhen,
                defaultWhen,
                validWhen);
    }

    public QuestionInstance {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Question instance key is required");
        }
        if (!key.matches("[a-zA-Z][a-zA-Z0-9]*")) {
            throw new IllegalArgumentException("Question key must be alphanumeric starting with a letter, got: " + key
                    + ". It becomes half of the answer path.");
        }
        if (catalogQuestionId == null) {
            throw new IllegalArgumentException("A question instance must reference a catalog question: " + key);
        }
        origin = origin == null ? Origin.ADDED : origin;
        layout = layout == null ? Layout.FULL : layout;
    }

    /** A question inherited from a template, at full width, not conditioned. */
    public static QuestionInstance fromTemplate(String key, QuestionId catalogId, int order, boolean required) {
        return new QuestionInstance(
                key,
                catalogId,
                Origin.TEMPLATE,
                true,
                order,
                required,
                Layout.FULL,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /** A question the tenant added themselves. */
    public static QuestionInstance added(String key, QuestionId catalogId, int order, boolean required) {
        return new QuestionInstance(
                key, catalogId, Origin.ADDED, true, order, required, Layout.FULL, null, null, null, null, null, null);
    }

    /**
     * Disables rather than removes.
     *
     * <p>A template-sourced question that is deleted loses its {@link Origin}, and with it the
     * ability to reconcile against a template upgrade — the author would be told the question is
     * "missing" rather than "you turned this off".
     */
    public QuestionInstance disable() {
        return enabled ? copyWith(false, required, layout, visibleWhen) : this;
    }

    public QuestionInstance enable() {
        return enabled ? this : copyWith(true, required, layout, visibleWhen);
    }

    public QuestionInstance withRequired(boolean value) {
        return copyWith(enabled, value, layout, visibleWhen);
    }

    public QuestionInstance withLayout(Layout value) {
        return copyWith(enabled, required, value, visibleWhen);
    }

    public QuestionInstance withVisibleWhen(Expression expression) {
        return copyWith(enabled, required, layout, expression);
    }

    public boolean isFromTemplate() {
        return origin == Origin.TEMPLATE;
    }

    private QuestionInstance copyWith(boolean enabled, boolean required, Layout layout, Expression visibleWhen) {
        return new QuestionInstance(
                key,
                catalogQuestionId,
                origin,
                enabled,
                order,
                required,
                layout,
                labelOverride,
                helpTextOverride,
                visibleWhen,
                requiredWhen,
                defaultWhen,
                validWhen);
    }
}
