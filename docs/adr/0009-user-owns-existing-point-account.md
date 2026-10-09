# ADR 0009: 사용자와 포인트 계정을 함께 생성한다

Status: Accepted
Date: 2026-10-09

## Context

모든 사용자가 포인트로 결제하는 서비스다. 기존에는 잔액이 없으면 포인트 접근 시 새 계정을 만들었고, 동시 첫 충전에서 생성이 겹칠 수 있었다. 사용자별 잔액 행을 비관적 락으로 보호하기에 앞서 계정의 존재 조건을 정한다.

## Decision

`UserRegistrationService.register()`는 사용자와 잔액 0원 계정을 같은 트랜잭션으로 생성한다. 사용자당 계정 하나는 기존 `user_id` 유니크 제약으로 보장한다. 존재하는 사용자의 계정이 누락되면 `PointBalanceService.getRequired()`는 INTERNAL_ERROR를 발생시키며, 조회·충전 시 자동 복구하지 않는다. 사용자 식별 실패는 기존 NOT_FOUND 정책을 유지한다.

현재 회원가입 HTTP API는 없으므로 application 진입점을 제공하고 테스트 fixture가 이를 사용한다. 포인트 계정 삽입 SQL 후 실패하면 사용자 행까지 롤백해야 한다.

## Consequences

첫 충전부터 잠글 계정이 존재한다. 이후 [ADR 0010](./0010-point-balance-locks-before-ledger-read.md)에 따라 포인트 잠금도 구현했다. 기존 DB의 누락 계정은 적용 전 점검해야 한다. 이번 작업은 기존 데이터를 자동으로 0원 계정으로 보완하지 않는다. 데이터 누락을 정상 신규 계정으로 오인하지 않도록 내역을 확인한 뒤 보완한다.
