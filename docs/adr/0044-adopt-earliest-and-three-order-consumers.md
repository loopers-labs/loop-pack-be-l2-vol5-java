# ADR 0044: 주문 이벤트는 earliest와 partition 3개·consumer 3개로 시작한다

Status: Accepted — 기본 설정 코드 연결, 업무용 배포·활성화 전
Related: [ADR 0037](0037-use-three-brokers-for-local-kafka-experiments.md), [ADR 0040](0040-prepare-order-event-receiving-without-auto-subscription.md), [week6 설계](../week6/design.md#consumer-baseline)

Verification: 후속 격리 검증은 [실험 문서](../week6/kafka-settings-experiments.md#three-consumer-purchase-experiment)와 [최신 구현 기록](../week6/implementation-log.md#selected-settings-verification)에 둔다. 아래 본문은 결정 당시 기록으로 보존한다.
Follow-up: 구매 활동의 group.id는 [ADR 0045](0045-use-purchase-activity-consumer-group.md)에서 purchase-activity로 정한다. 이 ADR의 기본 구성 결정은 Accepted를 유지하며 아래 본문은 group 이름 선택 전의 맥락을 보존한다.

Follow-up (ack): 당시 [ADR 0046](0046-acknowledge-order-records-with-manual-immediate.md)에서 MANUAL_IMMEDIATE를 선택했고, 현재는 [ADR 0054](0054-commit-order-consumer-offsets-per-poll.md)의 BATCH로 대체했다. 이 ADR의 기존 결정은 Accepted를 유지하며 아래 본문은 결정 당시 기록으로 보존한다.

Implementation (listener): [조건부 주문용 listener·factory 연결](../week6/implementation-log.md#order-listener-wiring). 기본 비활성과 기존 결정은 유지하며 아래 본문은 당시 기록으로 보존한다.

Follow-up (local): [ADR 0050](0050-create-order-topics-in-compose-and-enable-local-events.md)에서 로컬 `order-events`를 partition 3으로 만들고 streamer 1대의 concurrency 3으로 group 전체 3개를 맞춘다. 기존 결정은 Accepted를 유지한다.

## Context

첫 읽기 위치와 consumer 수의 실제 비교를 마친 뒤 사용자는 별도 학습 기록에서 earliest와 partition 3개·동일 group의 활성 consumer 3개를 선택했다. 검토에서 해당 방향에 이견이 없었으며, 사용자는 선택한 내용을 현재 설계에 반영하는 흐름을 확인했다. 기존 선택 대기 표기를 이 기록에 맞춘다.

## Decision

- 주문 이벤트 topic의 partition 수는 **3개**로 시작한다. 기존 메시지 key인 orderId 문자열과 주문 하나의 이벤트에 모든 품목을 담는 계약은 유지한다.
- 해당 group·partition에 유효한 committed offset이 없을 때 **auto.offset.reset=earliest**를 적용해 현재 Kafka에 보존된 기존 이벤트부터 읽는다. 유효한 저장 위치가 있으면 거기서 이어 읽으며, 보존 기간이 지나 삭제된 이벤트를 복구하는 결정은 아니다.
- 구매 활동을 담당하는 **동일 group 전체의 활성 consumer 목표 수는 3개**다. 각 애플리케이션 인스턴스마다 무조건 concurrency=3으로 설정하는 결정은 아니다. 배포 인스턴스·스레드 구성은 실제 연결 때 구체화한다.
- 4번째 consumer, 구체적인 처리율 한도, static membership과 producer batch 값은 채택하지 않는다. 사용자 기록의 가상 수치를 실측 결과로 취급하지 않는다.
- 이 변경에서는 topic을 생성하거나 실제 listener를 등록하지 않는다. group id·ack mode·오류 분류와 DLQ 세부 구성 등 남은 결정을 정한 뒤 선택한 설정을 업무용 코드에 연결한다.

## Rationale and alternatives

그룹을 새로 만들었다는 이유로 보존 중인 확정 주문을 놓치지 않도록 earliest를 선택했다. latest는 초기 위치를 정한 뒤의 새 메시지부터 수집하려는 업무에 해당하는 대안이다.

partition 3개와 consumer 3개를 시작 구성으로 택해 partition별 병렬성을 사용한다. 이는 운영 최적 처리량을 측정해 확정한 수치가 아니다. 한 consumer가 여러 partition을 맡을 수 있으며 추가 consumer가 항상 처리량을 높이는 것도 아니다. 같은 key의 partition 매핑과 순서 전제는 partition 수·partitioner·key 직렬화가 유지되는 조건을 따른다.

## Evidence and limits

- [첫 읽기 위치 비교](../week6/kafka-settings-experiments.md#initial-offset-experiment): 새 group의 earliest/latest 차이와 유효한 committed offset 우선 동작을 확인했다.
- [consumer 수 비교](../week6/kafka-settings-experiments.md#consumer-count-experiment): partition 2개에서 consumer 1·2·3개의 배정을 관찰했다. 업무용 partition 3개·consumer 3개의 처리량·lag·분포를 측정한 결과는 아니다.
- [반영 기록](../week6/implementation-log.md#consumer-settings-adoption)
- 이번에는 기존 사용자 선택을 문서에 반영했으며 새 테스트나 업무용 설정 적용을 실행하지 않았다.
