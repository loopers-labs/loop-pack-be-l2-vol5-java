package com.loopers.infrastructure.order;

import com.loopers.application.order.port.OrderRepository;
import com.loopers.domain.common.Money;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.Quantity;
import com.loopers.domain.product.ProductId;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.util.List;
import java.util.Optional;

@Repository
@Transactional(readOnly = true)
public class OrderPersistenceAdapter implements OrderRepository {
    private final OrderJpaRepository repository;
    public OrderPersistenceAdapter(OrderJpaRepository repository) { this.repository = repository; }
    @Override
    @Transactional
    public Order save(Order order) {
        OrderJpaEntity entity;
        if (order.getId() == null) {
            entity = new OrderJpaEntity();
            entity.userId = order.getUserId();
            order.getItems().forEach(i -> entity.items.add(new OrderItemJpaValue(i.productId().value(), i.quantity().value(), i.unitPrice().value())));
            entity.total = order.getTotal().value();
        } else {
            entity = repository.findById(order.getId()).orElseThrow();
        }
        entity.status = order.getStatus().name();
        entity.paidAmount = order.getPaidAmount().value();
        entity.paymentResult = order.getPaymentResult();
        return toDomain(repository.save(entity));
    }
    @Override
    public Optional<Order> findById(long id) { return repository.findById(id).map(this::toDomain); }
    @Override
    @Transactional
    public Optional<Order> findByIdForUpdate(long id) { return repository.findForUpdate(id).map(this::toDomain); }
    @Override
    public List<Order> findPage(Long userId, int page, int size) {
        var paging = PageRequest.of(page, size, Sort.by("id").descending());
        var found = userId == null ? repository.findAll(paging) : repository.findByUserId(userId, paging);
        return found.stream().map(this::toDomain).toList();
    }
    private Order toDomain(OrderJpaEntity entity) {
        Order order = Order.restore(entity.id, entity.userId, entity.items.stream()
            .map(i -> new OrderItem(new ProductId(i.productId), new Quantity(i.quantity), new Money(i.unitPrice))).toList(),
            Order.Status.valueOf(entity.status), new Money(entity.paidAmount), entity.paymentResult);
        if (order.getTotal().value() != entity.total) { throw new IllegalStateException("저장된 주문 합계가 품목 합계와 다릅니다."); }
        return order;
    }
}
