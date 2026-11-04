# ADR 0035: 주문 최초 확정 DB commit 성공 후 OrderConfirmed를 전송한다

Status: Accepted
Related: [ADR 0032](0032-persist-payment-result-before-order-application.md), [week6 설계](../week6/design.md#publish-after-commit)

Follow-up: eventId 고정 생성은 [ADR 0036](0036-derive-order-confirmation-event-id-from-order-id.md)에서 결정했다. 아래 본문의 구현 상태·검증 건수는 이 결정 채택 당시의 기록이며, 현재 상태와 이후 Kafka 검증은 [week6 설계](../week6/design.md)·[구현 기록](../week6/implementation-log.md#kafka-producer)을 따른다.

Follow-up: 후속 구매 활동의 별도 DB 트랜잭션과 상품별 유일성은 [ADR 0038](0038-store-order-purchase-activities-in-one-db-transaction.md)에서 확장한다. 발행 순서에 대한 이 결정은 Accepted를 유지한다.

Follow-up (producer): [ADR 0051](0051-limit-producer-metadata-wait-and-keep-default-retries.md)에서 `max.block.ms`를 5초로 줄이고 `retries`를 클라이언트 기본에 둔다. 발행 순서 결정은 Accepted를 유지한다.

## Context

6주차에는 PG로 확정한 주문을 Kafka로 전달하고 commerce-streamer에서 상품별 구매 활동을 저장한다. 기존 TX B는 검증한 결제 결과를 저장하고 TX C는 주문·예약에 반영한다. 현재 TX C는 최초 PG 확정에서 시각을 저장하고 내부 이벤트를 준비하며, 실제 Kafka 전송 구현체는 후속 작업이다.

DB 변경과 Kafka 전송은 DB 트랜잭션만으로 함께 commit·rollback되지 않는다. 전송 순서에 따라 남는 실패 상태가 달라지므로 구현 전에 순서를 결정한다.

## Decision

- 기존 TX C에서 PG SUCCESS를 최초 주문 CONFIRMED·예약 CONSUMED로 반영하고 DB commit이 성공한 뒤 OrderConfirmed를 전송한다. 기존 주문 잠금·결제 결과 대조·중복 적용 방지는 유지한다.
- TX C의 `completePayment(confirmedAt)` 호출 직후에 직접 Kafka 전송을 넣지 않는다. `@Transactional` 메서드 본문 끝도 commit 완료를 의미하지 않는다.
- 확정 TX 안에서 주문 ID·최초 확정 시각·저장된 품목별 수량 등 필요한 값을 복사해 메시지 객체를 준비한다. 이 준비는 Kafka 전송이 아니며, commit 성공 뒤 준비한 내용으로 전송한다. rollback이면 전송하지 않는다. 커밋 후 주문 Entity의 lazy loading이나 현재 상품 재조회에 의존하지 않는다. 구체적인 메시지 모델은 후속 결정으로 남긴다([흐름](../week6/design.md#prepare-message)).
- `OrderPaymentTransactions`가 최초 성공 확정에서 Spring 내부 이벤트를 전달하고, application의 `AFTER_COMMIT` listener가 domain의 발행 인터페이스를 호출한다. infrastructure의 Kafka 구현체가 실제 전송을 담당한다. `OrderModel`에는 Spring 이벤트 발행·Kafka 호출을 넣지 않는다. application에서 infrastructure 구현체를 직접 참조하지 않는다.
- 직접 호출로도 책임 분리가 가능하지만, 최초 확정 지점에서 전송을 연결하고 기존 캐시의 커밋 후 처리 패턴과 일치시키기 위해 `AFTER_COMMIT`을 선택한다. 이벤트 전달 자체가 별도 스레드 실행이나 영속적인 전송 대기를 뜻하지 않는다.
- `orders.confirmed_at`은 nullable `Instant`로 추가했다. application의 Clock에서 얻은 시각을 MySQL `datetime(6)`에 맞춰 마이크로초로 절삭하고 최초 PG 확정 전이에 저장한다. 중복 적용은 값을 유지하며 rollback에서는 주문 상태와 함께 취소된다. 기존 POINT 확정의 시각은 소급 생성하지 않는다.
- `OrderConfirmed(orderId, confirmedAt, items)`를 불변 내부 스냅샷으로 사용하고 상품별 수량을 합산·정렬한다. 최종 Kafka 메시지의 eventId 생성 방식은 사용자 선택 대기이며 producer 설정도 후속 단계다.
- `OrderConfirmationListener`는 `commerce.order-events.enabled=true`일 때 등록된다. 실제 구현체를 연결하기 전인 현재는 기본 비활성이며, 통합 테스트에서만 발행 포트 대역을 주입해 활성화했다.
- rollback된 확정과 결제 대기·UNKNOWN·FAILED에서는 확정 메시지를 전송하지 않는다. 이미 완료된 주문의 반복 적용으로 새 확정 이벤트를 만들지 않으며 이전 POINT 주문을 소급 발행하지 않는다.
- commit 뒤 전송이 실패해도 확정된 주문을 되돌리거나 PG 생성을 다시 요청하지 않는다. listener의 동기 발행 오류는 주문 ID와 함께 오류 로그로 기록한다. Kafka 비동기 전송 결과·metadata 관찰은 미구현이다.

## Alternatives and costs

| 순서 | 실패 구간과 영향 |
| --- | --- |
| Kafka 전송 후 DB commit | 전송 성공 뒤 DB commit이 실패하면 실제로 확정되지 않은 주문의 메시지가 소비될 수 있다. |
| DB commit 후 Kafka 전송 — 채택 | 확정된 주문 사실을 전달한다. commit 뒤 전송 전 중단·전송 실패로 메시지가 누락될 수 있다. |

after-commit 실행만으로 메시지 누락을 해결하지는 못한다. 현재 결제 복구는 미완료 주문의 결과 반영을 담당하며, 이미 확정된 주문의 이벤트 전송 여부를 추적하는 기능은 아니다. 이번 주에는 실제 주문 경로에서 누락을 재현하고, 다음 주 Outbox 개선으로 이어간다. Outbox는 현재 구현하지 않는다.

## 검증 상태

- `OrderModelTest` 12건: 최초 PG 확정 시각, 반복 확정 거절·시각 보존, null 시각 거절·상태 보존, POINT·대기·실패 상태를 확인했다.
- `OrderConfirmedTest` 3건: 상품별 합산·정렬·불변 스냅샷, 잘못된 사실·수량·overflow 거절을 확인했다.
- `OrderConfirmationEventIntegrationTest` 7건: 실제 MySQL·가짜 PG를 사용했다. commit 전 발행 포트 미호출, commit 후 별도 TX에서 확정 사실 조회, rollback과 복구 시각, 중복·동시 적용의 1회 호출, PENDING·FAILED·조회 불가의 미발행, 동기 발행 오류 후 주문 확정 유지·PG POST 추가 없음이 통과했다. 발행 포트는 대역이다.
- Java 21·Docker API 1.44 호환 설정으로 `:apps:commerce-api:check --offline`이 성공했다. 기본 검사 515건 실패·오류·skip 0, main/test Checkstyle 위반 0, ArchUnit 통과다. experiment 태그는 기존 설정에 따라 실행 대상에서 제외했다.

실제 Kafka send·metadata·비동기 오류·broker 누락 실험과 streamer 저장은 미구현·미검증이다. 위 검사를 broker record 검증으로 표현하지 않는다. 실행 명령·범위는 [week6 구현 기록](../week6/implementation-log.md#after-commit-boundary)에 둔다.
