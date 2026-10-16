package com.loopers.interfaces.api;

import com.loopers.support.page.SliceResult;
import java.util.List;
import java.util.function.Function;

public record SliceResponse<T>(List<T> content, int page, int size, boolean last) {

    public static <T, R> SliceResponse<R> from(SliceResult<T> result, Function<T, R> mapper) {
        SliceResult<R> mapped = result.map(mapper);
        return new SliceResponse<>(mapped.content(), mapped.page(), mapped.size(), mapped.last());
    }
}
