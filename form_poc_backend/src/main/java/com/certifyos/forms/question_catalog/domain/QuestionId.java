package com.certifyos.forms.question_catalog.domain;

/** Identity of a catalog question. A value object so it cannot be confused with any other id. */
public record QuestionId(String value) {

    public QuestionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("QuestionId cannot be blank");
        }
    }

    public static QuestionId of(String value) {
        return new QuestionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
