# ADR 0008: Aggregate 자식은 소유 외래 키로 저장한다

Status: Accepted
Date: 2026-09-18

## Context

`OrderItem`, `PointGrant`, `PointUsage`는 각각 Order 또는 PointBalance가 소유하는 자식이다. 단방향 `@OneToMany`의 기본 매핑을 사용하면 JPA가 조인 테이블을 만든다. DB 통합 테스트 정리 과정에서 이 자동 조인 테이블의 관계가 남아 다음 테스트의 저장과 충돌한 문제가 있었다.

## Decision

자식 테이블이 부모를 직접 참조하도록 `@JoinColumn`을 사용한다.

- Order → OrderItem: `order_id`
- PointBalance → PointGrant: `point_balance_id`
- PointGrant → PointUsage: `point_grant_id`

## Options considered

| Option | Benefit | Cost | Why not chosen |
| --- | --- | --- | --- |
| 기본 조인 테이블 유지 | 매핑 선언이 짧다 | 소유 경계가 DB에 드러나지 않고 정리 대상 테이블이 늘어난다 | Aggregate 소유 관계와 맞지 않는다 |
| 자식 테이블의 소유 외래 키 | DB 관계가 Aggregate 소유 경계와 같다 | 자식 테이블이 부모 외래 키를 가진다 | 선택 |

## Consequences

부모를 저장하면 cascade로 자식도 저장하고, 부모에서 제거하면 orphan removal로 자식도 제거한다. DB 통합 테스트는 이 외래 키 테이블까지 포함해 실제 저장·재조회와 정리 동작을 검증한다.

## Evidence

- [구현 기록](../week2/implementation-log.md)
- `apps/commerce-api/src/main/java/com/loopers/domain/order/OrderModel.java`
- `apps/commerce-api/src/main/java/com/loopers/domain/point/PointBalanceModel.java`
- `apps/commerce-api/src/main/java/com/loopers/domain/point/PointGrant.java`

## 3주차 보완 (2026-10-09)

PointBalance → PointGrant, PointGrant → PointUsage의 JoinColumn에 `nullable=false`를 지정했다. 부모 없이 INSERT한 다음 부모 ID를 UPDATE하던 SQL 대신 INSERT부터 부모 ID를 함께 저장한다. 포인트 잠금 적용 중 다른 사용자 충전이 대기하는 테스트가 이 변경 후 통과했으며, 같은 사용자의 대기는 유지된다. 자세한 조회·잠금 정책은 ADR 0010을 따른다.
