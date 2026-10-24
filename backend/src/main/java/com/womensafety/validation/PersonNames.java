package com.womensafety.validation;

public final class PersonNames {

    private PersonNames() {
    }

    /** Trims and collapses runs of whitespace, so "  Asha   Rao " is stored as "Asha Rao". */
    public static String normalise(String name) {
        return name.strip().replaceAll("\\s+", " ");
    }
}
