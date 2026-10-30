package com.loopers.infrastructure.order;

import java.util.List;

/**
 * 주문 이벤트 broker 검증의 정리 단계 실행기. 어떤 자원을 어떤 순서로 정리할지와 미생성 자원의 처리는 호출하는 테스트가 정한다.
 */
final class OrderEventTestCleanup {

    private OrderEventTestCleanup() {
    }

    record Step(String name, Action action) {
    }

    interface Action {
        void run() throws Exception;
    }

    /** 모든 단계를 순서대로 시도한다. 첫 오류를 던지고 이후 오류는 suppressed 로 붙여, 마지막 오류가 첫 오류를 덮지 않게 한다. */
    static void runAll(List<Step> steps) {
        RuntimeException first = null;
        for (Step step : steps) {
            try {
                step.action().run();
            } catch (Exception failure) {
                IllegalStateException wrapped = new IllegalStateException("정리 실패: " + step.name(), failure);
                if (first == null) {
                    first = wrapped;
                } else {
                    first.addSuppressed(wrapped);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
