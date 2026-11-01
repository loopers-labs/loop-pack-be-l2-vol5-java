package com.loopers.infrastructure.order;

import com.loopers.infrastructure.order.OrderEventTestCleanup.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * broker 검증의 공유 자원 정리 순서를 실패 주입으로 확인한다. 실제 PG·DB·broker 를 쓰지 않고 정리 단계만 대역으로 둔다.
 */
@DisplayName("broker 검증의 공유 자원 정리는 앞 단계가 실패해도 다음 단계를 시도하고, 첫 오류와 추가 오류를 모두 남긴다.")
class OrderEventKafkaBrokerCleanupTest {

    @DisplayName("PG 종료가 실패하면 스키마 삭제·Kafka 정리를 이어서 시도하고, 첫 오류를 던지며 이후 오류를 suppressed 로 보존한다.")
    @Test
    void attemptsEveryStepAndKeepsFirstFailure() {
        List<String> attempted = new ArrayList<>();
        IllegalStateException pgFailure = new IllegalStateException("PG 종료 실패 주입");
        IllegalStateException kafkaFailure = new IllegalStateException("Kafka 정리 실패 주입");

        Throwable thrown = catchThrowable(() -> OrderEventTestCleanup.runAll(List.of(
            new Step("PG fixture 종료", () -> {
                attempted.add("pg");
                throw pgFailure;
            }),
            new Step("검증 전용 스키마 삭제", () -> attempted.add("schema")),
            new Step("Kafka 소유 topic·group 정리", () -> {
                attempted.add("kafka");
                throw kafkaFailure;
            }))));

        assertAll(
            () -> assertThat(attempted).containsExactly("pg", "schema", "kafka"),
            () -> assertThat(thrown).hasMessageContaining("PG fixture 종료").hasCause(pgFailure),
            () -> assertThat(thrown.getSuppressed()).singleElement()
                .satisfies(suppressed -> assertThat(suppressed).hasMessageContaining("Kafka 소유 topic·group 정리").hasCause(kafkaFailure))
        );
    }

    @DisplayName("모든 단계가 성공하면 예외 없이 끝난다.")
    @Test
    void completesWhenEveryStepSucceeds() {
        List<String> attempted = new ArrayList<>();

        OrderEventTestCleanup.runAll(List.of(
            new Step("a", () -> attempted.add("a")),
            new Step("b", () -> attempted.add("b"))));

        assertThat(attempted).containsExactly("a", "b");
    }
}
