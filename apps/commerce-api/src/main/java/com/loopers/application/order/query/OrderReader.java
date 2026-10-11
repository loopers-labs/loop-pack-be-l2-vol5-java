package com.loopers.application.order.query;

import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 주문 조회 유스케이스 (DR-31). */
@RequiredArgsConstructor
@Component
public class OrderReader {
    private final UserService userService;
    private final OrderQueryRepository orderQueryRepository;

    /** FR-ORDER-03 내 주문 목록. DRAFT·CONFIRMED 모두, 최신순. */
    @Transactional(readOnly = true)
    public PageResult<OrderView.Detail> listMyOrders(Long requesterId, PageQuery query) {
        userService.getUser(requesterId);
        return orderQueryRepository.findPageByUserId(requesterId, query);
    }

    /** FR-ORDER-04 내 주문 상세. 없으면 ORDER_NOT_FOUND, 남의 주문은 NOT_OWNER (DR-18). */
    @Transactional(readOnly = true)
    public OrderView.Detail getMyOrder(Long requesterId, Long orderId) {
        userService.getUser(requesterId);
        OrderView.Detail order = getOrder(orderId);
        if (!order.userId().equals(requesterId)) {
            throw new CoreException(ErrorType.NOT_OWNER, "본인의 주문만 조회·확정할 수 있습니다.");
        }
        return order;
    }

    /** FR-ADMIN-ORDER-01 주문 목록 (관리자). 구매자별 묶음, 페이지 단위는 묶음 (ASM-19, ASM-20). */
    @Transactional(readOnly = true)
    public PageResult<OrderView.BuyerGroup> listOrdersForAdmin(Long requesterId, PageQuery query) {
        userService.getAdmin(requesterId);
        return orderQueryRepository.findBuyerGroupPage(query);
    }

    /** FR-ADMIN-ORDER-02 주문 상세 (관리자). 구매자 포함. */
    @Transactional(readOnly = true)
    public OrderView.Detail getOrderForAdmin(Long requesterId, Long orderId) {
        userService.getAdmin(requesterId);
        return getOrder(orderId);
    }

    private OrderView.Detail getOrder(Long orderId) {
        return orderQueryRepository.find(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다."));
    }
}
