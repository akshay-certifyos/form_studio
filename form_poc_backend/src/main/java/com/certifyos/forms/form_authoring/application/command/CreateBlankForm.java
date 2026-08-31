package com.certifyos.forms.form_authoring.application.command;

import com.certifyos.forms.shared_kernel.exception.InvariantViolated;

/**
 * Guards in these commands throw {@link InvariantViolated}, not {@code IllegalArgumentException}.
 *
 * <p>The distinction is not cosmetic. A command is constructed from a request body, so its guards are
 * validating caller input, and {@code InvariantViolated} is the one the mapper turns into a 422.
 * {@code IllegalArgumentException} has no mapper — deliberately, because in this codebase it means an
 * aggregate refused and a service is expected to translate it — so a command that threw one would
 * reach the client as an opaque 500 for a request the client could have fixed.
 *
 * <p>Bean validation cannot cover these cases: both create commands are legal with different required
 * fields depending on whether a blueprint or template was named, and {@code @NotBlank} has no way to
 * say "required only when that other field is absent".
 */
/**
 * Starts a form with no steps at all.
 *
 * <p>The other half of {@link CreateFormFromBlueprint}, and the one a payer form nobody has seen
 * before actually needs. Every form in the POC's first cut came from a blueprint, which quietly
 * meant every shape had to exist as a fixture before a form could exist at all — fine for a demo,
 * useless for onboarding a new payer.
 *
 * <p>{@code entityType} is required here where a blueprint would have supplied it. It decides which
 * entity the form is answered about, so defaulting it would mean guessing, and a form built against
 * the wrong entity type is wrong in a way that only surfaces at submission.
 */
public record CreateBlankForm(String tenantId, String name, String entityType) {

    public CreateBlankForm {
        if (tenantId == null || tenantId.isBlank()) {
            throw new InvariantViolated("A tenant id is required");
        }
        if (name == null || name.isBlank()) {
            throw new InvariantViolated("A form name is required");
        }
        if (entityType == null || entityType.isBlank()) {
            throw new InvariantViolated(
                    "An entity type is required — with no blueprint to inherit it from, there is nothing to default to");
        }
    }
}
