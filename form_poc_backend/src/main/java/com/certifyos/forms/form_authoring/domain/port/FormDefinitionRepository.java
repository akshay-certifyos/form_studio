package com.certifyos.forms.form_authoring.domain.port;

import com.certifyos.forms.form_authoring.domain.definition.FormDefinition;
import java.util.List;
import java.util.Optional;

/** Persistence for the {@link FormDefinition} aggregate. Implemented in infrastructure. */
public interface FormDefinitionRepository {

    Optional<FormDefinition> findById(String id);

    List<FormDefinition> findByTenant(String tenantId);

    FormDefinition save(FormDefinition definition);

    /** Throws rather than returning empty, for the many call sites where absence is a bug. */
    default FormDefinition require(String id) {
        return findById(id).orElseThrow(() -> new java.util.NoSuchElementException("No form definition: " + id));
    }
}
