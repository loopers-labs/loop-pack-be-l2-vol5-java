package com.loopers.domain.product;

import java.time.ZonedDateTime;
import java.util.List;

/** Immutable query data; likes and stock are deliberately not stored. */
public record PricePage(List<Item> items) {
    public PricePage { items = List.copyOf(items); }
    public record Item(Long id, String name, long price, ZonedDateTime createdAt) {}
}
