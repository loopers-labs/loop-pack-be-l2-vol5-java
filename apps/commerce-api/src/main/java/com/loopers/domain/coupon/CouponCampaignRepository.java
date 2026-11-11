package com.loopers.domain.coupon;

import java.time.ZonedDateTime;
import java.util.Optional;

public interface CouponCampaignRepository {

    CouponCampaignModel save(CouponCampaignModel campaign);

    /**
     * 활성·잔여 수량 조건을 만족할 때만 발급 수를 1 올리는 조건부 UPDATE. 쓰기 TX 의 첫 업무 SQL 이다.
     * 반환은 변경 행 수이며 0 은 품절·비활성·없음·기존 발급을 구분하지 않는다. DB 예외는 숨기지 않는다.
     */
    int reserveOne(Long campaignId, ZonedDateTime now);

    /** UPDATE 뒤 이름·혜택 scalar 값을 읽는다. Entity 를 영속성 컨텍스트에 올리지 않는다. */
    Optional<CouponCampaignIssueData> findIssueData(Long campaignId);

    /** 수량 미확보 뒤 새 읽기 TX 의 원인 확인 등 읽기 전용 경로에서 사용한다. 쓰기 TX 의 선행 로드에 쓰지 않는다. */
    Optional<CouponCampaignModel> findById(Long campaignId);
}
