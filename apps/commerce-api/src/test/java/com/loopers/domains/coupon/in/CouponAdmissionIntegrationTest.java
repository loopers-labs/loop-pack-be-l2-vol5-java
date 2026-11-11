package com.loopers.domains.coupon.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domains.coupon.app.port.AdmissionUnavailableException;
import com.loopers.domains.coupon.out.RedisCouponAdmission;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 8주차 2단계: 새 발급 앞의 Redis 수용(②③⑤)과 수용 설정 행. 실제 Redis·MySQL 컨테이너를 쓰고, mock Redis·JVM 카운터로 대신하지 않는다.
// 붙잡기·예외 주입은 테스트 설정의 수용 포트 래퍼(실제 어댑터에 위임)로만 한다. 모든 대기에는 deadline이 있고, 실패하면 자기 실행을 풀고 정리한다.
// 실제 Redis 컨테이너 중단·재개는 3단계에서 본다.
class CouponAdmissionIntegrationTest extends CouponIntegrationSupport {
    private static final long DEADLINE_SECONDS = 20;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private RedisCouponAdmission redisCouponAdmission;
    @Autowired private RedisProperties redisProperties;

    @Test
    @DisplayName("A1~A4: 자리 3개에 세 명이 자리를 얻은 채 붙잡히면 Set 크기 3이고 네 번째는 429·발급 진입 0이며, 하나가 끝나 반환되면 네 번째가 201을 받고 모두 끝나면 Set이 빈다")
    void capacityLimitsConcurrentIssuesAndFreesOnRelease() throws Exception {
        for (long userId = 1; userId <= 4; userId++) {
            user(userId);
        }
        long campaignId = campaign(true, 10, 0, 3000);
        admissionSettings(3, true);
        CountDownLatch held = new CountDownLatch(3);
        Semaphore proceed = new Semaphore(0);
        AtomicInteger holds = new AtomicInteger(3);
        admission.onAcquired(() -> {
            if (holds.getAndDecrement() <= 0) {
                return;
            }
            held.countDown();
            try {
                proceed.tryAcquire(DEADLINE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            List<Future<Integer>> held3 = new ArrayList<Future<Integer>>();
            for (long userId = 1; userId <= 3; userId++) {
                long user = userId;
                held3.add(executor.submit(() -> issue(user, campaignId).andReturn().getResponse().getStatus()));
            }

            // A1
            assertThat(held.await(DEADLINE_SECONDS, TimeUnit.SECONDS)).as("세 요청이 자리를 얻고 붙잡힘").isTrue();
            assertThat(permits()).isEqualTo(3);

            // A2
            issue(4, campaignId).andExpect(status().isTooManyRequests());
            assertThat(failingCoupons.increaseIssuedCalls()).isZero();
            assertThat(couponId(campaignId, 4)).isNull();

            // A3
            proceed.release();
            int finished = awaitAny(held3);
            assertThat(finished).isEqualTo(201);
            assertThat(permits()).isEqualTo(2);
            issue(4, campaignId).andExpect(status().isCreated());
            assertThat(couponId(campaignId, 4)).isNotNull();

            // A4
            proceed.release(2);
            for (Future<Integer> future : held3) {
                assertThat(future.get(DEADLINE_SECONDS, TimeUnit.SECONDS)).isEqualTo(201);
            }
            assertThat(coupons(campaignId)).isEqualTo(4);
            assertThat(issuedCount(campaignId)).isEqualTo(4);
            assertThat(permits()).isZero();
        } finally {
            proceed.release(10);
            executor.shutdownNow();
            executor.awaitTermination(DEADLINE_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("A5: 자기 token을 두 번 반환하거나 모르는 token을 반환해도 다른 활성 token은 그대로 남는다")
    void releaseOnlyRemovesOwnToken() {
        redisCouponAdmission.acquire("token-a", 3);
        redisCouponAdmission.acquire("token-b", 3);

        redisCouponAdmission.release("token-a");
        redisCouponAdmission.release("token-a");
        redisCouponAdmission.release("unknown-token");

        assertThat(admissionRedis.opsForSet().members(RedisCouponAdmission.KEY)).containsExactly("token-b");
    }

    @Test
    @DisplayName("A6: 서로 다른 Redis 연결을 쓰는 수용 어댑터 두 개로 2개와 1개를 얻으면 어느 쪽으로든 네 번째는 거절된다(자리 상태는 JVM이 아니라 Redis에 있다)")
    void stateLivesInRedisAcrossConnections() {
        LettuceConnectionFactory firstConnection = connection();
        LettuceConnectionFactory secondConnection = connection();
        try {
            RedisCouponAdmission first = new RedisCouponAdmission(template(firstConnection));
            RedisCouponAdmission second = new RedisCouponAdmission(template(secondConnection));

            assertThat(first.acquire("a", 3)).isTrue();
            assertThat(first.acquire("b", 3)).isTrue();
            assertThat(second.acquire("c", 3)).isTrue();

            assertThat(first.acquire("d", 3)).isFalse();
            assertThat(second.acquire("e", 3)).isFalse();
            assertThat(permits()).isEqualTo(3);
        } finally {
            firstConnection.destroy();
            secondConnection.destroy();
        }
    }

    @Test
    @DisplayName("A7: 수용이 닫혀 있으면 새 발급은 503·Retry-After 30이고 Redis를 부르지 않으며 DB도 그대로다. 이미 받은 사용자는 200·목록이 그대로다")
    void closedAdmissionRejectsNewIssuesOnly() throws Exception {
        user(1);
        user(2);
        long campaignId = campaign(true, 10, 0, 3000);
        long owned = body(issue(1, campaignId).andExpect(status().isCreated())).path("data").path("couponId").asLong();
        admissionSettings(3, false);
        admission.disarm();

        issue(2, campaignId).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "30"));

        assertThat(admission.acquireInTransaction()).isEmpty();
        assertThat(couponId(campaignId, 2)).isNull();
        assertThat(issuedCount(campaignId)).isEqualTo(1);
        assertThat(body(issue(1, campaignId).andExpect(status().isOk())).path("data").path("couponId").asLong()).isEqualTo(owned);
        mine(1).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("A8: 획득 스크립트는 capacity가 0이거나 정수가 아니거나 token이 비어 있으면 오류를 내고 Set을 바꾸지 않는다")
    void acquireScriptRejectsInvalidInput() {
        admissionRedis.opsForSet().add(RedisCouponAdmission.KEY, "existing");
        RedisScript<Long> script = RedisScript.of(new ClassPathResource(RedisCouponAdmission.SCRIPT), Long.class);

        assertThatThrownBy(() -> admissionRedis.execute(script, List.of(RedisCouponAdmission.KEY), "0", "t1")).isNotNull();
        assertThatThrownBy(() -> admissionRedis.execute(script, List.of(RedisCouponAdmission.KEY), "1.5", "t2")).isNotNull();
        assertThatThrownBy(() -> admissionRedis.execute(script, List.of(RedisCouponAdmission.KEY), "3", "")).isNotNull();

        assertThat(admissionRedis.opsForSet().members(RedisCouponAdmission.KEY)).containsExactly("existing");
    }

    @Test
    @DisplayName("A9: 새 발급 한 번에서 자리 획득과 반환은 각각 한 번이고 둘 다 DB 트랜잭션 밖에서 일어난다")
    void acquireAndReleaseHappenOutsideTransaction() throws Exception {
        user(1);
        long campaignId = campaign(true, 10, 0, 3000);

        issue(1, campaignId).andExpect(status().isCreated());

        assertThat(admission.acquireInTransaction()).containsExactly(false);
        assertThat(admission.releaseInTransaction()).containsExactly(false);
        assertThat(permits()).isZero();
    }

    @Test
    @DisplayName("A10: 발급 트랜잭션이 409(자리 얻은 뒤 비활성)나 예외로 끝나도 원래 오류가 나가고 자리는 반환되어 Set이 빈다")
    void seatIsReleasedWhenIssueFails() throws Exception {
        user(1);
        user(2);
        long closing = campaign(true, 10, 0, 3000);
        admission.onAcquired(() -> jdbc.update("update coupon_campaign set active = false where id = ?", closing));

        issue(1, closing).andExpect(status().isConflict());

        assertThat(admission.releaseInTransaction()).containsExactly(false);
        assertThat(permits()).isZero();
        admission.disarm();
        long failing = campaign(true, 10, 0, 3000);
        failingCoupons.failNextInsertWith(new DataAccessResourceFailureException("주입된 DB 접근 실패"));

        issue(2, failing).andExpect(status().is5xxServerError());

        assertThat(admission.releaseInTransaction()).containsExactly(false);
        assertThat(permits()).isZero();
        assertThat(issuedCount(failing)).isZero();
    }

    @Test
    @DisplayName("A11: 발급 뒤 자리 반환이 실패해도 응답은 201이고 쿠폰·발급 수는 남으며, 수용 설정 행이 닫히고 이유에 release가 남는다")
    void releaseFailureKeepsResultAndClosesAdmission() throws Exception {
        user(1);
        long campaignId = campaign(true, 10, 0, 3000);
        admission.failNextReleaseWith(new AdmissionUnavailableException("주입된 반환 실패"));

        issue(1, campaignId).andExpect(status().isCreated());

        assertThat(couponId(campaignId, 1)).isNotNull();
        assertThat(issuedCount(campaignId)).isEqualTo(1);
        assertThat(admissionRow()).containsEntry("open", false);
        assertThat((String) admissionRow().get("closed_reason")).contains("release");
        assertThat(admissionRow().get("closed_at")).isNotNull();
    }

    @Test
    @DisplayName("A12: 자리 획득 중 Redis 오류가 나면 503이고 수용 설정 행이 닫히며(이유 acquire) 발급 트랜잭션은 시작하지 않는다")
    void acquireFailureClosesAdmissionWithoutIssuing() throws Exception {
        user(1);
        long campaignId = campaign(true, 10, 0, 3000);
        admission.failNextAcquireWith(new AdmissionUnavailableException("주입된 획득 실패"));

        issue(1, campaignId).andExpect(status().isServiceUnavailable());

        assertThat(admissionRow()).containsEntry("open", false);
        assertThat((String) admissionRow().get("closed_reason")).contains("acquire");
        assertThat(failingCoupons.increaseIssuedCalls()).isZero();
        assertThat(issuedCount(campaignId)).isZero();
        assertThat(coupons(campaignId)).isZero();
    }

    @Test
    @DisplayName("A13: 429는 Retry-After 1, 503은 Retry-After 30이고 둘 다 실패 응답이며 본문에 발급 성공·보장 표현이 없다")
    void rejectionResponsesCarryRetryAfterWithoutPromise() throws Exception {
        user(1);
        long campaignId = campaign(true, 10, 0, 3000);
        admissionSettings(1, true);
        admissionRedis.opsForSet().add(RedisCouponAdmission.KEY, "someone-else");

        MockHttpServletResponse tooMany = issue(1, campaignId).andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "1")).andReturn().getResponse();
        admissionSettings(1, false);
        MockHttpServletResponse unavailable = issue(1, campaignId).andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "30")).andReturn().getResponse();

        for (MockHttpServletResponse response : List.of(tooMany, unavailable)) {
            JsonNode body = objectMapper.readTree(response.getContentAsString());
            assertThat(body.path("meta").path("result").asText()).isEqualTo("FAIL");
            assertThat(body.path("meta").path("message").asText()).doesNotContain("보장").doesNotContain("발급되었").doesNotContain("성공");
        }
    }

    @Test
    @DisplayName("보조: 자리 3개에 서로 다른 token 10개가 동시에 획득하면 정확히 3개만 얻고 Set 크기는 3이다")
    void concurrentAcquireNeverExceedsCapacity() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<Future<Boolean>>();
            for (int index = 0; index < threads; index++) {
                Callable<Boolean> attempt = () -> {
                    ready.countDown();
                    if (!start.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("시작 대기 시간 초과");
                    }
                    return redisCouponAdmission.acquire(UUID.randomUUID().toString(), 3);
                };
                futures.add(executor.submit(attempt));
            }
            assertThat(ready.await(DEADLINE_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int acquired = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
                    acquired++;
                }
            }

            assertThat(acquired).isEqualTo(3);
            assertThat(permits()).isEqualTo(3);
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(DEADLINE_SECONDS, TimeUnit.SECONDS);
        }
    }

    private int awaitAny(List<Future<Integer>> futures) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        while (System.nanoTime() < deadline) {
            for (Future<Integer> future : futures) {
                if (future.isDone()) {
                    return future.get();
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("붙잡은 요청 하나가 " + DEADLINE_SECONDS + "초 안에 끝나지 않음");
    }

    private LettuceConnectionFactory connection() {
        RedisStandaloneConfiguration master = new RedisStandaloneConfiguration(redisProperties.master().host(),
            redisProperties.master().port());
        master.setDatabase(redisProperties.database());
        LettuceConnectionFactory factory = new LettuceConnectionFactory(master);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static RedisTemplate<String, String> template(LettuceConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<String, String>();
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setConnectionFactory(factory);
        template.afterPropertiesSet();
        return template;
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
