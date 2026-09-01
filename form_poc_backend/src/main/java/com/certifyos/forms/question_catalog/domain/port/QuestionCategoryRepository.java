package com.certifyos.forms.question_catalog.domain.port;

import com.certifyos.forms.question_catalog.domain.QuestionCategory;
import java.util.List;
import java.util.Optional;

/** Read-mostly: the taxonomy changes rarely, and never as a side effect of authoring a question. */
public interface QuestionCategoryRepository {

    Optional<QuestionCategory> findByKey(String key);

    /** Every category, in {@code order}. */
    List<QuestionCategory> findAll();

    QuestionCategory save(QuestionCategory category);
}
