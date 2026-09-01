package com.certifyos.forms.form_authoring.interfaces.rest;

import com.certifyos.forms.form_authoring.application.FormAuthoringService;
import com.certifyos.forms.form_authoring.application.FormPublishingService;
import com.certifyos.forms.form_authoring.application.command.CreateBlankForm;
import com.certifyos.forms.form_authoring.application.command.CreateBlueprintFromForm;
import com.certifyos.forms.form_authoring.application.command.CreateFormFromBlueprint;
import com.certifyos.forms.form_authoring.application.command.PlaceSection;
import com.certifyos.forms.form_authoring.application.command.PreviewChangeSet;
import com.certifyos.forms.form_authoring.application.command.PublishFormVersion;
import com.certifyos.forms.form_authoring.application.command.RemoveNamedCondition;
import com.certifyos.forms.form_authoring.application.command.RemoveStep;
import com.certifyos.forms.form_authoring.application.command.ReorderSteps;
import com.certifyos.forms.form_authoring.application.command.UpdateStepCondition;
import com.certifyos.forms.form_authoring.application.command.UpsertNamedCondition;
import com.certifyos.forms.form_authoring.domain.definition.Step;
import com.certifyos.forms.form_authoring.domain.port.FormDefinitionRepository;
import com.certifyos.forms.form_authoring.domain.port.FormVersionRepository;
import com.certifyos.forms.form_authoring.domain.publishing.CompiledForm;
import com.certifyos.forms.form_authoring.interfaces.rest.dto.ChangePreviewView;
import com.certifyos.forms.form_authoring.interfaces.rest.dto.FormBlueprintView;
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
import jakarta.validation.constraints.NotEmpty;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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
     * Creates a form, either from a blueprint or empty.
     *
     * <p>One endpoint for both, because "create a form" is one intention and the presence of a
     * {@code blueprintId} already says which kind. Two endpoints would make the client decide which
     * to call before it knows whether the author picked a starting shape.
     *
     * <p><b>From a blueprint:</b> the blueprint names section <em>templates</em>, so this
     * instantiates one section per placement and places them as steps — one section per placement,
     * not per template, or a blueprint placing the same template twice (Practice Location and Billing
     * Address) would yield two steps sharing content. Each placement's condition and the blueprint's
     * named conditions come across with it. Either every section is created or none is: a blueprint
     * half-applied because a template had been deprecated leaves a tenant with a form that looks
     * complete and is not.
     *
     * <p><b>Empty:</b> no steps, and {@code entityType} is then required, since there is no blueprint
     * to inherit it from. This is the path for a payer nobody has onboarded before.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "createForm", summary = "Create a form from a blueprint, or empty")
    @APIResponse(responseCode = "200", description = "The created draft form")
    @APIResponse(
            responseCode = "422",
            description = "The blueprint references unavailable templates, or an empty form named no entity type",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public FormDetailView create(@PathParam("tenantId") String tenantId, @Valid CreateFormRequest request) {
        // Dispatch, not a business rule: which command this is depends only on what the caller sent.
        return FormDetailView.of(
                request.blueprintId() == null || request.blueprintId().isBlank()
                        ? authoring.handle(new CreateBlankForm(tenantId, request.name(), request.entityType()))
                        : authoring.handle(
                                new CreateFormFromBlueprint(tenantId, request.blueprintId(), request.name())));
    }

    /**
     * Places a section into the form as a new step.
     *
     * <p>The verb that makes a form assemblable rather than only instantiable. {@code stepKey} is the
     * caller's to choose because it is the answer namespace: placing one address section twice has to
     * produce {@code practiceLocation.*} and {@code billingAddress.*}, and only the author knows
     * which placement is which.
     *
     * <p>Returns the whole form. Adding a step changes what every other step's condition may
     * legally reference — a rule can only read from a step ahead of it — so the studio re-derives
     * that from the form rather than tracking it.
     */
    @POST
    @Path("/{formId}/steps")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "placeSection", summary = "Place a section into a form as a new step")
    @APIResponse(responseCode = "200", description = "The updated form")
    @APIResponse(
            responseCode = "422",
            description = "The step key is already used, is not a legal namespace, or the section is another tenant's",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public FormDetailView placeSection(
            @PathParam("tenantId") String tenantId,
            @PathParam("formId") String formId,
            @Valid PlaceSectionRequest request) {

        Step.Repeating repeating = request.repeating() == null
                ? null
                : new Step.Repeating(
                        request.repeating().min(),
                        request.repeating().max(),
                        request.repeating().itemLabel());

        return FormDetailView.of(authoring.handle(new PlaceSection(
                formId,
                request.sectionDefinitionId(),
                request.stepKey(),
                request.order(),
                request.group(),
                request.titleOverride(),
                repeating)));
    }

    /**
     * Takes a step out of the form.
     *
     * <p>A real DELETE, unlike a section's questions, which are only ever disabled. The asymmetry is
     * the point: a question's origin is provenance a template upgrade reconciles against, while a
     * step placed a section that still exists and can be placed again — there is nothing to preserve.
     *
     * <p>Does not check whether other steps read from this one. That is reported at {@code /validate}
     * along with everything else, rather than blocking an edit mid-way through a restructure.
     */
    @DELETE
    @Path("/{formId}/steps/{stepKey}")
    @Operation(operationId = "removeStep", summary = "Remove a step from a form")
    public FormDetailView removeStep(
            @PathParam("tenantId") String tenantId,
            @PathParam("formId") String formId,
            @PathParam("stepKey") String stepKey) {
        return FormDetailView.of(authoring.handle(new RemoveStep(formId, stepKey)));
    }

    /**
     * Sets the step order for the form.
     *
     * <p>{@code PUT} with the complete key list, for the same reason the section question reorder is:
     * whole-list is idempotent and atomic, while patching one step's order is two writes for a swap
     * and a half-applied swap leaves two steps sharing a number — after which the sort decides what
     * the provider sees.
     */
    @PUT
    @Path("/{formId}/steps/order")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "reorderSteps", summary = "Set the step order for a form")
    @APIResponse(
            responseCode = "422",
            description = "The key list is not exactly this form's steps",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public FormDetailView reorderSteps(
            @PathParam("tenantId") String tenantId,
            @PathParam("formId") String formId,
            @Valid ReorderStepsRequest request) {
        return FormDetailView.of(authoring.handle(new ReorderSteps(formId, request.keys())));
    }

    /**
     * Defines or replaces a named condition.
     *
     * <p>{@code PUT} because it is an upsert keyed by the path — the author is saying what a name
     * means, not creating a thing that might already exist.
     *
     * <p>Safe against published versions by construction: named conditions are inlined at compile
     * time, so every published version holds its own frozen copy and nothing here can reach one. That
     * is the entire reason for the inlining rule.
     */
    @PUT
    @Path("/{formId}/conditions/{key}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "upsertNamedCondition", summary = "Define or replace a named condition")
    public FormDetailView upsertNamedCondition(
            @PathParam("tenantId") String tenantId,
            @PathParam("formId") String formId,
            @PathParam("key") String key,
            @Valid UpsertNamedConditionRequest request) {

        return FormDetailView.of(authoring.handle(
                new UpsertNamedCondition(formId, key, request.label(), ExpressionCodec.read(request.expression()))));
    }

    /**
     * Deletes a named condition.
     *
     * <p>409 while any step still references it, naming those steps. The analyzer would catch the
     * dangling reference at publish, but by then the author is a screen away from the cause.
     */
    @DELETE
    @Path("/{formId}/conditions/{key}")
    @Operation(operationId = "removeNamedCondition", summary = "Delete a named condition")
    @APIResponse(
            responseCode = "409",
            description = "Steps still reference this condition",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public FormDetailView removeNamedCondition(
            @PathParam("tenantId") String tenantId, @PathParam("formId") String formId, @PathParam("key") String key) {
        return FormDetailView.of(authoring.handle(new RemoveNamedCondition(formId, key)));
    }

    /**
     * Promotes an assembled form into a reusable blueprint.
     *
     * <p>The form-level twin of {@code POST /sections/{id}/promote}, and what closes the reuse loop:
     * the first payer form is assembled from the catalog, and the second starts from its shape.
     *
     * <p>422 if any placed section came from no template, naming the steps. A blueprint references
     * templates, so promoting the sections first is the dependency rather than a rule invented here.
     */
    @POST
    @Path("/{formId}/promote")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "promoteFormToBlueprint", summary = "Promote a form into a reusable blueprint")
    @APIResponse(
            responseCode = "422",
            description = "A placed section came from no template, so there is nothing for the blueprint to point at",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public FormBlueprintView promote(
            @PathParam("tenantId") String tenantId,
            @PathParam("formId") String formId,
            @Valid PromoteFormRequest request) {

        return FormBlueprintView.of(
                authoring.handle(new CreateBlueprintFromForm(tenantId, formId, request.key(), request.name())));
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
        // One version lookup per form. A join would be better at scale, but the honest alternative
        // here was to omit the column — and "which version is actually live" is the first thing
        // anyone asks of a forms list.
        return definitions.findByTenant(tenantId).stream()
                .map(definition -> FormSummaryView.of(
                        definition,
                        versions.findActive(definition.id())
                                .map(version -> version.version())
                                .orElse(null)))
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

    /**
     * Nothing here is {@code @NotBlank}, because what is required depends on the other fields: an
     * empty form needs a name and an entity type, one from a blueprint inherits both. The commands
     * enforce it and answer 422 — see the note on {@code CreateBlankForm}.
     *
     * @param blueprintId absent or blank creates an empty form; set instantiates that blueprint
     * @param name null keeps the blueprint's own name; required for an empty form
     * @param entityType required only for an empty form — a blueprint carries its own
     */
    public record CreateFormRequest(String blueprintId, String name, String entityType) {}

    /**
     * @param stepKey the answer namespace this placement owns
     * @param order null appends after the last step
     */
    public record PlaceSectionRequest(
            @NotBlank String sectionDefinitionId,
            @NotBlank String stepKey,
            Integer order,
            String group,
            String titleOverride,
            RepeatingRequest repeating) {}

    public record RepeatingRequest(int min, int max, @NotBlank String itemLabel) {}

    /** @param keys every step key in the form, exactly once, in the order wanted */
    public record ReorderStepsRequest(@NotEmpty List<String> keys) {}

    /** @param label what the rule reads as in the builder; a referenced condition renders by it */
    public record UpsertNamedConditionRequest(@NotBlank String label, JsonNode expression) {}

    /**
     * @param key identifies the new blueprint
     * @param name null keeps the form's own name
     */
    public record PromoteFormRequest(@NotBlank String key, String name) {}

    /** Null {@code visibleWhen} clears the rule. Not the same as an empty {@code all}. */
    public record UpdateStepConditionRequest(JsonNode visibleWhen) {}
}
