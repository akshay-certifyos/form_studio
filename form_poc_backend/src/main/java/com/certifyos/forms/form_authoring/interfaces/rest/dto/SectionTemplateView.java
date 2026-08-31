package com.certifyos.forms.form_authoring.interfaces.rest.dto;

import com.certifyos.forms.form_authoring.domain.reuse.SectionTemplate;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A reusable section shape, as the studio needs it.
 *
 * <p>{@code global} is derived rather than exposing a null tenantId, because "available to everyone"
 * is the fact an author cares about and "the column is null" is not.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SectionTemplateView(
        String id,
        String key,
        String name,
        int version,
        String intro,
        boolean global,
        boolean repeating,
        int questionCount,
        List<TemplateQuestionView> questions) {

    public record TemplateQuestionView(
            String key, String catalogQuestionId, int order, boolean required, int layoutColumns) {}

    public static SectionTemplateView of(SectionTemplate template) {
        return new SectionTemplateView(
                template.id(),
                template.key(),
                template.name(),
                template.version(),
                template.intro(),
                template.tenantId() == null,
                template.repeating() != null,
                template.questions().size(),
                template.orderedQuestions().stream()
                        .map(q -> new TemplateQuestionView(
                                q.key(),
                                q.catalogQuestionId().value(),
                                q.order(),
                                q.required(),
                                q.layout().columns()))
                        .toList());
    }
}
