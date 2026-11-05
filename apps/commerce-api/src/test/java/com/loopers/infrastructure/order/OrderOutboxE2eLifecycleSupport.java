package com.loopers.infrastructure.order;

import com.loopers.infrastructure.order.OrderEventTestCleanup.Step;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 E2E(05 전달·07 O11·C10 지원)와 05a 정리 회귀가 함께 쓰는 자식 기동 소유권·부분 실패 정리 판단(설계 W7.4-2·W7.4-3a).
 * 리팩토링 09 에서 OrderOutboxDeliveryEndToEndIntegrationTest 안의 같은 코드를 그대로 옮겼다. 판단·순서·예외·로그 label 은 바꾸지 않았다.
 * 실제 자원 행동({@link CleanupSteps} 구현)과 각 E2E 의 업무·관찰 절차는 호출한 테스트에 남는다.
 */
final class OrderOutboxE2eLifecycleSupport {

    /** release 의 정리 로그 label. 옮기기 전 값 그대로다(O11 도 같은 label 로 출력한다). */
    private static final String LABEL = "W7E2E";
    private static final Set<String> PURCHASE_TABLES = Set.of("processed_order_confirmation", "product_purchase_activity");

    private OrderOutboxE2eLifecycleSupport() {
    }

    // ---------------------------------------------------------------- 소유권·정리 판단(정리 회귀 테스트가 같은 경로를 쓴다)

    /** 이 실행이 소유한 구매 테이블. 시작 전 부재를 확인한 순간부터 이번 child 가 만들 테이블은 이 실행의 것이다. */
    static final class Ownership {

        private boolean purchaseTables;

        /** 시작 전부터 구매 테이블이 있으면 소유하지 않고 실패한다. 그 테이블은 지우지 않고 원인을 볼 수 있게 남긴다. */
        void claimPurchaseTables(Collection<String> existingTables) {
            assertThat(existingTables).as("자식 기동 전 구매 테이블 없음(이전 실행 잔여면 지우지 않고 실패)")
                .doesNotContainAnyElementsOf(PURCHASE_TABLES);
            purchaseTables = true;
        }

        boolean ownsPurchaseTables() {
            return purchaseTables;
        }
    }

    /** child 를 띄우는 두 단계. 기동 뒤 준비 대기가 실패해도 child 참조와 소유권은 정리에 남는다. */
    interface ChildLaunch {

        void start() throws Exception;

        void awaitReady();
    }

    /** 부재 확인 → 소유 → child 기동 → 준비 대기 순서. 소유 표시는 기동 전에 끝나므로 기동·준비 실패에도 이번 테이블을 정리할 수 있다. */
    static void launchChild(Ownership ownership, Collection<String> existingTables, ChildLaunch launch) throws Exception {
        ownership.claimPurchaseTables(existingTables);
        launch.start();
        launch.awaitReady();
    }

    /** 정리 단계의 실제 행동. 판단은 {@link #release} 가 한다. */
    interface CleanupSteps {

        void stopRelay() throws Exception;

        void restoreTopicConfig() throws Exception;

        void stopChild() throws Exception;

        /** 실제 프로세스가 끝났는지. 정상 종료 여부와 별개로 지금 살아 있는지를 본다. */
        boolean childExited();

        void truncateApiData() throws Exception;

        void closeApi() throws Exception;

        void closePg() throws Exception;

        void dropPurchaseTables() throws Exception;

        void closeTopicAdmin() throws Exception;

        void closeKafka() throws Exception;
    }

    /**
     * 모든 단계를 시도하되, writer(relay·child) 종료가 확인되지 않으면 그 writer 가 쓰는 자원의 파괴적 정리를 건너뛴다.
     * API 데이터 TRUNCATE·구매 테이블 DROP 은 relay idle 과 child 실제 종료가 모두 확인돼야 하고, topic·group 삭제는 child 종료가 필요하다.
     * client·context·PG 종료는 독립적으로 계속한다. 첫 오류를 던지고 후속 오류와 남긴 자원을 suppressed 로 붙인다. 남긴 자원 목록을 반환한다.
     */
    static List<String> release(Ownership ownership, CleanupSteps steps) {
        boolean[] relayIdle = {false};
        List<String> kept = new ArrayList<>();
        try {
            OrderEventTestCleanup.runAll(List.of(
                new Step("새 relay 진입 중단", () -> {
                    steps.stopRelay();
                    relayIdle[0] = true;
                }),
                new Step("소유 topic 의 min.insync.replicas 복원", steps::restoreTopicConfig),
                new Step("streamer 자식 JVM 종료", steps::stopChild),
                new Step("API 테스트 데이터 정리", () -> {
                    if (relayIdle[0] && steps.childExited()) {
                        steps.truncateApiData();
                    } else {
                        kept.add("API 테스트 데이터·Redis(relay idle=" + relayIdle[0] + ", child 종료=" + steps.childExited() + ")");
                    }
                }),
                new Step("API 컨텍스트 종료", steps::closeApi),
                new Step("PG fixture 종료", steps::closePg),
                new Step("이 실행이 만든 구매 테이블 삭제", () -> {
                    if (!ownership.ownsPurchaseTables()) {
                        return;
                    }
                    if (relayIdle[0] && steps.childExited()) {
                        steps.dropPurchaseTables();
                    } else {
                        kept.add("구매 테이블 두 개(relay idle=" + relayIdle[0] + ", child 종료=" + steps.childExited() + ")");
                    }
                }),
                new Step("topic 관리 client 종료", steps::closeTopicAdmin),
                new Step("Kafka 소유 topic·group 정리", () -> {
                    if (steps.childExited()) {
                        steps.closeKafka();
                    } else {
                        kept.add("Kafka 소유 topic·group·관리 client(child 생존)");
                    }
                })));
        } catch (RuntimeException failure) {
            if (!kept.isEmpty()) {
                failure.addSuppressed(new IllegalStateException("writer 종료를 확인하지 못해 남긴 자원: " + kept));
            }
            throw failure;
        } finally {
            System.out.println("[" + LABEL + " CLEANUP] kept=" + kept);
        }
        if (!kept.isEmpty()) {
            throw new IllegalStateException("writer 종료를 확인하지 못해 남긴 자원: " + kept);
        }
        return kept;
    }
}
