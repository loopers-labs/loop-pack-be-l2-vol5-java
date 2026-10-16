package com.loopers.domain.product;

import java.util.List;

/** Shared first-page ordering; product fields, counters and personal state remain in the DB. */
public record PopularPage(List<Long> ids) {
    public PopularPage { ids = List.copyOf(ids); }
}
