package com.loopers.product.application;

import com.loopers.product.application.port.in.StockReservationUseCase;
import com.loopers.product.application.port.out.ProductPort;
import com.loopers.product.application.port.out.StockReservationPort;
import com.loopers.product.domain.ProductModel;
import com.loopers.product.domain.StockReservation;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 재고 선점 (ADR-W5-02). 자기 트랜잭션을 열지 않고 호출자(결제 요청 TX A·결과 반영 TX)에 참여한다 — 주문·결제 상태와 함께 반영·되돌림.
 * 목록 캐시는 재고를 담지 않으므로 ProductChangedEvent를 발행하지 않는다 (W4, ProductChangedEvent 주석).
 */
@RequiredArgsConstructor
@Component
public class StockReservationService implements StockReservationUseCase {

    private final ProductPort productPort;
    private final StockReservationPort stockReservationPort;

    /**
     * 상품을 id 오름차순으로 잠근 뒤(ADR-W3-03) 잠근 최신 상태로 판단한다. 중간 상품에서 거절되면 앞에서 줄인 재고도 TX와 함께 되돌려진다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public void reserve(Long orderId, Map<Long, Integer> quantitiesByProductId) {
        if (quantitiesByProductId.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "선점할 상품이 없습니다.");
        }
        Map<Long, ProductModel> productsById = productPort.findAllByIdsForUpdate(quantitiesByProductId.keySet()).stream()
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));

        List<StockReservation> reservations = quantitiesByProductId.keySet().stream()
            .sorted()
            .map(productId -> {
                ProductModel product = productsById.get(productId);
                // ORD-03과 같은 판단: 주문은 있는데 상품이 사라졌으면 주문의 현재 상태와의 충돌이다
                if (product == null || !product.isSellable()) {
                    throw new CoreException(ErrorType.CONFLICT, "[productId = " + productId + "] 더 이상 판매하지 않는 상품입니다.");
                }
                int quantity = quantitiesByProductId.get(productId);
                product.decreaseStock(quantity);
                productPort.save(product);
                return new StockReservation(orderId, productId, quantity);
            })
            .toList();
        stockReservationPort.saveAll(reservations);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public int consume(Long orderId) {
        List<StockReservation> consumed = stockReservationPort.findByOrderIdForUpdate(orderId).stream()
            .filter(StockReservation::consume)
            .toList();
        stockReservationPort.saveAll(consumed);
        return consumed.size();
    }

    /**
     * 잠금 순서: 선점 행 → 상품 id 오름차순 (ADR-W5-04). 실제로 RESERVED → RELEASED가 된 행의 수량만 되돌려, 중복 실패가 재고를 두 번 늘리지 않는다 (R-2).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public int release(Long orderId) {
        List<StockReservation> released = stockReservationPort.findByOrderIdForUpdate(orderId).stream()
            .filter(StockReservation::release)
            .toList();
        if (released.isEmpty()) {
            return 0;
        }
        stockReservationPort.saveAll(released);

        Map<Long, Integer> quantitiesByProductId = released.stream()
            .collect(Collectors.toMap(StockReservation::getProductId, StockReservation::getQuantity));
        // 삭제된 상품도 되돌린다 — 선점한 수량은 원래 그 상품의 재고였다
        productPort.findAllByIdsForUpdate(quantitiesByProductId.keySet()).forEach(product -> {
            product.increaseStock(quantitiesByProductId.get(product.getId()));
            productPort.save(product);
        });
        return released.size();
    }
}
