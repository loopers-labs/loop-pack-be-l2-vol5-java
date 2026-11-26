package com.loopers.domains.payment.in;

import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderApplicationService;
import com.loopers.domains.payment.app.port.LookupJitter;
import com.loopers.domains.payment.app.port.PaymentRefundCaseRepository;
import com.loopers.domains.payment.domain.RefundCase;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

// 같은 시각에 만료된 여러 결제의 첫 조회 시각이 공용 LookupJitter로 흩어지는지 실제 DB로 본다(10주차 5b 계획 3-1, S10).
// jitter 대역은 만료 동안만 0 → 상한의 절반 → 상한을 차례로 골라, 하한(now)·상한(now+1분)과 상한 1분 전달을 정확한 값으로 확인한다.
// 만료 밖(결제 조회 일정)에서 부르는 jitter는 상한을 돌려 기존 흐름에 영향을 주지 않는다.
// 전용 테스트 설정이라 캐시에 남으면 DB 연결을 계속 붙잡는다(전체 검사에서 Too many connections). 클래스가 끝나면 닫는다.
@DirtiesContext
@SpringBootTest
@Import(RefundCheckJitterIntegrationTest.JitterConfig.class)
class RefundCheckJitterIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final Instant EXPIRES_AT = NOW.plus(Duration.ofMinutes(2));

    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private PaymentRefundCaseRepository refundCases;
    @Autowired private ScriptedJitter jitter;
    @Autowired private MutablePaymentClock clock;
    @Autowired private TestPgFixture pg;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    private TestPgServer server;

    @BeforeEach
    void prepare() {
        clock.set(NOW);
        server = pg.start();
        users.save(new UserJpaEntity(1));
    }

    @AfterEach
    void clean() throws Exception {
        jitter.disarm();
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("같은 시각에 만료된 결제 3건의 첫 조회 시각은 상한 1분으로 jitter가 고른 값이라 now·now+30초·now+1분으로 흩어진다")
    void spreadsFirstCheckOfSimultaneousExpiries() {
        long brand = brands.create("브랜드").id();
        long productId = products.create(brand, "상품", 1000, 10).id();
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        List<Long> orderIds = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            long orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1))).id();
            orders.confirm(1, orderId, TestPgFixture.command());
            orderIds.add(orderId);
        }
        clock.set(EXPIRES_AT);
        jitter.arm();

        orders.expireOverduePayments();

        assertThat(jitter.maxima()).containsExactly(60_000L, 60_000L, 60_000L);
        List<Instant> nextChecks = orderIds.stream()
            .map(orderId -> refundCases.findByOrderId(orderId).orElseThrow())
            .map(RefundCase::getNextCheckAt)
            .toList();
        assertThat(nextChecks).containsExactlyInAnyOrder(EXPIRES_AT, EXPIRES_AT.plusSeconds(30), EXPIRES_AT.plusSeconds(60));
        assertThat(nextChecks).allSatisfy(nextCheck -> assertThat(nextCheck)
            .isBetween(EXPIRES_AT, EXPIRES_AT.plus(RefundCase.FIRST_CHECK_DELAY)));
    }

    @TestConfiguration
    static class JitterConfig {
        @Bean
        @Primary
        MutablePaymentClock refundJitterClock() {
            return new MutablePaymentClock();
        }

        @Bean
        @Primary
        ScriptedJitter scriptedJitter() {
            return new ScriptedJitter();
        }
    }

    // 무장 동안만 0 → 절반 → 상한을 차례로 고르고 받은 상한을 남긴다. 무장 밖에서는 상한을 그대로 고른다.
    static class ScriptedJitter implements LookupJitter {
        private final AtomicBoolean armed = new AtomicBoolean();
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Long> maxima = new CopyOnWriteArrayList<>();

        void arm() {
            maxima.clear();
            calls.set(0);
            armed.set(true);
        }

        void disarm() {
            armed.set(false);
        }

        List<Long> maxima() {
            return maxima;
        }

        @Override
        public long chooseMillis(long maximumMillis) {
            if (!armed.get()) {
                return maximumMillis;
            }
            maxima.add(maximumMillis);
            return switch (calls.getAndIncrement() % 3) {
                case 0 -> 0;
                case 1 -> maximumMillis / 2;
                default -> maximumMillis;
            };
        }
    }
}
