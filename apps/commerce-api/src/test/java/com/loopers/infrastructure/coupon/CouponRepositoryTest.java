package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 8주차 설계 §6 DB 설계. 총량 조건부 UPDATE·(campaign, user) UNIQUE·본인 목록 정렬을 저장 후 flush/clear 재조회로 확인한다.
 */
@SpringBootTest
class CouponRepositoryTest {

    private static final ZonedDateTime EXPIRES_AT = ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, ZoneId.of("Asia/Seoul"));

    private final CouponCampaignRepository couponCampaignRepository;
    private final CouponRepository couponRepository;
    private final EntityManager entityManager;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public CouponRepositoryTest(
        CouponCampaignRepository couponCampaignRepository,
        CouponRepository couponRepository,
        EntityManager entityManager,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.couponCampaignRepository = couponCampaignRepository;
        this.couponRepository = couponRepository;
        this.entityManager = entityManager;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private CouponCampaign saveCampaign(int totalQuota) {
        return couponCampaignRepository.save(
            new CouponCampaign("선착순 쿠폰", totalQuota, Coupon.BenefitType.FIXED, 1000L, EXPIRES_AT));
    }

    @DisplayName("캠페인을 저장할 때, ")
    @Nested
    class SaveCampaign {
        @DisplayName("flush·clear 후 재조회하면 활성 상태·총량·발급 수 0·혜택·만료 시각이 그대로 조회된다.")
        @Transactional
        @Test
        void persistsCampaign_whenReloadedAfterFlushAndClear() {
            CouponCampaign saved = saveCampaign(5);

            entityManager.flush();
            entityManager.clear();
            CouponCampaign found = couponCampaignRepository.findById(saved.getId()).orElseThrow();

            assertThat(found.getName()).isEqualTo("선착순 쿠폰");
            assertThat(found.isActive()).isTrue();
            assertThat(found.getTotalQuota()).isEqualTo(5);
            assertThat(found.getIssuedCount()).isZero();
            assertThat(found.getBenefitType()).isEqualTo(Coupon.BenefitType.FIXED);
            assertThat(found.getBenefitValue()).isEqualTo(1000L);
            assertThat(found.getCouponExpiresAt()).isEqualTo(EXPIRES_AT);
        }
    }

    @DisplayName("issued_count 를 조건부로 올릴 때, ")
    @Nested
    class IncreaseIssuedCount {
        @DisplayName("총량 2 이면 두 번은 1행이 갱신되고 세 번째는 0행이며, 재조회한 issued_count 는 2 다.")
        @Transactional
        @Test
        void stopsAtTotalQuota() {
            CouponCampaign saved = saveCampaign(2);
            entityManager.flush();
            entityManager.clear();

            int first = couponCampaignRepository.increaseIssuedCountIfAvailable(saved.getId());
            int second = couponCampaignRepository.increaseIssuedCountIfAvailable(saved.getId());
            int third = couponCampaignRepository.increaseIssuedCountIfAvailable(saved.getId());

            entityManager.clear();
            CouponCampaign found = couponCampaignRepository.findById(saved.getId()).orElseThrow();
            assertThat(first).isEqualTo(1);
            assertThat(second).isEqualTo(1);
            assertThat(third).isZero();
            assertThat(found.getIssuedCount()).isEqualTo(2);
        }

        @DisplayName("없는 캠페인이면 0행이 갱신된다.")
        @Transactional
        @Test
        void returnsZero_whenCampaignMissing() {
            assertThat(couponCampaignRepository.increaseIssuedCountIfAvailable(999L)).isZero();
        }
    }

    @DisplayName("쿠폰을 저장할 때, ")
    @Nested
    class SaveCoupon {
        @DisplayName("flush·clear 후 재조회하면 캠페인에서 복사한 혜택·만료 시각과 ISSUED 상태가 그대로 조회된다.")
        @Transactional
        @Test
        void persistsCoupon_whenReloadedAfterFlushAndClear() {
            CouponCampaign campaign = saveCampaign(5);
            ZonedDateTime issuedAt = ZonedDateTime.of(2026, 11, 13, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));
            couponRepository.save(new Coupon(campaign, 1L, issuedAt));

            entityManager.flush();
            entityManager.clear();
            Coupon found = couponRepository.findByCampaignIdAndUserId(campaign.getId(), 1L).orElseThrow();

            assertThat(found.getCampaignId()).isEqualTo(campaign.getId());
            assertThat(found.getUserId()).isEqualTo(1L);
            assertThat(found.getIssuedAt()).isEqualTo(issuedAt);
            assertThat(found.getExpiresAt()).isEqualTo(EXPIRES_AT);
            assertThat(found.getBenefitType()).isEqualTo(Coupon.BenefitType.FIXED);
            assertThat(found.getBenefitValue()).isEqualTo(1000L);
            assertThat(found.getStatus()).isEqualTo(Coupon.Status.ISSUED);
        }

        @DisplayName("다른 사용자의 쿠폰은 (campaign, user) 조회에 나타나지 않는다.")
        @Transactional
        @Test
        void doesNotExposeOtherUsersCoupon() {
            CouponCampaign campaign = saveCampaign(5);
            couponRepository.save(new Coupon(campaign, 1L, ZonedDateTime.now()));
            entityManager.flush();
            entityManager.clear();

            assertThat(couponRepository.findByCampaignIdAndUserId(campaign.getId(), 2L)).isEmpty();
        }

        /**
         * C5 식별 근거. 같은 (campaign, user) 를 두 번 INSERT 하면 어떤 예외가, 어떤 제약 이름으로 올라오는지 출력해 보고한다.
         * 트랜잭션 없이 실행해 INSERT 가 실제로 DB 에 나가도록 한다.
         */
        @DisplayName("같은 (campaign, user) 를 두 번 저장하면 DataIntegrityViolationException 이 나고, Hibernate 가 돌려주는 제약 이름은 테이블 접두가 붙은 coupon.uk_coupon_campaign_user 다.")
        @Test
        void throwsConstraintViolation_whenDuplicateCampaignUser() {
            CouponCampaign campaign = saveCampaign(5);
            couponRepository.save(new Coupon(campaign, 1L, ZonedDateTime.now()));

            Throwable thrown = catchThrowable(() -> couponRepository.save(new Coupon(campaign, 1L, ZonedDateTime.now())));

            Throwable root = NestedExceptionUtils.getMostSpecificCause(thrown);
            System.out.println("[C5] thrown=" + thrown.getClass().getName());
            System.out.println("[C5] root=" + root.getClass().getName() + " message=" + root.getMessage());
            Throwable cause = thrown;
            ConstraintViolationException hibernate = null;
            while (cause != null) {
                if (cause instanceof ConstraintViolationException cve) {
                    hibernate = cve;
                    break;
                }
                cause = cause.getCause();
            }
            System.out.println("[C5] hibernate ConstraintViolationException found=" + (hibernate != null)
                + " constraintName=" + (hibernate == null ? null : hibernate.getConstraintName()));

            assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(hibernate).isNotNull();
            // MySQL 8 은 'coupon.uk_coupon_campaign_user' 처럼 테이블 이름을 접두로 붙여 돌려준다. 4단계 식별 로직은 이 형식을 전제로 한다
            assertThat(hibernate.getConstraintName()).isEqualTo("coupon." + Coupon.UK_CAMPAIGN_USER);
        }
    }

    @DisplayName("본인 보유 목록을 조회할 때, ")
    @Nested
    class FindAllByUserId {
        @DisplayName("issuedAt 내림차순, 같은 시각이면 id 내림차순으로 정렬되고 타인의 쿠폰은 포함되지 않는다.")
        @Transactional
        @Test
        void ordersByIssuedAtDescThenIdDesc_andExcludesOthers() {
            CouponCampaign c1 = saveCampaign(5);
            CouponCampaign c2 = saveCampaign(5);
            CouponCampaign c3 = saveCampaign(5);
            ZonedDateTime older = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneId.of("Asia/Seoul"));
            ZonedDateTime newer = older.plusDays(1);
            Coupon oldOne = couponRepository.save(new Coupon(c1, 1L, older));
            Coupon newFirst = couponRepository.save(new Coupon(c2, 1L, newer));
            Coupon newSecond = couponRepository.save(new Coupon(c3, 1L, newer));
            couponRepository.save(new Coupon(c1, 2L, newer));
            entityManager.flush();
            entityManager.clear();

            List<Coupon> found = couponRepository.findAllByUserId(1L);

            assertThat(found).extracting(Coupon::getId)
                .containsExactly(newSecond.getId(), newFirst.getId(), oldOne.getId());
        }
    }
}
