package com.certifyos.forms.form_authoring.infrastructure.mongo;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/** Raw Panache access to {@code section_definitions}. See FormDefinitionPanacheRepository for why. */
@ApplicationScoped
public class SectionDefinitionPanacheRepository
        implements PanacheMongoRepositoryBase<SectionDefinitionDocument, String> {}
