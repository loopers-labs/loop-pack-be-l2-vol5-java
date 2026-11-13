package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.coupon.vo.CouponIssueRequested;
import com.loopers.support.error.RejectReason;
import com.loopers.support.error.RejectedEventException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponIssueRequestParserTest {

    private final CouponIssueRequestParser parser = new CouponIssueRequestParser(new ObjectMapper());

    private static byte[] json(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }

    @DisplayName("계약대로 온 메시지는 접수 사실로 바뀐다.")
    @Test
    void parsesContract() {
        CouponIssueRequested parsed = parser.parse("3:7", json(
            "{\"schemaVersion\":1,\"campaignId\":3,\"userId\":7,\"sequence\":12,\"requestedAt\":\"2026-11-13T10:00+09:00\"}"));

        assertThat(parsed).isEqualTo(new CouponIssueRequested(3, 7, 12));
    }

    @DisplayName("key 가 payload 의 캠페인:사용자와 다르면 KEY_MISMATCH 다.")
    @Test
    void rejectsKeyMismatch() {
        assertThatThrownBy(() -> parser.parse("3:8", json("{\"schemaVersion\":1,\"campaignId\":3,\"userId\":7,\"sequence\":1}")))
            .isInstanceOfSatisfying(RejectedEventException.class, e -> assertThat(e.getReason()).isEqualTo(RejectReason.KEY_MISMATCH));
    }

    @DisplayName("버전이 다르거나 형식이 계약과 다르면 다시 받아도 같으므로 영구 오류다.")
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"schemaVersion\":2,\"campaignId\":3,\"userId\":7,\"sequence\":1}",
        "{\"schemaVersion\":1,\"campaignId\":3,\"userId\":7,\"sequence\":0}",
        "{\"schemaVersion\":1,\"campaignId\":3,\"userId\":7}",
        "not-json"
    })
    void rejectsInvalidFormat(String body) {
        assertThatThrownBy(() -> parser.parse("3:7", json(body))).isInstanceOf(RejectedEventException.class);
    }
}
