package com.certifyos.forms.question_catalog.domain;

import com.certifyos.forms.shared_kernel.expression.Operator;
import java.util.List;
import java.util.Optional;

/**
 * How a question is answered. Deliberately a closed vocabulary — a new response type is a
 * deliberate product decision, not something a form author invents.
 *
 * <p>{@link #applicableOperators()} is what the condition builder reads to filter its operator
 * dropdown, so picking a date field offers before/after while a single-select offers is/is one of.
 * Mirrored in {@code packages/form-expression/registry.ts}.
 */
public enum ResponseType {
    /** Single-line free text. */
    TEXT("text"),
    /** A group of related text fields (address line 1 / city / state), each independently required. */
    TEXT_GROUP("textGroup"),
    DATE("date"),
    SINGLE_SELECT("singleSelect"),
    MULTI_SELECT("multiSelect"),
    /** Rendered as buttons rather than a dropdown — Yes / No / Unknown. */
    BUTTON_SELECT("buttonSelect"),
    FILE("file"),
    /** A repeatable sub-form, e.g. one entry per license. */
    GROUP("group");

    private final String wireName;

    ResponseType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<ResponseType> fromWireName(String name) {
        for (ResponseType t : values()) {
            if (t.wireName.equals(name)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    public boolean isSelect() {
        return this == SINGLE_SELECT || this == MULTI_SELECT || this == BUTTON_SELECT;
    }

    /** Operators that make sense for this response type. Drives the builder's dropdown. */
    public List<Operator> applicableOperators() {
        return switch (this) {
            case TEXT -> List.of(
                    Operator.EQ,
                    Operator.NEQ,
                    Operator.IN,
                    Operator.NIN,
                    Operator.CONTAINS,
                    Operator.MATCHES,
                    Operator.EXISTS,
                    Operator.EMPTY);
            case DATE -> List.of(
                    Operator.EQ,
                    Operator.NEQ,
                    Operator.GT,
                    Operator.GTE,
                    Operator.LT,
                    Operator.LTE,
                    Operator.EXISTS,
                    Operator.EMPTY);
            case SINGLE_SELECT, BUTTON_SELECT -> List.of(
                    Operator.EQ, Operator.NEQ, Operator.IN, Operator.NIN, Operator.EXISTS, Operator.EMPTY);
            case MULTI_SELECT -> List.of(Operator.CONTAINS, Operator.EXISTS, Operator.EMPTY);
            case FILE -> List.of(Operator.EXISTS, Operator.EMPTY);
                // A group has no scalar value of its own — conditions address its children, or
                // quantify over its items.
            case TEXT_GROUP, GROUP -> List.of(Operator.EXISTS, Operator.EMPTY);
        };
    }
}
