package com.loopers.domains.coupon.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

// 동시 발급. 준비 데이터는 JDBC로 먼저 commit하고, 시작 장벽은 테스트 쪽에만 둔다(서비스 경로에는 장벽·sleep이 없다).
// 결과는 201·200(성공), 409(업무 거절), 그 밖의 상태·예외(기술 오류)로 모두 세어 합계를 맞춘다. 한 JVM의 여러 DB 연결이라 여러 서버 동시 실행은 증명하지 못한다.
class CouponIssueConcurrencyTest extends CouponIntegrationSupport {
    private static final long DEADLINE_SECONDS = 30;

    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("T3: 한 사용자가 10개 스레드로 동시에 요청하면 201은 정확히 1개, 나머지 9개는 200과 같은 couponId이고 쿠폰 1·발급 수 1이다")
    void sameUserConcurrentRequestsIssueOnce() throws Exception {
        user(1);
        long campaignId = campaign(true, 10, 0, 3000);

        List<Outcome> outcomes = runConcurrently(10, index -> 1L, campaignId);

        assertThat(outcomes).hasSize(10);
        assertThat(count(outcomes, 201)).isEqualTo(1);
        assertThat(count(outcomes, 200)).isEqualTo(9);
        assertThat(technical(outcomes)).isZero();
        assertThat(outcomes).extracting(Outcome::couponId).containsOnly(couponId(campaignId, 1));
        assertThat(coupons(campaignId)).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
    }

    @Test
    @DisplayName("T4: 총량 5인 캠페인에 서로 다른 8명이 동시에 요청하면 201 5개·409 3개·기술 오류 0이고 쿠폰 5·발급 수 5다")
    void quotaIsNeverExceededUnderConcurrency() throws Exception {
        for (long userId = 1; userId <= 8; userId++) {
            user(userId);
        }
        long campaignId = campaign(true, 5, 0, 3000);

        List<Outcome> outcomes = runConcurrently(8, index -> (long) index + 1, campaignId);

        assertThat(outcomes).hasSize(8);
        assertThat(count(outcomes, 201)).isEqualTo(5);
        assertThat(count(outcomes, 409)).isEqualTo(3);
        assertThat(technical(outcomes)).isZero();
        assertThat(coupons(campaignId)).isEqualTo(5);
        assertThat(issuedCount(campaignId)).isEqualTo(5);
    }

    private List<Outcome> runConcurrently(int threads, IntFunction<Long> userOf, long campaignId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<Future<Outcome>>();
            for (int index = 0; index < threads; index++) {
                long userId = userOf.apply(index);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
                        return Outcome.technical("시작 대기 시간 초과");
                    }
                    try {
                        MockHttpServletResponse response = issue(userId, campaignId).andReturn().getResponse();
                        JsonNode body = objectMapper.readTree(response.getContentAsString());
                        Long couponId = body.path("data").hasNonNull("couponId") ? body.path("data").path("couponId").asLong() : null;
                        return new Outcome(response.getStatus(), couponId, null);
                    } catch (Exception e) {
                        return Outcome.technical(e.toString());
                    }
                }));
            }
            assertThat(ready.await(DEADLINE_SECONDS, TimeUnit.SECONDS)).as("모든 스레드 준비").isTrue();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<Outcome>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(DEADLINE_SECONDS, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(DEADLINE_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static long count(List<Outcome> outcomes, int status) {
        return outcomes.stream().filter(outcome -> outcome.status() == status).count();
    }

    // 201·200·409가 아닌 응답과 예외는 모두 기술 오류다. 업무 거절로 세지 않는다.
    private static long technical(List<Outcome> outcomes) {
        return outcomes.stream().filter(outcome -> outcome.status() != 201 && outcome.status() != 200 && outcome.status() != 409).count();
    }

    record Outcome(int status, Long couponId, String error) {
        static Outcome technical(String error) {
            return new Outcome(-1, null, error);
        }
    }
}
