# ADR 0050: 로컬 클러스터의 topic을 compose에서 만들고 local profile에서 주문 이벤트를 켠다

Status: Accepted — 로컬 Compose·local profile 적용
Related: [ADR 0037](0037-use-three-brokers-for-local-kafka-experiments.md), [ADR 0040](0040-prepare-order-event-receiving-without-auto-subscription.md), [ADR 0044](0044-adopt-earliest-and-three-order-consumers.md), [ADR 0049](0049-connect-order-consumer-error-handler.md), [week6 설계](../week6/design.md#local-topics-and-activation)

Verification: `min.insync.replicas=2`의 근거는 [구현 기록 33절](../week6/implementation-log.md#min-isr-loss-experiment)에서 실제 유실 장면으로 확인했다. 같은 장애에서 1은 확인받은 20건을 잃었고 2는 처음부터 거절했다. 아래 본문은 결정 당시 기록으로 보존한다.

## Context

앞서 `order-events`의 partition·복제 수·최소 ISR, 브로커 자동 topic 생성, 내부 offset topic의 복제 수를 정했다. 실제 broker 설정 조회에서 bitnami 이미지가 `offsets.topic.replication.factor=1`을 넣고 있었고, 자동 생성은 켜져 있었다. ADR 0049로 주문 소비의 오류 처리기를 연결했으므로, 로컬에서 발행과 소비를 실제로 켤 조건이 갖춰졌다.

## Decision

- 로컬 브로커 3대 모두 `auto.create.topics.enable=false`로 둔다. 오타 topic이나 만들기 전의 `order-events`가 기본값(partition 1·복제 1)으로 조용히 생기지 않게 한다. 클라이언트 설정의 같은 이름은 브로커를 제어하지 못한다.
- 로컬 브로커 3대 모두 `offsets.topic.replication.factor=3`으로 둔다. committed offset 저장소가 브로커 1대 장애로 멈추지 않게 한다. 이 값은 내부 topic이 처음 만들어질 때만 적용된다.
- compose의 init 컨테이너가 `--if-not-exists`로 업무 topic을 만든다.

  | topic | partition | 복제 수 | `min.insync.replicas` |
  | --- | --- | --- | --- |
  | `order-events` | 3 | 3 | 2 |
  | `order-events-dlt` | 1 | 3 | 2 |

  DLQ도 원본과 같은 복제 조건을 둔다. DLQ는 양이 적고 사람이 순서대로 보므로 partition 1개로 둔다. ADR 0049에 따라 partition은 원본 key로 정하므로 원본과 개수가 같을 필요가 없다.
- `local` profile에서만 commerce-api의 발행(`commerce.order-events.enabled=true`)과 commerce-streamer의 소비(`commerce.order-events.consumer.enabled=true`, `concurrency=3`)를 켠다. 로컬은 streamer 1대이므로 concurrency 3이 ADR 0044의 group 전체 consumer 3개다. 기본값과 `test` profile은 꺼진 상태를 유지한다.
- producer의 `max.block.ms`·`retries`는 이 결정에 포함하지 않는다.

## Options considered

| 항목 | 선택하지 않은 안 | 선택하지 않은 이유 |
| --- | --- | --- |
| topic 생성 주체 | 앱의 `NewTopic` bean | 배포만으로 partition을 조용히 늘릴 수 있고 복제 수는 바꾸지 못한다. 코드의 값과 실제 topic이 달라질 수 있다 |
| topic 생성 주체 | 사람이 CLI로 생성 | 클러스터를 다시 만들 때마다 빠뜨릴 수 있다 |
| 자동 생성 | 켜 둔 채 init으로 먼저 생성 | 오타 topic은 여전히 조용히 생기고 producer는 성공 로그를 남긴다 |
| DLQ partition | 원본과 같은 3개 | 원본 key로 배정하므로 맞출 필요가 없고, 사람이 볼 때 1개가 단순하다 |

## Consequences

- 로컬에서 api와 streamer를 `local` profile로 띄우면 주문 확정부터 구매 활동 저장까지 실제로 흐른다.
- init은 이미 있는 topic의 설정을 바꾸지 않는다. 값을 바꾸려면 topic을 지우거나 volume을 지우고 클러스터를 다시 만든다.
- 기존 단일 브로커 `infra-compose.yml`의 Kafka와 포트 19092·9099가 겹친다. 두 구성을 동시에 띄우지 않는다.
- ADR 0040의 "소비 설정을 고르기 전에는 자동 구독하지 않는다"는 조건이 채워졌다. ADR 0040은 기본값과 test profile에서 계속 유효하다.

## Evidence and limits

- [구현 기록](../week6/implementation-log.md#local-topics-and-activation): 새로 띄운 로컬 클러스터에서 init이 두 topic을 정한 값으로 만들었다(ISR 3). 세 브로커의 실효 설정에서 자동 생성 false·offsets 복제 3을 확인했다. 없는 topic으로 보내면 만들어지지 않고 metadata timeout이 났다.
- `__consumer_offsets`는 consumer group이 처음 쓰일 때 만들어지므로, 복제 3은 streamer를 처음 실행한 뒤에 확인해야 한다.
- local profile의 구독 등록·concurrency 3은 설정 테스트로 확인했다. 실제 broker에 붙여 처리한 결과는 두 앱 연결 검증에서 기록한다.
