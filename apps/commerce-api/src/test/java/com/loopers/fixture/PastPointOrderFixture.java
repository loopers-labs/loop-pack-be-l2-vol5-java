package com.loopers.fixture;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.point.PointHistoryModel;
import com.loopers.domain.point.PointHistoryRepository;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.StockHistoryModel;
import com.loopers.domain.product.StockHistoryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 과거 POINT snapshot 준비용. 신규 결제 API를 거치지 않는다. */
@Component
public class PastPointOrderFixture {
    private final OrderRepository orders;
    private final PointRepository points;
    private final PointHistoryRepository pointHistories;
    private final ProductRepository products;
    private final StockHistoryRepository stockHistories;

    public PastPointOrderFixture(OrderRepository orders, PointRepository points, PointHistoryRepository pointHistories,
                                 ProductRepository products, StockHistoryRepository stockHistories) {
        this.orders = orders;
        this.points = points;
        this.pointHistories = pointHistories;
        this.products = products;
        this.stockHistories = stockHistories;
    }

    @Transactional
    public void confirm(Long userId, Long orderId) {
        OrderModel order = orders.findForUpdate(orderId).orElseThrow();
        PointModel point = points.findByUserIdForUpdate(userId).orElseThrow();
        var change = point.use(order.getTotalAmount().toWon());
        pointHistories.save(PointHistoryModel.usedForOrder(point.getId(), orderId, change));
        for (var item : order.getItems()) {
            ProductModel product = products.findActiveForUpdate(item.getProductId()).orElseThrow();
            stockHistories.save(StockHistoryModel.deductedByOrder(product.getId(), orderId,
                product.decreaseStock(item.getQuantity())));
        }
        order.confirmWithPoints(change.changedAmount());
    }
}
