package com.loopers.infrastructure.dao.ordering;

import com.loopers.application.ordering.dao.ConfirmOrderLoad;
import com.loopers.application.ordering.dao.ConfirmOrderWriter;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PointBill;
import com.loopers.domain.pay.model.Wallet;
import com.loopers.domain.pay.repository.PointBillRepository;
import com.loopers.domain.pay.repository.WalletRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
// 주문 확정용 조회/저장 JPA 구현체
public class JpaConfirmOrderWriter implements ConfirmOrderWriter {
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final WalletRepository walletRepository;
    private final PointBillRepository pointBillRepository;

    // 주문 -> 해당 사용자 지갑 -> 상품 ID 오름차순 순서로 잠가 조회
    @Override
    public ConfirmOrderLoad load(long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.ORDER_NOT_FOUND));

        Wallet wallet = walletRepository.findByUserIdForUpdate(order.getUserId()).orElseThrow();

        TreeSet<Long> productIds = new TreeSet<>();
        for (OrderItem item : order.getItems()) {
            productIds.add(item.getProductId());
        }
        Map<Long, Product> productsByProductId = new LinkedHashMap<>();
        for (Long productId : productIds) {
            Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.PRODUCT_NOT_FOUND));
            productsByProductId.put(productId, product);
        }

        return new ConfirmOrderLoad(order, productsByProductId, wallet);
    }

    // 재고, 지갑 잔액, 사용 기록을 각 repository로 저장하고 주문 기록은 주문 저장에 cascade로 함께 저장한다
    @Override
    public void save(ConfirmOrderLoad load, PointBill pointBill) {
        for (Product product : load.productsByProductId().values()) {
            productRepository.save(product);
        }
        walletRepository.save(load.wallet());
        pointBillRepository.save(pointBill);
        orderRepository.save(load.order());
    }
}
