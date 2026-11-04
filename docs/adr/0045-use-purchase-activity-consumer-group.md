# ADR 0045: 구매 활동 consumer group 이름을 purchase-activity로 정한다

Status: Accepted — 주문용 listener에 이름 연결, 기본 비활성
Related: [ADR 0044](0044-adopt-earliest-and-three-order-consumers.md), [week6 설계](../week6/design.md#consumer-baseline)

Verification: 후속 격리 검증은 [실험 문서](../week6/kafka-settings-experiments.md#three-consumer-purchase-experiment)와 [최신 구현 기록](../week6/implementation-log.md#selected-settings-verification)에 둔다. 아래 본문은 결정 당시 기록으로 보존한다.

Follow-up (ack): 당시 [ADR 0046](0046-acknowledge-order-records-with-manual-immediate.md)에서 MANUAL_IMMEDIATE를 선택했고, 현재는 [ADR 0054](0054-commit-order-consumer-offsets-per-poll.md)의 BATCH로 대체했다. 이 ADR의 기존 결정은 Accepted를 유지하며 아래 본문은 결정 당시 기록으로 보존한다.

Implementation (listener): [조건부 주문용 listener·factory 연결](../week6/implementation-log.md#order-listener-wiring). 기본 비활성과 기존 결정은 유지하며 아래 본문은 당시 기록으로 보존한다.

## Context

주문 확정 이벤트를 읽어 구매 활동을 저장할 consumer를 같은 group 전체에서 3개 실행하기로 했다. 실제 group 이름은 남겨 두었고 사용자는 purchase-activity를 선택했다.

## Decision

- 구매 활동 업무의 group.id는 **purchase-activity**다. 이 업무를 나눠 처리하는 consumer 3개는 같은 group 이름을 사용한다.
- 같은 group으로 재시작해 해당 topic·partition의 저장된 읽기 위치를 이어 사용한다. 유효한 저장 위치가 없을 때의 earliest 정책은 ADR 0044를 따른다.
- 알림·통계 등 같은 이벤트를 별도로 읽는 다른 업무는 별도 group으로 구분하는 원칙을 따른다. 다른 업무의 구체적인 group 이름은 이번에 정하지 않는다.
- 실제 업무용 listener·factory 적용은 후속 작업이다. 공통 템플릿의 loopers-default-consumer와 demo 설정을 이번에 전역 변경하지 않는다. ack mode·오류 분류·중단 범위·재시작 주체와 DLQ 세부 구성은 아직 남아 있다.

## Rationale

group 이름에 구매 활동이라는 업무 목적을 드러낸다. 공통 템플릿 이름을 그대로 사용하기보다 같은 topic을 독립적으로 읽을 다른 업무와 group의 처리 위치·partition 배정을 구분하기 쉽다. 이 이름 자체를 성능 비교로 정한 것은 아니다.

## Evidence and limits

- [같은 group의 배정 실험](../week6/kafka-settings-experiments.md#consumer-count-experiment)은 group 안에서 consumer가 partition을 나눠 맡는 동작을 확인했다. 이번 이름으로 업무용 listener를 실행한 결과는 아니다.
- [이름 선택·미적용 상태 기록](../week6/implementation-log.md#purchase-activity-group)
- 문서로 이름을 채택한 변경이며 새 테스트·Java/YAML 변경·자동 구독 활성화는 실행하지 않았다.
