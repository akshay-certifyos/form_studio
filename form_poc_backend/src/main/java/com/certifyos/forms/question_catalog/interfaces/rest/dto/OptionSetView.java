package com.certifyos.forms.question_catalog.interfaces.rest.dto;

import com.certifyos.forms.question_catalog.domain.OptionSet;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** An option set, tags included — the frontend needs them to narrow a filtered select. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OptionSetView(String key, String name, List<OptionView> options) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OptionView(String value, String label, Map<String, List<String>> tags) {}

    public static OptionSetView of(OptionSet set) {
        return new OptionSetView(
                set.key(),
                set.name(),
                set.options().stream()
                        .map(o -> new OptionView(o.value(), o.label(), o.tags().isEmpty() ? null : o.tags()))
                        .toList());
    }
}
