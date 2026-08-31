package com.certifyos.forms.question_catalog.infrastructure.mongo;

import com.certifyos.forms.question_catalog.domain.CatalogStatus;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.port.QuestionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Mongo-backed {@link QuestionRepository}. */
@ApplicationScoped
public class MongoQuestionRepository implements QuestionRepository {

    private final QuestionPanacheRepository documents;

    @Inject
    public MongoQuestionRepository(QuestionPanacheRepository documents) {
        this.documents = documents;
    }

    @Override
    public Optional<Question> findById(QuestionId id) {
        return documents.findByIdOptional(id.value()).map(QuestionDocument::toDomain);
    }

    @Override
    public List<Question> findAllById(Collection<QuestionId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<String> raw = ids.stream().map(QuestionId::value).toList();
        return documents.find("_id in ?1", raw).stream()
                .map(QuestionDocument::toDomain)
                .toList();
    }

    /**
     * Global entries plus the tenant's own. A tenant sees Certify's catalog and anything they have
     * added, never another tenant's.
     */
    @Override
    public List<Question> findActiveFor(String tenantId) {
        return documents
                .find("status = ?1 and (tenantId is null or tenantId = ?2)", CatalogStatus.ACTIVE.wireName(), tenantId)
                .stream()
                .map(QuestionDocument::toDomain)
                .toList();
    }

    /** Matches label and aliases, because a payer's phrasing is usually an alias, not the label. */
    @Override
    public List<Question> search(String tenantId, String text, boolean includeProposed) {
        List<Question> candidates = includeProposed ? findVisibleFor(tenantId) : findActiveFor(tenantId);

        if (text == null || text.isBlank()) {
            return candidates;
        }

        // Filtered in memory rather than by a second query. The catalog is small, and the
        // alternative was a `like` query with no status predicate — which is how the two branches
        // came to disagree about which entries exist.
        String needle = text.trim().toLowerCase();
        return candidates.stream().filter(question -> matches(question, needle)).toList();
    }

    /** Active plus proposed. Deprecated entries stay hidden. */
    private List<Question> findVisibleFor(String tenantId) {
        return documents
                .find(
                        "status in ?1 and (tenantId is null or tenantId = ?2)",
                        List.of(CatalogStatus.ACTIVE.wireName(), CatalogStatus.PROPOSED.wireName()),
                        tenantId)
                .stream()
                .map(QuestionDocument::toDomain)
                .toList();
    }

    /** Aliases are searched because a payer's phrasing is usually an alias, not the label. */
    private static boolean matches(Question question, String needle) {
        if (question.label().toLowerCase().contains(needle)) return true;
        if (question.key().toLowerCase().contains(needle)) return true;
        return question.aliases().stream().anyMatch(alias -> alias.toLowerCase().contains(needle));
    }

    @Override
    public Question save(Question question) {
        documents.persistOrUpdate(QuestionDocument.from(question));
        return question;
    }
}
