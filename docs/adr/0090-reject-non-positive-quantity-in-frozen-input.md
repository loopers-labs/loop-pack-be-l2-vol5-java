# ADR 0090: 입력 고정에서 수량이 0 이하인 구매 활동을 만나면 건너뛰지 않고 실패한다

Status: Accepted
Related: [ADR 0081](0081-freeze-snapshot-once-in-revision-order.md), [ADR 0085](0085-freeze-input-with-read-committed-insert-select.md), [9주차 설계 3절](../week9/design.md)

## Context

구매 활동은 commerce-streamer가 저장한 사실이다. 정상이라면 수량은 양수다. 수량이 0 이하인 행은 앞 단계가 깨졌다는 신호다.

공개 단계는 "점수 합 = 입력 수량 합"을 확인한다. 하지만 음수 수량이 섞여도 두 합은 같아지므로 이 확인을 통과한다. 그러면 점수가 0 이하인 상품이 순위에 들어간 완성본이 공개될 수 있다.

## Decision

- 입력 고정 Step은 원본을 복사한 직후, 같은 TX 안에서 **복사한 snapshot 입력**에 수량이 0 이하인 행이 있는지 센다.
- 하나라도 있으면 그 건수를 담아 실패한다. 같은 TX라 복사본과 snapshot 정보가 함께 rollback된다.
- 원본의 주인(commerce-streamer) 쪽에서 원인을 고친 뒤 같은 parameter로 다시 실행한다.

원본이 아니라 복사본을 확인한다. 이 Step은 READ COMMITTED라, 원본을 따로 다시 읽으면 복사한 행과 확인한 행이 다를 수 있다(ADR 0085). 복사본에서 세면 둘이 같다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| 그 행만 건너뛰고 계속 | Job이 완료로 끝나 원인이 묻힌다. 상품이 조용히 빠진 랭킹이 공개될 수 있다 |
| 복사 전에 원본을 확인 | READ COMMITTED에서는 확인과 복사 사이에 들어온 행을 놓친다 |
| 확인하지 않고 streamer의 검사를 믿음 | 공개 단계의 합계 확인으로는 음수를 잡지 못한다 |
| `ranking_snapshot_input`에 `CHECK (quantity > 0)` | DB가 막아 주지만 `CREATE TABLE IF NOT EXISTS`로는 이미 있는 테이블에 붙지 않고, 실패 메시지에 원인이 드러나지 않는다 |

## Consequences

- 입력 고정마다 복사본을 한 번 더 세는 조회가 추가된다.
- 잘못된 행이 하나라도 있으면 그 주의 계산이 멈춘다. 원본을 고치기 전까지 이어 가지 않는다.

## 검증

- `WeeklySnapshotFreezeTest`: 수량 2와 0의 구매를 넣자 Job이 FAILED로 끝났고, 실패 메시지에 "수량이 0 이하인 구매 활동이 1건"이 담겼다. snapshot 정보·입력·점수가 모두 남지 않았다.
