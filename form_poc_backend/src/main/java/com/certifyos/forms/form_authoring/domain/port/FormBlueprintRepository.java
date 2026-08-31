package com.certifyos.forms.form_authoring.domain.port;

import com.certifyos.forms.form_authoring.domain.reuse.FormBlueprint;
import java.util.List;
import java.util.Optional;

/** Persistence for {@link FormBlueprint}. Implemented in infrastructure. */
public interface FormBlueprintRepository {

    Optional<FormBlueprint> findById(String id);

    /** Global blueprints plus the tenant's own, active only. */
    List<FormBlueprint> findAvailableFor(String tenantId);

    FormBlueprint save(FormBlueprint blueprint);
}
