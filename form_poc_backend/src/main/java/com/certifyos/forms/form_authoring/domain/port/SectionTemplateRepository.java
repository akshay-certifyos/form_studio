package com.certifyos.forms.form_authoring.domain.port;

import com.certifyos.forms.form_authoring.domain.reuse.SectionTemplate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for {@link SectionTemplate}. Implemented in infrastructure.
 *
 * <p>Reads resolve the <em>latest</em> version of a template. A specific historical version is not
 * retrievable, and deliberately so for v0: a definition records the version it came from as a fact
 * about its own history, and drift only ever compares against the current one. Fetching an arbitrary
 * old version would invite a "re-sync to v2" feature that nothing here has a use for.
 */
public interface SectionTemplateRepository {

    Optional<SectionTemplate> findById(String id);

    List<SectionTemplate> findAllById(Collection<String> ids);

    /** Global templates plus the tenant's own, active only. */
    List<SectionTemplate> findAvailableFor(String tenantId);

    SectionTemplate save(SectionTemplate template);
}
