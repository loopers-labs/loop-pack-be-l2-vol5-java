# 상품 일괄 삭제 구현

[← 전체 선택 현황](total_trade_off.md)

## 판단할 문제

브랜드 삭제 시 해당 브랜드의 미삭제 상품을 한 번에 삭제 상태로 바꿀 저장소 계약과 SQL, 영속성 컨텍스트 처리, 인덱스 필요 여부를 정한다.

> **채택 — 도메인 `ProductRepository.deleteAllByBrandId(long brandId)` + JPQL `@Modifying` 일괄 UPDATE, 새 인덱스 없음**
>
> ```
> update ProductJpaEntity p set p.deleted = true, p.updatedAt = :now
> where p.brandId = :brandId and p.deleted = false
> ```
> - `@Modifying(flushAutomatically = true, clearAutomatically = true)`로 앞선 브랜드 변경을 먼저 반영하고, 같은 트랜잭션의 영속성 컨텍스트가 오래된 상품 값을 보지 않게 한다.
> - `updated_at`은 `@PreUpdate`가 돌지 않으므로 구현에서 `Instant.now()`를 넘긴다.
> - 반환은 삭제한 상품 수(`int`). 테스트·로그 용도다.
> - 기존 `(deleted, brand_id, …)` 인덱스 범위를 쓰므로 인덱스를 추가하지 않는다.
>
> 대신 상품마다 `Product.delete()`를 거치지 않는다. 일괄 삭제의 조건(`deleted = false`)이 도메인의 "이미 삭제된 상품은 그대로" 규칙을 대신한다.

## 장단점 비교

| 기준 | **JPQL 일괄 UPDATE** | native SQL | JDBC |
|---|---|---|---|
| 데이터 접근 기준(쓰기는 JPA) | 맞음 | JPA 안이지만 SQL 문자열 | 배치 전용 기준에 어긋남 |
| 실행 계획 | `(deleted, brand_id)` 범위(측정) | 같음 | 같음 |
| 영속성 컨텍스트 | `clearAutomatically`로 정리 | 같음 | 컨텍스트를 모름 |

## 함께 고민한 내용

1. **조건에 `deleted = false`를 두는 이유:** 이미 삭제된 상품의 `updated_at`을 바꾸지 않는다(R01 규칙). 동시에 이 조건이 인덱스 앞부분 `(deleted, brand_id)`과 맞아 범위 스캔이 된다. 측정에서 잠긴 행이 1,004개로 줄어든 이유다.
2. **`brand_id` 단독 인덱스:** `@OneToMany`가 사라지면 `brand_id`만으로 상품을 찾는 쿼리가 없다. 추가하면 쓰기마다 유지 비용만 는다.
3. **R04와의 관계:** `like_count`는 `updatable = false`라 JPA 저장에는 영향이 없고, 이 UPDATE는 `deleted`·`updated_at`만 바꾼다.

## 옵션별 판단

> **채택 — JPQL 일괄 UPDATE:** 기준에 맞고 필요한 행만 잠근다.

> **미채택 — native SQL / JDBC:** JPA로 표현 가능하고, JDBC는 배치 전용이다.

> **미채택 — `brand_id` 인덱스 추가:** 이 변경에서 쓰는 쿼리가 없다.
