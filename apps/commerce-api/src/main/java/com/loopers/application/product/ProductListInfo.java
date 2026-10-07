package com.loopers.application.product;

import java.util.List;

public record ProductListInfo(List<ProductInfo> items, int page, int size, long totalCount) {
}
