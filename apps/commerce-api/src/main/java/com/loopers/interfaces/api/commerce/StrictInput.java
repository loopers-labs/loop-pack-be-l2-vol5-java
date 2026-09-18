package com.loopers.interfaces.api.commerce;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

public final class StrictInput {
    private StrictInput() {
    }

    public static JsonNode object(JsonNode body, String... fields) {
        if (body == null || !body.isObject() || body.size() != fields.length) {
            throw new InvalidRequestException();
        }
        Set<String> allowed = Set.of(fields);
        body.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field) || body.get(field).isNull()) {
                throw new InvalidRequestException();
            }
        });
        return body;
    }

    public static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isTextual()) {
            throw new InvalidRequestException();
        }
        return value.textValue();
    }

    public static long number(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new InvalidRequestException();
        }
        return value.longValue();
    }

    public static int integer(JsonNode body, String field) {
        long value = number(body, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new InvalidRequestException();
        }
        return (int) value;
    }

    public static long id(String value) {
        long parsed = unsigned(value);
        if (parsed == 0) {
            throw new InvalidRequestException();
        }
        return parsed;
    }

    public static Long optionalId(String value) {
        return value == null ? null : id(value);
    }

    public static int page(String value) {
        long parsed = value == null ? 0 : unsigned(value);
        if (parsed > Integer.MAX_VALUE) {
            throw new InvalidRequestException();
        }
        return (int) parsed;
    }

    public static int size(String value) {
        long parsed = value == null ? 20 : unsigned(value);
        if (parsed < 1 || parsed > 100) {
            throw new InvalidRequestException();
        }
        return (int) parsed;
    }

    private static long unsigned(String value) {
        if (value == null || !value.matches("[0-9]+")) {
            throw new InvalidRequestException();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new InvalidRequestException();
        }
    }
}
