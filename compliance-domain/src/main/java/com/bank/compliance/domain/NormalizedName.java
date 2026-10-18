package com.bank.compliance.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A person's name in the form it is matched against the sanctions and PEP
 * lists: canonical decomposition (NFD) with every combining mark removed,
 * upper case, every character that is not a letter or digit turned into a
 * space, whitespace collapsed and trimmed. Letters without a decomposition
 * (for example a stroked L) are kept as they are.
 *
 * The list loader applies the same rule in Python
 * (scripts/ops/normalize-screening-name.py). The two are kept in step by the
 * shared fixtures scripts/ops/test/screening-name-fixtures.csv, checked by
 * NormalizedNameTest here and by the loader rehearsal in CI; change this
 * method, the script and the fixtures together.
 */
public record NormalizedName(String value) {

    public NormalizedName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (!value.equals(normalize(value))) {
            throw new IllegalArgumentException("value is not a normalised name");
        }
    }

    /** Normalises the given name parts (for example first and last name) joined by a space. */
    public static NormalizedName of(String... parts) {
        Objects.requireNonNull(parts, "name parts are required");
        StringJoiner joined = new StringJoiner(" ");
        for (String part : parts) {
            if (part != null) {
                joined.add(part);
            }
        }
        String normalized = normalize(joined.toString());
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        return new NormalizedName(normalized);
    }

    static String normalize(String raw) {
        String decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD);
        StringBuilder letters = new StringBuilder(decomposed.length());
        decomposed.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK) {
                return;
            }
            letters.appendCodePoint(codePoint);
        });
        String upper = letters.toString().toUpperCase(Locale.ROOT);
        StringBuilder spaced = new StringBuilder(upper.length());
        upper.codePoints().forEach(codePoint -> spaced.appendCodePoint(isLetterOrNumber(codePoint) ? codePoint : ' '));
        return String.join(" ", spaced.toString().trim().split("\\s+")).trim();
    }

    /** Unicode categories L* and N*, the same set Python's str.isalnum() keeps. */
    private static boolean isLetterOrNumber(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                 Character.MODIFIER_LETTER, Character.OTHER_LETTER,
                 Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true;
            default -> false;
        };
    }
}
