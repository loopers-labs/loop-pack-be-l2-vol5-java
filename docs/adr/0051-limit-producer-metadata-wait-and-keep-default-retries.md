# ADR 0051: producer의 metadata 대기를 5초로 줄이고 재시도 횟수는 클라이언트 기본에 둔다

Status: Accepted
Related: [ADR 0035](0035-publish-order-confirmation-after-db-commit.md), [ADR 0049](0049-connect-order-consumer-error-handler.md), [week6 설계](../week6/design.md#producer-settings)

Verification: 이후 측정은 [구현 기록 32절](../week6/implementation-log.md#max-block-measurement)에 둔다. metadata가 없는 producer에서 요청이 약 5초 막히고 그동안 DB 커넥션을 쥔 것, metadata가 있으면 막히지 않는 것을 확인했다. 아래 본문은 결정 당시 기록으로 보존한다.

Correction: 아래 Decision의 "DLQ 전송 결과 대기 10초가 그보다 먼저 끝나면"은 사실과 다르다. 10초는 최소 대기이며 실제로는 약 120초 뒤 실패했다([구현 기록 36절](../week6/implementation-log.md#dlt-timeout-verification)). 이 대기를 받아들인 결정은 [ADR 0055](0055-accept-default-delivery-timeout-for-dead-letter-publishing.md)에 둔다.

## Context

공통 `kafka.yml`의 producer는 템플릿에서 온 `retries: 3`과 클라이언트 기본 `max.block.ms=60000`을 쓰고 있었다. 두 값 모두 일부러 고른 값이 아니었다.

- 주문 확정 이벤트는 AFTER_COMMIT listener가 **요청 스레드**에서 `send()`를 부른다(ADR 0035). 브로커가 모두 꺼져 metadata를 얻지 못하면 `send()`가 `max.block.ms` 동안 멈추고, 주문은 이미 commit됐는데 API 응답이 최대 60초 늦어진다.
- `retries=3`이면 ISR 미달처럼 재시도할 수 있는 오류도 3번 뒤 몇 초 안에 실패한다. 브로커 1대 재시작처럼 잠깐의 장애에도 그 주문의 이벤트가 빠진다. 복제 장애 실험에서는 재시도를 다 쓴 뒤 같은 idempotent producer가 `OutOfOrderSequenceException`을 내는 실행도 관찰했다.

## Decision

- 공통 producer의 `max.block.ms`를 **5000**(5초)으로 둔다. 브로커 전체 장애에서 요청 스레드가 막히는 시간을 5초로 제한한다. 이 실패는 listener의 catch가 잡아 "Order event publication failed after commit" 로그를 남기고, 주문은 CONFIRMED로 남는다.
- `retries` 설정을 지워 **클라이언트 기본(사실상 무제한)**에 둔다. 실패까지의 시간은 `delivery.timeout.ms`(기본 120초)가 정한다. idempotent producer에서 재시도 횟수 대신 전체 시간으로 제한하는 방식이다.
- 두 값은 공통 모듈 설정이므로 commerce-streamer의 DLQ producer에도 적용된다. DLQ 전송 결과 대기 10초(ADR 0049)가 그보다 먼저 끝나면 DLQ 전송 실패로 보고 소비를 멈춘다.
- 발행 누락 자체(구간 X)는 바뀌지 않는다. 막는 것은 W7 Outbox의 몫이다.

## Options considered

| 항목 | 선택하지 않은 안 | 선택하지 않은 이유 |
| --- | --- | --- |
| `max.block.ms` | 기본 60초 유지 | 브로커 전체 장애 때 주문 API 응답이 최대 60초 늦어진다 |
| `retries` | 3 유지 | 짧은 장애에도 빨리 포기해 누락이 늘고, 소진 뒤 sequence 오류를 관찰했다 |

## Consequences

- 브로커 1대 재시작처럼 120초 안에 회복되는 장애에서는 발행이 결국 성공한다. 그동안 성공·실패 로그가 늦게 나온다.
- 120초를 넘는 장애에서는 비동기 실패 로그("Order event publication failed")가 남는다.
- 새 값은 설정 테스트로 확인했다. 브로커 전체 장애에서 실제로 5초 안에 실패하는지, 1대 재시작에서 재시도로 성공하는지는 실행으로 확인하지 않았다.

## Evidence and limits

- [구현 기록](../week6/implementation-log.md#producer-settings): `KafkaClientConfigurationTest`가 local·test profile의 producer 설정에서 `max.block.ms=5000`이고 `retries`가 없음을 확인한다. 변경 전에 실패하는 것을 먼저 확인했다.
- `OutOfOrderSequenceException`은 복제 장애 실험(retries 3)에서 관찰한 것이다. 기본 retries에서 재현되지 않는다는 것을 실험으로 보이지 않았다.
