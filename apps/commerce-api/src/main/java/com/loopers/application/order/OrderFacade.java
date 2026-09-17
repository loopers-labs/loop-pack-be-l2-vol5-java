package com.loopers.application.order;

import com.loopers.domain.catalog.ProductModel;
import com.loopers.domain.catalog.ProductService;
import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.point.PointService;
import com.loopers.domain.user.UserService;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class OrderFacade {
    private final UserService userService;
    private final ProductService productService;
    private final PointService pointService;
    private final OrderService orderService;

    /**
     * FR-ORDER-01 주문 생성. ST-03 (없음) → DRAFT. 재고·잔액 변화 없음, 재고 부족은 확인하지 않는다.
     * 카탈로그 BC 에서 존재·ACTIVE·현재 가격(단가 원천)을 조회한다 (5-6). 검사 순서: 상품 → 품목 규칙(Model).
     */
    @Transactional
    public OrderInfo createOrder(Long requesterId, List<OrderItemCommand> items) {
        userService.getUser(requesterId);
        List<OrderItemCommand> safeItems = items == null ? List.of() : items;
        Map<Long, ProductModel> products = productService
            .getActiveProducts(safeItems.stream().map(OrderItemCommand::productId).toList())
            .stream().collect(Collectors.toMap(ProductModel::getId, Function.identity()));
        List<OrderModel.Line> lines = safeItems.stream()
            .map(item -> new OrderModel.Line(item.productId(), item.quantity(), products.get(item.productId()).getPrice()))
            .toList();
        return OrderInfo.from(orderService.create(requesterId, lines));
    }

    /**
     * FR-ORDER-02 주문 확정. ST-03 DRAFT → CONFIRMED.
     * 한 트랜잭션에서 상품 삭제 재검증 → 재고 차감(품목마다) → 잔액 차감 → 확정. 어느 하나가 실패하면 전부 롤백 (ASM-14, DR-08).
     * 검사 순서는 요구사항 실패 케이스 순서: 주문 없음 → 소유 → DRAFT → 상품 삭제 → 재고 → 잔액 (EP-11).
     */
    @Transactional
    public OrderInfo confirmOrder(Long requesterId, Long orderId) {
        userService.getUser(requesterId);
        OrderModel order = orderService.getOwned(orderId, requesterId);
        order.ensureDraft();
        productService.getActiveProducts(order.getItems().stream().map(OrderItemModel::getProductId).toList());
        for (OrderItemModel item : order.getItems()) {
            productService.deductStock(item.getProductId(), item.getQuantity());
        }
        pointService.deduct(requesterId, order.getTotalAmount());
        order.confirm();
        return OrderInfo.from(order);
    }

    /** FR-ORDER-03 내 주문 목록. DRAFT·CONFIRMED 모두, 최신순. */
    @Transactional(readOnly = true)
    public PageResult<OrderInfo> listMyOrders(Long requesterId, PageQuery query) {
        userService.getUser(requesterId);
        return orderService.listByUser(requesterId, query).map(OrderInfo::from);
    }

    /** FR-ORDER-04 내 주문 상세. 남의 주문은 NOT_OWNER. */
    @Transactional(readOnly = true)
    public OrderInfo getMyOrder(Long requesterId, Long orderId) {
        userService.getUser(requesterId);
        return OrderInfo.from(orderService.getOwned(orderId, requesterId));
    }

    /** FR-ADMIN-ORDER-01 주문 목록 (관리자). 구매자별 묶음, 페이지 단위는 묶음 (ASM-19, ASM-20). */
    @Transactional(readOnly = true)
    public PageResult<OrderGroupInfo> listOrdersForAdmin(Long requesterId, PageQuery query) {
        userService.getAdmin(requesterId);
        return orderService.listGroupedByBuyer(query).map(OrderGroupInfo::from);
    }

    /** FR-ADMIN-ORDER-02 주문 상세 (관리자). 구매자 포함. */
    @Transactional(readOnly = true)
    public OrderInfo getOrderForAdmin(Long requesterId, Long orderId) {
        userService.getAdmin(requesterId);
        return OrderInfo.from(orderService.get(orderId));
    }
}
