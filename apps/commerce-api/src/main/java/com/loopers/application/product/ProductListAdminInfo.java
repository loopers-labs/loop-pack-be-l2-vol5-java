package com.loopers.application.product;

import java.util.List;

public record ProductListAdminInfo(List<ProductAdminInfo> items, int page, int size, long totalCount) {
}
