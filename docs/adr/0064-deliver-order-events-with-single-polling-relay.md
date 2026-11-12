# ADR 0064: 주문 확정 이벤트를 단일 polling relay로 전달한다

Status: Accepted
Supersedes: [ADR 0035](0035-publish-order-confirmation-after-db-commit.md)
Related: [ADR 0065](0065-recover-pre-outbox-orders-from-stored-facts.md), [ADR 0051](0051-limit-producer-metadata-wait-and-keep-default-retries.md), [ADR 0063](0063-record-order-confirmation-outbox-in-tx-c.md), [week7 설계](../week7/design.md)

## Context

ADR 0063으로 전달할 사실이 DB에 남지만 읽어서 보내는 주체가 없다. 직접 전송을 함께 두면 같은 사건을 평소에도 두 경로로 보내게 되고, 직접 전송의 결과는 상태로 남지 않는다.

Outbox `payload`는 TX C에서 직렬화한 JSON 문자열이다. 공통 `kafka.yml`의 값 직렬화는 `JsonSerializer`라서 문자열을 넘기면 따옴표로 한 번 더 감싸 보낸다.

## Decision

- `OrderConfirmationListener`의 after-commit 전송을 제거하고 `OrderOutboxRelay`를 유일한 전송 주체로 둔다.
- 한 회차는 `PENDING`이고 `next_attempt_at`이 지난 행을 `id` 순으로 찾아 직렬로 처리한다. 행마다 시도 수를 저장하고, TX 밖에서 전송하고, broker 확인 뒤 별도 TX에서 PUBLISHED를 저장한다.
- 성공 판단은 producer future의 성공이다. 대기 초과·전송 실패는 같은 행을 PENDING으로 두고 다음 시각과 오류를 저장한다. 수신 0회라고 단정하지 않으며 PUBLISHED 표시도 하지 않는다.
- 상태 변경은 relay 실행 흐름만 수행한다. 늦게 끝난 전송의 callback은 행을 바꾸지 않는다.
- 전송 실패가 나면 그 회차를 멈춘다. 다음 회차에서는 재시도 시각이 안 된 행을 건너뛰고 뒤 행이 진행한다.
- 반복 실패는 별도 BLOCKED 상태 없이 PENDING으로 유지한다. 재시도 간격은 2초부터 2배씩 늘어 최대 60초다.
- 같은 프로세스의 실행 겹침은 `fixedDelay`와 `ReentrantLock`으로 막는다. 여러 프로세스의 동시 실행은 막지 못하므로 `commerce.order-events.relay.scheduling-enabled`를 인스턴스 하나에서만 켠다. 기본값은 false이고 local에서만 true다.
- 저장된 payload 문자열을 해석하지 않고 그대로 보낸다. commerce-api의 producer 값 직렬화만 `StringSerializer`로 덮어쓴다. api가 Kafka로 보내는 길은 Outbox와 relay 하나라서 모든 값이 이미 문자열이다. 공통 `kafka.yml`은 다른 앱의 기본값이라 바꾸지 않는다.
- relay는 payload 내용을 검사하지 않는다. 읽을 수 없는 payload는 PUBLISHED가 되고, consumer가 형식 오류로 DLT에 격리한다. relay의 책임은 저장된 사건을 한 번 이상 전달하는 것이다.
- 전송 대기는 producer 기본 `delivery.timeout.ms`(120초)보다 긴 130초로 둔다. 한 회차 처리 개수는 200건, 실행 간격은 10초다. 실제 트래픽이 없어 운영 수치로 정한 값이 아니다. 처리 한계 ≈ `batch-size / (fixed-delay + batch-size × 행당 처리 시간)`으로, 처음 값(20건·1초)에서 측정한 한계(초당 약 16건)를 유지하면서 대상 조회 회차를 10분의 1로 줄이는 조합을 골랐다. 주간 집계 전 여유(가정 1시간)에 비해 지연 10초 안팎은 작다([실험 3](../week7/outbox-experiments.md#실험-3-처리량-지금-relay-설정은-초당-몇-건까지-따라잡나)). 대기를 producer의 재시도 기한보다 짧게 두면, relay가 다음 시도로 넘어간 뒤에도 앞선 전송이 늦게 기록되어 같은 사건이 시도 수만큼 쌓인다([실험 2](../week7/outbox-experiments.md#실험-2-기다림-broker-응답을-짧게만-기다리면)).

## Alternatives and costs

| 대안 | 판단 |
| --- | --- |
| PUBLISHED를 send 전에 저장 | 중단 시 미발행 사건을 완료로 숨긴다. 채택하지 않음 |
| 여러 relay, CDC | claim·소유권·만료 또는 connector 운영이 필요하다. 과제 범위 밖으로 채택하지 않음 |
| 저장 JSON을 `JsonNode`로 읽어 공통 `JsonSerializer`로 전송 | 설정을 바꾸지 않지만 저장 문자열을 다시 직렬화한다. 현재 payload에서는 같은 bytes가 나오지만 구조로 보장하지 않고, 깨진 payload가 relay에서 PENDING으로 계속 재시도된다. 채택하지 않음 |
| 공통 `kafka.yml`의 값 직렬화를 `StringSerializer`로 변경 | 같은 효과지만 객체를 보낼 수 있는 다른 앱의 기본값까지 바꾼다. 채택하지 않음 |
| publisher 전용 `KafkaTemplate`을 코드로 구성 | streamer DLT producer와 같은 방식이지만, api의 producer가 하나뿐이라 설정 한 줄을 코드로 옮기는 셈이다. 채택하지 않음 |
| 20건·1초 (처음 값) | 지연은 가장 짧지만 1시간에 3,600번 조회한다. 주간 집계 기준에 비해 과하다. 채택하지 않음 |
| 1,200건·60초 | 조회는 가장 적지만 한 회차가 스케줄러를 약 15초 붙잡고 장애를 1분 늦게 알아챈다. 채택하지 않음 |
| relay 전용 `delivery.timeout.ms`·전용 스케줄러 스레드 | 장애 대기를 줄이지만, 다른 예약 작업이 밀리는 문제를 측정하지 않았다. 필요가 확인되면 다시 판단한다 |

채택안의 비용은 다음과 같다.

- broker 확인 뒤 PUBLISHED 저장 전에 멈추면 같은 사건을 재발행한다. 업무 중복은 consumer가 막아야 한다.
- 스케줄러가 기본 단일 스레드라, 응답 없는 전송을 기다리는 동안 다른 `@Scheduled` 작업이 밀릴 수 있다. 한 행의 대기는 future 대기(130초)만이 아니다. 그 앞의 `send()`도 metadata·buffer 확보로 `max.block.ms`(5초)까지 기다릴 수 있다.
- relay가 내용을 검사하지 않으므로, 잘못 고친 payload는 Outbox에서 PUBLISHED가 된다. 이 사건의 위치는 Outbox가 아니라 consumer의 DLT에서 찾아야 한다.
- commerce-api 설정 파일에서 import한 `kafka.yml`이 첫 문서보다 우선하므로, 덮어쓰는 값을 뒤 문서에 둔다. 이 배치를 바꾸면 덮어쓰기가 조용히 사라진다.
- 복구할 수 없는 행도 PENDING으로 남아 계속 시도한다. 60초는 다음 시도 시각까지의 대기 상한이며, 실제 재시도에는 회차 간격(10초)과 회차 처리 시간이 더해진다.

## 검증

- `OrderOutboxRelayTest`가 실제 MySQL·Kafka에서 저장값 전송, 재시작 뒤 전송, broker 확인 뒤 PUBLISHED 저장 전 중단의 다른 offset 재발행, 전송 실패 재시도, 회차 크기, 실행 겹침을 확인한다.
- 전송 전에 PUBLISHED를 저장하도록 임시로 바꾸면 위 테스트 중 2건이 실패했다.
- `OrderOutboxRelayTest`의 저장값 전송 검사가 Kafka record의 값과 저장 payload의 문자열 일치를 확인한다. 문자열 전송으로 바꾸고 덮어쓰기 설정이 없을 때는 이 검사가 따옴표로 감싼 값 때문에 실패했다.
- `OrderEventProducerConfigurationTest`가 commerce-api의 실제 설정 파일로 local·test profile의 값 직렬화가 `StringSerializer`인지 확인한다.
- 실패와 중단은 test hook으로 만들었다. broker 실제 장애, 프로세스 강제 종료, 실제 consumer 수렴은 이 결정의 검증 범위가 아니다.
