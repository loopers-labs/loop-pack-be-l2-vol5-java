package com.loopers.infrastructure.order;

import com.loopers.infrastructure.order.OrderOutboxDeliveryEndToEndIntegrationTest.ChildLaunch;
import com.loopers.infrastructure.order.OrderOutboxDeliveryEndToEndIntegrationTest.CleanupSteps;
import com.loopers.infrastructure.order.OrderOutboxDeliveryEndToEndIntegrationTest.Ownership;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 05a: 실제 전달 E2E 의 부분 실패 정리 판단(구현 05a). E2E 가 쓰는 같은 {@code launchChild}·{@code release} 경로를 통제한 단계 행동으로 실행해
 * 어떤 파괴적 정리가 실제로 호출되는지 본다. 실제 DB·broker·child 는 쓰지 않으며, 살아 있는 writer 의 데이터를 지우는 실험은 하지 않는다.
 */
@DisplayName("E2E 정리는 이번 실행이 만든 구매 테이블만 소유하고, writer 종료가 확인되지 않으면 DB·topic 의 파괴적 정리를 건너뛴다.")
class OrderOutboxDeliveryCleanupTest {

    private static final Set<String> API_TABLES = Set.of("orders", "payment", "order_confirmed_outbox");

    @DisplayName("부재 확인을 통과한 뒤 child 준비 대기가 실패해도 소유권이 남고, child 종료 확인 뒤 소유 테이블을 지운다.")
    @Test
    void readyFailureKeepsOwnership() {
        Ownership ownership = new Ownership();
        List<String> launched = new ArrayList<>();
        AssertionError notReady = new AssertionError("streamer 자식 기동 완료 가 deadline 안에 관찰되지 않았습니다.");

        assertThatThrownBy(() -> OrderOutboxDeliveryEndToEndIntegrationTest.launchChild(ownership, API_TABLES, new ChildLaunch() {
            @Override
            public void start() {
                launched.add("start");
            }

            @Override
            public void awaitReady() {
                throw notReady;
            }
        })).isSameAs(notReady);
        RecordingSteps steps = new RecordingSteps();
        List<String> kept = OrderOutboxDeliveryEndToEndIntegrationTest.release(ownership, steps);

        assertAll(
            () -> assertThat(launched).containsExactly("start"),
            () -> assertThat(ownership.ownsPurchaseTables()).isTrue(),
            () -> assertThat(steps.calls).containsExactly("stopRelay", "restoreTopicConfig", "stopChild", "truncateApiData", "closeApi",
                "closePg", "dropPurchaseTables", "closeTopicAdmin", "closeKafka"),
            () -> assertThat(kept).isEmpty()
        );
    }

    @DisplayName("시작 전에 구매 테이블이 있으면 child 를 띄우지 않고 실패하며, 정리는 그 테이블을 지우지 않는다.")
    @Test
    void existingTablesAreNotOwned() {
        Ownership ownership = new Ownership();
        List<String> launched = new ArrayList<>();

        assertThatThrownBy(() -> OrderOutboxDeliveryEndToEndIntegrationTest.launchChild(ownership,
            Set.of("orders", "processed_order_confirmation"), new ChildLaunch() {
                @Override
                public void start() {
                    launched.add("start");
                }

                @Override
                public void awaitReady() {
                    launched.add("awaitReady");
                }
            })).isInstanceOf(AssertionError.class).hasMessageContaining("구매 테이블 없음");
        RecordingSteps steps = new RecordingSteps();
        OrderOutboxDeliveryEndToEndIntegrationTest.release(ownership, steps);

        assertAll(
            () -> assertThat(launched).isEmpty(),
            () -> assertThat(ownership.ownsPurchaseTables()).isFalse(),
            () -> assertThat(steps.calls).doesNotContain("dropPurchaseTables")
        );
    }

    @DisplayName("child 종료가 실패하고 실제로 살아 있으면 DB TRUNCATE·구매 테이블 DROP·topic 삭제를 건너뛰고, 독립 client 정리는 하며 첫 실패와 남긴 자원을 보고한다.")
    @Test
    void aliveChildBlocksDestructiveCleanup() {
        Ownership ownership = owned();
        RecordingSteps steps = new RecordingSteps();
        IllegalStateException stopFailure = new IllegalStateException("streamer 자식 종료 실패");
        steps.stopChildFailure = stopFailure;
        steps.childExited = false;

        assertThatThrownBy(() -> OrderOutboxDeliveryEndToEndIntegrationTest.release(ownership, steps))
            .satisfies(failure -> {
                assertThat(failure).hasMessageContaining("streamer 자식 JVM 종료").hasCause(stopFailure);
                assertThat(failure.getSuppressed()).extracting(Throwable::getMessage).anySatisfy(message -> assertThat(message)
                    .contains("API 테스트 데이터·Redis").contains("구매 테이블 두 개").contains("Kafka 소유 topic·group"));
            });

        assertAll(
            () -> assertThat(steps.calls).doesNotContain("truncateApiData", "dropPurchaseTables", "closeKafka"),
            () -> assertThat(steps.calls).containsExactly("stopRelay", "restoreTopicConfig", "stopChild", "closeApi", "closePg",
                "closeTopicAdmin")
        );
    }

    @DisplayName("정상 종료 timeout 뒤 강제 종료로 실제 끝났으면 실패는 그대로 보고하되 소유 데이터·topic 정리를 한다.")
    @Test
    void forcedExitAllowsCleanupButKeepsFailure() {
        Ownership ownership = owned();
        RecordingSteps steps = new RecordingSteps();
        IllegalStateException forced = new IllegalStateException("정상 종료 deadline 안에 끝나지 않아 강제 종료했습니다. [alive=false]");
        steps.stopChildFailure = forced;
        steps.childExited = true;

        assertThatThrownBy(() -> OrderOutboxDeliveryEndToEndIntegrationTest.release(ownership, steps)).hasCause(forced);

        assertThat(steps.calls).containsExactly("stopRelay", "restoreTopicConfig", "stopChild", "truncateApiData", "closeApi", "closePg",
            "dropPurchaseTables", "closeTopicAdmin", "closeKafka");
    }

    @DisplayName("relay idle 확인이 실패하면 child 가 끝났어도 API 데이터·구매 테이블은 지우지 않는다.")
    @Test
    void busyRelayBlocksDatabaseCleanup() {
        Ownership ownership = owned();
        RecordingSteps steps = new RecordingSteps();
        steps.stopRelayFailure = new IllegalStateException("진행 중인 relay 가 10초 안에 끝나지 않았습니다.");

        assertThatThrownBy(() -> OrderOutboxDeliveryEndToEndIntegrationTest.release(ownership, steps))
            .hasCause(steps.stopRelayFailure);

        assertThat(steps.calls).doesNotContain("truncateApiData", "dropPurchaseTables")
            .contains("stopChild", "closeApi", "closePg", "closeTopicAdmin", "closeKafka");
    }

    private static Ownership owned() {
        Ownership ownership = new Ownership();
        ownership.claimPurchaseTables(API_TABLES);
        return ownership;
    }

    /** 호출 순서를 기록하고 지정한 단계만 실패시키는 정리 행동. 판단은 release 가 한다. */
    private static final class RecordingSteps implements CleanupSteps {

        private final List<String> calls = new ArrayList<>();
        private RuntimeException stopRelayFailure;
        private RuntimeException stopChildFailure;
        private boolean childExited = true;

        @Override
        public void stopRelay() {
            calls.add("stopRelay");
            if (stopRelayFailure != null) {
                throw stopRelayFailure;
            }
        }

        @Override
        public void restoreTopicConfig() {
            calls.add("restoreTopicConfig");
        }

        @Override
        public void stopChild() {
            calls.add("stopChild");
            if (stopChildFailure != null) {
                throw stopChildFailure;
            }
        }

        @Override
        public boolean childExited() {
            return childExited;
        }

        @Override
        public void truncateApiData() {
            calls.add("truncateApiData");
        }

        @Override
        public void closeApi() {
            calls.add("closeApi");
        }

        @Override
        public void closePg() {
            calls.add("closePg");
        }

        @Override
        public void dropPurchaseTables() {
            calls.add("dropPurchaseTables");
        }

        @Override
        public void closeTopicAdmin() {
            calls.add("closeTopicAdmin");
        }

        @Override
        public void closeKafka() {
            calls.add("closeKafka");
        }
    }
}
