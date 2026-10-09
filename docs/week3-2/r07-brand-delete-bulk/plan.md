# R07 구현 계획

[요구사항](requirement.md) · [트레이드오프](trade_off/total_trade_off.md) · [전체 요구사항](../total_requirement.md)

작업 브랜치: `volume-3/r07-brand-delete-bulk` · PR 대상: `volume-3/main`

상태: 사용자 합의 완료(2026-10-09), Sonnet 위임. R04 브랜치(PR #18)에서 분기했고, R04 병합 후 main 위로 리베이스했다(트리 동일).

## 문서와 진행 원칙

- 결정 근거는 [01 규칙 위치](trade_off/01-rule-location.md), [02 일괄 UPDATE](trade_off/02-bulk-update.md), [03 동시성](trade_off/03-concurrency.md), [04 검증](trade_off/04-verification.md)을 따른다.
- 커밋마다 관련 테스트를 먼저 고치거나 쓴다. 커밋 메시지는 `type: 한국어 한 문장`이며 Co-Authored-By 등 AI 표기 줄을 붙이지 않는다.
- Checkstyle·ArchUnit·기존 테스트 기대값을 완화하지 않는다. 아래에 적은 테스트만 대상 동작이 사라지거나 다른 계층으로 옮겨져 삭제·이동한다.
- 새 파일 첫 줄에는 한국어 역할 주석을 단다(기존 스타일대로 클래스 선언 위). 동시성·`slow` 태그 테스트는 추가하지 않는다. push·PR은 하지 않는다.

## 변경 책임·구조

| 계층 | 요소 | 변경 |
|---|---|---|
| domain | `Brand` | `products` 필드, `restoreForDeletion`, 상품 연쇄 삭제 제거. `delete()`는 `ensureActive()` 후 자신만 삭제 상태로 |
| domain | `BrandRepository` | `findForDeletion` 제거. `findByIdForUpdate(long)`(`PESSIMISTIC_WRITE`), `findByIdForShare(long)`(`PESSIMISTIC_READ`) 추가 |
| domain | `ProductRepository` | `int deleteAllByBrandId(long brandId)` 추가 |
| application | `BrandService` 삭제 | `findByIdForUpdate` → 없으면 `BRAND_NOT_FOUND` → `brand.delete()`(이미 삭제면 `DELETED_BRAND`) → `brandRepository.save(brand)` → `productRepository.deleteAllByBrandId(brandId)` |
| application | `ProductService` 등록 | `brandRepository.findById` → `findByIdForShare`. 수정·재고 설정·단건 삭제는 변경 없음 |
| infrastructure | `BrandJpaEntity`, `BrandEntityMapper` | `@OneToMany products`, `toDomainForDeletion` 제거 |
| infrastructure | `BrandJpaRepository` | `findForDeletion` 제거, `@Lock(PESSIMISTIC_WRITE)`·`@Lock(PESSIMISTIC_READ)` 단건 조회 추가 |
| infrastructure | `BrandRepositoryImpl.save` | 상품 순회·`saveAndFlush` 제거, 브랜드만 저장 |
| infrastructure | `ProductJpaRepository` | `@Modifying(flushAutomatically = true, clearAutomatically = true) @Query("update ProductJpaEntity p set p.deleted = true, p.updatedAt = :now where p.brandId = :brandId and p.deleted = false")` |
| infrastructure | `ProductRepositoryImpl.deleteAllByBrandId` | `Instant.now()`를 넘겨 호출, 영향 행 수 반환 |

`ProductJpaEntity.updatedAt` 필드명이 다르면 실제 이름을 따른다. JPQL에서 `updatedAt`을 쓰려면 엔티티에 해당 필드가 매핑돼 있어야 한다(현재 `updated_at` 컬럼 매핑 있음).

## 호출·트랜잭션 경계

```
DELETE /api-admin/v1/brands/{brandId}
  BrandService.execute(Delete)  @Transactional
    brandRepository.findByIdForUpdate      SELECT … FROM brands WHERE id = ? FOR UPDATE
    brand.delete()                          브랜드 상태만
    brandRepository.save(brand)             UPDATE brands
    productRepository.deleteAllByBrandId    UPDATE products SET deleted = 1, updated_at = ? WHERE brand_id = ? AND deleted = 0

POST /api-admin/v1/products
  ProductService.execute(Create)  @Transactional
    brandRepository.findByIdForShare       SELECT … FROM brands WHERE id = ? FOR SHARE
    brand.ensureActive() → INSERT products
```

## 커밋별 구현 순서

### 커밋 1 — 상품 일괄 삭제 저장소 추가

- [x] `ProductRepositoryIntegrationTest`에 테스트를 먼저 추가한다. 대상 브랜드의 활성 상품 2개만 삭제 상태가 되고 `updated_at`이 바뀌며 반환값이 2인지, 이미 삭제된 상품의 `updated_at`과 다른 브랜드 상품이 그대로인지 확인한다.
- [x] `ProductRepository.deleteAllByBrandId`, `ProductJpaRepository`의 JPQL, `ProductRepositoryImpl` 구현을 추가한다.
- [x] 커밋: `refactor: 브랜드의 미삭제 상품을 한 번에 삭제하는 저장소 기능 추가`

### 커밋 2 — 브랜드 잠금 조회 추가

- [x] `BrandRepository.findByIdForUpdate`·`findByIdForShare`와 구현을 추가한다(`BrandJpaRepository`에 `@Lock` + `@Query` 단건 조회). 기존 `ProductJpaRepository.findByIdForUpdate` 형식을 따른다.
- [x] 통합 테스트는 기존 `BrandRepositoryIntegrationTest`에 "잠금 조회로도 브랜드를 읽는다" 수준 1개만 추가한다(잠금 동작 자체는 검증하지 않음).
- [x] 커밋: `refactor: 브랜드 쓰기·공유 잠금 조회 추가`

### 커밋 3 — 브랜드 삭제를 서비스 호출과 상품 일괄 삭제로 전환

- [x] `BrandServiceTest`(Mockito, 신규)를 먼저 쓴다. 정상 삭제는 `findByIdForUpdate` → `save` → `deleteAllByBrandId` 순서로 호출(`InOrder`), 없는 브랜드는 `BRAND_NOT_FOUND`이고 저장·상품 삭제 미호출, 이미 삭제된 브랜드는 `DELETED_BRAND`이고 저장·상품 삭제 미호출.
- [x] `DeleteBrandRollbackIntegrationTest`의 실패 주입을 `productJpaRepository.saveAndFlush`에서 상품 일괄 삭제(`ProductJpaRepository`의 새 메서드, 공유 spy)로 바꾼다. 브랜드·상품 모두 변경 전 상태이고 다른 대상이 영향받지 않는다는 기대값은 그대로 둔다.
- [x] `BrandService` 삭제 흐름을 바꾼다.
- [x] 커밋: `refactor: 브랜드 삭제를 브랜드 저장 후 상품 일괄 삭제로 전환`

### 커밋 4 — 상품 등록의 브랜드 공유 잠금

- [x] `ProductServiceTest`(Mockito, 신규 또는 기존)에 등록이 `findByIdForShare`로 브랜드를 읽고, 삭제된 브랜드면 `DELETED_BRAND`로 거절하며 저장하지 않는지 확인한다.
- [x] `ProductService` 등록만 `findByIdForShare`로 바꾼다. 수정·재고 설정의 `findBrand`는 그대로 둔다.
- [x] 커밋: `refactor: 상품 등록 시 브랜드를 공유 잠금으로 읽어 동시 삭제와 순서를 정함`

### 커밋 5 — 브랜드→상품 연관관계와 삭제 전용 조회 제거

- [x] 제거: `Brand.products`·`restoreForDeletion`·상품 연쇄 삭제, `BrandRepository.findForDeletion`, `BrandJpaRepository.findForDeletion`, `BrandJpaEntity`의 `@OneToMany`, `BrandEntityMapper.toDomainForDeletion`, `BrandRepositoryImpl.save`의 상품 순회.
- [x] 테스트 정리(대상 동작이 사라지거나 커밋 1·3으로 옮겨짐):
  - `BrandTest`: "연결된 미삭제 상품 전체도 함께 삭제", "연결 상품이 없는 브랜드도 정상 삭제", "이미 삭제된 상품은 다시 처리하지 않음", "상품 목록을 조회하지 않은 상태는 구조적으로 거부"를 삭제하고 "삭제하면 브랜드만 삭제 상태가 된다" 1개로 바꾼다. "이미 삭제된 브랜드는 다시 삭제할 수 없다"는 유지.
  - `BrandRepositoryIntegrationTest`: 삭제 전용 조회·브랜드 저장의 상품 반영 테스트 2개를 삭제한다(커밋 1·2 테스트가 대신함).
  - `BrandFindForDeletionLockIntegrationTest`(slow) 삭제([04](trade_off/04-verification.md)).
- [x] `git grep -n "findForDeletion\|restoreForDeletion\|toDomainForDeletion\|OneToMany" apps/commerce-api/src` 결과가 없어야 한다.
- [x] 커밋: `refactor: 브랜드 삭제 전용 조회와 브랜드-상품 연관관계 제거`

### 커밋 6 — 잠금 범위 측정과 문서 갱신

- [x] R04 [explain 스크립트](../r04-like-sort-index/explain/)(`before.sql` → `seed.sql` → `after.sql`)로 로컬 Docker MySQL의 스크래치 스키마에 상품 100만 건을 넣는다. 트랜잭션 안에서 아래를 실행하고 `performance_schema.data_locks`를 테이블·인덱스별로 센 뒤 롤백한다. 측정 후 스크래치 스키마를 삭제한다.
  - 변경 후 삭제: `SELECT … FROM brands WHERE id = ? FOR UPDATE` → `UPDATE brands SET deleted = 1 …` → `UPDATE products SET deleted = 1, updated_at = NOW(6) WHERE brand_id = ? AND deleted = 0`
  - 변경 후 등록: `SELECT … FROM brands WHERE id = ? FOR SHARE`
  - 비교용 변경 전 수치는 [요구사항 2절](requirement.md#2-현재-동작과-변경점)을 쓴다.
- [x] 결과를 이 문서의 검증 기록에 적는다.
- [x] `docs/test/*.md`, `CLAUDE.md`(브랜드 삭제 설명이 있으면), R01 `result.md` 상단의 후속 결정 안내를 갱신한다.
- [x] 커밋: `docs: 브랜드 삭제 잠금 범위 측정 결과와 관련 문서 갱신`

## 검증 계획

- 커밋마다 `--tests "*Brand*"`, `--tests "*Product*"`와 Checkstyle을 실행한다.
- 마지막에 `./gradlew :apps:commerce-api:check`(test 태스크 한 번으로 slow 포함 전체 실행)를 실행하고 건수·실패·skip을 기록한다.
- **중단 조건:** JPQL 일괄 UPDATE의 실행 계획이 `(deleted, brand_id, …)` 인덱스 범위가 아니거나(측정에서 상품 잠금이 대상 브랜드 활성 상품 수보다 크게 나오면), `PESSIMISTIC_READ`가 MySQL에서 `FOR SHARE`로 나가지 않으면 임의로 바꾸지 말고 SQL·측정 결과와 함께 보고한다.

## 완료 체크리스트

- [x] 브랜드 삭제가 해당 브랜드 활성 상품만 잠그는 것을 측정으로 기록
- [x] 삭제 전용 조회·연관관계 제거
- [x] 기존 브랜드 삭제 E2E·롤백 테스트가 기대값 변경 없이 통과
- [x] `./gradlew :apps:commerce-api:check` 통과

## 검증 기록

### 생성된 SQL (테스트 로그의 Hibernate SQL)

- 상품 일괄 삭제: `update products pje1_0 set deleted=1,updated_at=? where pje1_0.brand_id=? and pje1_0.deleted=0`
- 브랜드 삭제 조회: `select … from brands bje1_0 where bje1_0.id=? for update`
- 상품 등록의 브랜드 조회: `select … from brands bje1_0 where bje1_0.id=? for share` (`PESSIMISTIC_READ`가 `FOR SHARE`로 나감)

### 잠금 범위 측정 (2026-10-09, MySQL 8.0 Docker, R04 스크립트로 `r04_after`에 상품 100만 건, 브랜드 500에 활성 상품 1,000건, 트랜잭션 후 ROLLBACK, 측정 후 스크래치 스키마 삭제)

`EXPLAIN UPDATE products SET deleted = 1, updated_at = NOW(6) WHERE brand_id = 500 AND deleted = 0`: `type=range`, `key=idx_products_deleted_brand_created`(key_len 9, ref `const,const`), `rows=1000`, `Using where; Using temporary`.

| 구분 | 실행 | 잠긴 행/락(`performance_schema.data_locks`) |
|---|---|---|
| 변경 전 삭제(요구사항 2절) | `findForDeletion` 조회 | 상품 PRIMARY 1,000,000 + 보조 인덱스 1,002,941 |
| 변경 후 삭제 | `SELECT … FROM brands WHERE id = 500 FOR UPDATE` → `UPDATE brands SET deleted = 1` → 위 상품 UPDATE(영향 행 1,000) | brands PRIMARY RECORD 1, products PRIMARY RECORD 1,000, products `idx_products_deleted_brand_created` RECORD 1,004 (+ 테이블 의도 락 각 1) |
| 변경 후 등록 | `SELECT id FROM brands WHERE id = 500 FOR SHARE` | brands PRIMARY RECORD 1 (`S,REC_NOT_GAP`), 상품 락 없음 |

대상 브랜드의 활성 상품 수(1,000) 수준이며 중단 조건에 해당하지 않는다.

### 최종 `./gradlew :apps:commerce-api:check`

BUILD SUCCESSFUL. 테스트 260건(클래스 99개), 실패 0, 오류 0, skip 0(`build/test-results/test/*.xml` 집계, Checkstyle·ArchUnit 포함 통과).
`git grep -n "findForDeletion\|restoreForDeletion\|toDomainForDeletion\|OneToMany" apps/commerce-api/src`는 무관한 `OrderJpaEntity`의 `@OneToMany`만 남는다.
