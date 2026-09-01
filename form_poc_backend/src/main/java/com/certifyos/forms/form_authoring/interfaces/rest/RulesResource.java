package com.certifyos.forms.form_authoring.interfaces.rest;

import com.certifyos.forms.form_authoring.application.RulesInventory;
import com.certifyos.forms.form_authoring.interfaces.rest.dto.RulesInventoryView;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Every rule a tenant has, in one request.
 *
 * <p>Server-side rather than assembled in the browser, and the reason is not only round trips.
 * Deriving this in the client would mean fetching every form and every section and then
 * reimplementing ref resolution and path collection in TypeScript — a second implementation of
 * {@code ExpressionAnalyzer} whose only job is a read screen, and one more place for the two halves
 * to disagree. The conformance suite exists to keep the <em>evaluator</em> in step; it says nothing
 * about a bespoke analyzer written for one view.
 *
 * <p>Read-only and derived on every call. Nothing here is stored: an index of rules that could go
 * stale is worse than no index, because an author would trust it.
 */
@Path("/api/v1/tenants/{tenantId}/rules")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
@Tag(name = "Rules")
public class RulesResource {

    private final RulesInventory inventory;

    @Inject
    public RulesResource(RulesInventory inventory) {
        this.inventory = inventory;
    }

    @GET
    @Operation(
            operationId = "listRules",
            summary = "Every step condition, named condition and question condition a tenant has")
    public RulesInventoryView list(@PathParam("tenantId") String tenantId) {
        return RulesInventoryView.of(inventory.of(tenantId));
    }
}
