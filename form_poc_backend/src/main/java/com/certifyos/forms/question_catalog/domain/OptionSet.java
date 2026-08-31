package com.certifyos.forms.question_catalog.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A catalogued list of values. Aggregate root.
 *
 * <p>Kept separate from {@link ResponseType} on purpose: the response type is the widget, the
 * option set is the data. Conditional filtering (PRD §4.3, "specialty list changes based on
 * provider type") is driven by {@link Option#tags} rather than by hard-coded parent/child rules,
 * so a new filtering rule is a new tag and never a code change.
 */
public record OptionSet(String id, String tenantId, String key, String name, List<Option> options, boolean active) {

    public OptionSet {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("OptionSet key is required");
        }
        options = options == null ? List.of() : List.copyOf(options);

        Set<String> seen = new LinkedHashSet<>();
        for (Option o : options) {
            if (!seen.add(o.value())) {
                throw new IllegalArgumentException("Duplicate option value '" + o.value() + "' in set '" + key
                        + "' — conditions reference values, so they must be unique");
            }
        }
    }

    /**
     * @param tags arbitrary axes a parent question can filter on, e.g.
     *     {@code {"providerType": ["MD", "DO"]}}
     */
    public record Option(String value, String label, Map<String, List<String>> tags) {
        public Option {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Option value is required");
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException(
                        "Option label is required — condition prose prints labels, never stored values");
            }
            tags = tags == null ? Map.of() : Map.copyOf(tags);
        }

        public boolean matchesTag(String tagKey, String parentValue) {
            List<String> allowed = tags.get(tagKey);
            return allowed == null || allowed.contains(parentValue);
        }
    }

    public boolean isGlobal() {
        return tenantId == null || tenantId.isBlank();
    }

    public Set<Object> values() {
        return options.stream().map(o -> (Object) o.value()).collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Label for a stored value.
     *
     * <p>Condition summaries must print labels rather than values: {@code DC} means "Chiropractor"
     * in the provider-type set and "Chiropractic" in the specialty set, so a summary showing raw
     * values is genuinely ambiguous to the person reading it.
     */
    public Optional<String> labelFor(String value) {
        return options.stream()
                .filter(o -> o.value().equals(value))
                .map(Option::label)
                .findFirst();
    }

    /** The subset visible once a parent answer narrows this list. */
    public List<Option> filteredBy(String tagKey, String parentValue) {
        if (tagKey == null || parentValue == null) {
            return options;
        }
        return options.stream().filter(o -> o.matchesTag(tagKey, parentValue)).toList();
    }
}
