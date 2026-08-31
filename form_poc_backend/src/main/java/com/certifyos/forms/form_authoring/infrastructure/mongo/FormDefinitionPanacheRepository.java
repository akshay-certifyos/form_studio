package com.certifyos.forms.form_authoring.infrastructure.mongo;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Raw Panache access to the {@code form_definitions} collection.
 *
 * <p>Separate from the port adapter on purpose. Implementing both {@code PanacheMongoRepositoryBase}
 * and the domain port on one class does not compile — both declare {@code findById}, with different
 * return types and the same erasure — and the collision is a useful hint: the domain port speaks in
 * {@code Optional<FormDefinition>}, Panache speaks in documents, and one class trying to be both
 * ends up leaking the driver's vocabulary into the domain's interface.
 */
@ApplicationScoped
public class FormDefinitionPanacheRepository implements PanacheMongoRepositoryBase<FormDefinitionDocument, String> {}
