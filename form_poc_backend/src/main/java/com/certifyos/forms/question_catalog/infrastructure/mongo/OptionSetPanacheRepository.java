package com.certifyos.forms.question_catalog.infrastructure.mongo;

import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

/** Raw Panache access to {@code option_sets}. */
@ApplicationScoped
public class OptionSetPanacheRepository implements PanacheMongoRepositoryBase<OptionSetDocument, String> {}
