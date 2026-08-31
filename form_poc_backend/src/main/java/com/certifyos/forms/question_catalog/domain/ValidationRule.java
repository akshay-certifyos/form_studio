package com.certifyos.forms.question_catalog.domain;

import java.util.Map;

/**
 * A validation, expressed as a rule name plus parameters rather than as code.
 *
 * <p>The whole of PRD §3 — character limits, numeric, phone, fax, TIN, NPI checksum, email — is
 * this closed vocabulary. Each rule is implemented once; adding one to a question is config.
 *
 * @param rule one of the names in {@link Kind}
 * @param params rule-specific, e.g. {@code {"exact": 10}} or {@code {"pattern": "^\\d{5}$"}}
 */
public record ValidationRule(String rule, Map<String, Object> params) {

    /** The closed vocabulary. A new entry here is a deliberate decision, not a form author's. */
    public enum Kind {
        REQUIRED("required"),
        MIN_LENGTH("minLength"),
        MAX_LENGTH("maxLength"),
        LENGTH("length"),
        NUMERIC("numeric"),
        PHONE("phone"),
        FAX("fax"),
        TIN("tin"),
        NPI_CHECKSUM("npiChecksum"),
        EMAIL("email"),
        DATE_RANGE("dateRange"),
        REGEX("regex"),
        FILE_TYPE("fileType"),
        FILE_SIZE("fileSize");

        private final String wireName;

        Kind(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        static boolean isKnown(String name) {
            for (Kind k : values()) {
                if (k.wireName.equals(name)) {
                    return true;
                }
            }
            return false;
        }
    }

    public ValidationRule {
        if (rule == null || rule.isBlank()) {
            throw new IllegalArgumentException("Validation rule name is required");
        }
        if (!Kind.isKnown(rule)) {
            throw new IllegalArgumentException(
                    "Unknown validation rule: " + rule + ". Rules are a closed vocabulary — add it to "
                            + "ValidationRule.Kind and implement it once, rather than per form.");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public static ValidationRule of(Kind kind) {
        return new ValidationRule(kind.wireName(), Map.of());
    }

    public static ValidationRule of(Kind kind, Map<String, Object> params) {
        return new ValidationRule(kind.wireName(), params);
    }

    public boolean isRequired() {
        return Kind.REQUIRED.wireName().equals(rule);
    }
}
