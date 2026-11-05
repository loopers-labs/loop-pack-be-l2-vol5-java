# ADR 0063: 주문 최초 확정과 같은 TX C에 Outbox를 기록한다

Status: Accepted
Extends: [ADR 0032](0032-persist-payment-result-before-order-application.md), [ADR 0035](0035-publish-order-confirmation-after-db-commit.md)
Related: [ADR 0036](0036-derive-order-confirmation-event-id-from-order-id.md), [week7 설계](../week7/design.md)

Follow-up: Outbox 전달은 [ADR 0064](0064-deliver-order-events-with-single-polling-relay.md)에서 결정했다. 아래 본문의 직접 전송 관련 문장은 이 결정 당시의 상태다.

## Context

ADR 0035의 after-commit 전송은 주문 commit 뒤 send 전에 멈추면 전달 대상을 다시 찾을 기록을 남기지 않는다. consumer의 중복 제거는 도착한 record만 처리하므로 이 누락을 찾지 못한다. 전달할 사실을 주문 확정과 함께 DB에 남겨야 한다.

과제의 일반 예시는 PG 성공 사실까지 같은 TX로 설명한다. 현재 구조는 ADR 0032에 따라 검증한 PG 결과를 TX B에 저장하고 주문 반영을 TX C에서 수행한다.

## Decision

- `OrderPaymentTransactions.applyRecordedResult()`의 최초 PG 성공 분기에서 주문 CONFIRMED·예약 CONSUMED·확정 시각과 Outbox PENDING 행을 같은 TX C로 commit·rollback한다. 별도 `REQUIRES_NEW`나 after-commit insert로 나누지 않는다.
- TX B와 TX C의 분리는 유지한다. Outbox 실패로 TX C가 rollback돼도 TX B의 SUCCESS가 남아 PG 호출 없이 TX C를 다시 실행할 수 있다. 원자성의 범위는 Outbox와 주문 확정이며 PG 결과 저장은 포함하지 않는다.
- `order_outbox` 행은 `event_id` 유일 제약, `order_id`, `event_type`, `schema_version`, `message_key`, `payload`, `status`, `attempt_count`, `next_attempt_at`, `last_error`, `created_at`, `published_at`을 가진다. 사건 식별·key·payload 컬럼은 `updatable = false`로 생성 뒤 변경하지 않는다.
- payload는 TX C에서 기존 `OrderConfirmedMessage` 계약을 JSON 문자열로 직렬화해 저장한다. relay가 현재 상품을 다시 읽어 만들지 않는다. eventId와 key는 기존 값(`order-confirmed:v1:{orderId}`, 주문 ID 문자열)을 유지한다.
- application은 domain의 `OrderOutboxRecorder`에 의존하고, 직렬화와 저장은 infrastructure의 `JsonOrderOutboxRecorder`가 맡는다. 직렬화·insert 오류는 TX C 실패로 전파한다.
- Outbox 기록은 `commerce.order-events.enabled`와 무관하게 항상 수행한다. 이 설정은 전달 실행에 관한 것이며, 기록을 끄면 그 기간의 확정 사실을 다시 찾을 수 없다.
- ADR 0035의 직접 전송은 relay와 교체할 때 함께 제거한다. 이 ADR 시점에는 relay가 없으므로 직접 전송과 relay가 병행되지 않는다.

## Alternatives and costs

| 대안 | 판단 |
| --- | --- |
| TX B와 TX C를 합쳐 PG 성공 사실까지 한 TX로 저장 | Outbox 실패가 PG 결과 저장까지 되돌려 재조회가 필요해지고 ADR 0032의 복구 정책이 바뀐다. 채택하지 않음 |
| `REQUIRES_NEW`로 Outbox를 따로 commit | TX C가 rollback돼도 Outbox가 남아 확정되지 않은 주문의 이벤트가 전달될 수 있다. 채택하지 않음 |
| after-commit에서 Outbox insert | commit 뒤 insert 전 중단에서 기존 누락이 그대로 남는다. 채택하지 않음 |
| 발행할 때 주문을 읽어 payload 생성 | 재발행 내용이 현재 데이터에 따라 달라질 수 있다. 채택하지 않음 |

채택안의 비용은 확정 TX마다 직렬화와 insert가 하나 늘고, 전달이 relay 실행까지 지연되며, 쌓이는 행의 보존 정책이 필요하다는 점이다. Outbox insert 실패는 PG가 성공했더라도 주문 확정을 지연시킨다.

## 검증

- `OrderConfirmationOutboxAtomicityTest`가 실제 MySQL에서 최초 성공, 중복 적용, 동시 적용, 비성공, 직렬화 실패, 유일 제약에 의한 insert 실패, insert 뒤 TX 실패와 재적용을 확인한다.
- 구현을 연결하기 전에는 Outbox가 있어야 하는 6건만 실패했다. 기록을 임시로 `REQUIRES_NEW`로 바꾸면 insert 뒤 TX 실패 시나리오가 rollback 뒤 남은 행(`expected: 0 but was: 1`)으로 실패했다.
- broker 전송, relay, 중단·재시작은 이 결정의 검증 범위가 아니다.
