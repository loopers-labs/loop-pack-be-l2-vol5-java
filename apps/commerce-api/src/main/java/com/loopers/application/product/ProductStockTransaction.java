package com.loopers.application.product;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

@RequiredArgsConstructor
@Component
public class ProductStockTransaction {
    private final ProductRepository productRepository;

    // 상품 행을 먼저 잠근 뒤 최신 commit된 예약을 읽는다. PG 결과 반영은 상품 잠금 전 주문을 바꾸므로
    // 주문 행을 잠그지 않는 READ_COMMITTED 조회로 역순 잠금과 오래된 RR 스냅샷을 피한다.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProductInfo changeStock(Long productId, long quantity) {
        Product product = productRepository.findByIdWithExclusiveLock(productId)
            .filter(found -> found.getDeletedAt() == null)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "상품을 찾을 수 없습니다."));
        product.changeStockTo(quantity);
        product.getStock().validateReservationCapacity(productRepository.sumPendingReservationQuantity(productId));
        if (productRepository.updateActiveStock(productId, quantity, ZonedDateTime.now()) != 1) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "잠근 상품의 재고를 설정하지 못했습니다.");
        }
        return ProductInfo.from(product);
    }
}
