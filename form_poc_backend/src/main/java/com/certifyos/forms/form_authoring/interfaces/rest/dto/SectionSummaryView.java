package com.certifyos.forms.form_authoring.interfaces.rest.dto;

import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Row in the sections list. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SectionSummaryView(
        String id,
        String key,
        String name,
        String sourceTemplateId,
        Integer sourceTemplateVersion,
        int questionCount,
        int disabledCount,
        /**
         * Questions this section's own rules read from outside itself — its contract.
         *
         * <p>Computed, never authored. Surfaced because placing a section in a form that lacks these
         * questions fails at compile time, and an author choosing a section deserves to know that
         * before they place it rather than after.
         */
        List<String> requires) {

    public static SectionSummaryView of(SectionDefinition section) {
        return new SectionSummaryView(
                section.id(),
                section.key(),
                section.name(),
                section.sourceTemplateId(),
                section.sourceTemplateVersion(),
                section.questions().size(),
                (int) section.questions().stream().filter(q -> !q.enabled()).count(),
                List.copyOf(section.externalRefs()));
    }
}
