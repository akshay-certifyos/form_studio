package com.certifyos.forms.form_authoring.domain.port;

import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Persistence for the {@link SectionDefinition} aggregate. */
public interface SectionDefinitionRepository {

    Optional<SectionDefinition> findById(String id);

    /** Batch lookup — a form resolves every section it places in one call, not N. */
    List<SectionDefinition> findAllById(Collection<String> ids);

    List<SectionDefinition> findByTenant(String tenantId);

    SectionDefinition save(SectionDefinition definition);
}
