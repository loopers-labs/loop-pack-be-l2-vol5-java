package com.loopers.interfaces.api;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import java.util.Arrays;
import java.util.List;

public final class ProductIds {
    private ProductIds() { }
    public static List<Long> parse(String raw) {
        if (raw == null) throw new CoreException(ErrorCode.INVALID_REQUEST);
        String[] tokens = raw.split(",", -1);
        if (tokens.length < 1 || tokens.length > 100) throw new CoreException(ErrorCode.INVALID_REQUEST);
        try {
            List<Long> ids = Arrays.stream(tokens).map(Long::parseLong).toList();
            if (ids.stream().anyMatch(id -> id <= 0)) throw new CoreException(ErrorCode.INVALID_REQUEST);
            return ids.stream().distinct().sorted().toList();
        } catch (NumberFormatException e) { throw new CoreException(ErrorCode.INVALID_REQUEST); }
    }
}
