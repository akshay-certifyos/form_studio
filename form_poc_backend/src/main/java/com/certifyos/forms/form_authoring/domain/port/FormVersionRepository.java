package com.certifyos.forms.form_authoring.domain.port;

import com.certifyos.forms.form_authoring.domain.publishing.FormVersion;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for published versions.
 *
 * <p>Note what is absent: no {@code archive} or {@code deactivate}. The active version is
 * <em>derived</em> — the highest one with a publish timestamp — so publishing is a single insert
 * with no multi-document write, no transaction, and version records that are genuinely immutable
 * once written.
 */
public interface FormVersionRepository {

    Optional<FormVersion> findById(String id);

    /** The live version: highest version number that has been published. */
    Optional<FormVersion> findActive(String formDefinitionId);

    List<FormVersion> findHistory(String formDefinitionId);

    FormVersion save(FormVersion version);
}
