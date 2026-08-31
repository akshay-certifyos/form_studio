package com.certifyos.forms.question_catalog.interfaces.rest.dto;

import com.certifyos.forms.question_catalog.application.CatalogService;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The outcome of a promotion attempt.
 *
 * <p>When it is refused, the response carries the questions it collided with and an explanation of
 * each. That is deliberate: the steward's next move is almost always "absorb this phrasing as an
 * alias of the question that already exists", and they cannot make that call from a bare rejection.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PromotionResultView(boolean promoted, QuestionView question, List<DuplicateView> duplicates) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DuplicateView(
            String existingQuestionId, String existingLabel, String reason, double confidence, String explanation) {}

    public static PromotionResultView of(CatalogService.PromotionResult result) {
        return new PromotionResultView(
                result.promoted(),
                QuestionView.of(result.question()),
                result.duplicates().isEmpty()
                        ? null
                        : result.duplicates().stream()
                                .map(m -> new DuplicateView(
                                        m.existing().id().value(),
                                        m.existing().label(),
                                        m.reason().name(),
                                        m.confidence(),
                                        m.explanation()))
                                .toList());
    }
}
