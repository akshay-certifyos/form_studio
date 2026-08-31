package com.certifyos.forms.form_authoring.infrastructure;

import com.certifyos.forms.form_authoring.domain.compile.CatalogSnapshot;
import com.certifyos.forms.form_authoring.domain.port.QuestionCatalogPort;
import com.certifyos.forms.question_catalog.domain.OptionSet;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.port.OptionSetRepository;
import com.certifyos.forms.question_catalog.domain.port.QuestionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one place {@code form_authoring} touches {@code question_catalog}.
 *
 * <p>An anti-corruption seam. It lives in {@code form_authoring.infrastructure} rather than in the
 * catalog, because the consumer owns the shape it needs — that is what lets the catalog become a
 * separate service later with only this class changing.
 *
 * <p>Note that it resolves option sets <em>transitively</em> from the questions. An earlier version
 * of the port asked the caller for them separately, which was redundant and was the only way to get
 * it wrong: a caller passing an empty collection silently lost every dropdown's options, and the
 * analyzer quietly stopped validating condition values.
 */
@ApplicationScoped
public class InProcessQuestionCatalogAdapter implements QuestionCatalogPort {

    private final QuestionRepository questions;
    private final OptionSetRepository optionSets;

    @Inject
    public InProcessQuestionCatalogAdapter(QuestionRepository questions, OptionSetRepository optionSets) {
        this.questions = questions;
        this.optionSets = optionSets;
    }

    @Override
    public CatalogSnapshot resolve(Collection<QuestionId> questionIds) {
        List<Question> resolved = questions.findAllById(questionIds);

        Set<String> keys = resolved.stream()
                .map(Question::optionSetKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Grouped questions carry children with their own option sets.
        resolved.forEach(q -> collectChildKeys(q, keys));

        List<OptionSet> sets = keys.isEmpty() ? List.of() : optionSets.findAllByKey(null, keys);
        return CatalogSnapshot.of(resolved, sets);
    }

    private static void collectChildKeys(Question question, Set<String> keys) {
        for (Question child : question.children()) {
            if (child.optionSetKey() != null) {
                keys.add(child.optionSetKey());
            }
            collectChildKeys(child, keys);
        }
    }
}
