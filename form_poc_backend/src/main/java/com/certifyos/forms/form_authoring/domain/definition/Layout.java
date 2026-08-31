package com.certifyos.forms.form_authoring.domain.definition;

/**
 * How wide a question renders, on a 12-column grid.
 *
 * <p>Present because the live renderer requires it: every one of the 63 fields in the production
 * credentialing config carries {@code layout: { columns: N }}, overwhelmingly 6 (half width), 12
 * (full width) or 4 (a third). A compiler that omitted it would emit artifacts the renderer lays
 * out wrongly.
 *
 * <p>Width is a property of the <em>appearance</em>, not of the question type — the same catalog
 * question is half-width in one form and full-width in another — so it lives on the instance.
 */
public record Layout(int columns) {

    /** Full width. What a question gets when the author expresses no preference. */
    public static final Layout FULL = new Layout(12);

    public static final Layout HALF = new Layout(6);
    public static final Layout THIRD = new Layout(4);

    public Layout {
        if (columns < 1 || columns > 12) {
            throw new IllegalArgumentException("Layout columns must be between 1 and 12, got: " + columns);
        }
    }
}
