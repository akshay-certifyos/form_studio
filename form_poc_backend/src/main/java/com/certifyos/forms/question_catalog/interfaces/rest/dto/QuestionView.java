package com.certifyos.forms.question_catalog.interfaces.rest.dto;

import com.certifyos.forms.question_catalog.domain.Question;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A catalog question as the authoring UI needs it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionView(
        String id,
        String key,
        String label,
        String helpText,
        String responseType,
        String optionSetKey,
        String filteredBy,
        String status,
        List<String> validations,
        /** Defined once, globally — what replaces per-tenant fieldMappings string paths. */
        Map<String, String> platformMapping,
        /** Payer phrasings absorbed here rather than becoming near-duplicate entries. */
        Set<String> aliases,
        Set<String> tags) {

    public static QuestionView of(Question question) {
        return new QuestionView(
                question.id().value(),
                question.key(),
                question.label(),
                question.helpText(),
                question.responseType().wireName(),
                question.optionSetKey(),
                question.filteredBy(),
                question.status().wireName(),
                question.validations().stream().map(v -> v.rule()).toList(),
                question.platformMapping(),
                question.aliases(),
                question.tags());
    }
}
