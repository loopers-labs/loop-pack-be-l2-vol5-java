package com.loopers.infrastructure.coupon;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponCampaignIssueData;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponModel;
import com.loopers.domain.coupon.CouponQueryRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.OwnedCoupon;
import com.loopers.fixture.CouponFixture;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 본인 쿠폰 조회의 DB 계약(상세설계 4.1·4.2·5.2, Q1·Q2·Q15 의 DB 부분). 응답 JSON 형식·HTTP 상태는 검증하지 않는다.
 */
@SpringBootTest
@DisplayName("본인 쿠폰 조회는 본인 필터·안정 정렬·총건수·현재 이름/과거 혜택·저장 시각을 지키고 연결 손상을 숨기지 않는다.")
class CouponQueryRepositoryIntegrationTest {

    private static final DateTimeFormatter MICROS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");

    @Autowired
    private CouponQueryRepository queryRepository;
    @Autowired
    private CouponCampaignRepository campaignRepository;
    @Autowired
    private CouponRepository couponRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private CouponFixture fixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("findOwned 는 본인 쿠폰만 반환하고 타인·미발급은 빈 값이다.")
    @Test
    void findOwnedFiltersByUser() {
        List<Long> users = fixture.users(2);
        Long campaignId = fixture.activeCampaign("캠페인", 5L, CouponBenefitType.PERCENTAGE, 10L);
        Long couponId = issue(users.get(0), campaignId);

        Optional<OwnedCoupon> mine = read(() -> queryRepository.findOwned(users.get(0), campaignId));

        assertAll(
            () -> assertThat(mine).hasValueSatisfying(owned -> {
                assertThat(owned.couponId()).isEqualTo(couponId);
                assertThat(owned.campaignId()).isEqualTo(campaignId);
                assertThat(owned.campaignName()).isEqualTo("캠페인");
                assertThat(owned.benefitType()).isEqualTo(CouponBenefitType.PERCENTAGE);
                assertThat(owned.benefitValue()).isEqualTo(10L);
            }),
            () -> assertThat(read(() -> queryRepository.findOwned(users.get(1), campaignId))).isEmpty(),
            () -> assertThat(read(() -> queryRepository.findOwned(users.get(0), 999_999L))).isEmpty()
        );
    }

    @DisplayName("목록은 본인 것만 총건수와 함께 반환하고, 쿠폰이 없으면 빈 목록·총건수 0 이다.")
    @Test
    void pageFiltersByUserAndCounts() {
        List<Long> users = fixture.users(3);
        Long first = fixture.activeCampaign(10L);
        Long second = fixture.activeCampaign(10L);
        issue(users.get(0), first);
        issue(users.get(0), second);
        issue(users.get(1), first);

        PageResult<OwnedCoupon> mine = read(() -> queryRepository.findOwnedPage(users.get(0), PageCommand.of(null, null), ListSort.LATEST));
        PageResult<OwnedCoupon> empty = read(() -> queryRepository.findOwnedPage(users.get(2), PageCommand.of(null, null), ListSort.LATEST));

        assertAll(
            () -> assertThat(mine.items()).extracting(OwnedCoupon::campaignId).containsExactlyInAnyOrder(first, second),
            () -> assertThat(mine.totalElements()).isEqualTo(2L),
            () -> assertThat(mine.totalPages()).isEqualTo(1),
            () -> assertThat(empty.items()).isEmpty(),
            () -> assertThat(empty.totalElements()).isZero(),
            () -> assertThat(empty.totalPages()).isZero()
        );
    }

    @DisplayName("latest 는 발급 시각·ID 내림차순, oldest 는 오름차순이며 같은 시각은 ID 로 정렬하고 페이지가 겹치지 않는다.")
    @Test
    void stableSortAndPaging() {
        Long userId = fixture.user();
        Long[] coupons = new Long[5];
        for (int i = 0; i < 5; i++) {
            coupons[i] = issue(userId, fixture.activeCampaign(1L));
        }
        // c0 가장 이른 시각, c1·c2·c3 같은 시각, c4 가장 늦은 시각
        fixture.setCouponCreatedAt(coupons[0], "2026-11-10 09:00:00.000001");
        fixture.setCouponCreatedAt(coupons[1], "2026-11-10 09:00:00.500000");
        fixture.setCouponCreatedAt(coupons[2], "2026-11-10 09:00:00.500000");
        fixture.setCouponCreatedAt(coupons[3], "2026-11-10 09:00:00.500000");
        fixture.setCouponCreatedAt(coupons[4], "2026-11-10 09:00:01.000000");

        List<Long> latest = ids(read(() -> queryRepository.findOwnedPage(userId, PageCommand.of(0, 5), ListSort.LATEST)));
        List<Long> oldest = ids(read(() -> queryRepository.findOwnedPage(userId, PageCommand.of(0, 5), ListSort.OLDEST)));
        PageResult<OwnedCoupon> page0 = read(() -> queryRepository.findOwnedPage(userId, PageCommand.of(0, 2), ListSort.LATEST));
        PageResult<OwnedCoupon> page1 = read(() -> queryRepository.findOwnedPage(userId, PageCommand.of(1, 2), ListSort.LATEST));
        PageResult<OwnedCoupon> page2 = read(() -> queryRepository.findOwnedPage(userId, PageCommand.of(2, 2), ListSort.LATEST));
        PageResult<OwnedCoupon> beyond = read(() -> queryRepository.findOwnedPage(userId, PageCommand.of(3, 2), ListSort.LATEST));

        assertAll(
            () -> assertThat(latest).containsExactly(coupons[4], coupons[3], coupons[2], coupons[1], coupons[0]),
            () -> assertThat(oldest).containsExactly(coupons[0], coupons[1], coupons[2], coupons[3], coupons[4]),
            () -> assertThat(ids(page0)).containsExactly(coupons[4], coupons[3]),
            () -> assertThat(ids(page1)).containsExactly(coupons[2], coupons[1]),
            () -> assertThat(ids(page2)).containsExactly(coupons[0]),
            () -> assertThat(beyond.items()).isEmpty(),
            () -> assertThat(page0.totalElements()).isEqualTo(5L),
            () -> assertThat(page0.totalPages()).isEqualTo(3)
        );
    }

    @DisplayName("캠페인 이름은 조회 시 현재 값, 혜택은 발급 당시 값이며 비활성·품절 캠페인의 쿠폰도 목록에 남는다.")
    @Test
    void currentNameAndPastBenefit() {
        Long userId = fixture.user();
        Long campaignId = fixture.activeCampaign("처음 이름", 1L, CouponBenefitType.FIXED_AMOUNT, 5_000L);
        Long couponId = issue(userId, campaignId);

        fixture.rename(campaignId, "바뀐 이름");
        fixture.changeBenefit(campaignId, CouponBenefitType.PERCENTAGE, 30L);
        jdbcTemplate.update("UPDATE coupon_campaign SET status = 'INACTIVE' WHERE id = ?", campaignId);

        OwnedCoupon owned = read(() -> queryRepository.findOwned(userId, campaignId)).orElseThrow();
        PageResult<OwnedCoupon> page = read(() -> queryRepository.findOwnedPage(userId, PageCommand.of(null, null), ListSort.LATEST));

        assertAll(
            () -> assertThat(owned.couponId()).isEqualTo(couponId),
            () -> assertThat(owned.campaignName()).isEqualTo("바뀐 이름"),
            () -> assertThat(owned.benefitType()).isEqualTo(CouponBenefitType.FIXED_AMOUNT),
            () -> assertThat(owned.benefitValue()).isEqualTo(5_000L),
            () -> assertThat(page.items()).singleElement().isEqualTo(owned)
        );
    }

    @DisplayName("조회 시각은 DB 에 저장된 created_at 과 마이크로초까지 같고 반복 조회에서 변하지 않는다.")
    @Test
    void storedCreatedAtIsStable() {
        Long userId = fixture.user();
        Long campaignId = fixture.activeCampaign(1L);
        Long couponId = issue(userId, campaignId);
        String stored = fixture.storedCreatedAt(couponId);

        OwnedCoupon first = read(() -> queryRepository.findOwned(userId, campaignId)).orElseThrow();
        OwnedCoupon second = read(() -> queryRepository.findOwnedPage(userId, PageCommand.of(null, null), ListSort.LATEST))
            .items().get(0);
        System.out.println("[COUPON CREATED_AT] stored=" + stored + " read=" + first.createdAt());

        assertAll(
            () -> assertThat(utcMicros(first.createdAt())).isEqualTo(stored),
            () -> assertThat(second.createdAt().toInstant()).isEqualTo(first.createdAt().toInstant())
        );
    }

    @DisplayName("연결 캠페인이 없으면 findOwned 는 빈 값으로 숨기지 않고 INTERNAL_ERROR, 목록도 쿠폰을 빼지 않고 INTERNAL_ERROR 다.")
    @Test
    void brokenCampaignLinkFailsExplicitly() {
        Long userId = fixture.user();
        Long broken = fixture.activeCampaign(1L);
        Long healthy = fixture.activeCampaign(1L);
        issue(userId, broken);
        issue(userId, healthy);
        fixture.breakCampaignLink(broken);

        assertAll(
            () -> assertThatThrownBy(() -> read(() -> queryRepository.findOwned(userId, broken)))
                .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(() -> read(() -> queryRepository.findOwnedPage(userId, PageCommand.of(null, null), ListSort.LATEST)))
                .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThat(read(() -> queryRepository.findOwned(userId, healthy))).isPresent(),
            () -> assertThat(fixture.couponCount(broken)).as("쿠폰 행은 그대로 남음").isEqualTo(1L)
        );
    }

    private Long issue(Long userId, Long campaignId) {
        return transactionTemplate.execute(status -> {
            if (campaignRepository.reserveOne(campaignId, ZonedDateTime.now()) != 1) {
                throw new IllegalStateException("발급 준비 실패");
            }
            CouponCampaignIssueData data = campaignRepository.findIssueData(campaignId).orElseThrow();
            return couponRepository.save(CouponModel.issue(userId, campaignId, data.benefitType(), data.benefitValue())).getId();
        });
    }

    private <T> T read(java.util.function.Supplier<T> query) {
        return transactionTemplate.execute(status -> query.get());
    }

    private static List<Long> ids(PageResult<OwnedCoupon> page) {
        return page.items().stream().map(OwnedCoupon::couponId).toList();
    }

    private static String utcMicros(ZonedDateTime value) {
        return value.withZoneSameInstant(ZoneOffset.UTC).format(MICROS);
    }
}
