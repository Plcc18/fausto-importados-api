package com.example.fausto_importados_api.dto.validation;

import com.example.fausto_importados_api.exception.FieldValidationException;

import java.util.ArrayList;
import java.util.List;

/**
 * Acumula os erros de uma entrada para responder todos de uma vez. Uma instância por requisição: faça as conferências
 * e termine com {@link #throwIfInvalid}.
 */
public final class FieldChecks {

    private final List<InvalidField> errors = new ArrayList<>();

    public void reject(String field, String message) {
        errors.add(new InvalidField(field, message));
    }

    /**
     * Texto puro de uma linha.
     *
     * @return o texto sem espaços nas pontas, ou nulo se vier vazio
     */
    public String optionalText(String field, String value, int maxLength) {
        return text(field, value, maxLength, false);
    }

    /** Como {@link #optionalText}, mas aceita várias linhas. */
    public String optionalMultilineText(String field, String value, int maxLength) {
        return text(field, value, maxLength, true);
    }

    /** Como {@link #optionalText}, mas recusa o campo vazio. */
    public String requiredText(String field, String value, int maxLength) {
        String text = optionalText(field, value, maxLength);
        if (text == null) {
            reject(field, "Preencha este campo.");
        }
        return text;
    }

    public void throwIfInvalid() {
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    public static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private String text(String field, String value, int maxLength, boolean multiline) {
        String text = blankToNull(value);
        if (text == null) {
            return null;
        }
        if (text.indexOf('<') >= 0 || text.indexOf('>') >= 0) {
            reject(field, "Use só texto: os sinais < e > não são aceitos.");
        } else if (text.codePoints().anyMatch(c -> breaksPlainText(c) && !(multiline && isLineLayout(c)))) {
            reject(field, multiline ? "Remova os caracteres de controle."
                    : "Remova quebras de linha e caracteres de controle.");
        } else if (text.codePointCount(0, text.length()) > maxLength) {
            reject(field, "Use no máximo " + maxLength + " caracteres.");
        }
        return text;
    }

    private static boolean isLineLayout(int codePoint) {
        return codePoint == '\n' || codePoint == '\r' || codePoint == '\t';
    }

    // controle (inclui \n e o \u0000, que o JSONB recusa), separadores de linha e metades soltas de um par UTF-16
    private static boolean breaksPlainText(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE;
    }
}
