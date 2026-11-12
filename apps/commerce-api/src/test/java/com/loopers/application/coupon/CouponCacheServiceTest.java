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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * 캐시 서비스의 오류 변환(설계 §13.4·13.9, KQ6 의 서비스 부분)과 로컬 품절 한 슬롯(§13.11, KQ7 의 서비스 부분).
 * 저장소는 대역이며 실제 Redis 장애 재현이 아니다. 로컬 슬롯은 서비스 인스턴스에 있으므로 사례마다 새 인스턴스로 시작한다.
 * 시간 경계는 짧은 TTL 과 실제 단조 시각·유한 대기로 확인하며, 판정에 수백 ms 이상의 여유를 둔다.
 */
@DisplayName("캐시 서비스는 조회 오류를 miss 로, 저장 오류를 무시로 바꾸되 재시도하지 않고, Error 는 삼키지 않으며, 설정 TTL 로 저장한다.")
class CouponCacheServiceTest {

    private static final Long USER = 7L;
    private static final Long CAMPAIGN = 42L;
    private static final IssuedCoupon VALUE = new IssuedCoupon(9L, CAMPAIGN, "가을 캠페인", CouponBenefitType.PERCENTAGE, 10L);
    private static final Long OTHER = 9_101L;

    private CouponCacheRepository repository;
    private CouponCacheService service;

    @BeforeEach
    void setUp() {
        repository = mock(CouponCacheRepository.class);
        service = newService(Duration.ofSeconds(5));
    }

    /** 같은 저장소 대역에 로컬 품절 TTL 만 다른 새 서비스. 슬롯을 다른 사례와 공유하지 않는다. */
    private CouponCacheService newService(Duration localSoldOutTtl) {
        return new CouponCacheService(repository, new CouponCacheProperties(Duration.ofMillis(1_500), Duration.ofSeconds(2), localSoldOutTtl));
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static void sleepUntil(long startedNanos, long millis) throws InterruptedException {
        long remaining = millis - elapsedMillis(startedNanos);
        if (remaining > 0) {
            Thread.sleep(remaining);
        }
    }

    private static CouponIssueResult result(boolean created) {
        return new CouponIssueResult(9L, CAMPAIGN, "가을 캠페인", CouponBenefitType.PERCENTAGE, 10L, created);
    }

    @DisplayName("정상 hit·miss 는 그대로 전달한다. 품절 false 는 '캐시에서 확인 못함'이다. 기발급에는 로컬이 없어 매번 저장소를 본다.")
    @Test
    void passesThroughNormalResults() {
        when(repository.findIssued(USER, CAMPAIGN)).thenReturn(Optional.of(VALUE), Optional.empty());
        when(repository.findSoldOutTtl(CAMPAIGN)).thenReturn(OptionalLong.of(1_000L));
        CouponCacheService missing = newService(Duration.ofSeconds(5));

        assertAll(
            () -> assertThat(service.findIssued(USER, CAMPAIGN)).contains(VALUE),
            () -> assertThat(service.findIssued(USER, CAMPAIGN)).isEmpty(),
            () -> verify(repository, times(2)).findIssued(USER, CAMPAIGN),
            () -> assertThat(service.isSoldOut(CAMPAIGN)).isTrue()
        );
        // 로컬이 비어 있는 새 인스턴스에서 Redis 없음은 false 다(원래 true→false 전달 검사의 miss 부분).
        when(repository.findSoldOutTtl(CAMPAIGN)).thenReturn(OptionalLong.empty());
        assertThat(missing.isSoldOut(CAMPAIGN)).isFalse();
        verify(repository, times(2)).findSoldOutTtl(CAMPAIGN);
    }

    @DisplayName("Redis 품절 true 를 읽으면 로컬을 채우고, 유효한 동안은 같은 캠페인 품절 조회에 Redis 를 부르지 않는다.")
    @Test
    void localHitSkipsRedis() {
        when(repository.findSoldOutTtl(CAMPAIGN)).thenReturn(OptionalLong.of(10_000L));

        assertAll(
            () -> assertThat(service.isSoldOut(CAMPAIGN)).isTrue(),
            () -> assertThat(service.isSoldOut(CAMPAIGN)).isTrue(),
            () -> assertThat(service.isSoldOut(CAMPAIGN)).isTrue(),
            () -> verify(repository, times(1)).findSoldOutTtl(CAMPAIGN)
        );
    }

    @DisplayName("로컬 수명은 첫 관찰부터 흐르고 조회 hit 으로 연장하지 않는다. 만료 뒤에는 Redis 를 다시 읽는다.")
    @Test
    void localExpiresWithoutExtension() throws InterruptedException {
        when(repository.findSoldOutTtl(CAMPAIGN)).thenReturn(OptionalLong.of(60_000L));
        CouponCacheService local = newService(Duration.ofMillis(2_000));

        long started = System.nanoTime();
        assertThat(local.isSoldOut(CAMPAIGN)).isTrue();
        sleepUntil(started, 1_000);
        long beforeHit = elapsedMillis(started);
        assertThat(local.isSoldOut(CAMPAIGN)).isTrue(); // 연장했다면 3초까지 유효해진다.
        verify(repository, times(1)).findSoldOutTtl(CAMPAIGN);

        sleepUntil(started, 2_500);
        assertThat(local.isSoldOut(CAMPAIGN)).isTrue();
        assertAll(
            () -> assertThat(beforeHit).as("중간 hit 은 첫 수명 안이어야 판정이 유효하다").isLessThan(2_000),
            () -> verify(repository, times(2)).findSoldOutTtl(CAMPAIGN)
        );
    }

    @DisplayName("Redis 남은 수명이 로컬 TTL 보다 짧으면 그 남은 수명이 로컬 상한이다.")
    @Test
    void shortRedisRemainingCapsLocal() throws InterruptedException {
        when(repository.findSoldOutTtl(CAMPAIGN)).thenReturn(OptionalLong.of(300L));

        long started = System.nanoTime();
        assertThat(service.isSoldOut(CAMPAIGN)).isTrue();
        sleepUntil(started, 1_000);
        assertThat(service.isSoldOut(CAMPAIGN)).isTrue();

        verify(repository, times(2)).findSoldOutTtl(CAMPAIGN); // 로컬 5초였다면 Redis 를 다시 읽지 않는다.
    }

    @DisplayName("조회 시작부터의 지연이 Redis 남은 수명을 다 쓰면 품절로 쓰지 않고 로컬도 채우지 않는다.")
    @Test
    void exhaustedBudgetDoesNotFill() {
        when(repository.findSoldOutTtl(CAMPAIGN)).thenAnswer(invocation -> {
            Thread.sleep(300);
            return OptionalLong.of(100L);
        });

        assertAll(
            () -> assertThat(service.isSoldOut(CAMPAIGN)).isFalse(),
            () -> assertThat(service.isSoldOut(CAMPAIGN)).isFalse(),
            () -> verify(repository, times(2)).findSoldOutTtl(CAMPAIGN)
        );
    }

    @DisplayName("없음·0·변환 overflow·조회 오류는 false 이고 로컬을 채우지 않는다.")
    @Test
    void missOverflowAndErrorDoNotFill() {
        when(repository.findSoldOutTtl(CAMPAIGN)).thenReturn(OptionalLong.empty(), OptionalLong.of(0L), OptionalLong.of(Long.MAX_VALUE))
            .thenThrow(new QueryTimeoutException("timeout(대역)"))
            .thenReturn(OptionalLong.empty());

        List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(service.isSoldOut(CAMPAIGN));
        }

        assertAll(
            () -> assertThat(results).containsOnly(false),
            () -> verify(repository, times(5)).findSoldOutTtl(CAMPAIGN)
        );
    }

    @DisplayName("슬롯은 하나다. 다른 캠페인을 채우면 교체되고, 원래 캠페인은 다시 Redis 를 읽는다. ID 는 값으로 비교한다.")
    @Test
    void singleSlotReplacedByOtherCampaign() {
        when(repository.findSoldOutTtl(CAMPAIGN)).thenReturn(OptionalLong.of(10_000L));
        when(repository.findSoldOutTtl(OTHER)).thenReturn(OptionalLong.of(10_000L));

        assertAll(
            () -> assertThat(service.isSoldOut(Long.valueOf(OTHER))).isTrue(),
            () -> assertThat(service.isSoldOut(Long.valueOf(OTHER))).as("128 이상 ID 도 새 Long 으로 hit").isTrue(),
            () -> assertThat(service.isSoldOut(CAMPAIGN)).isTrue(),
            () -> assertThat(service.isSoldOut(CAMPAIGN)).isTrue(),
            () -> assertThat(service.isSoldOut(Long.valueOf(OTHER))).isTrue(),
            () -> verify(repository, times(2)).findSoldOutTtl(OTHER),
            () -> verify(repository, times(1)).findSoldOutTtl(CAMPAIGN)
        );
    }

    @DisplayName("동시 miss 는 각자 Redis 를 읽는다. 늦게 온 응답은 조회 시작부터의 경과를 빼서 자기 Redis 남은 수명 안에서만 로컬을 둔다.")
    @Test
    void concurrentLateResponseKeepsItsOwnBound() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.findSoldOutTtl(CAMPAIGN)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("해제 대기 초과(대역)");
            }
            return OptionalLong.of(2_000L);
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> futures = new ArrayList<>();
        long started;
        try {
            started = System.nanoTime();
            futures.add(workers.submit(() -> service.isSoldOut(CAMPAIGN)));
            futures.add(workers.submit(() -> service.isSoldOut(CAMPAIGN)));
            assertThat(entered.await(10, TimeUnit.SECONDS)).as("두 요청 모두 Redis 조회에 들어갔다").isTrue();
            sleepUntil(started, 1_000); // 응답 지연. 남은 2초 중 약 1초를 쓴다.
            release.countDown();
            for (Future<Boolean> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).as("worker 종료").isTrue();
        }
        verify(repository, times(2)).findSoldOutTtl(CAMPAIGN);

        // 지연을 빼지 않았다면 로컬은 관찰(약 1초) 뒤 2초, 즉 약 3초까지 유효하다. 빼면 조회 시작부터 약 2초에 끝난다.
        sleepUntil(started, 2_500);
        when(repository.findSoldOutTtl(CAMPAIGN)).thenReturn(OptionalLong.empty());
        assertThat(service.isSoldOut(CAMPAIGN)).isFalse();
        verify(repository, times(3)).findSoldOutTtl(CAMPAIGN);
    }

    @DisplayName("rememberSoldOut 은 성공·실패와 무관하게 Redis 저장만 하고 로컬을 채우지 않는다.")
    @Test
    void rememberSoldOutDoesNotFillLocal() {
        when(repository.findSoldOutTtl(CAMPAIGN)).thenReturn(OptionalLong.empty());

        service.rememberSoldOut(CAMPAIGN);
        boolean afterSuccess = service.isSoldOut(CAMPAIGN);
        doThrow(new QueryTimeoutException("timeout(대역)")).when(repository).putSoldOutIfAbsent(any(), anyLong());
        service.rememberSoldOut(CAMPAIGN);
        boolean afterFailure = service.isSoldOut(CAMPAIGN);

        assertAll(
            () -> assertThat(afterSuccess).isFalse(),
            () -> assertThat(afterFailure).isFalse(),
            () -> verify(repository, times(2)).putSoldOutIfAbsent(CAMPAIGN, 2_000L),
            () -> verify(repository, times(2)).findSoldOutTtl(CAMPAIGN)
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
