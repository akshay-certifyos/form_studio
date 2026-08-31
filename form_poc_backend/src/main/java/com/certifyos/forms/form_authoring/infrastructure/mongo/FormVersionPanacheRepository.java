package com.certifyos.forms.form_authoring.infrastructure.mongo;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/** Raw Panache access to {@code form_versions}. */
@ApplicationScoped
public class FormVersionPanacheRepository implements PanacheMongoRepositoryBase<FormVersionDocument, String> {}
