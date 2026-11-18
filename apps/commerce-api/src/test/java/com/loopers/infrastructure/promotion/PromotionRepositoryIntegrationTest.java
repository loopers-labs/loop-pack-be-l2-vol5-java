package com.loopers.infrastructure.promotion;

import com.loopers.domain.promotion.CouponIssuePromotionModel;
import com.loopers.domain.promotion.IssuedCouponModel;
import com.loopers.domain.promotion.PromotionModel;
import com.loopers.domain.promotion.PromotionRepository;
import com.loopers.domain.promotion.PromotionStatus;
import com.loopers.domain.promotion.PromotionType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * promotion·coupon_issue_promotion 저장과 다시 읽기. 실제 MySQL.
 * 실행: ./gradlew :apps:commerce-api:test --tests '*PromotionRepositoryIntegrationTest' (Docker 필요, Testcontainers).
 */
@SpringBootTest
class PromotionRepositoryIntegrationTest {

    private static final ZonedDateTime STARTS_AT = ZonedDateTime.parse("2026-11-10T10:00:00+09:00[Asia/Seoul]");
    private static final ZonedDateTime ENDS_AT = STARTS_AT.plusDays(7);

    @Autowired
    private PromotionRepository promotionRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private PromotionModel savedPromotion() {
        return promotionRepository.save(PromotionModel.create("11월 선착순 쿠폰", PromotionType.COUPON_ISSUE, STARTS_AT, ENDS_AT));
    }

    @DisplayName("프로모션을 저장하고 다시 읽으면 이름·종류·상태·기간이 그대로다. 상태 변경도 저장된다.")
    @Test
    void savesAndFindsPromotion() {
        PromotionModel promotion = savedPromotion();
        promotion.activate();
        promotionRepository.save(promotion);

        PromotionModel found = promotionRepository.find(promotion.getId()).orElseThrow();

        assertThat(found.getName()).isEqualTo("11월 선착순 쿠폰");
        assertThat(found.getType()).isEqualTo(PromotionType.COUPON_ISSUE);
        assertThat(found.getStatus()).isEqualTo(PromotionStatus.ACTIVE);
        assertThat(found.getStartsAt().toInstant()).isEqualTo(STARTS_AT.toInstant());
        assertThat(found.getEndsAt().toInstant()).isEqualTo(ENDS_AT.toInstant());
    }

    @DisplayName("쿠폰 발급 설정을 promotionId 로 다시 읽으면 한도와 쿠폰 속성(중첩 객체·배열·숫자·문자열·불리언)이 그대로다.")
    @Test
    void savesAndFindsCouponIssue_withJsonSpec() {
        PromotionModel promotion = savedPromotion();
        Map<String, Object> spec = Map.of(
            "name", "3천원 할인",
            "discount", Map.of("type", "FIXED", "value", 3000),
            "validDays", 7,
            "stackable", false,
            "categories", List.of("shoes", "bags"));
        promotionRepository.saveCouponIssue(CouponIssuePromotionModel.create(promotion.getId(), 100, 1, spec));

        CouponIssuePromotionModel found = promotionRepository.findCouponIssue(promotion.getId()).orElseThrow();

        assertThat(found.getPromotionId()).isEqualTo(promotion.getId());
        assertThat(found.getTotalQuantity()).isEqualTo(100);
        assertThat(found.getPerUserLimit()).isEqualTo(1);
        assertThat(found.getCouponSpec()).isEqualTo(spec);
    }

    @DisplayName("없는 promotionId 로는 쿠폰 발급 설정을 찾지 않는다.")
    @Test
    void findCouponIssue_emptyWhenAbsent() {
        assertThat(promotionRepository.findCouponIssue(999L)).isEmpty();
        assertThat(promotionRepository.findCouponIssue(null)).isEmpty();
    }

    @DisplayName("발급 내역이 있는 (사용자, 프로모션) 만 existsIssuedCoupon 이 참이다.")
    @Test
    void existsIssuedCoupon_matchesUserAndPromotion() {
        PromotionModel promotion = savedPromotion();
        promotionRepository.insertIssuedCoupon(IssuedCouponModel.create(7L, promotion.getId())).orElseThrow();

        assertThat(promotionRepository.existsIssuedCoupon(7L, promotion.getId())).isTrue();
        assertThat(promotionRepository.existsIssuedCoupon(8L, promotion.getId())).isFalse();
        assertThat(promotionRepository.existsIssuedCoupon(7L, promotion.getId() + 1)).isFalse();
    }

    @DisplayName("한 프로모션에 쿠폰 발급 설정은 하나다. 같은 promotionId 로 두 번 저장하면 거절된다.")
    @Test
    void couponIssue_isOnePerPromotion() {
        PromotionModel promotion = savedPromotion();
        promotionRepository.saveCouponIssue(CouponIssuePromotionModel.create(promotion.getId(), 100, 1, Map.of("k", "v")));

        assertThatThrownBy(() -> promotionRepository.saveCouponIssue(
            CouponIssuePromotionModel.create(promotion.getId(), 50, 1, Map.of("k", "v2"))))
            .isInstanceOf(DataIntegrityViolationException.class);
    }
}
