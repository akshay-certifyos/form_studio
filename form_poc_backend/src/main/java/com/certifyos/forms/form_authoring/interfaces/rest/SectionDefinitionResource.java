package com.certifyos.forms.form_authoring.interfaces.rest;

import com.certifyos.forms.form_authoring.application.SectionAuthoringService;
import com.certifyos.forms.form_authoring.application.command.CreateBlankSection;
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
import jakarta.ws.rs.QueryParam;
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
     * Creates a section, either from a template or empty.
     *
     * <p><b>From a template:</b> copy-on-use. The result is the tenant's own — a later edit to the
     * template does not reach it. What the tenant gets instead is a computed {@code drift} report and
     * the choice of what to do about it.
     *
     * <p><b>Empty:</b> no questions, no source template, and a {@code key} the caller supplies since
     * there is nothing to inherit one from. This is the path for the part of a payer form that is
     * genuinely specific to it — every real form examined had some — and the questions are then added
     * from the catalog, so the section is bespoke while its content stays shared.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "createSection", summary = "Create a section from a template, or empty")
    @APIResponse(
            responseCode = "422",
            description = "An empty section named no key",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public SectionDetailView create(@PathParam("tenantId") String tenantId, @Valid CreateSectionRequest request) {
        // Dispatch only — which command applies follows from what the caller sent.
        SectionDefinition created = request.sectionTemplateId() == null
                        || request.sectionTemplateId().isBlank()
                ? authoring.handle(new CreateBlankSection(tenantId, request.key(), request.name()))
                : authoring.handle(
                        new CreateSectionFromTemplate(tenantId, request.sectionTemplateId(), request.name()));

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

    /**
     * Makes this section reusable.
     *
     * <p>One verb covering both cases, because the author's intention is the same either way and
     * which applies is a fact about the section rather than a choice to put to them:
     *
     * <ul>
     *   <li>The section came from a template → mints version n+1 of it. No body needed.
     *   <li>The section was authored from scratch → mints a new template at version 1 and links the
     *       section to it. Needs a {@code key}, since nothing exists to inherit one from.
     * </ul>
     *
     * <p>{@code key} and {@code name} are query parameters rather than a body, because the body would
     * have to be optional — a template-backed promote needs none — and an optional entity makes a
     * bodyless POST a 415 in practice. Two optional scalars on an action endpoint are what query
     * parameters are for.
     *
     * <p>The new template is <b>tenant-owned, not global</b>. Publishing one client's section shape
     * to every tenant is a curation decision Certify makes deliberately, not a side effect of an
     * author pressing promote.
     */
    @POST
    @Path("/{sectionId}/promote")
    @Operation(operationId = "promoteSection", summary = "Promote a section into a template version, or a new template")
    @APIResponse(
            responseCode = "422",
            description = "The section came from no template and the request named no key for the new one",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public SectionTemplateView promote(
            @PathParam("tenantId") String tenantId,
            @PathParam("sectionId") String sectionId,
            @QueryParam("key") String key,
            @QueryParam("name") String name) {

        return SectionTemplateView.of(authoring.promote(sectionId, key, name));
    }

    /**
     * Conditionally required rather than {@code @NotBlank}, as with {@code CreateFormRequest}: an
     * empty section needs a key and a name, one from a template inherits both.
     *
     * @param sectionTemplateId absent or blank creates an empty section; set instantiates that template
     * @param name null keeps the template's own name; required for an empty section
     * @param key required only for an empty section — a template carries its own
     */
    public record CreateSectionRequest(String sectionTemplateId, String name, String key) {}

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
