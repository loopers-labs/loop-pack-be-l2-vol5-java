package com.loopers.fixture;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.point.PointModel;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointHistoryJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockHistoryJpaRepository;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 주문 확정 테스트의 판정용 상태를 새 조회로 읽는다.
 * 호출마다 Repository 의 자체 트랜잭션·영속성 컨텍스트를 사용하므로, 테스트에서 부모 트랜잭션 없이 호출하면
 * 이미 commit 된 DB 상태를 읽는다. 삭제된 상품도 활성 조건 없이 읽는다.
 */
@Component
public class OrderStateReader {

    private final OrderJpaRepository orderJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final PointJpaRepository pointJpaRepository;
    private final PointHistoryJpaRepository pointHistoryJpaRepository;
    private final StockHistoryJpaRepository stockHistoryJpaRepository;

    public OrderStateReader(OrderJpaRepository orderJpaRepository, ProductJpaRepository productJpaRepository,
                            PointJpaRepository pointJpaRepository, PointHistoryJpaRepository pointHistoryJpaRepository,
                            StockHistoryJpaRepository stockHistoryJpaRepository) {
        this.orderJpaRepository = orderJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.pointJpaRepository = pointJpaRepository;
        this.pointHistoryJpaRepository = pointHistoryJpaRepository;
        this.stockHistoryJpaRepository = stockHistoryJpaRepository;
    }

    /**
     * 주문 확정이 바꿀 수 있는 상태 전체.
     * order: 상태·총액·포인트 사용액·결제액·품목(상품 ID 순의 상품·수량·단가), 없는 주문이면 null.
     * balance: Point 가 없으면 null. stocks: 요청한 상품 순서의 재고. History 는 id 순 전체 행.
     */
    public record ConfirmState(List<Object> order, Long balance, List<Long> stocks,
                               List<List<Object>> pointHistories, List<List<Object>> stockHistories) {
    }

    public ConfirmState confirmState(Long orderId, Long userId, Collection<Long> productIds) {
        return new ConfirmState(orderRow(orderId), balance(userId), stocks(productIds),
            pointHistoryRows(), stockHistoryRows());
    }

    public List<Object> orderRow(Long orderId) {
        return orderJpaRepository.findWithItemsById(orderId).map(this::toRow).orElse(null);
    }

    public Long balance(Long userId) {
        return pointJpaRepository.findByUserId(userId).map(PointModel::getBalance).orElse(null);
    }

    /** History 의 Point 대상 판정용. */
    public Long pointId(Long userId) {
        return pointJpaRepository.findByUserId(userId).map(PointModel::getId).orElseThrow();
    }

    public List<Long> stocks(Collection<Long> productIds) {
        return productIds.stream()
            .map(id -> productJpaRepository.findById(id).orElseThrow().getStockQuantity())
            .toList();
    }

    /** [id, pointId, 변경 전, 변경 후, 변경량, 원인, 주문 ID] */
    public List<List<Object>> pointHistoryRows() {
        return pointHistoryJpaRepository.findAll().stream()
            .sorted(Comparator.comparing(history -> history.getId()))
            .map(history -> Arrays.<Object>asList(history.getId(), history.getPointId(), history.getBeforeBalance(),
                history.getAfterBalance(), history.getChangedAmount(), history.getCause(), history.getOrderId()))
            .toList();
    }

    /** [id, productId, 변경 전, 변경 후, 변경량, 원인, 주문 ID] */
    public List<List<Object>> stockHistoryRows() {
        return stockHistoryJpaRepository.findAll().stream()
            .sorted(Comparator.comparing(history -> history.getId()))
            .map(history -> Arrays.<Object>asList(history.getId(), history.getProductId(),
                history.getBeforeQuantity(), history.getAfterQuantity(), history.getChangedQuantity(),
                history.getCause(), history.getOrderId()))
            .toList();
    }

    private List<Object> toRow(OrderModel order) {
        List<List<Object>> items = order.getItems().stream()
            .sorted(Comparator.comparing(item -> item.getProductId()))
            .map(item -> List.<Object>of(item.getProductId(), item.getQuantity(), item.getUnitPrice().toWon()))
            .toList();
        return Arrays.asList(order.getUserId(), order.getStatus(), order.getOrderTotal().toWon(),
            order.getUsedPointAmount(), order.getPaymentAmount() != null ? order.getPaymentAmount().toWon() : null,
            items);
    }
}
