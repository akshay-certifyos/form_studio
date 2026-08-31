package com.certifyos.forms.form_authoring.interfaces.rest;

import com.certifyos.forms.form_authoring.application.FormAuthoringService;
import com.certifyos.forms.form_authoring.application.FormPublishingService;
import com.certifyos.forms.form_authoring.application.command.CreateFormFromBlueprint;
import com.certifyos.forms.form_authoring.application.command.PreviewChangeSet;
import com.certifyos.forms.form_authoring.application.command.PublishFormVersion;
import com.certifyos.forms.form_authoring.application.command.UpdateStepCondition;
import com.certifyos.forms.form_authoring.domain.port.FormDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.FormVersionRepository;
import com.certifyos.forms.form_authoring.domain.publishing.CompiledForm;
import com.certifyos.forms.form_authoring.interfaces.rest.dto.ChangePreviewView;
import com.certifyos.forms.form_authoring.interfaces.rest.dto.FormDetailView;
import com.certifyos.forms.form_authoring.interfaces.rest.dto.FormSummaryView;
import com.certifyos.forms.form_authoring.interfaces.rest.dto.PublishedVersionView;
import com.certifyos.forms.shared_kernel.exception.NotFound;
import com.certifyos.forms.shared_kernel.expression.ExpressionCodec;
import com.certifyos.forms.shared_kernel.interfaces.ApiError;
import com.certifyos.forms.shared_kernel.security.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Form authoring and publishing.
 *
 * <p>Thin by design: one application-service call per endpoint, primitive DTOs in, view records out,
 * and <b>no {@code try/catch}</b> — failures go through the two exception mappers. Compare with the
 * existing service, where every resource method repeats its own error recovery and each call site
 * quietly picks its own status code.
 *
 * <p>{@code @RunOnVirtualThread} rather than Mutiny: the calls underneath block on Mongo, and a
 * parked virtual thread costs nothing. Readable domain code is part of what this POC is proposing.
 */
@Path("/api/v1/tenants/{tenantId}/forms")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
@Tag(name = "Form authoring")
public class FormDefinitionResource {

    private final FormDefinitionRepository definitions;
    private final FormVersionRepository versions;
    private final FormPublishingService publishing;
    private final FormAuthoringService authoring;
    private final UserContext user;

    @Inject
    public FormDefinitionResource(
            FormDefinitionRepository definitions,
            FormVersionRepository versions,
            FormPublishingService publishing,
            FormAuthoringService authoring,
            UserContext user) {
        this.definitions = definitions;
        this.versions = versions;
        this.publishing = publishing;
        this.authoring = authoring;
        this.user = user;
    }

    /**
     * Creates a form from a blueprint.
     *
     * <p>The blueprint names section <em>templates</em>, so this instantiates one section per
     * placement and places them as steps. One section per placement, not per template: a blueprint
     * that places the same template twice — Practice Location and Billing Address — must yield two
     * independent sections, or customising one would change the other.
     *
     * <p>Either every section is created or none is. A blueprint half-applied because a template had
     * been deprecated would leave a tenant with a form that looks complete and is not.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "createFormFromBlueprint", summary = "Create a form from a blueprint")
    @APIResponse(responseCode = "200", description = "The created draft form")
    @APIResponse(
            responseCode = "422",
            description = "The blueprint references section templates that are not available",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public FormDetailView createFromBlueprint(
            @PathParam("tenantId") String tenantId, @Valid CreateFromBlueprintRequest request) {
        return FormDetailView.of(
                authoring.handle(new CreateFormFromBlueprint(tenantId, request.blueprintId(), request.name())));
    }

    /**
     * Replaces the rule deciding whether one step appears — the endpoint the condition builder saves
     * through, and the one that makes "a rule change is config, not a release" true rather than
     * merely claimed.
     *
     * <p>A null or absent {@code visibleWhen} clears the rule, which is how a step becomes
     * unconditional. Returns the whole form rather than the step, because clearing a rule can change
     * what other steps depend on and the studio re-derives that view from the form.
     */
    @PATCH
    @Path("/{formId}/steps/{stepKey}/condition")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            operationId = "updateStepCondition",
            summary = "Set or clear the condition controlling one step's visibility")
    @APIResponse(responseCode = "200", description = "The updated form")
    @APIResponse(
            responseCode = "422",
            description = "The expression is not valid against the grammar",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public FormDetailView updateStepCondition(
            @PathParam("tenantId") String tenantId,
            @PathParam("formId") String formId,
            @PathParam("stepKey") String stepKey,
            UpdateStepConditionRequest request) {
        // Parsing here rather than in the service keeps the wire format out of the domain: the
        // service takes an Expression, and a malformed body fails before any aggregate is loaded.
        return FormDetailView.of(authoring.handle(new UpdateStepCondition(
                formId, stepKey, ExpressionCodec.read(request == null ? null : request.visibleWhen()))));
    }

    @GET
    @Operation(summary = "List a tenant's forms")
    public List<FormSummaryView> list(@PathParam("tenantId") String tenantId) {
        return definitions.findByTenant(tenantId).stream()
                .map(FormSummaryView::of)
                .toList();
    }

    @GET
    @Path("/{formId}")
    @Operation(summary = "Get a form as the authoring UI needs it")
    public FormDetailView get(@PathParam("tenantId") String tenantId, @PathParam("formId") String formId) {
        return FormDetailView.of(definitions.findById(formId).orElseThrow(() -> new NotFound("Form", formId)));
    }

    /**
     * Compiles without persisting.
     *
     * <p>Returns 200 with the change preview when the form is sound, and 422 with every problem
     * pinned to its step when it is not — the author fixes them in one pass rather than one per
     * attempt.
     */
    @GET
    @Path("/{formId}/validate")
    @Operation(summary = "Compile without publishing, and report every problem at once")
    @APIResponse(
            responseCode = "422",
            description = "The form does not compile",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ChangePreviewView validate(@PathParam("tenantId") String tenantId, @PathParam("formId") String formId) {
        return changePreview(tenantId, formId);
    }

    /**
     * What publishing would cost.
     *
     * <p>The endpoint behind the screen that replaces a hand-ticked checkbox: change class plus the
     * specific answers at risk, seen <em>before</em> committing.
     */
    @GET
    @Path("/{formId}/change-preview")
    @Operation(summary = "Show the change class and the answers publishing would reset")
    public ChangePreviewView changePreview(@PathParam("tenantId") String tenantId, @PathParam("formId") String formId) {

        FormPublishingService.Preview preview = publishing.handle(new PreviewChangeSet(tenantId, formId));
        if (!preview.compiles()) {
            // Same 422 shape as publish, produced by the same report — one description of "broken",
            // not two that can disagree.
            throw new com.certifyos.forms.form_authoring.domain.compile.CompilationFailedException(preview.report());
        }
        return ChangePreviewView.of(preview.changeSet(), preview.report());
    }

    @POST
    @Path("/{formId}/publish")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Compile, diff and publish an immutable version")
    public PublishedVersionView publish(
            @PathParam("tenantId") String tenantId, @PathParam("formId") String formId, @Valid PublishRequest request) {

        return PublishedVersionView.of(publishing.handle(
                new PublishFormVersion(tenantId, formId, request.changelog(), request.ticketId(), user.actor())));
    }

    @GET
    @Path("/{formId}/versions")
    @Operation(summary = "Version history, newest first")
    public List<PublishedVersionView> versions(
            @PathParam("tenantId") String tenantId, @PathParam("formId") String formId) {
        return versions.findHistory(formId).stream()
                .map(PublishedVersionView::of)
                .toList();
    }

    /**
     * The compiled artifact.
     *
     * <p>This is the payload the existing renderer consumes unchanged — the whole point of
     * compiling rather than interpreting.
     */
    @GET
    @Path("/{formId}/versions/{versionId}/compiled")
    @Operation(summary = "The compiled artifact, in the format the renderer already consumes")
    public CompiledForm compiled(
            @PathParam("tenantId") String tenantId,
            @PathParam("formId") String formId,
            @PathParam("versionId") String versionId) {

        return versions.findById(versionId)
                .orElseThrow(() -> new NotFound("Form version", versionId))
                .artifact();
    }

    /** @param changelog what changed, in the author's words — shown in version history */
    public record PublishRequest(@NotBlank String changelog, String ticketId) {}

    public record CreateFromBlueprintRequest(@NotBlank String blueprintId, String name) {}

    /** Null {@code visibleWhen} clears the rule. Not the same as an empty {@code all}. */
    public record UpdateStepConditionRequest(JsonNode visibleWhen) {}
}
