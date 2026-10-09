# ADR 0015: 포인트 적립 만료는 날짜 경계로 계산하고 매일 배치로 반영한다

Status: Accepted
Date: 2026-10-08
Refines: [ADR 0006](./0006-point-grants-expire-on-request.md)

## Context

[ADR 0006](./0006-point-grants-expire-on-request.md)은 적립 포인트가 지급 시각의 정확히 1년 후 만료되고, 요청 시 만료를 확인·반영하기로 했다. 요청이 없는 사용자의 저장 잔액은 만료 시각 이후에도 갱신되지 않을 수 있다는 한계가 있었다.

## Decision

요청 시 만료 확인은 유지하면서, 매일 한국 시간 00:00에 실행하는 만료 배치를 추가한다. 새 적립분은 한국 시간 기준 지급일의 1년 뒤 날짜까지 사용 가능하며 다음 날 00:00에 만료한다(정확히 1년 후 시각이 아니라 날짜 경계). 2월 29일 지급분은 다음 해 2월 28일까지 사용 가능하다. 기존 지급건에 저장된 만료 시각은 소급 변경하지 않는다.

`PointExpirationBatch`는 100명씩 사용자 ID를 조회하고 별도 `PointExpirationWorker` bean을 호출한다. 사용자 한 명의 만료 기록과 잔액 변경을 한 트랜잭션으로 묶는다. 실패는 바깥에서 로그로 기록한 뒤 다음 사용자를 처리하고, 즉시 재시도하지 않는다. 다음 배치에서 다시 처리할 수 있다. 배치 기준 시각은 시작 때 고정한다.

## Consequences

배치가 도입된 시점에는 포인트를 바꾸는 다른 요청과의 동시성 보호 방식이 아직 미정이었다. 따라서 자동 실행은 `point.expiration.scheduling-enabled=true`로 명시적으로 켜며 기본 비활성화한다. 이후 결제·충전·조회·배치가 같은 사용자 잔액 잠금과 지급 기록 잠금 조회를 사용하도록 구현했다 ([ADR 0010](./0010-point-balance-locks-before-ledger-read.md) 참고). 자동 배치 실행은 여전히 명시적으로 설정을 켜는 방식을 유지한다.

## Evidence

- [ADR 0006](./0006-point-grants-expire-on-request.md) — 만료 시각 규칙의 원 결정
- [ADR 0010](./0010-point-balance-locks-before-ledger-read.md) — 이후 적용된 동시성 보호
- [3주차 설계 2.4](../week3/design.md#24-포인트-결제충전만료)

- [3주차 구현 기록 4.2](../week3/implementation-log.md#42-날짜-기준-만료와-매일-배치) — 테스트 결과
