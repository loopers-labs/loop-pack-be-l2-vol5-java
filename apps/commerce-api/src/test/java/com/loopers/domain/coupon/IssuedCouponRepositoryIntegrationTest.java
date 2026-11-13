package com.loopers.domain.coupon;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 발급된 쿠폰 테이블이 실제 MySQL에서 지키는 규칙(docs/week8/decisions.md INV-802, INV-806, D-8, D-16).
 * - (campaign_id, user_id) unique 위반만 IssuedCouponDuplicateException으로 바뀌고, 다른 제약 위반은 그대로 나간다.
 * - 보유 목록은 요청자 것만, 발급 시각 내림차순, 같으면 id 내림차순이다.
 */
@SpringBootTest
class IssuedCouponRepositoryIntegrationTest {

    private static final ZonedDateTime T0 = ZonedDateTime.parse("2026-11-11T10:00:00Z");

    @Autowired
    private IssuedCouponRepository issuedCouponRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static IssuedCoupon coupon(Long campaignId, Long userId, ZonedDateTime issuedAt) {
        return IssuedCoupon.issue(campaignId, userId, CouponBenefit.of("5,000원 할인권", 5_000L), issuedAt);
    }

    @DisplayName("같은 캠페인·같은 사용자로 두 번째 쿠폰을 저장하면, IssuedCouponDuplicateException이고 행은 하나다.")
    @Test
    void campaignUserUniqueViolation_becomesDuplicateException() {
        issuedCouponRepository.save(coupon(1L, 7L, T0));

        IssuedCouponDuplicateException exception = assertThrows(IssuedCouponDuplicateException.class,
            () -> issuedCouponRepository.save(coupon(1L, 7L, T0.plusSeconds(1))));

        assertAll(
            () -> assertThat(exception.getCause()).isInstanceOf(DataIntegrityViolationException.class),
            () -> assertThat(count("SELECT COUNT(*) FROM issued_coupon WHERE campaign_id = 1 AND user_id = 7")).isEqualTo(1L)
        );
    }

    @DisplayName("다른 제약 위반(다른 이름의 unique)은 중복 발급으로 바뀌지 않고 DataIntegrityViolationException 그대로 나간다.")
    @Test
    void otherIntegrityViolation_isNotConvertedToDuplicate() {
        // 운영 스키마에는 다른 unique가 없으므로 이 테스트 동안만 하나 더 만든다. 같은 "Duplicate entry"(1062) 오류라도
        // 제약 이름이 다르면 기존 쿠폰 반환(200)으로 바뀌면 안 된다(D-8).
        jdbcTemplate.execute("CREATE UNIQUE INDEX uk_test_issued_coupon_issued_at ON issued_coupon (issued_at)");
        try {
            issuedCouponRepository.save(coupon(1L, 7L, T0));

            DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class,
                () -> issuedCouponRepository.save(coupon(2L, 8L, T0)));

            assertThat(exception).isNotInstanceOf(IssuedCouponDuplicateException.class);
            assertThat(count("SELECT COUNT(*) FROM issued_coupon")).isEqualTo(1L);
        } finally {
            jdbcTemplate.execute("DROP INDEX uk_test_issued_coupon_issued_at ON issued_coupon");
        }
    }

    @DisplayName("보유 목록은 그 사용자 것만, 발급 시각 내림차순이고 시각이 같으면 id 내림차순이다.")
    @Test
    void listsOnlyOwnCouponsInStableOrder() {
        Long first = issuedCouponRepository.save(coupon(1L, 7L, T0)).getId();
        Long sameTimeA = issuedCouponRepository.save(coupon(2L, 7L, T0.plusMinutes(5))).getId();
        Long sameTimeB = issuedCouponRepository.save(coupon(3L, 7L, T0.plusMinutes(5))).getId();
        Long latest = issuedCouponRepository.save(coupon(4L, 7L, T0.plusMinutes(10))).getId();
        issuedCouponRepository.save(coupon(1L, 8L, T0.plusMinutes(20)));

        List<IssuedCoupon> mine = issuedCouponRepository.findAllByUserId(7L);

        assertAll(
            () -> assertThat(mine).extracting(IssuedCoupon::getId).containsExactly(latest, sameTimeB, sameTimeA, first),
            () -> assertThat(mine).extracting(IssuedCoupon::getUserId).containsOnly(7L)
        );
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
