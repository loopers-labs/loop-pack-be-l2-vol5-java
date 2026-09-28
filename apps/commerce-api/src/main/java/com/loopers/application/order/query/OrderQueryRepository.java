package com.loopers.application.order.query;

import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;

import java.util.Optional;

/** 주문 조회 전용 Repository. 주문과 품목을 {@link OrderView} 로 바로 반환한다 (DR-31). */
public interface OrderQueryRepository {
    /** FR-ORDER-04, FR-ADMIN-ORDER-02: 주문 한 건과 품목. 소유 판정은 Reader 가 한다. */
    Optional<OrderView.Detail> find(Long orderId);

    /** FR-ORDER-03: 사용자의 주문 전부, 최신순. */
    PageResult<OrderView.Detail> findPageByUserId(Long userId, PageQuery query);

    /** FR-ADMIN-ORDER-01: 페이지 단위는 구매자 묶음, 묶음 순서 사용자 ID 오름차순, 묶음 안 최신순 (ASM-19, ASM-20, DR-11). */
    PageResult<OrderView.BuyerGroup> findBuyerGroupPage(PageQuery query);
}
