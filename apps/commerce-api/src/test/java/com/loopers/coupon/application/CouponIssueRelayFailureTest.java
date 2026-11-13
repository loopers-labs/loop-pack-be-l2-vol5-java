package com.loopers.coupon.application;

import com.loopers.coupon.application.port.in.CouponIssueRelayUseCase;
import com.loopers.coupon.application.port.out.CouponIssueOutboxPort;
import com.loopers.outbox.application.port.out.MessageSendPort;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * relay 실패 경로: broker 응답(ack)이 실패이거나 send()가 바로 실패하면 PUBLISHED가 아니고, 실패 횟수를 세어 다시 차례가 된다.
 * 3번째 실패면 BLOCKED (W7 OrderOutbox와 같은 전이).
 */
// 이 클래스만 쓰는 설정(속성·mock)이라 컨텍스트를 다른 테스트와 공유하지 않는다. 끝나면 닫아 커넥션 풀(최소 30개)을 돌려준다 —
// 닫지 않으면 캐시된 컨텍스트들의 커넥션이 MySQL max_connections(151)를 넘어 다른 테스트가 뜨지 못했다(11-12).
@DirtiesContext
@SpringBootTest(properties = "coupon.outbox.relay.send-wait=300ms")
class CouponIssueRelayFailureTest {

    @Autowired
    private CouponIssueOutboxPort outboxPort;

    @Autowired
    private CouponIssueRelayUseCase relay;

    @MockitoBean
    private MessageSendPort messageSendPort;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Map<String, Object> row(long userId) {
        return jdbcTemplate.queryForMap("SELECT status, attempt_count, last_error FROM coupon_issue_outbox WHERE user_id = ?", userId);
    }

    @DisplayName("broker가 실패로 답하면 PUBLISHED가 아니라 PENDING(실패 1번)으로 남는다.")
    @Test
    void failedAckStaysPending() {
        given(messageSendPort.send(anyString(), anyString(), anyString()))
            .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        outboxPort.appendIfAbsent(1L, 7L, ZonedDateTime.now());

        var result = relay.relayOnce();

        assertThat(result.published()).isZero();
        assertThat(row(7L).get("status")).isEqualTo("PENDING");
        assertThat(row(7L).get("attempt_count")).isEqualTo(1);
        assertThat((String) row(7L).get("last_error")).contains("broker down");
    }

    @DisplayName("ack가 send-wait 안에 오지 않으면(timeout) PENDING(실패 1번)으로 남는다.")
    @Test
    void ackTimeoutStaysPending() {
        given(messageSendPort.send(anyString(), anyString(), anyString())).willReturn(new CompletableFuture<>());
        outboxPort.appendIfAbsent(1L, 8L, ZonedDateTime.now());

        relay.relayOnce();

        assertThat(row(8L).get("status")).isEqualTo("PENDING");
        assertThat(row(8L).get("attempt_count")).isEqualTo(1);
    }

    @DisplayName("send() 호출 자체가 실패하면 그 행을 실패로 기록하고 회차를 멈춘다 — 뒤 행은 맡지 않는다.")
    @Test
    void sendCallFailureStopsRound() {
        given(messageSendPort.send(anyString(), anyString(), any())).willThrow(new IllegalStateException("metadata not available"));
        outboxPort.appendIfAbsent(1L, 9L, ZonedDateTime.now());
        outboxPort.appendIfAbsent(1L, 10L, ZonedDateTime.now());

        var result = relay.relayOnce();

        assertThat(result.stopped()).isTrue();
        assertThat(row(9L).get("attempt_count")).isEqualTo(1);
        assertThat(row(10L).get("status")).isEqualTo("PENDING");
        assertThat(row(10L).get("attempt_count")).isEqualTo(0);
    }
}
