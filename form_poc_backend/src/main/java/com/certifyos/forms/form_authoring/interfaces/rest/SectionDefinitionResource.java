package com.certifyos.forms.form_authoring.interfaces.rest;

import com.certifyos.forms.form_authoring.application.SectionAuthoringService;
import com.certifyos.forms.form_authoring.application.command.CreateSectionFromTemplate;
import com.certifyos.forms.form_authoring.domain.definition.SectionDefinition;
import com.certifyos.forms.form_authoring.domain.port.SectionDefinitionRepository;
import com.certifyos.forms.form_authoring.interfaces.rest.dto.SectionDetailView;
import com.certifyos.forms.form_authoring.interfaces.rest.dto.SectionDriftView;
import com.certifyos.forms.form_authoring.interfaces.rest.dto.SectionSummaryView;
import com.certifyos.forms.form_authoring.interfaces.rest.dto.SectionTemplateView;
import com.certifyos.forms.question_catalog.domain.Question;
import com.certifyos.forms.question_catalog.domain.QuestionId;
import com.certifyos.forms.question_catalog.domain.port.QuestionRepository;
import com.certifyos.forms.shared_kernel.exception.NotFound;
import com.certifyos.forms.shared_kernel.interfaces.ApiError;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Section definitions.
 *
 * <p>Exists because the form endpoint returns step <em>placements</em> — order, condition, key — but
 * not the questions inside them. That split is correct in the model (a section owns content, a step
 * owns where and when) and it means the authoring tree needs both.
 *
 * <p>Each question is returned already resolved against the catalog, so the client never has to
 * join a catalog lookup onto a section lookup to render a label.
 */
@Path("/api/v1/tenants/{tenantId}/sections")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
@Tag(name = "Section definitions")
public class SectionDefinitionResource {

    private final SectionDefinitionRepository sections;
    private final QuestionRepository questions;
    private final SectionAuthoringService authoring;

    @Inject
    public SectionDefinitionResource(
            SectionDefinitionRepository sections, QuestionRepository questions, SectionAuthoringService authoring) {
        this.sections = sections;
        this.questions = questions;
        this.authoring = authoring;
    }

    /**
     * Instantiates a section from a template — copy-on-use.
     *
     * <p>The resulting section is the tenant's own. A later edit to the template does not reach it;
     * what the tenant gets instead is a computed {@code drift} report and the choice of what to do
     * about it.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "createSectionFromTemplate", summary = "Create a section from a template")
    public SectionDetailView createFromTemplate(
            @PathParam("tenantId") String tenantId, @Valid CreateFromTemplateRequest request) {

        SectionDefinition created =
                authoring.handle(new CreateSectionFromTemplate(tenantId, request.sectionTemplateId(), request.name()));
        return SectionDetailView.of(created, resolveCatalog(created));
    }

    /**
     * Adds a question the template does not have.
     *
     * <p>Recorded with origin {@code ADDED}, which is what lets drift distinguish it from a question
     * the template has since dropped. Without that provenance the two are indistinguishable.
     */
    @POST
    @Path("/{sectionId}/questions")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "addSectionQuestion", summary = "Add a locally-added question to a section")
    public SectionDetailView addQuestion(
            @PathParam("tenantId") String tenantId,
            @PathParam("sectionId") String sectionId,
            @Valid AddQuestionRequest request) {

        SectionDefinition updated = authoring.addQuestion(
                sectionId,
                request.key(),
                request.catalogQuestionId(),
                Boolean.TRUE.equals(request.required()),
                request.order() == null ? 0 : request.order());
        return SectionDetailView.of(updated, resolveCatalog(updated));
    }

    /**
     * Enables or disables a question.
     *
     * <p>There is no DELETE. Disabling compiles the question out of the artifact entirely — the same
     * runtime effect as removal — while keeping the provenance a template upgrade needs. §9 lists a
     * DELETE; it is deliberately not implemented, because it would be the one operation in this API
     * that destroys information a later reconciliation depends on.
     */
    @PATCH
    @Path("/{sectionId}/questions/{questionKey}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "patchSectionQuestion", summary = "Enable or disable a question")
    public SectionDetailView patchQuestion(
            @PathParam("tenantId") String tenantId,
            @PathParam("sectionId") String sectionId,
            @PathParam("questionKey") String questionKey,
            PatchQuestionRequest request) {

        SectionDefinition updated = authoring.setQuestionEnabled(
                sectionId, questionKey, request != null && Boolean.TRUE.equals(request.enabled()));
        return SectionDetailView.of(updated, resolveCatalog(updated));
    }

    /**
     * Sets the question order for a section.
     *
     * <p>{@code PUT} with the complete key list, not {@code PATCH} with one question and a position.
     * Whole-list is idempotent and atomic: one request, one valid end state. Moving a question by
     * patching a single order value is two writes if it is a swap, and a half-applied swap leaves two
     * questions sharing a number — after which the form renders in whichever sequence the sort
     * happens to produce.
     */
    @PUT
    @Path("/{sectionId}/questions/order")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "reorderSectionQuestions", summary = "Set the question order for a section")
    @APIResponse(responseCode = "200", description = "The reordered section")
    @APIResponse(
            responseCode = "422",
            description = "The key list is not exactly this section's questions",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public SectionDetailView reorderQuestions(
            @PathParam("tenantId") String tenantId,
            @PathParam("sectionId") String sectionId,
            @Valid ReorderQuestionsRequest request) {

        SectionDefinition updated = authoring.reorderQuestions(sectionId, request.keys());
        return SectionDetailView.of(updated, resolveCatalog(updated));
    }

    @GET
    @Path("/{sectionId}/drift")
    @Operation(
            operationId = "getSectionDrift",
            summary = "What re-syncing with the template would bring in, and what it would overwrite")
    public SectionDriftView drift(@PathParam("tenantId") String tenantId, @PathParam("sectionId") String sectionId) {
        return SectionDriftView.of(authoring.drift(sectionId));
    }

    /** Promotes this section into the next version of its template. */
    @POST
    @Path("/{sectionId}/promote")
    @Operation(operationId = "promoteSection", summary = "Promote a section into a new template version")
    public SectionTemplateView promote(
            @PathParam("tenantId") String tenantId, @PathParam("sectionId") String sectionId) {
        return SectionTemplateView.of(authoring.promote(sectionId));
    }

    public record CreateFromTemplateRequest(@NotBlank String sectionTemplateId, String name) {}

    /** @param keys every question key in the section, exactly once, in the order wanted */
    public record ReorderQuestionsRequest(@NotEmpty List<String> keys) {}

    public record AddQuestionRequest(
            @NotBlank String key, @NotBlank String catalogQuestionId, Boolean required, Integer order) {}

    /** Null {@code enabled} is treated as false — the request said to change it, so it must say to what. */
    public record PatchQuestionRequest(Boolean enabled) {}

    @GET
    @Operation(summary = "List a tenant's section definitions")
    public List<SectionSummaryView> list(@PathParam("tenantId") String tenantId) {
        return sections.findByTenant(tenantId).stream()
                .map(SectionSummaryView::of)
                .toList();
    }

    @GET
    @Path("/{sectionId}")
    @Operation(summary = "Get a section with its questions resolved against the catalog")
    public SectionDetailView get(@PathParam("tenantId") String tenantId, @PathParam("sectionId") String sectionId) {
        SectionDefinition section = sections.findById(sectionId).orElseThrow(() -> new NotFound("Section", sectionId));
        return SectionDetailView.of(section, resolveCatalog(section));
    }

    /**
     * One batched catalog lookup per section.
     *
     * <p>Resolved server-side rather than leaving the client to join, because the alternative is the
     * authoring tree issuing a request per question to render a label.
     */
    private Map<QuestionId, Question> resolveCatalog(SectionDefinition section) {
        List<QuestionId> ids = section.questions().stream()
                .map(q -> q.catalogQuestionId())
                .distinct()
                .toList();

        Map<QuestionId, Question> byId = new LinkedHashMap<>();
        questions.findAllById(ids).forEach(q -> byId.put(q.id(), q));
        return byId;
    }
}
