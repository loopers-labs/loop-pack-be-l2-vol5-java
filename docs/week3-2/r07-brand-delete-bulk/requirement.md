# R07. 브랜드 삭제의 상품 일괄 삭제 전환

[전체 요구사항](../total_requirement.md) · 작업 브랜치: `volume-3/r07-brand-delete-bulk` · PR 대상: `volume-3/main`

상태: 구현·검증 완료([결과](result.md)). 최종 check 260건 통과. 브랜치는 R04 브랜치(PR #18)에서 분기했고 R04 병합(PR #18) 후 main 기준으로 리베이스했다. [PR #19](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/19) 병합 완료.
기준 자료: 2026-10-09 브랜드 삭제 방식 조사(아래 2절 측정), 사용자 제안 "애플리케이션 레이어에서 브랜드를 삭제하고 그 브랜드 id를 가진 상품을 모두 삭제".

## 1. 목적

브랜드 삭제가 상품 테이블 전체를 잠그지 않고 해당 브랜드의 활성 상품만 잠그게 한다. 삭제를 위해서만 존재하던 브랜드→상품 연관관계와 도메인 상품 목록을 없앤다.
삭제와 동시에 들어온 상품 등록이 "삭제된 브랜드의 활성 상품"을 남기는 구멍도 막는다.

## 2. 현재 동작과 변경점

| 구분 | 현재 코드에서 확인한 동작 | 요구하는 변경 |
|---|---|---|
| 삭제 조회 | `BrandJpaRepository.findForDeletion`: 브랜드 `LEFT JOIN FETCH` 상품 전체 `ORDER BY p.id` + `PESSIMISTIC_WRITE` | 브랜드 행만 `PESSIMISTIC_WRITE`로 조회 |
| 삭제 규칙 | 도메인 `Brand.delete()`가 브랜드와 미삭제 상품을 함께 삭제(R01) | `Brand.delete()`는 브랜드만. `BrandService`가 브랜드 저장 후 상품 일괄 삭제 호출 |
| 상품 반영 | 상품마다 `findById` → `apply` → `saveAndFlush` | JPQL 일괄 UPDATE 1회 |
| 연관관계 | `BrandJpaEntity`의 조회용 `@OneToMany`, `Brand.products`·`restoreForDeletion`, `toDomainForDeletion` | 제거 |
| 상품 등록 | 브랜드를 잠금 없이 읽고 활성 확인 | 브랜드를 `PESSIMISTIC_READ`(`FOR SHARE`)로 읽음 |

조사 측정(MySQL 8.0, 상품 100만 건, 브랜드 하나에 상품 1,000건, 롤백으로 측정):

| | 실행 계획 | 잠긴 상품 행(`performance_schema.data_locks`) |
|---|---|---|
| 현재 삭제 조회 | 상품 인덱스 전체 스캔(약 99만 행) + filesort | PRIMARY 1,000,000 + 보조 인덱스 1,002,941 |
| `UPDATE products SET deleted = 1 WHERE brand_id = ? AND deleted = 0` | `(deleted, brand_id, …)` 인덱스 범위 | PRIMARY 1,000 + 보조 인덱스 1,004 |

원인: `products`에 `brand_id`로 시작하는 인덱스가 없어 `brand_id`만으로 조인하는 잠금 조회가 인덱스를 처음부터 끝까지 훑고, 훑은 행을 모두 잠근다.

## 3. 포함·제외 범위

**포함**

- 브랜드 삭제 흐름, 상품 일괄 삭제 저장소 계약과 구현, 연관관계·삭제 전용 조회 제거.
- 상품 등록의 브랜드 공유 잠금.
- 변경 후 잠금 범위 수동 측정과 기록.

**제외**

- 상품 수정·재고 설정·단건 삭제의 잠금 변경(상품 행 잠금으로 이미 안전, [03](trade_off/03-concurrency.md)).
- 브랜드 삭제와 주문 확정 사이 데드락 방지(한계로 기록).
- 새 인덱스 추가, API 계약 변경.

## 4. 규칙

- 브랜드를 삭제하면 같은 트랜잭션에서 그 브랜드의 미삭제 상품이 모두 삭제된다. 실패하면 브랜드 삭제도 함께 롤백된다(R01 유지).
- 이미 삭제된 상품의 상태와 `updated_at`은 바뀌지 않는다. 다른 브랜드의 상품은 바뀌지 않는다.
- 없는 브랜드는 `BRAND_NOT_FOUND`, 이미 삭제된 브랜드는 `DELETED_BRAND`로 기존 응답을 유지한다.
- 삭제와 동시에 들어온 상품 등록은 삭제가 끝난 뒤 브랜드를 다시 확인해 거절되거나, 삭제보다 먼저 끝나 함께 삭제된다.

## 5. 시나리오와 기대 결과

| 시나리오 | 사전 상태 | 실행 | 기대 결과 | 유지되어야 할 상태 |
|---|---|---|---|---|
| 브랜드 삭제 | 활성 상품 2개, 삭제된 상품 1개, 다른 브랜드 상품 1개 | 브랜드 삭제 | 브랜드·활성 상품 2개 삭제 | 삭제된 상품의 `updated_at`, 다른 브랜드 상품 |
| 실패 롤백 | 활성 상품 여러 개 | 상품 일괄 삭제 단계에서 실패 | 브랜드·상품 모두 변경 전 상태 | 기존 R01 롤백 계약 |
| 없는·삭제된 브랜드 | — | 삭제 | 404 `BRAND_NOT_FOUND` / `DELETED_BRAND` 응답 유지, 상품 일괄 삭제 미호출 | — |
| 잠금 범위 | 상품 100만 건 | 브랜드 삭제 SQL을 트랜잭션 안에서 실행 | 잠긴 상품 행이 해당 브랜드 활성 상품 수 수준 | — |

## 6. 완료 조건

- 운영 코드에서 `findForDeletion`, `restoreForDeletion`, `toDomainForDeletion`, `BrandJpaEntity`의 `@OneToMany`가 사라진다.
- 위 시나리오 테스트가 통과하고, 잠금 범위 측정 결과를 `result.md`에 기록한다.
- `./gradlew :apps:commerce-api:check`가 통과한다. Checkstyle·ArchUnit 규칙과 기존 테스트 기대값을 완화하지 않는다.

## 7. 미정 사항

모두 [트레이드오프](trade_off/total_trade_off.md)에서 결정했다.
