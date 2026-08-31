package com.certifyos.forms.form_authoring.domain.port;

import com.certifyos.forms.form_authoring.domain.compile.CatalogSnapshot;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import java.util.Collection;

/**
 * The one place {@code form_authoring} reaches into {@code question_catalog}.
 *
 * <p>An anti-corruption seam: in-process today, and able to become an HTTP call later without the
 * domain noticing. Returns a {@link CatalogSnapshot} rather than live aggregates so the compiler
 * stays a pure function over data it was handed.
 */
public interface QuestionCatalogPort {

    /**
     * Resolves the given questions <b>and the option sets they reference</b>.
     *
     * <p>Option sets are reachable from the questions, so asking a caller to list them separately
     * was redundant — and redundant parameters are how a caller silently passes an empty set and
     * loses every dropdown's options without any error.
     */
    CatalogSnapshot resolve(Collection<QuestionId> questionIds);
}
