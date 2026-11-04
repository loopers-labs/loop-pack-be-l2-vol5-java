package com.loopers.order.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.order.application.event.OrderConfirmedEvent;
import com.loopers.order.application.outbox.OrderConfirmedOutboxWriter;
import com.loopers.order.infrastructure.kafka.contract.orderconfirmed.Item;
import com.loopers.order.infrastructure.kafka.contract.orderconfirmed.OrderConfirmedV1Schema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Instant;

@Component
public class OrderConfirmedOutboxStoreAdapter implements OrderConfirmedOutboxWriter {

    private final OrderConfirmedOutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OrderConfirmedOutboxStoreAdapter(
        OrderConfirmedOutboxRepository repository,
        ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void write(OrderConfirmedEvent event) {
        OrderConfirmedV1Schema message = toContractMessage(event);
        try {
            String payload = objectMapper.writeValueAsString(message);
            repository.saveAndFlush(OrderConfirmedOutbox.pending(
                event.eventId(),
                event.orderId(),
                "OrderConfirmed",
                1,
                event.orderId().toString(),
                payload,
                Instant.now()
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize OrderConfirmed contract", exception);
        }
    }

    private OrderConfirmedV1Schema toContractMessage(OrderConfirmedEvent event) {
        OrderConfirmedV1Schema message = new OrderConfirmedV1Schema();
        message.setEventType("OrderConfirmed");
        message.setSchemaVersion(1L);
        message.setEventId(event.eventId());
        message.setOrderId(event.orderId());
        message.setConfirmedAt(event.confirmedAt().toString());
        List<Item> items = event.items().stream().map(item -> {
            Item contractItem = new Item();
            contractItem.setProductId(item.productId());
            contractItem.setQuantity((long) item.quantity());
            return contractItem;
        }).toList();
        message.setItems(items);
        return message;
    }
}
