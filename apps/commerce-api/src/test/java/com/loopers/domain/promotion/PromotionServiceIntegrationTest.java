package com.loopers.domain.promotion;

import com.loopers.infrastructure.promotion.IssuedCouponJpaRepository;
import com.loopers.infrastructure.promotion.PromotionJpaRepository;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.Map;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쿠폰 발급 프로모션 생성과 발급 (PromotionService). 실제 MySQL. 트랜잭션은 Facade 대신 TransactionTemplate 이 연다.
 * 실행: ./gradlew :apps:commerce-api:test --tests '*PromotionServiceIntegrationTest' (Docker 필요, Testcontainers).
 */
@SpringBootTest
class PromotionServiceIntegrationTest {

    private static final ZonedDateTime STARTS_AT = ZonedDateTime.parse("2026-11-10T10:00:00+09:00[Asia/Seoul]");
    private static final ZonedDateTime ENDS_AT = STARTS_AT.plusDays(7);
    private static final Map<String, Object> SPEC = Map.of("discountType", "FIXED", "discountValue", 3000);

    @Autowired
    private PromotionService promotionService;
    @Autowired
    private PromotionRepository promotionRepository;
    @Autowired
    private PromotionJpaRepository promotionJpaRepository;
    @Autowired
    private IssuedCouponJpaRepository issuedCouponJpaRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Long createPromotion(int totalQuantity) {
        return transactionTemplate.execute(s ->
            promotionService.createCouponIssue("11월 선착순 쿠폰", STARTS_AT, ENDS_AT, totalQuantity, 1, SPEC).promotion().getId());
    }

    private IssuedCouponModel issue(Long userId, Long promotionId) {
        return transactionTemplate.execute(s -> promotionService.issue(userId, promotionId));
    }

    private int remainingOf(Long promotionId) {
        return promotionRepository.findStock(promotionId).orElseThrow().getRemaining();
    }

    private long issuedCount(Long promotionId) {
        return issuedCouponJpaRepository.findAll().stream().filter(c -> c.getPromotionId().equals(promotionId)).count();
    }

    @DisplayName("생성하면 promotion 은 바로 ACTIVE 이고, 쿠폰 발급 설정과 남은 수량(= 발급 한도)이 함께 만들어진다.")
    @Test
    void createCouponIssue_createsActivePromotionWithSettingAndStock() {
        Long promotionId = createPromotion(10);

        PromotionModel promotion = promotionRepository.find(promotionId).orElseThrow();
        assertThat(promotion.getStatus()).isEqualTo(PromotionStatus.ACTIVE);
        assertThat(promotion.getType()).isEqualTo(PromotionType.COUPON_ISSUE);
        CouponIssuePromotionModel couponIssue = promotionRepository.findCouponIssue(promotionId).orElseThrow();
        assertThat(couponIssue.getTotalQuantity()).isEqualTo(10);
        assertThat(couponIssue.getCouponSpec()).isEqualTo(SPEC);
        assertThat(remainingOf(promotionId)).isEqualTo(10);
    }

    @DisplayName("쿠폰 발급 설정이 잘못되면 INVALID_PROMOTION 이고 promotion 도 남지 않는다.")
    @Test
    void createCouponIssue_rollsBackPromotionWhenSettingInvalid() {
        assertThrowsErrorType(() -> transactionTemplate.execute(s ->
            promotionService.createCouponIssue("p", STARTS_AT, ENDS_AT, 10, 2, SPEC)), ErrorType.INVALID_PROMOTION);

        assertThat(promotionJpaRepository.count()).isZero();
    }

    @DisplayName("발급하면 발급 내역이 한 행 생기고 남은 수량이 1 줄어든다.")
    @Test
    void issue_insertsCouponAndDecreasesStock() {
        Long promotionId = createPromotion(10);

        IssuedCouponModel issued = issue(7L, promotionId);

        assertThat(issued.getId()).isNotNull();
        assertThat(issued.getUserId()).isEqualTo(7L);
        assertThat(issued.getPromotionId()).isEqualTo(promotionId);
        assertThat(issued.getIssuedAt()).isNotNull();
        assertThat(remainingOf(promotionId)).isEqualTo(9);
    }

    @DisplayName("없는 프로모션은 PROMOTION_NOT_FOUND 다.")
    @Test
    void getIssuable_rejectsUnknownPromotion() {
        assertThrowsErrorType(() -> promotionService.getIssuable(999L), ErrorType.PROMOTION_NOT_FOUND);
    }

    @DisplayName("ACTIVE 가 아니면 PROMOTION_NOT_ACTIVE 이고 발급 내역·수량이 그대로다.")
    @Test
    void getIssuable_rejectsNotActivePromotion() {
        Long promotionId = createPromotion(10);
        transactionTemplate.executeWithoutResult(s -> {
            PromotionModel promotion = promotionRepository.find(promotionId).orElseThrow();
            promotion.pause();
            promotionRepository.save(promotion);
        });

        assertThrowsErrorType(() -> promotionService.getIssuable(promotionId), ErrorType.PROMOTION_NOT_ACTIVE);

        assertThat(issuedCount(promotionId)).isZero();
        assertThat(remainingOf(promotionId)).isEqualTo(10);
    }

    @DisplayName("[DR-60] 발급 전 확인: 받은 적 없으면 통과하고, 이미 받았으면 COUPON_ALREADY_ISSUED 다. 다른 프로모션은 따로 본다.")
    @Test
    void checkNotIssued_rejectsOnlyAlreadyIssuedUser() {
        Long promotionId = createPromotion(10);
        Long otherPromotionId = createPromotion(10);
        promotionService.checkNotIssued(7L, promotionId);
        issue(7L, promotionId);

        assertThrowsErrorType(() -> promotionService.checkNotIssued(7L, promotionId), ErrorType.COUPON_ALREADY_ISSUED);
        promotionService.checkNotIssued(8L, promotionId);
        promotionService.checkNotIssued(7L, otherPromotionId);
    }

    @DisplayName("같은 사용자가 다시 받으면 COUPON_ALREADY_ISSUED 이고 수량은 처음 한 번만 줄어 있다.")
    @Test
    void issue_rejectsSecondIssueForSameUser() {
        Long promotionId = createPromotion(10);
        issue(7L, promotionId);

        assertThrowsErrorType(() -> issue(7L, promotionId), ErrorType.COUPON_ALREADY_ISSUED);

        assertThat(issuedCount(promotionId)).isEqualTo(1);
        assertThat(remainingOf(promotionId)).isEqualTo(9);
    }

    @DisplayName("소진되면 COUPON_SOLD_OUT 이고, 먼저 넣은 발급 내역도 rollback 되어 남지 않는다.")
    @Test
    void issue_rejectsWhenSoldOut_andRollsBackIssuedCoupon() {
        Long promotionId = createPromotion(1);
        issue(7L, promotionId);

        assertThrowsErrorType(() -> issue(8L, promotionId), ErrorType.COUPON_SOLD_OUT);

        assertThat(issuedCount(promotionId)).isEqualTo(1);
        assertThat(issuedCouponJpaRepository.findAll()).extracting(IssuedCouponModel::getUserId).containsExactly(7L);
        assertThat(remainingOf(promotionId)).isZero();
    }
}
