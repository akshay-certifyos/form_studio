package com.certifyos.forms.form_authoring.domain.compile;

import com.certifyos.forms.question_catalog.domain.OptionSet;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catalog data frozen at one moment, handed to the compiler.
 *
 * <p>A snapshot rather than live repository access is what keeps {@link FormCompiler} a pure
 * function — same inputs, same artifact, no I/O in the middle. That is also what makes the golden
 * tests possible and what makes a published version exactly reproducible.
 */
public record CatalogSnapshot(Map<QuestionId, Question> questions, Map<String, OptionSet> optionSets) {

    public CatalogSnapshot {
        questions = questions == null ? Map.of() : Map.copyOf(questions);
        optionSets = optionSets == null ? Map.of() : Map.copyOf(optionSets);
    }

    public static CatalogSnapshot of(List<Question> questions, List<OptionSet> optionSets) {
        return new CatalogSnapshot(
                questions.stream().collect(java.util.stream.Collectors.toMap(Question::id, q -> q)),
                optionSets.stream().collect(java.util.stream.Collectors.toMap(OptionSet::key, s -> s)));
    }

    public Optional<Question> question(QuestionId id) {
        return Optional.ofNullable(questions.get(id));
    }

    public Optional<OptionSet> optionSet(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(optionSets.get(key));
    }

    public static CatalogSnapshot empty() {
        return new CatalogSnapshot(Map.of(), Map.of());
    }
}
