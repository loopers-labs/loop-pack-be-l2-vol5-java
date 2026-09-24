package com.loopers.infrastructure.ordering.order;

import com.loopers.application.ordering.order.ConfirmOrderLoad;
import com.loopers.application.ordering.order.ConfirmOrderWriter;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.domain.mall.product.Product;
import com.loopers.domain.mall.product.ProductRepository;
import com.loopers.domain.ordering.order.Order;
import com.loopers.domain.ordering.order.OrderItem;
import com.loopers.domain.ordering.order.OrderRepository;
import com.loopers.domain.pay.orderbill.OrderBill;
import com.loopers.domain.pay.orderbill.OrderBillRepository;
import com.loopers.domain.pay.wallet.PointBill;
import com.loopers.domain.pay.wallet.PointBillRepository;
import com.loopers.domain.pay.wallet.Wallet;
import com.loopers.domain.pay.wallet.WalletRepository;
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
    private final OrderBillRepository orderBillRepository;

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

    // 재고, 지갑 잔액, 결제 기록, 주문 상태를 각 repository로 저장
    @Override
    public void save(ConfirmOrderLoad load, PointBill pointBill, OrderBill orderBill) {
        for (Product product : load.productsByProductId().values()) {
            productRepository.save(product);
        }
        walletRepository.save(load.wallet());
        pointBillRepository.save(pointBill);
        orderBillRepository.save(orderBill);
        orderRepository.save(load.order());
    }
}
