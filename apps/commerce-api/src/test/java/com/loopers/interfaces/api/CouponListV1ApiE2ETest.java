package com.loopers.interfaces.api;

import com.loopers.domain.coupon.CouponModel;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.interfaces.api.coupon.CouponV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponListV1ApiE2ETest {

    private static final ZonedDateTime BASE = ZonedDateTime.parse("2026-01-01T00:00:00+09:00");

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.CouponListResponse>> getCoupons(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set("X-USER-ID", String.valueOf(userId));
        }
        return testRestTemplate.exchange(
            "/api/v1/coupons", HttpMethod.GET, new HttpEntity<>(null, headers),
            new ParameterizedTypeReference<>() {}
        );
    }

    @DisplayName("쿠폰이 없는 사용자는, 빈 목록을 받는다.")
    @Test
    void returnsEmptyList_whenNoCoupons() {
        // act
        var response = getCoupons(1L);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().coupons()).isEmpty();
    }

    @DisplayName("본인의 쿠폰만, 발급 시각 내림차순(같으면 couponId 내림차순)으로 받는다.")
    @Test
    void returnsOnlyOwnCoupons_sortedByIssuedAtThenIdDesc() {
        // arrange — 사용자 1: 오래된 것 → 같은 시각 두 장 → 최근 것, 사용자 2: 한 장
        Long oldest = couponJpaRepository.save(new CouponModel(1L, 1L, "오래된", BASE)).getId();
        Long sameTimeFirst = couponJpaRepository.save(new CouponModel(2L, 1L, "같은 시각 먼저", BASE.plusDays(1))).getId();
        Long sameTimeSecond = couponJpaRepository.save(new CouponModel(3L, 1L, "같은 시각 나중", BASE.plusDays(1))).getId();
        Long newest = couponJpaRepository.save(new CouponModel(4L, 1L, "최근", BASE.plusDays(2))).getId();
        Long othersCoupon = couponJpaRepository.save(new CouponModel(1L, 2L, "남의 쿠폰", BASE.plusDays(3))).getId();

        // act
        var mine = getCoupons(1L);
        var others = getCoupons(2L);

        // assert
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody().data().coupons()).extracting(CouponV1Dto.CouponResponse::couponId)
            .containsExactly(newest, sameTimeSecond, sameTimeFirst, oldest)
            .doesNotContain(othersCoupon);
        assertThat(others.getBody().data().coupons()).extracting(CouponV1Dto.CouponResponse::couponId)
            .containsExactly(othersCoupon);
    }

    @DisplayName("X-USER-ID 헤더가 없으면, 400으로 거절한다.")
    @Test
    void rejectsRequestWithoutUserHeader() {
        // act
        var response = getCoupons(null);

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
