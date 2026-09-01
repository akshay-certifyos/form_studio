package com.certifyos.forms.question_catalog.interfaces.rest.dto;

import com.certifyos.forms.question_catalog.domain.QuestionCategory;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A catalog category.
 *
 * @param questionCount how many active questions sit on this shelf. Supplied because a browse panel
 *     needs it on the heading, and counting client-side would mean the count following whatever
 *     filter is applied — which is a different, less useful number than "what is in the catalog".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionCategoryView(String key, String label, String description, int order, int questionCount) {

    public static QuestionCategoryView of(QuestionCategory category, int questionCount) {
        return new QuestionCategoryView(
                category.key(), category.label(), category.description(), category.order(), questionCount);
    }
}
