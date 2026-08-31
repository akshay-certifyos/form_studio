package com.certifyos.forms.form_authoring.interfaces.rest.dto;

import com.certifyos.forms.form_authoring.domain.compile.CompilationReport;
import com.certifyos.forms.form_authoring.domain.publishing.ChangeSet;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Set;

/**
 * What publishing would do, before committing to it.
 *
 * <p>The screen this feeds is the answer to "today a checkbox decides whether providers lose their
 * work". {@link #keysRequiringReset} is the surgical part: the specific answers at risk, not a
 * blanket warning.
 *
 * <p>{@link #notices} is the other half of being honest before a publish: parts of the form that are
 * saved but that this version of the compiler will not emit. They do not block publishing, so they
 * cannot ride on the 422 path with problems — which is exactly why they were invisible.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangePreviewView(
        String changeClass,
        boolean requiresReset,
        Set<String> addedKeys,
        Set<String> removedKeys,
        Set<String> changedKeys,
        Set<String> keysRequiringReset,
        List<String> notes,
        List<NoticeView> notices) {

    /** @param stepKey where to pin it in the tree; null for a form-level notice */
    public record NoticeView(String code, String message, String stepKey, String questionKey) {}

    public static ChangePreviewView of(ChangeSet changeSet, CompilationReport report) {
        return new ChangePreviewView(
                changeSet.changeClass().name().toLowerCase(java.util.Locale.ROOT),
                changeSet.requiresReset(),
                changeSet.addedKeys(),
                changeSet.removedKeys(),
                changeSet.changedKeys(),
                changeSet.keysRequiringReset(),
                changeSet.notes(),
                report.notices().stream()
                        .map(n -> new NoticeView(n.code().name(), n.message(), n.stepKey(), n.questionKey()))
                        .toList());
    }
}
