package com.loopers.infrastructure.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponIssueGate;
import com.loopers.domain.coupon.CouponIssueGate.Decision;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.testcontainers.RedisTestContainersConfig;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앱 서버가 두 대일 때 판단 정보를 어디에 두느냐에 따라 결과가 갈리는지 본다.
 * 서버 1·2를 판단 객체 두 개로 흉내 낸다. 둘 다 같은 DB 값(남은 2장, 발급자 없음)으로 준비한다.
 * 앱 메모리 객체는 각자 자기 메모리를 가지고, Redis 객체는 같은 Redis를 본다.
 */
@SpringBootTest
@Import({MySqlTestContainersConfig.class, RedisTestContainersConfig.class})
class CouponIssueGateTwoServersTest {
    private static final long CAMPAIGN = 7_777_777L;
    private static final long A = 1L;
    private static final long B = 2L;
    private static final long C = 3L;
    private final Instant now = Instant.now();
    private final Instant endAt = now.plusSeconds(3600);
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> redis;

    @AfterEach
    void cleanUp() {
        redis.delete(List.of(RedisCouponIssueGate.stockKey(CAMPAIGN), RedisCouponIssueGate.usersKey(CAMPAIGN)));
    }

    /**
     * 앱 메모리: 2장 남았는데 A·C는 서버 1, B는 서버 2로 동시에 들어온다. 서버마다 "2장 남음"을 따로 세므로 셋 다 통과한다.
     * 셋째 통과자는 DB 조건부 UPDATE에서야 거절된다.
     */
    @Test
    void localMemoryAdmitsThreeWhenTwoRemainAcrossTwoServers() throws Exception {
        CouponIssueGate server1 = prepared(new LocalCouponIssueGate());
        CouponIssueGate server2 = prepared(new LocalCouponIssueGate());

        Map<Long, Decision> decisions = enterAtOnce(Map.of(A, server1, B, server2, C, server1));

        assertThat(decisions.values()).containsOnly(Decision.PASSED);
    }

    /** Redis: 같은 조건에서 두 서버가 같은 남은 수를 보므로 두 명만 통과하고 한 명은 매진이다. */
    @Test
    void sharedRedisAdmitsOnlyTwoWhenTwoRemainAcrossTwoServers() throws Exception {
        CouponIssueGate server1 = prepared(new RedisCouponIssueGate(redis));
        CouponIssueGate server2 = prepared(new RedisCouponIssueGate(redis));

        Map<Long, Decision> decisions = enterAtOnce(Map.of(A, server1, B, server2, C, server1));

        assertThat(decisions.values()).filteredOn(Decision.PASSED::equals).hasSize(2);
        assertThat(decisions.values()).filteredOn(Decision.SOLD_OUT::equals).hasSize(1);
    }

    /** 앱 메모리: A가 서버 1에서 통과한 뒤 다시 누른 요청이 서버 2로 가면, 서버 2는 A를 모르므로 또 통과한다. */
    @Test
    void localMemoryAdmitsTheSameUserAgainOnTheOtherServer() {
        CouponIssueGate server1 = prepared(new LocalCouponIssueGate());
        CouponIssueGate server2 = prepared(new LocalCouponIssueGate());

        assertThat(server1.enter(CAMPAIGN, A, now).decision()).isEqualTo(Decision.PASSED);
        assertThat(server2.enter(CAMPAIGN, A, now).decision()).isEqualTo(Decision.PASSED);
    }

    /** Redis: 서버 2도 A의 기록을 보므로 처리 중으로 걸러진다. */
    @Test
    void sharedRedisRecognizesTheSameUserOnTheOtherServer() {
        CouponIssueGate server1 = prepared(new RedisCouponIssueGate(redis));
        CouponIssueGate server2 = prepared(new RedisCouponIssueGate(redis));

        assertThat(server1.enter(CAMPAIGN, A, now).decision()).isEqualTo(Decision.PASSED);
        assertThat(server2.enter(CAMPAIGN, A, now).decision()).isEqualTo(Decision.ALREADY_RESERVED);
    }

    // 운영에서는 서버마다 시작할 때 같은 DB 값으로 준비한다. Redis는 같은 키를 다시 채울 뿐이다.
    private CouponIssueGate prepared(CouponIssueGate server) {
        server.prepare(CAMPAIGN, 2, List.of(), endAt, endAt.plusSeconds(3600));
        return server;
    }

    /** 사용자마다 정해진 서버로 동시에 요청을 보내고 판단 결과를 모은다. */
    private Map<Long, Decision> enterAtOnce(Map<Long, CouponIssueGate> routes) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(routes.size());
        try {
            Map<Long, Future<Decision>> futures = new HashMap<>();
            routes.forEach((userId, server) -> futures.put(userId, executor.submit((Callable<Decision>) () -> {
                start.await();
                return server.enter(CAMPAIGN, userId, now).decision();
            })));
            start.countDown();
            Map<Long, Decision> decisions = new HashMap<>();
            for (var entry : futures.entrySet()) {
                decisions.put(entry.getKey(), entry.getValue().get(10, TimeUnit.SECONDS));
            }
            return decisions;
        } finally {
            executor.shutdownNow();
        }
    }
}
