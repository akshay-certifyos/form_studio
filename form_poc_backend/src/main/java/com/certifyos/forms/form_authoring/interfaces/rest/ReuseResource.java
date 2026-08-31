package com.certifyos.forms.form_authoring.interfaces.rest;

import com.certifyos.forms.form_authoring.domain.port.FormBlueprintRepository;
import com.certifyos.forms.form_authoring.domain.port.SectionTemplateRepository;
import com.certifyos.forms.form_authoring.interfaces.rest.dto.FormBlueprintView;
import com.certifyos.forms.form_authoring.interfaces.rest.dto.SectionTemplateView;
import com.certifyos.forms.shared_kernel.exception.NotFound;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Section templates and form blueprints — the reusable shapes, read-only in v0.
 *
 * <p>Read-only because authoring a template directly is not the intended path. A template earns its
 * generality by being promoted from a section a tenant actually evolved
 * ({@code POST /sections/{id}/promote}), which keeps shared shapes grounded in real use rather than
 * in someone's guess about what will be reusable.
 *
 * <p>Tenant-scoped even though most entries are global: the reads return global templates plus that
 * tenant's own, and a tenant must not be able to enumerate another's.
 */
@Path("/api/v1/tenants/{tenantId}")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
@Tag(name = "Reusable shapes")
public class ReuseResource {

    private final SectionTemplateRepository templates;
    private final FormBlueprintRepository blueprints;

    @Inject
    public ReuseResource(SectionTemplateRepository templates, FormBlueprintRepository blueprints) {
        this.templates = templates;
        this.blueprints = blueprints;
    }

    @GET
    @Path("/section-templates")
    @Operation(operationId = "listSectionTemplates", summary = "Section templates available to a tenant")
    public List<SectionTemplateView> sectionTemplates(@PathParam("tenantId") String tenantId) {
        return templates.findAvailableFor(tenantId).stream()
                .map(SectionTemplateView::of)
                .toList();
    }

    @GET
    @Path("/section-templates/{templateId}")
    @Operation(operationId = "getSectionTemplate", summary = "One section template, with its questions")
    public SectionTemplateView sectionTemplate(
            @PathParam("tenantId") String tenantId, @PathParam("templateId") String templateId) {
        return SectionTemplateView.of(
                templates.findById(templateId).orElseThrow(() -> new NotFound("Section template", templateId)));
    }

    @GET
    @Path("/blueprints")
    @Operation(operationId = "listBlueprints", summary = "Form blueprints available to a tenant")
    public List<FormBlueprintView> blueprints(@PathParam("tenantId") String tenantId) {
        return blueprints.findAvailableFor(tenantId).stream()
                .map(FormBlueprintView::of)
                .toList();
    }

    @GET
    @Path("/blueprints/{blueprintId}")
    @Operation(operationId = "getBlueprint", summary = "One blueprint, with its placements")
    public FormBlueprintView blueprint(
            @PathParam("tenantId") String tenantId, @PathParam("blueprintId") String blueprintId) {
        return FormBlueprintView.of(
                blueprints.findById(blueprintId).orElseThrow(() -> new NotFound("Form blueprint", blueprintId)));
    }
}
