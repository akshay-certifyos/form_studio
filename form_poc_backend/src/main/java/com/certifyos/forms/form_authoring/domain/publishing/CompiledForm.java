package com.certifyos.forms.form_authoring.domain.publishing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * The runtime artifact — the exact shape the existing renderer already consumes.
 *
 * <p>This is what makes the whole approach incremental: the compiler's output is today's
 * {@code FormConfig}, so {@code getFormConfig} does not change, the submission pipeline does not
 * change, and existing forms keep working. Nothing downstream learns that a catalog exists.
 *
 * <p>Modelled from the <b>observed</b> production config
 * ({@code apps/provider-portal/features/practitioner/constants/credentialing-form-config.ts}, 11
 * steps and 63 fields) rather than from the TypeScript interface. That mattered: every field there
 * carries {@code layout}, which the interface marks optional and which a compiler written from the
 * declaration would have omitted.
 *
 * <p>{@code @JsonInclude(NON_NULL)} throughout, because absent and null are different in the target
 * format and a diff between artifacts has to be meaningful.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompiledForm(
        String title, String instructionsTitle, List<CompiledStep> steps, Boolean requiresPractitionerSignature) {

    public CompiledForm {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /**
     * One step. A {@link com.certifyos.forms.form_authoring.domain.definition.Step} compiles 1:1 to
     * one of these, so its {@code visibleWhen} maps straight onto {@link #condition} with nothing
     * lost — which is why the design has one grouping level rather than pages above sections.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompiledStep(
            String id,
            String title,
            String type,
            List<CompiledField> fields,
            JsonNode condition,
            String audience,
            JsonNode instructionsContent) {

        public CompiledStep {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /**
     * One field.
     *
     * @param name the answer key. Placement-scoped in the definition; see
     *     {@link com.certifyos.forms.form_authoring.domain.compile.FormCompiler} for how it is
     *     emitted.
     * @param dependsOn production's own visibility mechanism — a field shown once its parent is
     *     answered. Emitted for the simple case so existing forms stay round-trippable, while
     *     richer rules use {@link #condition}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompiledField(
            String name,
            String label,
            String type,
            Boolean required,
            String hint,
            List<CompiledOption> options,
            CompiledValidation validation,
            CompiledLayout layout,
            String dependsOn,
            JsonNode condition,
            String accept,
            Integer rows,
            List<CompiledField> groupFields) {

        public CompiledField {
            options = options == null ? null : List.copyOf(options);
            groupFields = groupFields == null ? null : List.copyOf(groupFields);
        }
    }

    /**
     * @param filterValue production's option-level filter: this option shows only when the
     *     {@code dependsOn} parent holds this value. How PRD §4.3 is expressed in the target format.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompiledOption(String value, String label, String filterValue) {}

    /** Validations flattened into the shape the renderer reads. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompiledValidation(
            Integer minLength,
            Integer maxLength,
            Integer min,
            Integer max,
            String pattern,
            String message,
            Integer maxSize,
            String customValidator,
            String maxDate,
            String minDate) {}

    /** Grid width. Present on every field in the production config. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompiledLayout(Integer columns) {}
}
