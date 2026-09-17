package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public final class RequestValues {
    private RequestValues() { }

    public static long integer(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new CoreException(ErrorType.BAD_REQUEST);
        }
        return node.longValue();
    }

    public static int stock(JsonNode node) {
        long value = integer(node);
        if (value < 0 || value > Integer.MAX_VALUE) { throw new CoreException(ErrorType.BAD_REQUEST); }
        return (int) value;
    }

    public static String name(JsonNode node) {
        if (node == null || !node.isTextual()) { throw new CoreException(ErrorType.BAD_REQUEST); }
        return node.textValue();
    }

    public static void page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) { throw new CoreException(ErrorType.BAD_REQUEST); }
    }
}
