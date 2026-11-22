# ADR 0083: 완성 조건을 통과하지 못한 snapshot은 ABANDONED로 표시하고 다음 revision으로 다시 계산한다

Status: Accepted
Extends: [ADR 0082](0082-publish-verified-snapshot-atomically-and-converge.md) (완성 조건 실패의 복구 경로와 `ABANDONED` 상태를 더한다)
Related: [9주차 설계 6절](../week9/design.md), [구현 기록](../week9/implementation-log.md)

## Context

공개 Step은 다음을 확인한다. 하나라도 다르면 공개하지 않는다(ADR 0082).

- stage 행 수 = 상품 수
- stage 점수 합 = 입력 수량 합
- 입력 요약 = 고정 당시 값

이 검증이 실패하는 대표적인 원인은 계산 코드의 오류다. 이때는 복구할 길이 막힌다.

- 계산 Step은 이미 `COMPLETED`라, 같은 parameter의 재시작은 공개만 다시 하고 같은 이유로 실패한다.
- 같은 기간의 다음 revision은 `FROZEN`인 이전 revision 때문에 시작할 수 없다(ADR 0082의 직렬 실행).

## Decision

- snapshot 상태에 `ABANDONED`를 둔다. 운영자가 원인을 고친 뒤 `FROZEN`인 snapshot을 `ABANDONED`로 바꾼다. 행과 Batch 기록은 지우지 않는다.
- 같은 기간의 직렬 실행 검사는 `FROZEN`만 막는다. `ABANDONED`는 다음 revision을 막지 않는다.
- revision 번호는 계속 마지막 + 1이다. `ABANDONED`도 번호를 차지한다.
- 공개 Step은 `ABANDONED` snapshot을 거부한다. 그 JobInstance를 재시작해도 공개되지 않는다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| 계산 Step을 완료된 뒤에도 재실행 허용(`allowStartIfComplete`) | 같은 입력으로 점수를 처음부터 다시 계산해 고칠 수 있다(절대값 쓰기라 안전). 하지만 공개만 실패한 정상 경우에도 매번 전체를 다시 계산하고, "틀린 계산이 있었다"는 사실이 기록에 남지 않는다 |
| stage를 지우고 계산 Step 기록을 FAILED로 되돌림 | 같은 instance로 다시 계산할 수 있지만 stage·metadata를 지워 이전 실행의 흔적을 없앤다 |
| 복구 경로를 두지 않고 문서로만 남김 | 버그가 나면 그 기간을 다시 계산할 정해진 방법이 없다 |

## Consequences

- 다시 계산한 revision은 그 시점의 원본으로 입력을 새로 고정한다. 포기한 snapshot 이후 도착한 구매도 들어간다.
- 운영자가 SQL로 상태를 바꾸는 절차가 생긴다. 이 저장소에 관리 API는 만들지 않는다.
- 감지기(ADR 0079)는 `ABANDONED`를 실패한 revision으로 보지 않아야 한다. 재시작 대상은 `FROZEN`이다.

## 검증

`WeeklyRankingPublishTest`에서 확인했다. 계산 버그를 흉내 내 상품 2 점수를 1 크게 계산했다.

- r1의 공개가 거부되고 r1은 `FROZEN`이다. 이 상태에서는 r2가 막힌다.
- 버그를 고치고 r1을 `ABANDONED`로 표시한 뒤에도 r3(건너뛰기)은 거부된다.
- 앞서 막혔던 r2 instance를 재시작하면 COMPLETED, pointer r2, 랭킹 `2:4:1`, `1:3:2`가 된다.
- 포기한 r1을 재시작하면 FAILED이고 pointer는 r2 그대로다.
