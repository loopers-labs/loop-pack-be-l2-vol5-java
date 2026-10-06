package com.loopers.infrastructure.order;

import com.loopers.application.order.port.OrderRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.Quantity;
import com.loopers.domain.product.ProductId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class OrderPersistenceAdapter implements OrderRepository {
    private final OrderJpaRepository orderJpaRepository;

    public OrderPersistenceAdapter(OrderJpaRepository orderJpaRepository) {
        this.orderJpaRepository = orderJpaRepository;
    }

    @Override
    @Transactional
    public Order save(Order order) {
        OrderJpaEntity entity;
        if (order.getId() == null) {
            entity = new OrderJpaEntity();
            entity.userId = order.getUserId();
            order.getItems().forEach(item -> entity.items.add(
                new OrderItemJpaValue(item.productId().value(), item.quantity().value(), item.unitPrice().value())));
            entity.total = order.getTotal().value();
        } else {
            entity = orderJpaRepository.findById(order.getId()).orElseThrow();
        }
        entity.status = order.getStatus().name();
        entity.paidAmount = order.getPaidAmount().value();
        entity.paymentResult = order.getPaymentResult();
        return toDomain(orderJpaRepository.save(entity));
    }

    @Override
    public Optional<Order> findById(long id) {
        return orderJpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional
    public Optional<Order> findByIdForUpdate(long id) {
        return orderJpaRepository.findForUpdate(id).map(this::toDomain);
    }

    @Override
    public List<Order> findPage(Long userId, int page, int size) {
        PageRequest paging = PageRequest.of(page, size, Sort.by("id").descending());
        Page<OrderJpaEntity> found = userId == null
            ? orderJpaRepository.findAll(paging)
            : orderJpaRepository.findByUserId(userId, paging);
        return found.stream().map(this::toDomain).toList();
    }

    private Order toDomain(OrderJpaEntity entity) {
        List<OrderItem> items = entity.items.stream()
            .map(item -> new OrderItem(new ProductId(item.productId), new Quantity(item.quantity), new Money(item.unitPrice)))
            .toList();
        Order order = Order.restore(entity.id, entity.userId, items, Order.Status.valueOf(entity.status),
            new Money(entity.paidAmount), entity.paymentResult);
        if (order.getTotal().value() != entity.total) {
            throw new IllegalStateException("저장된 주문 합계가 품목 합계와 다릅니다.");
        }
        return order;
    }
}
