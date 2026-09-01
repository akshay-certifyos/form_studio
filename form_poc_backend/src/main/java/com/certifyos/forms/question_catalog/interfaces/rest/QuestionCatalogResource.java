package com.certifyos.forms.question_catalog.interfaces.rest;

import com.certifyos.forms.question_catalog.application.CatalogService;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.interfaces.rest.dto.OptionSetView;
import com.certifyos.forms.question_catalog.interfaces.rest.dto.PromotionResultView;
import com.certifyos.forms.question_catalog.interfaces.rest.dto.QuestionCategoryView;
import com.certifyos.forms.question_catalog.interfaces.rest.dto.QuestionView;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** The question catalog. */
@Path("/api/v1/tenants/{tenantId}/catalog")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
@Tag(name = "Question catalog")
public class QuestionCatalogResource {

    private final CatalogService catalog;

    @Inject
    public QuestionCatalogResource(CatalogService catalog) {
        this.catalog = catalog;
    }

    /**
     * The catalog's taxonomy, in display order, each with a count.
     *
     * <p>Its own endpoint rather than a field on every question: the headings are needed once per
     * screen, and repeating a label per question is both wasteful and a way for the two to disagree.
     */
    @GET
    @Path("/categories")
    @Operation(operationId = "listQuestionCategories", summary = "Catalog categories, in display order")
    public List<QuestionCategoryView> categories(@PathParam("tenantId") String tenantId) {
        return catalog.categories(tenantId).stream()
                .map(entry -> QuestionCategoryView.of(entry.category(), entry.questionCount()))
                .toList();
    }

    /** Searches label, aliases and key — a payer's phrasing is usually an alias, not the label. */
    @GET
    @Path("/questions")
    @Operation(summary = "Search the catalog by label, alias or key")
    public List<QuestionView> search(
            @PathParam("tenantId") String tenantId,
            @QueryParam("q") String text,
            @QueryParam("includeProposed") @DefaultValue("false") boolean includeProposed) {
        return catalog.search(tenantId, text, includeProposed).stream()
                .map(QuestionView::of)
                .toList();
    }

    @GET
    @Path("/questions/{questionId}")
    @Operation(summary = "Get one catalog question")
    public QuestionView get(@PathParam("tenantId") String tenantId, @PathParam("questionId") String questionId) {
        return QuestionView.of(catalog.require(QuestionId.of(questionId)));
    }

    /**
     * Promotes a proposed question, duplicate-checked.
     *
     * <p>Returns <b>409 with the colliding questions</b> rather than a bare rejection, because the
     * steward's usual next move is to absorb the phrasing as an alias of what already exists — and
     * that decision needs the collision in front of them.
     */
    @POST
    @Path("/questions/{questionId}/promote")
    @Operation(summary = "Promote a proposed question into the catalog, after a duplicate check")
    public Response promote(@PathParam("tenantId") String tenantId, @PathParam("questionId") String questionId) {
        CatalogService.PromotionResult result = catalog.promote(tenantId, QuestionId.of(questionId));
        PromotionResultView view = PromotionResultView.of(result);

        return result.promoted()
                ? Response.ok(view).build()
                : Response.status(Response.Status.CONFLICT).entity(view).build();
    }

    /** Records a payer's wording against an existing question instead of minting a new one. */
    @POST
    @Path("/questions/{questionId}/aliases")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Absorb a payer's phrasing as an alias")
    public QuestionView addAlias(
            @PathParam("tenantId") String tenantId, @PathParam("questionId") String questionId, AliasRequest request) {
        return QuestionView.of(catalog.absorbAlias(QuestionId.of(questionId), request.phrasing()));
    }

    @POST
    @Path("/questions/{questionId}/deprecate")
    @Operation(summary = "Retire a question without deleting it, so history stays readable")
    public QuestionView deprecate(@PathParam("tenantId") String tenantId, @PathParam("questionId") String questionId) {
        return QuestionView.of(catalog.deprecate(QuestionId.of(questionId)));
    }

    @GET
    @Path("/option-sets")
    @Operation(summary = "Option sets available to this tenant")
    public List<OptionSetView> optionSets(@PathParam("tenantId") String tenantId) {
        return catalog.optionSetsFor(tenantId).stream().map(OptionSetView::of).toList();
    }

    public record AliasRequest(String phrasing) {}
}
