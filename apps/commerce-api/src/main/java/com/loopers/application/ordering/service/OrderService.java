package com.loopers.application.ordering.service;

import com.loopers.application.ordering.command.OrderCommand;
import com.loopers.application.ordering.result.OrderResult;
import com.loopers.application.ordering.usecase.CreateOrderUseCase;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
// 주문 생성 유스케이스 구현체
public class OrderService implements CreateOrderUseCase {
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    // 상품별 수량을 합산해 주문 생성
    @Override
    @Transactional
    public OrderResult execute(OrderCommand.Create command) {
        Map<Long, Integer> mergedQuantities = mergeQuantities(command.items());
        List<OrderItem> items = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : mergedQuantities.entrySet()) {
            Product product = productRepository.findById(entry.getKey())
                .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.PRODUCT_NOT_FOUND));
            product.ensureActive();
            items.add(OrderItem.create(product.getId(), product.getName(), product.getPrice(), entry.getValue()));
        }
        Order order = Order.create(command.userId(), items);
        return OrderResult.from(orderRepository.save(order));
    }

    // 동일 상품 수량 병합
    private Map<Long, Integer> mergeQuantities(List<OrderCommand.Item> items) {
        Map<Long, Integer> merged = new LinkedHashMap<>();
        for (OrderCommand.Item item : items) {
            merged.merge(item.productId(), item.quantity(), this::addExact);
        }
        return merged;
    }

    private int addExact(int a, int b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new DomainException(DomainErrorCode.CALCULATION_OVERFLOW);
        }
    }
}
