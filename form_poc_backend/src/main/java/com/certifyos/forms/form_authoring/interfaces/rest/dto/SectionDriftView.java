package com.certifyos.forms.form_authoring.interfaces.rest.dto;

import com.certifyos.forms.form_authoring.domain.reuse.SectionDrift;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * How far a section has moved from its template.
 *
 * <p>Findings are split into two lists rather than one, because the UI has to say two different
 * things: what re-syncing would bring in, and what it would destroy. A single flat list invites a
 * "Sync" button that silently discards a tenant's customisations.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SectionDriftView(
        String sectionId,
        String sourceTemplateId,
        Integer definitionTemplateVersion,
        Integer currentTemplateVersion,
        boolean behindTemplate,
        boolean hasDrift,
        List<FindingView> templateChanges,
        List<FindingView> localCustomisations) {

    public record FindingView(String code, String questionKey, String detail) {}

    public static SectionDriftView of(SectionDrift drift) {
        return new SectionDriftView(
                drift.sectionDefinitionId(),
                drift.sourceTemplateId(),
                drift.definitionTemplateVersion(),
                drift.currentTemplateVersion(),
                drift.behindTemplate(),
                drift.hasDrift(),
                drift.templateChanges().stream().map(SectionDriftView::view).toList(),
                drift.localCustomisations().stream().map(SectionDriftView::view).toList());
    }

    private static FindingView view(SectionDrift.Finding finding) {
        return new FindingView(finding.code().name(), finding.questionKey(), finding.detail());
    }
}
