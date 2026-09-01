package com.certifyos.forms.question_catalog.application;

import com.certifyos.forms.question_catalog.domain.CatalogStatus;
import com.certifyos.forms.question_catalog.domain.DuplicateDetector;
import com.certifyos.forms.question_catalog.domain.OptionSet;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionCategory;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.port.OptionSetRepository;
import com.certifyos.forms.question_catalog.domain.port.QuestionCategoryRepository;
import com.certifyos.forms.question_catalog.domain.port.QuestionRepository;
import com.certifyos.forms.shared_kernel.exception.ConflictingState;
import com.certifyos.forms.shared_kernel.exception.NotFound;
import java.util.List;

/**
 * Catalog operations. Orchestration only — the rules live in {@link Question} and {@link
 * DuplicateDetector}.
 *
 * <p>The interesting method is {@link #promote}: it is the gate standing between this catalog and
 * the state where "NPI", "NPI Number", "National Provider Identifier" and "Individual NPI" are four
 * separate entries. Catalog rot is the highest risk in the design, and it does not arrive as a bug
 * — it arrives as a hundred reasonable-looking approvals.
 */
public class CatalogService {

    private final QuestionRepository questions;
    private final OptionSetRepository optionSets;
    private final QuestionCategoryRepository categories;

    public CatalogService(
            QuestionRepository questions, OptionSetRepository optionSets, QuestionCategoryRepository categories) {
        this.questions = questions;
        this.optionSets = optionSets;
        this.categories = categories;
    }

    public List<Question> search(String tenantId, String text, boolean includeProposed) {
        return questions.search(tenantId, text, includeProposed);
    }

    public Question require(QuestionId id) {
        return questions.findById(id).orElseThrow(() -> new NotFound("Question", id.value()));
    }

    public List<OptionSet> optionSetsFor(String tenantId) {
        return optionSets.findAllFor(tenantId);
    }

    /** A proposed question. It is not usable in a form until a steward promotes it. */
    public Question propose(Question question) {
        return questions.save(question);
    }

    /**
     * Runs the duplicate check, then promotes.
     *
     * <p>Refuses when a match is found and hands back the matches, because the right resolution is
     * usually not "reject" but "add this phrasing as an alias of the question that already exists" —
     * and the steward can only make that call if they are shown what it collided with.
     */
    public PromotionResult promote(String tenantId, QuestionId id) {
        Question candidate = require(id);

        if (candidate.status() == CatalogStatus.ACTIVE) {
            throw new ConflictingState("'" + candidate.label() + "' is already in the catalog.");
        }

        List<DuplicateDetector.Match> duplicates =
                DuplicateDetector.findDuplicates(candidate, questions.findActiveFor(tenantId));

        if (!duplicates.isEmpty()) {
            return PromotionResult.blocked(candidate, duplicates);
        }
        return PromotionResult.promoted(questions.save(candidate.promote()));
    }

    /**
     * Absorbs a phrasing into an existing question instead of creating a near-duplicate.
     *
     * <p>The intended outcome of a blocked promotion, and the reason aliases exist at all.
     */
    public Question absorbAlias(QuestionId existingId, String phrasing) {
        Question existing = require(existingId);
        return questions.save(existing.withAlias(phrasing));
    }

    public Question deprecate(QuestionId id) {
        return questions.save(require(id).deprecate());
    }

    /**
     * @param duplicates non-empty when promotion was refused; each entry explains itself
     */
    public record PromotionResult(boolean promoted, Question question, List<DuplicateDetector.Match> duplicates) {

        public PromotionResult {
            duplicates = duplicates == null ? List.of() : List.copyOf(duplicates);
        }

        static PromotionResult promoted(Question question) {
            return new PromotionResult(true, question, List.of());
        }

        static PromotionResult blocked(Question question, List<DuplicateDetector.Match> duplicates) {
            return new PromotionResult(false, question, duplicates);
        }
    }

    /**
     * The taxonomy, with a count of the active questions on each shelf.
     *
     * <p>Counted here rather than in the client, because a client counting whatever it has loaded
     * would report the size of the current filter — a different and much less useful number than
     * "what is in the catalog". Deprecated entries are excluded for the same reason they are excluded
     * from search: they are not available to place.
     */
    public List<CategoryWithCount> categories(String tenantId) {
        java.util.Map<String, Long> counts = questions.findActiveFor(tenantId).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Question::categoryKey, java.util.stream.Collectors.counting()));

        return categories.findAll().stream()
                .map(category -> new CategoryWithCount(
                        category, counts.getOrDefault(category.key(), 0L).intValue()))
                .toList();
    }

    /**
     * A category and how many questions sit on it.
     *
     * <p>Declared here rather than returning the REST view directly: an application service handing
     * back an interfaces type reads as harmless and quietly inverts the dependency, so the next
     * service does it too and the boundary stops meaning anything.
     */
    public record CategoryWithCount(QuestionCategory category, int questionCount) {}

    /**
     * Whether a category key resolves.
     *
     * <p>Referential integrity lives here rather than in {@link Question}, which cannot see a
     * repository. Checked at the boundary where a reference is created, so an author hears about a
     * typo while looking at the question rather than discovering an empty shelf later.
     */
    public boolean categoryExists(String categoryKey) {
        return categories.findByKey(categoryKey).isPresent();
    }
}
