package com.certifyos.forms.question_catalog.domain.port;

import com.certifyos.forms.question_catalog.domain.OptionSet;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Persistence for the {@link OptionSet} aggregate. */
public interface OptionSetRepository {

    Optional<OptionSet> findByKey(String tenantId, String key);

    List<OptionSet> findAllByKey(String tenantId, Collection<String> keys);

    List<OptionSet> findAllFor(String tenantId);

    OptionSet save(OptionSet optionSet);
}
