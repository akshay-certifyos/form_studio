package com.certifyos.forms.question_catalog.domain.port;

import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Persistence for the {@link Question} aggregate. */
public interface QuestionRepository {

    Optional<Question> findById(QuestionId id);

    List<Question> findAllById(Collection<QuestionId> ids);

    /** Every active entry visible to a tenant: global plus that tenant's own. */
    List<Question> findActiveFor(String tenantId);

    /**
     * Searches label, key and aliases.
     *
     * @param includeProposed also return entries awaiting promotion. Deprecated entries are never
     *     returned — hiding those is the point of deprecating them. The flag is explicit because the
     *     two branches used to disagree: an empty search returned active entries only, while a text
     *     search silently returned proposed ones too, so the same catalog had two different sizes
     *     depending on whether the box was empty.
     */
    List<Question> search(String tenantId, String text, boolean includeProposed);

    Question save(Question question);
}
