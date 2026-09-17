package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderModel create(Long userId, List<OrderModel.Line> lines) {
        return orderRepository.save(OrderModel.create(userId, lines));
    }

    /** 존재하는 주문. 없으면 ER-05 ORDER_NOT_FOUND. */
    public OrderModel get(Long orderId) {
        return orderRepository.find(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다."));
    }

    /** 요청자의 주문. 남의 것이면 ER-06 NOT_OWNER (ASM-09, DR-18: 존재를 숨기지 않는다). */
    public OrderModel getOwned(Long orderId, Long userId) {
        OrderModel order = get(orderId);
        if (!order.isOwnedBy(userId)) {
            throw new CoreException(ErrorType.NOT_OWNER, "본인의 주문만 조회·확정할 수 있습니다.");
        }
        return order;
    }

    public PageResult<OrderModel> listByUser(Long userId, PageQuery query) {
        return orderRepository.findPageByUserId(userId, query);
    }

    /** FR-ADMIN-ORDER-01: 페이지 단위는 구매자 묶음, 묶음 순서 사용자 ID 오름차순, 묶음 안 최신순 (ASM-19, ASM-20, DR-11). */
    public PageResult<BuyerOrders> listGroupedByBuyer(PageQuery query) {
        PageResult<Long> buyerPage = orderRepository.findBuyerIdPage(query);
        Map<Long, List<OrderModel>> byBuyer = orderRepository.findByUserIds(buyerPage.items()).stream()
            .collect(Collectors.groupingBy(OrderModel::getUserId));
        List<BuyerOrders> groups = new ArrayList<>();
        for (Long userId : buyerPage.items()) {
            groups.add(new BuyerOrders(userId, byBuyer.getOrDefault(userId, List.of())));
        }
        return PageResult.of(groups, query, buyerPage.totalCount());
    }
}
