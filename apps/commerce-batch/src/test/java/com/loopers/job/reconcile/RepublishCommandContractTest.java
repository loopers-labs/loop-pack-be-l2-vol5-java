package com.loopers.job.reconcile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.loopers.batch.job.reconcile.RepublishCommandSerializer;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

// commerce-api의 명령 파서 계약 테스트와 같은 fixture(modules/kafka testFixtures)를 쓴다.
class RepublishCommandContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("주문 42·요청 시각 2026-10-29T01:10:00Z의 재발행 명령은 공유 계약 fixture와 정규화해서 같다")
    void commandMatchesSharedFixture() throws IOException {
        String command = RepublishCommandSerializer.serialize(42, Instant.parse("2026-10-29T01:10:00Z"));

        assertThat(JSON.readTree(command)).isEqualTo(fixture());
    }

    private static JsonNode fixture() throws IOException {
        try (InputStream input = new ClassPathResource("contracts/republish-order-confirmed-v1.json").getInputStream()) {
            return JSON.readTree(input);
        }
    }
}
