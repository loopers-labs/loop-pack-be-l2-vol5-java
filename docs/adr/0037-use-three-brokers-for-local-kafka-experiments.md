# ADR 0037: Kafka 설정 비교를 위한 로컬 실험 기반을 브로커 3개로 구성한다

Status: Accepted

Follow-up: 업무용 topic의 partition 3개와 consumer 기본 선택은 [ADR 0044](0044-adopt-earliest-and-three-order-consumers.md)에서 별도로 채택한다. 브로커 3개 선택은 Accepted를 유지하며 아래 본문은 당시 미선택 범위를 보존한다.
Related: [week6 설계](../week6/design.md#three-brokers)

Follow-up (topics): [ADR 0050](0050-create-order-topics-in-compose-and-enable-local-events.md)에서 이 클러스터의 자동 생성 끄기·offset topic 복제 3·init topic 생성을 적용한다. 브로커 3개 결정은 Accepted를 유지한다.

## Context

사용자는 이번 주문 과제에서 브로커·복제·전송 확인 등의 조건을 하나씩 바꿔 결과 차이를 배우고자 한다. 기존 로컬 Compose는 broker와 controller를 겸하는 KRaft 서버 1개다. 사용자는 브로커 3개 구성을 선택했으며, 나머지 설정은 미리 정하지 말라고 명시했다.

## Decision

- 브로커 3개를 `docker/kafka-cluster-compose.yml`에 별도의 Compose 프로젝트 `week6-kafka`로 구성한다. Kafka 이미지 버전은 기존과 같은 3.5.1을 유지한다.
- 각 서버는 기존 로컬과 같은 broker·controller 겸용 역할을 맡고, 3개 controller가 하나의 KRaft quorum을 구성한다. node ID·공통 cluster ID·서버 주소·분리된 데이터 volume·관리 UI 연결은 이 환경을 실행하기 위한 설정이다.
- 기존 단일 브로커의 컨테이너·volume을 새 클러스터에 재사용하거나 초기화하지 않는다. 기존 파일은 보존하고 새 환경은 별도 volume을 사용한다.
- 이번 결정은 브로커 수와 실행 기반에 한정한다. 업무용 토픽 생성, partition 수, topic 복제 수, acks, 최소 ISR, producer·consumer 튜닝은 선택·적용하지 않는다. 주문 앱의 로컬 발행 활성화도 수행하지 않는다.

## Options considered

| 구성 | 검토 |
| --- | --- |
| 기존 서버 1개 유지 | 현재 주문 전송 연결을 확인할 수 있으나 여러 브로커 사이의 복제·전환 실험 기반이 없음 |
| 겸용 서버 2개 | partition 복사본을 둘 수 있으나 controller 하나가 중단되면 quorum 과반수를 유지하지 못함 |
| 겸용 서버 3개 | controller 하나 중단 후에도 과반수를 유지하는 실험 기반. 사용자 선택 |

## Consequences

브로커 3개가 있다고 사용자 topic에 replica 3개가 생기지는 않는다. topic의 partition·복제 수는 이후 별도로 정한다. 장애 시 메시지 전송·읽기가 계속되는지는 후속 선택과 실제 실험으로 확인해야 한다.

각 서버는 같은 Mac의 Docker 컨테이너다. 이 환경은 로컬 학습 기반이며 운영 배포의 물리적 장애 분리를 증명하지 않는다. KRaft 겸용 역할·과반수 조건은 [Kafka 3.5 문서](https://kafka.apache.org/35/operations/kraft/)를 참고했다.

## Evidence

- [Compose 구성](../../docker/kafka-cluster-compose.yml)
- 실제 실행·연결 검증은 [week6 구현 기록](../week6/implementation-log.md#three-brokers)에 남긴다. 이후 실험 결과와 현재 설정은 주차 문서에서 관리한다.
