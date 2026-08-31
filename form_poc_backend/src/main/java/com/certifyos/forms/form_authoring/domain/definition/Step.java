package com.certifyos.forms.form_authoring.domain.definition;

import com.certifyos.forms.shared_kernel.expression.Expression;

/**
 * One use of a section definition in one form. Compiles 1:1 to a {@code step} in the artifact.
 *
 * <p>Holds the three things that differ between two uses of the same section:
 *
 * <ul>
 *   <li><b>where</b> it sits — {@link #order}
 *   <li><b>when</b> it appears — {@link #visibleWhen}
 *   <li><b>where its answers go</b> — {@link #key}
 * </ul>
 *
 * <p>Cross-section conditions live here rather than on the section definition, because the form is
 * the only level that knows the complete namespace. A shared "Licensure" section carrying a
 * condition on {@code hasCaqhId} would break in any form without that question; the same section
 * placed twice with different conditions works because the condition belongs to the placement.
 *
 * @param group a purely presentational label, e.g. "Credentials". Compiles to nothing. If grouping
 *     ever needs behaviour — save points, completion gating — it gets promoted to a real level
 *     then, with a use case to justify it.
 */
public record Step(
        StepKey key,
        String sectionDefinitionId,
        int order,
        boolean enabled,
        String titleOverride,
        String group,
        Repeating repeating,
        Expression visibleWhen,
        Expression audienceWhen) {

    public Step {
        if (key == null) {
            throw new IllegalArgumentException("A step needs a key — it is the answer namespace");
        }
        if (sectionDefinitionId == null || sectionDefinitionId.isBlank()) {
            throw new IllegalArgumentException("Step '" + key + "' must reference a section definition");
        }
    }

    /**
     * @param itemLabel singular, e.g. "License" — the UI renders "Add another license"
     */
    public record Repeating(int min, int max, String itemLabel) {
        public Repeating {
            if (min < 0) {
                throw new IllegalArgumentException("Repeating min cannot be negative");
            }
            if (max < min) {
                throw new IllegalArgumentException("Repeating max must be at least min");
            }
            if (itemLabel == null || itemLabel.isBlank()) {
                throw new IllegalArgumentException("A repeating step needs an item label");
            }
        }
    }

    public static Step of(String key, String sectionDefinitionId, int order) {
        return new Step(StepKey.of(key), sectionDefinitionId, order, true, null, null, null, null, null);
    }

    public boolean isRepeating() {
        return repeating != null;
    }

    /** The answer path for one question in this step. */
    public String pathFor(String questionKey) {
        return key.pathFor(questionKey);
    }

    public Step withVisibleWhen(Expression expression) {
        return new Step(
                key, sectionDefinitionId, order, enabled, titleOverride, group, repeating, expression, audienceWhen);
    }

    public Step withRepeating(Repeating value) {
        return new Step(
                key, sectionDefinitionId, order, enabled, titleOverride, group, value, visibleWhen, audienceWhen);
    }

    public Step withOrder(int value) {
        return new Step(
                key, sectionDefinitionId, value, enabled, titleOverride, group, repeating, visibleWhen, audienceWhen);
    }

    public Step withGroup(String value) {
        return new Step(
                key, sectionDefinitionId, order, enabled, titleOverride, value, repeating, visibleWhen, audienceWhen);
    }

    public Step disable() {
        return new Step(
                key, sectionDefinitionId, order, false, titleOverride, group, repeating, visibleWhen, audienceWhen);
    }
}
