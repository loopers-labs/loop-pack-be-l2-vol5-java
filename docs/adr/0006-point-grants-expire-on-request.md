# ADR 0006: 포인트는 지급 건과 변동 기록으로 관리하고 요청 시 만료한다

Status: Accepted
Date: 2026-09-17
Refined by: [ADR 0015](./0015-point-expiration-uses-date-boundary-and-daily-batch.md) (만료 시각 계산, 일일 배치), [ADR 0010](./0010-point-balance-locks-before-ledger-read.md) (동시성 보호)

## Context

충전 포인트는 만료되지 않고, 주문 결제의 2% 적립 포인트는 지급 시각의 정확히 1년 후 만료된다. 단순 잔액만 저장하면 어느 포인트가 먼저 만료·사용됐는지 알 수 없고, 만료를 중복 반영할 위험이 있다.

## Decision

`PointGrant`는 충전·적립 지급 사실과 만료 시각을 저장한다. `PointUsage`는 지급 건에서 빠진 금액을 `PAYMENT` 또는 `EXPIRATION` 타입으로 기록한다. 사용 가능 금액은 지급액에서 두 타입의 기록 합계를 뺀 값으로 계산한다.

잔액 조회, 충전, 주문 최초 확정 직전에 만료된 적립 지급 건의 남은 금액을 `EXPIRATION` 기록으로 반영한다. 별도 배치는 두지 않는다. 결제에는 만료 시각이 가까운 지급 건부터 사용하고, 만료 없는 충전분은 마지막에 사용한다.

## Consequences

- 요청이 없는 사용자의 저장 잔액은 만료 시각 직후 즉시 갱신되지 않을 수 있다.
- 요청 시점의 잔액과 결제 가능 금액은 정확하게 계산된다.
- 지급·사용·만료 이력이 남아 계산 근거를 확인할 수 있다.
- 대량 만료 처리·알림·정시 회계 처리가 필요해지면 배치를 별도 도입한다.
- 3주차에 만료 시각 계산을 날짜 경계로 다듬고 매일 배치를 추가했다. [ADR 0015](./0015-point-expiration-uses-date-boundary-and-daily-batch.md) 참고.
- 3주차에 결제·충전·조회·배치의 동시성 보호를 추가했다. [ADR 0010](./0010-point-balance-locks-before-ledger-read.md) 참고.
