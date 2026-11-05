package com.loopers.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * commerce-api(producer)와 commerce-streamer(consumer)가 함께 읽는 메시지 예시.
 * producer 테스트는 "만든 메시지가 이 예시와 같은가", consumer 테스트는 "이 예시를 읽는가"를 확인한다.
 * 한쪽만 바뀌면 그쪽 테스트가 실패한다.
 * <p>
 * v1 예시는 고치지 않는다. Outbox의 PENDING 행, Kafka에 보관된 record, DLT에는 배포 뒤에도 v1이 남으므로
 * consumer는 v1을 계속 읽어야 한다. 형식을 바꾸면 v2 예시를 새로 만들고 producer 테스트를 v2로 옮기며,
 * consumer 테스트는 v1과 v2를 모두 읽는지 확인한다.
 */
public final class OrderEventContract {
    /** 주문 42, 확정 시각 2026-10-29T01:00:00.123456Z, 상품 7×5·상품 8×3. */
    public static final String ORDER_CONFIRMED_V1 = read("contracts/order-confirmed-v1.json");

    private OrderEventContract() {
    }

    private static String read(String path) {
        try (InputStream input = OrderEventContract.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("계약 예시 파일이 없습니다: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
