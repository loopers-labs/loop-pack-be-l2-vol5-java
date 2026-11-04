# ADR 0042: consumer의 DB 저장을 최대 2회 재시도한다

Status: Superseded by [ADR 0053](0053-retry-transient-consumer-failures-for-minutes-before-stopping.md) — 아래 본문은 결정 당시 기록으로 보존한다
Related: [ADR 0041](0041-separate-invalid-messages-from-db-retry-exhaustion.md), [week6 설계](../week6/design.md#consumer-failure-policy)

Verification: 후속 격리 검증은 [실험 문서](../week6/kafka-settings-experiments.md#selected-db-retry-experiment)와 [최신 구현 기록](../week6/implementation-log.md#selected-settings-verification)에 둔다. 아래 본문은 결정 당시 기록으로 보존한다.
Follow-up: 두 재시도 전 1초·2초 대기는 [ADR 0043](0043-increase-db-retry-delays-from-one-to-two-seconds.md)에서 구체화한다. 최대 2회 결정은 Accepted를 유지하며 아래 본문은 간격 선택 전의 맥락을 보존한다.

## Context

메시지 자체 오류는 DLQ로 분리하고, 재시도 대상으로 분류한 DB 오류가 제한 재시도 뒤에도 계속되면 소비를 중단하기로 했다. ADR 0041에서는 재시도 횟수와 간격을 미정으로 남겼다. 사용자는 첫 시작값으로 재시도 2회를 선택했다.

## Decision

- consumer가 record의 DB 저장에 처음 실패한 뒤 **추가로 최대 2회 재시도**한다. 최초 시도까지 합쳐 최대 3번이며 중간에 성공하면 그 시점에 정상 처리를 이어간다.
- 최초 시도와 두 재시도가 모두 실패하면 ADR 0041에 따라 소비를 중단하고 원인을 드러낸다. 실패 record를 지나가도록 offset을 commit하거나 DB 재시도 소진만으로 DLQ로 보내지 않는다.
- 재시도 사이의 대기 간격은 아직 선택하지 않았다. 상세 예외 분류·중단 범위·재시작 주체와 DLQ 세부 구성도 후속 선택이다.
- 이 횟수는 consumer의 DB 저장 재시도에 적용할 정책이다. producer 전송 재시도와 구분한다. 업무용 listener·error handler 구현이나 공통 YAML의 producer retries 변경은 이번 결정에 포함하지 않는다.

## Rationale and alternatives

짧은 오류의 자동 회복 기회를 주면서 계속 실패할 때 중단하는 시작값으로 2회를 택했다. 앞선 격리 실험에서도 2회를 사용해 시도 수를 이해할 수 있었지만, 이것이 운영 최적 횟수임을 측정한 결과는 아니다.

재시도 3회는 최초 시도까지 최대 4번이다. 사용자는 우선 2회를 선택했고 대기 간격과 실제 실패 처리 조합의 검증은 나누어 진행한다.

## Consequences

실제 연결 시 최초 시도와 추가 재시도를 구분해 관찰해야 한다. 성공한 시도에서는 DB commit 또는 동일 사실 no-op 뒤에 offset을 진행하고, 세 번 모두 실패하면 offset 보호·소비 중단·같은 group 재시작을 검증한다. 메시지 자체 오류의 DLQ 정책은 유지한다.

## Evidence

- [기존 실패 처리 비교](../week6/kafka-settings-experiments.md#failure-handling-experiment)는 2회·200ms와 기본 recoverer를 사용했다. 이번에 횟수만 2회로 선택했으며 200ms나 실패 후 skip을 채택한 것은 아니다.
- [이번 선택 기록](../week6/implementation-log.md#db-retry-count)
- 문서로 횟수를 채택한 변경이며 새 테스트를 실행하지 않았다. 업무용 제한 재시도 후 중단 조합과 DLQ 경로는 아직 구현·검증 전이다.
