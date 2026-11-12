package com.loopers.application.coupon;

import com.loopers.config.CouponCacheProperties;
import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponCacheRepository;
import com.loopers.domain.coupon.CouponCacheRepository.IssuedCoupon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 캐시 서비스의 오류 변환(설계 §13.4·13.9, KQ6 의 서비스 부분). 저장소는 대역이며 실제 Redis 장애 재현이 아니다.
 * 로컬 품절 슬롯은 아직 없으므로(B단위) 모든 조회가 저장소로 간다.
 */
@DisplayName("캐시 서비스는 조회 오류를 miss 로, 저장 오류를 무시로 바꾸되 재시도하지 않고, Error 는 삼키지 않으며, 설정 TTL 로 저장한다.")
class CouponCacheServiceTest {

    private static final Long USER = 7L;
    private static final Long CAMPAIGN = 42L;
    private static final IssuedCoupon VALUE = new IssuedCoupon(9L, CAMPAIGN, "가을 캠페인", CouponBenefitType.PERCENTAGE, 10L);

    private CouponCacheRepository repository;
    private CouponCacheService service;

    @BeforeEach
    void setUp() {
        repository = mock(CouponCacheRepository.class);
        service = new CouponCacheService(repository, new CouponCacheProperties(Duration.ofMillis(1_500), Duration.ofSeconds(2)));
    }

    private static CouponIssueResult result(boolean created) {
        return new CouponIssueResult(9L, CAMPAIGN, "가을 캠페인", CouponBenefitType.PERCENTAGE, 10L, created);
    }

    @DisplayName("정상 hit·miss 는 그대로 전달한다. 품절 false 는 '캐시에서 확인 못함'이다.")
    @Test
    void passesThroughNormalResults() {
        when(repository.findIssued(USER, CAMPAIGN)).thenReturn(Optional.of(VALUE), Optional.empty());
        when(repository.findSoldOutTtl(CAMPAIGN)).thenReturn(OptionalLong.of(1_000L), OptionalLong.empty());

        assertAll(
            () -> assertThat(service.findIssued(USER, CAMPAIGN)).contains(VALUE),
            () -> assertThat(service.findIssued(USER, CAMPAIGN)).isEmpty(),
            () -> assertThat(service.isSoldOut(CAMPAIGN)).isTrue(),
            () -> assertThat(service.isSoldOut(CAMPAIGN)).isFalse()
        );
    }

    @DisplayName("저장은 created 를 버린 다섯 값과 각 설정 TTL 의 밀리초로 한 번만 부른다.")
    @Test
    void remembersWithConfiguredTtl() {
        service.rememberIssued(USER, CAMPAIGN, result(true));
        service.rememberSoldOut(CAMPAIGN);

        assertAll(
            () -> verify(repository, times(1)).putIssuedIfAbsent(USER, CAMPAIGN, VALUE, 1_500L),
            () -> verify(repository, times(1)).putSoldOutIfAbsent(CAMPAIGN, 2_000L)
        );
    }

    @DisplayName("조회 timeout·손상 예외는 miss·false 이고 각각 한 번만 시도한다.")
    @Test
    void readFailuresBecomeMiss() {
        when(repository.findIssued(USER, CAMPAIGN)).thenThrow(new QueryTimeoutException("timeout(대역)"));
        when(repository.findSoldOutTtl(CAMPAIGN)).thenThrow(new IllegalStateException("손상(대역)"));

        assertAll(
            () -> assertThat(service.findIssued(USER, CAMPAIGN)).isEmpty(),
            () -> assertThat(service.isSoldOut(CAMPAIGN)).isFalse(),
            () -> verify(repository, times(1)).findIssued(USER, CAMPAIGN),
            () -> verify(repository, times(1)).findSoldOutTtl(CAMPAIGN)
        );
    }

    @DisplayName("저장 오류는 무시하고 한 번만 시도한다. 값 불변식에 어긋난 결과는 저장소를 부르지 않는다.")
    @Test
    void writeFailuresAreIgnored() {
        doThrow(new QueryTimeoutException("timeout(대역)")).when(repository).putIssuedIfAbsent(any(), any(), any(), anyLong());
        doThrow(new QueryTimeoutException("timeout(대역)")).when(repository).putSoldOutIfAbsent(any(), anyLong());

        service.rememberIssued(USER, CAMPAIGN, result(false));
        service.rememberSoldOut(CAMPAIGN);
        service.rememberIssued(USER, CAMPAIGN, new CouponIssueResult(9L, CAMPAIGN, " 공백 ", CouponBenefitType.PERCENTAGE, 10L, false));
        service.rememberIssued(USER, CAMPAIGN, new CouponIssueResult(9L, CAMPAIGN, "가을", CouponBenefitType.PERCENTAGE, 101L, false));

        assertAll(
            () -> verify(repository, times(1)).putIssuedIfAbsent(any(), any(), any(), anyLong()),
            () -> verify(repository, times(1)).putSoldOutIfAbsent(any(), anyLong())
        );
    }

    @DisplayName("Error 는 miss·무시로 바꾸지 않고 그대로 올린다.")
    @Test
    void errorsPropagate() {
        when(repository.findIssued(USER, CAMPAIGN)).thenThrow(new OutOfMemoryError("대역"));
        doThrow(new StackOverflowError("대역")).when(repository).putSoldOutIfAbsent(any(), anyLong());

        assertThatThrownBy(() -> service.findIssued(USER, CAMPAIGN)).isInstanceOf(OutOfMemoryError.class);
        assertThatThrownBy(() -> service.rememberSoldOut(CAMPAIGN)).isInstanceOf(StackOverflowError.class);
        verify(repository, never()).putIssuedIfAbsent(any(), any(), any(), anyLong());
    }
}
