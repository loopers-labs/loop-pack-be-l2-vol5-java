package com.loopers.domain.product;

import com.loopers.domain.common.PageResult;

import java.util.HashSet;
import java.util.Set;

public record ProductListSnapshot(long revision, PageResult<Item> page) {
    public ProductListSnapshot {
        if (revision < 0 || page == null || page.page() != 0 || page.size() != 20 || page.totalElements() < 0) {
            throw new IllegalArgumentException("Invalid product list snapshot metadata");
        }
        long pages = page.totalElements() / 20 + (page.totalElements() % 20 == 0 ? 0 : 1);
        if (page.totalPages() != pages || page.items().size() != Math.min(20L, page.totalElements())) {
            throw new IllegalArgumentException("Inconsistent product list snapshot page");
        }
        Set<Long> productIds = new HashSet<>();
        Item previous = null;
        for (Item item : page.items()) {
            if (item == null || !productIds.add(item.productId())) {
                throw new IllegalArgumentException("Duplicate or null product in snapshot");
            }
            if (previous != null && (previous.likeCount() < item.likeCount()
                || previous.likeCount() == item.likeCount() && previous.productId() < item.productId())) {
                throw new IllegalArgumentException("Invalid product list snapshot ranking");
            }
            previous = item;
        }
    }

    public record Item(Long productId, String name, long price, int stockQuantity,
                       Long brandId, String brandName, long likeCount) {
        public Item {
            if (productId == null || productId < 1 || brandId == null || brandId < 1
                || price < 1 || stockQuantity < 0 || likeCount < 0 || !validName(name) || !validName(brandName)) {
                throw new IllegalArgumentException("Invalid product list snapshot item");
            }
        }

        private static boolean validName(String value) {
            return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= 100;
        }
    }
}
