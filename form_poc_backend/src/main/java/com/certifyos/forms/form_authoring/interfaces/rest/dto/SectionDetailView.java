package com.certifyos.forms.form_authoring.interfaces.rest.dto;

import com.certifyos.forms.form_authoring.domain.definition.QuestionInstance;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.shared_kernel.expression.ExpressionCodec;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** A section with its questions resolved against the catalog, ready for the authoring tree. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SectionDetailView(
        String id,
        String key,
        String name,
        String intro,
        String sourceTemplateId,
        Integer sourceTemplateVersion,
        List<String> requires,
        List<QuestionInstanceView> questions) {

    /**
     * @param origin {@code TEMPLATE} or {@code ADDED} — drives the drift indicator, and is why a
     *     template question is disabled rather than deleted
     * @param enabled false means the question is compiled out entirely. Distinct from a condition,
     *     which leaves it in the artifact and hides it at runtime. The UI shows these differently
     *     for exactly that reason.
     * @param optionSetKey present for selects; the client fetches the values separately
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuestionInstanceView(
            String key,
            String catalogQuestionId,
            String label,
            String helpText,
            String responseType,
            String optionSetKey,
            String filteredBy,
            String origin,
            boolean enabled,
            int order,
            boolean required,
            int layoutColumns,
            List<String> validations,
            JsonNode visibleWhen) {}

    public static SectionDetailView of(SectionDefinition section, Map<QuestionId, Question> catalog) {
        List<QuestionInstanceView> views = section.questions().stream()
                .sorted(Comparator.comparingInt(QuestionInstance::order))
                .map(instance -> {
                    Question question = catalog.get(instance.catalogQuestionId());
                    return new QuestionInstanceView(
                            instance.key(),
                            instance.catalogQuestionId().value(),
                            instance.labelOverride() != null
                                    ? instance.labelOverride()
                                    : question == null ? instance.key() : question.label(),
                            instance.helpTextOverride() != null
                                    ? instance.helpTextOverride()
                                    : question == null ? null : question.helpText(),
                            question == null ? "text" : question.responseType().wireName(),
                            question == null ? null : question.optionSetKey(),
                            question == null ? null : question.filteredBy(),
                            instance.origin().name(),
                            instance.enabled(),
                            instance.order(),
                            instance.required(),
                            instance.layout().columns(),
                            question == null
                                    ? List.of()
                                    : question.validations().stream()
                                            .map(v -> v.rule())
                                            .toList(),
                            ExpressionCodec.write(instance.visibleWhen()));
                })
                .toList();

        return new SectionDetailView(
                section.id(),
                section.key(),
                section.name(),
                section.intro(),
                section.sourceTemplateId(),
                section.sourceTemplateVersion(),
                List.copyOf(section.externalRefs()),
                views);
    }
}
