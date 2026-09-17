package com.loopers.application.order;

import java.util.List;

public record OrderListAdminInfo(List<OrderAdminInfo> items, int page, int size, long totalCount) {
}
