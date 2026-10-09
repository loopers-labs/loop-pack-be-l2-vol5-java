package com.loopers.interfaces.api.ordering.dto;

import com.loopers.application.ordering.command.OrderCommand;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.util.List;

// 주문 API 요청 DTO 묶음
public final class OrderApiDto {
    private OrderApiDto() {}

    public record ItemRequest(Long productId, Integer quantity) {
        // 요청을 커맨드 품목으로 변환
        OrderCommand.Item toCommandItem() {
            if (productId == null || quantity == null || productId <= 0 || quantity <= 0) {
                throw new DomainException(DomainErrorCode.EMPTY_ORDER_ITEMS);
            }
            return new OrderCommand.Item(productId, quantity);
        }
    }

    public record CreateRequest(List<ItemRequest> items) {
        // 요청을 주문 생성 커맨드로 변환
        public OrderCommand.Create toCommand(long userId) {
            if (items == null || items.isEmpty()) {
                throw new DomainException(DomainErrorCode.EMPTY_ORDER_ITEMS);
            }
            List<OrderCommand.Item> commandItems = items.stream().map(ItemRequest::toCommandItem).toList();
            return new OrderCommand.Create(userId, commandItems);
        }
    }
}
