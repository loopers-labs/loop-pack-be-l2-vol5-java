# ADR 0093: Step 안에서는 재시도하지도 건너뛰지도 않는다

Status: Accepted
Related: [9주차 설계 6절](../week9/design.md), [ADR 0090](0090-reject-non-positive-quantity-in-frozen-input.md)

## Context

실패에는 두 종류가 있다.

- **다시 하면 성공할 수 있는 것**: 잠금 대기 시간 초과, 교착 상태, 연결 끊김, 프로세스 종료
- **다시 해도 같은 것**: 잘못된 입력(ADR 0090), 완성 조건 불일치, revision 규칙 위반, 코드 결함

입력을 고정했기 때문에 두 번째 종류는 다시 해도 결과가 바뀌지 않는다. Spring Batch는 chunk Step에 재시도(`faultTolerant().retry()`)와 건너뛰기(`skip`)를 제공한다.

## Decision

Step 안에서는 재시도하지도 건너뛰지도 않는다. 실패하면 Job은 `FAILED`로 끝나고 0이 아닌 종료 코드를 돌려준다. 다시 시도는 같은 parameter로 Job을 다시 실행하는 것이다. 완료한 Step은 건너뛰고 점수 계산은 커밋한 다음 상품부터 이어 가므로 결과가 같다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| 잠금 오류만 chunk 안에서 2~3회 재시도 | 일시 오류가 날 자리가 거의 없다. 입력 고정은 원본을 잠그지 않고(ADR 0085), 같은 snapshot을 쓰는 다른 실행은 Spring Batch가 막는다. 재시도할 예외와 하지 않을 예외를 따로 관리해야 하고, 재시도가 원인을 가릴 수 있다 |
| 실패한 상품을 건너뛰고 계속 | 그 상품이 조용히 빠진 랭킹이 완성본이 된다. 공개 단계의 상품 수 확인에서 막히더라도 원인이 흐려진다 |
| tasklet 안에 `RetryTemplate` | tasklet에는 chunk 재시도가 적용되지 않아 따로 만들어야 하고, 위와 같은 이유로 얻는 것이 작다 |

## Consequences

- 잠깐의 오류에도 Job이 `FAILED`로 끝난다. 다시 실행은 바깥 스케줄러나 운영자가 한다. 자동으로 다시 띄울지와 그 횟수·간격은 스케줄러 설정이다.
- 다시 해도 같은 실패는 원인을 고친 뒤 다시 실행하고, 계산이 틀렸으면 그 snapshot을 포기하고 다음 revision으로 계산한다(ADR 0083).

## 검증

코드에 `faultTolerant()`·`retry`·`skip` 설정이 없다(`WeeklyRankingJobConfig`). 실패 뒤 같은 parameter로 다시 실행한 결과가 정상 1회 실행과 같은 것은 `RankingJobRestartTest`가 확인한다.
