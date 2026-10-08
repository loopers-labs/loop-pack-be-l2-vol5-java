package com.loopers.application.order;

import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.point.PointRepository;
import com.loopers.application.user.UserValidator;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderFacade {
    private final BrandRepository brandRepository;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PointRepository pointRepository;
    private final UserValidator userValidator;

    @Transactional
    public OrderInfo create(Long userId, List<OrderRequestItem> requestItems) {
        if (userId == null || requestItems == null || requestItems.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 정보가 올바르지 않습니다.");
        }
        userValidator.validateExists(userId);
        List<OrderItem> items = requestItems.stream().map(this::toOrderItem).toList();
        return OrderInfo.from(orderRepository.save(Order.create(userId, items)));
    }

    @Transactional
    public OrderInfo confirm(Long userId, Long orderId) {
        userValidator.validateExists(userId);
        Order order = orderRepository.findById(orderId)
            .filter(found -> found.getUserId().equals(userId))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
        order.validateDraft();
        pointRepository.findByUserId(userId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "사용자의 포인트를 찾을 수 없습니다."));

        lockActiveBrands(order);
        order.confirm();
        int confirmedRows = orderRepository.confirmIfDraft(
            orderId, userId, order.getPaymentAmount(), ZonedDateTime.now()
        );
        if (confirmedRows == 0) {
            throw new CoreException(ErrorType.CONFLICT, "DRAFT 주문만 확정할 수 있습니다.");
        }

        order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::getProductId))
            .forEach(item -> {
                int updatedRows = productRepository.decreaseActiveStock(
                    item.getProductId(), item.getQuantity(), ZonedDateTime.now()
                );
                if (updatedRows == 0) {
                    throw new CoreException(ErrorType.CONFLICT, "상품이 삭제되었거나 재고가 부족합니다.");
                }
            });

        int debitedRows = pointRepository.decreaseBalanceIfEnough(
            userId, order.getTotalAmount(), ZonedDateTime.now()
        );
        if (debitedRows == 0) {
            throw new CoreException(ErrorType.CONFLICT, "포인트 잔액이 부족합니다.");
        }
        return OrderInfo.from(order);
    }

    @Transactional(readOnly = true)
    public List<OrderInfo> getMyOrders(Long userId) {
        userValidator.validateExists(userId);
        return orderRepository.findAllByUserId(userId).stream().map(OrderInfo::from).toList();
    }

    @Transactional(readOnly = true)
    public OrderInfo getMyOrder(Long userId, Long orderId) {
        userValidator.validateExists(userId);
        Order order = orderRepository.findById(orderId)
            .filter(found -> found.getUserId().equals(userId))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
        return OrderInfo.from(order);
    }

    @Transactional(readOnly = true)
    public List<OrderInfo> getAllOrders() {
        return orderRepository.findAll().stream().map(OrderInfo::from).toList();
    }

    @Transactional(readOnly = true)
    public OrderInfo getOrder(Long orderId) {
        return orderRepository.findById(orderId).map(OrderInfo::from)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
    }

    private void lockActiveBrands(Order order) {
        List<Long> brandIds = order.getItems().stream()
            .map(item -> productRepository.findById(item.getProductId())
                .filter(product -> product.getDeletedAt() == null)
                .orElseThrow(() -> new CoreException(ErrorType.CONFLICT, "상품이 삭제되었거나 재고가 부족합니다.")))
            .map(Product::getBrandId)
            .distinct()
            .sorted()
            .toList();

        for (Long brandId : brandIds) {
            brandRepository.findActiveByIdWithSharedLock(brandId)
                .orElseThrow(() -> new CoreException(ErrorType.CONFLICT, "상품이 삭제되었거나 재고가 부족합니다."));
        }
    }

    private OrderItem toOrderItem(OrderRequestItem item) {
        Product product = productRepository.findById(item.productId())
            .filter(found -> found.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        return OrderItem.create(product.getId(), product.getName(), product.getPrice(), item.quantity());
    }

    public record OrderRequestItem(Long productId, int quantity) {}
}
