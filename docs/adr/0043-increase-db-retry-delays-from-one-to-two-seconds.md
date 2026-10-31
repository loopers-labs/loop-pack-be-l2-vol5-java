# ADR 0043: 두 번의 DB 재시도 전에 각각 1초와 2초를 기다린다

Status: Superseded by [ADR 0053](0053-retry-transient-consumer-failures-for-minutes-before-stopping.md) — 아래 본문은 결정 당시 기록으로 보존한다
Related: [ADR 0042](0042-use-two-db-retries-before-stopping-consumer.md), [week6 설계](../week6/design.md#consumer-failure-policy)

Verification: 후속 격리 검증은 [실험 문서](../week6/kafka-settings-experiments.md#selected-db-retry-experiment)와 [최신 구현 기록](../week6/implementation-log.md#selected-settings-verification)에 둔다. 아래 본문은 결정 당시 기록으로 보존한다.

## Context

consumer의 DB 저장을 최초 실패 뒤 최대 2회 재시도하고, 모두 실패하면 소비를 중단하기로 했다. 대기 간격은 남겨 두었다. 사용자는 고정 간격과 증가 간격을 비교한 설명을 보고 두 번째 재시도의 대기를 늘리는 시작값을 선택했다.

## Decision

- 최초 DB 저장 실패 뒤 **1초 대기 후 첫 번째 재시도**, 이것도 실패하면 **2초 대기 후 두 번째 재시도**를 수행한다.
- 재시도는 최대 2회다. 중간에 성공하면 정상 처리를 이어가고, 최초 시도와 두 재시도가 모두 실패하면 ADR 0041의 소비 중단·offset 보호를 따른다. 두 번째 재시도 실패 뒤 추가로 기다리며 세 번째 재시도를 하지 않는다.
- 모두 실패했을 때 재시도 대기의 합계는 3초다. DB 처리·poll 등 실제 실행에 걸리는 시간은 별도로 더해진다.
- 예외 분류·중단 범위·재시작 주체와 DLQ의 세부 구성은 후속 선택이다. 이 변경은 간격의 채택이며 업무용 listener를 활성화하거나 producer 재시도를 변경하지 않는다.

## Rationale and alternatives

고정 1초 간격은 두 번 모두 같은 시간 기다린다. 선택한 1초·2초는 실패가 계속될 때 회복을 더 기다리는 시작값이다. 이를 운영 최적값으로 측정한 결과는 없으며, 실제 동작·지연·DB 부하를 검증하고 필요하면 후속 결정으로 조정한다.

## Validation scope

설정의 선택은 사용자가 의도한 동작을 정하는 단계다. 테스트는 정한 1초·2초 대기·최대 2회·소진 후 중단과 실제 offset 보호가 구현되었는지 검증한다. 부하 실험은 처리량과 지연의 적합성을 판단할 별도 근거다.

현재까지의 비교는 2회·200ms 뒤 기본 skip과 즉시 중단을 각각 관찰한 것이다. **1초·2초 뒤 중단하는 새 조합은 아직 구현·실행하지 않았다.**

## Evidence

- [기존 실패 처리 비교와 현재 선택 상태](../week6/kafka-settings-experiments.md#failure-handling-experiment)
- [간격·기존 선택 반영 기록](../week6/implementation-log.md#consumer-settings-adoption)
- 이번 변경에서는 문서만 갱신했고 새 테스트를 실행하지 않았다.
