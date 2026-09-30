package com.loopers.application.ordering.command;

import java.util.List;

// 주문 생성 요청 커맨드 묶음
public final class OrderCommand {
    private OrderCommand() {}

    public record Create(long userId, List<Item> items) {}

    public record Item(long productId, int quantity) {}
}
