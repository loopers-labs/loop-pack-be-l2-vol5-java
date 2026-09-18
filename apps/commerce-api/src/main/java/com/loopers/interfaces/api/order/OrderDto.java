package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.loopers.application.order.CreateOrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.interfaces.api.support.StrictLongDeserializer;
import com.loopers.interfaces.api.support.StrictIntegerDeserializer;
import java.util.List;

public class OrderDto {
    public record ItemRequest(@JsonDeserialize(using = StrictLongDeserializer.class) Long productId,
                              @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer quantity) {
        public CreateOrderFacade.Item toCommand() { return new CreateOrderFacade.Item(productId, quantity); }
    }
    public record Create(List<ItemRequest> items) {
        public List<CreateOrderFacade.Item> toCommand() {
            return items == null ? null : items.stream().map(item -> item == null ? null : item.toCommand()).toList();
        }
    }
    public record Item(long productId, int quantity, long unitPrice, long lineAmount) {
        public static Item from(OrderInfo.Item item) { return new Item(item.productId(),item.quantity(),item.unitPrice(),item.lineAmount()); }
    }
    public record Customer(long orderId, String status, List<Item> items, long totalAmount, Long paidAmount, String paymentResult) {
        public static Customer from(OrderInfo o) {
            return new Customer(o.orderId(),o.status(),o.items().stream().map(Item::from).toList(),o.totalAmount(),o.paidAmount(),o.paymentResult());
        }
    }
    public record Admin(long orderId, long userId, String status, List<Item> items, long totalAmount, Long paidAmount, String paymentResult) {
        public static Admin from(OrderInfo o) {
            return new Admin(o.orderId(),o.userId(),o.status(),o.items().stream().map(Item::from).toList(),o.totalAmount(),o.paidAmount(),o.paymentResult());
        }
    }
    public record OrderList(List<Customer> items) {}
}
