package com.certifyos.forms.form_authoring.interfaces.rest.dto;

import com.certifyos.forms.form_authoring.domain.publishing.FormVersion;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** A published version. The compiled artifact is fetched separately — it is large. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublishedVersionView(
        String id,
        int version,
        String changeClass,
        String changelog,
        String ticketId,
        Instant publishedAt,
        String publishedBy,
        int stepCount) {

    public static PublishedVersionView of(FormVersion version) {
        return new PublishedVersionView(
                version.id(),
                version.version(),
                version.changeClass().name().toLowerCase(java.util.Locale.ROOT),
                version.changelog(),
                version.ticketId(),
                version.publishedAt(),
                version.publishedBy(),
                version.artifact().steps().size());
    }
}
