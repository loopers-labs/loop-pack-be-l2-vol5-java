# R07 구현 결과

[요구사항](requirement.md) · [트레이드오프](trade_off/total_trade_off.md) · [구현 계획](plan.md) · [전체 요구사항](../total_requirement.md)

작업 브랜치: `volume-3/r07-brand-delete-bulk` (R04 브랜치에서 분기, R04 병합(PR #18) 후 main 위로 리베이스, 트리 동일) · PR 대상: `volume-3/main`

상태: 구현·검증 완료. `./gradlew :apps:commerce-api:check`(test 태스크 한 번으로 slow 포함 전체 실행)에서 테스트 260건 모두 통과했다(실패·오류·skip 0). Checkstyle·ArchUnit도 통과했다.

## 1. 구현 결과

브랜드 삭제가 브랜드 행 하나와 해당 브랜드의 활성 상품만 잠그게 바뀌었다. 삭제만을 위해 있던 브랜드→상품 연관관계와 도메인 상품 목록은 없어졌다.

| 영역 | 이전(R01) | 이후 | 결정 |
|---|---|---|---|
| 삭제 규칙 위치 | 도메인 `Brand.delete()`가 브랜드와 상품을 함께 삭제 | `BrandService`가 브랜드 잠금 → `brand.delete()`(브랜드만) → 저장 → `productRepository.deleteAllByBrandId` 순서로 호출 | [01](trade_off/01-rule-location.md) |
| 삭제 조회 | 브랜드 `LEFT JOIN FETCH` 상품 전체 `FOR UPDATE` | `BrandRepository.findByIdForUpdate`(브랜드 행만) | [01](trade_off/01-rule-location.md) |
| 상품 반영 | 상품마다 `findById` → `apply` → `saveAndFlush` | JPQL `@Modifying(flush·clear)` 일괄 UPDATE 1회(`deleteAllActiveByBrandId(brandId, now)`) | [02](trade_off/02-bulk-update.md) |
| 상품 등록 | 브랜드를 잠금 없이 읽음 | `BrandRepository.findByIdForShare`(`FOR SHARE`) | [03](trade_off/03-concurrency.md) |
| 제거 | — | `Brand.products`·`restoreForDeletion`, `findForDeletion`, `BrandJpaEntity`의 `@OneToMany`, `toDomainForDeletion`, `BrandRepositoryImpl`·`BrandEntityMapper`의 상품 의존성 | [01](trade_off/01-rule-location.md) |

### 호출·SQL (테스트 로그)

```
DELETE /api-admin/v1/brands/{brandId}
  BrandService.execute(Delete)  @Transactional
    select … from brands where id=? for update
    update brands …                                        (brand.delete() 후 저장)
    update products set deleted=1, updated_at=? where brand_id=? and deleted=0

POST /api-admin/v1/products
  ProductService.execute(Create)  @Transactional
    select … from brands where id=? for share → ensureActive → insert products
```

상품 수정·재고 설정·단건 삭제는 바꾸지 않았다. 상품 행 잠금이 이미 일괄 UPDATE와 순서를 정한다([03](trade_off/03-concurrency.md)).

## 2. 실행·검증 결과

### 잠금 범위 (MySQL 8.0, 상품 100만 건, 브랜드 하나에 활성 상품 1,000건, 트랜잭션 안에서 실행 후 롤백)

| | 실행 계획 | 잠긴 행(`performance_schema.data_locks`) |
|---|---|---|
| 변경 전 삭제 | 상품 인덱스 전체 스캔(약 99만 행) + filesort | 상품 PRIMARY 1,000,000 + 보조 인덱스 1,002,941 |
| 변경 후 삭제 | 상품 UPDATE `type=range`, `idx_products_deleted_brand_created`(`const,const`), rows 1,000 | 브랜드 1, 상품 PRIMARY 1,000 + 보조 인덱스 1,004 |
| 변경 후 등록 | — | 브랜드 1(`S,REC_NOT_GAP`), 상품 잠금 없음 |

상세 기록은 [plan.md 검증 기록](plan.md#검증-기록)에 있다.

### 테스트

| 시점 | 결과 |
|---|---|
| 커밋별(에이전트) | `*Brand*`, `*Product*`, Checkstyle 통과 |
| 최종(에이전트) | `check` 260건, 실패·오류·skip 0 |
| 검토 후 재실행(직접) | 테스트 결과를 지우고 `check` 재실행, 96초, 260건 통과 |

- **삭제 흐름:** `BrandServiceTest`가 잠금 조회 → 저장 → 상품 일괄 삭제 순서와, 없는·이미 삭제된 브랜드에서 저장·상품 삭제를 부르지 않음을 확인한다.
- **일괄 UPDATE:** `ProductRepositoryIntegrationTest`가 대상 브랜드 활성 상품만 삭제·`updated_at` 갱신, 이미 삭제된 상품과 다른 브랜드 상품 불변, 반환 건수를 확인한다.
- **롤백:** `DeleteBrandRollbackIntegrationTest`가 상품 일괄 삭제 실패 시 브랜드·상품 모두 변경 전 상태임을 확인한다(기대값 유지, 실패 주입 위치만 변경).
- **등록 잠금:** `ProductServiceTest`가 등록이 `findByIdForShare`를 쓰고 삭제된 브랜드면 거절함을 확인한다.
- **브랜드 삭제 E2E:** `BrandApiE2ETest`가 기대값 변경 없이 통과했다.

## 3. 계획과의 차이

| 항목 | 계획 | 실제 | 이유 |
|---|---|---|---|
| JPQL 메서드 | `ProductJpaRepository`에 일괄 UPDATE | `deleteAllActiveByBrandId(brandId, now)`, 도메인 계약은 `deleteAllByBrandId(brandId)` | 시각을 구현(`ProductRepositoryImpl`)에서 넘기기 위해 |
| 롤백 테스트 이름 | 유지 | 표시 이름·메서드 이름을 "상품 일괄 삭제가 실패하면…"으로 변경 | 실패 지점이 바뀌어 기존 이름("두 번째 상품 저장")이 틀려짐. 단언은 그대로 |
| 의존성 정리 | 명시 없음 | `BrandRepositoryImpl`·`BrandEntityMapper`의 상품 저장소·매퍼 의존 제거 | 연관관계 제거로 쓰이지 않게 됨 |
| 삭제한 테스트 | 계획 커밋 5 목록 | 같음(`BrandTest` 4건, `BrandRepositoryIntegrationTest` 2건, `BrandFindForDeletionLockIntegrationTest`) | 대상 동작이 사라지거나 새 테스트로 옮겨짐 |

## 4. 한계와 후속 검토

- **주문 확정과의 데드락:** 일괄 UPDATE는 인덱스 순서(`created_at` 역순), 주문 확정은 상품 id 오름차순으로 잠근다. 같은 브랜드 상품 여러 개가 동시에 얽히면 데드락이 날 수 있고 MySQL이 한쪽을 롤백한다. 데드락 전용 예외 처리가 없어 롤백된 요청의 응답 코드는 확인하지 않았다(결정대로 한계로 기록).
- **등록·삭제 잠금 순서의 자동 검증 없음:** 브랜드 `FOR SHARE`/`FOR UPDATE` 순서는 SQL 로그와 수동 측정으로만 확인했다.
- **R01 테스트에서 빠진 단언:** 예전 도메인 테스트는 "재고 0인 상품도 함께 삭제", 저장소 테스트는 "가격·재고·설명 보존"을 확인했다. 일괄 UPDATE는 재고를 조건으로 보지 않고 `deleted`·`updated_at`만 바꾸므로 동작은 같지만, 새 저장소 테스트는 이를 단언하지 않는다.
- **도메인 규칙 우회:** 상품은 `Product.delete()`를 거치지 않고 SQL 조건(`deleted = false`)으로 "이미 삭제된 상품은 그대로" 규칙을 지킨다.

## 5. 회고

- **측정이 설계를 바꿨다.** "브랜드 삭제 방식 조사" 요청에서 잠금 조회가 상품 100만 행 전체를 잠근다는 것을 `data_locks`로 확인했고, 사용자의 "서비스에서 브랜드 id로 상품 일괄 삭제" 제안이 같은 측정으로 1,000행 수준임이 바로 증명됐다. R01의 "직접 bulk UPDATE" 미채택 이유(도메인 우회)는 규칙을 서비스로 옮기면서 사라졌다.
- **동시성 결정을 따라가다 새 데드락을 찾았다.** "등록 경쟁을 막는다"를 수정·재고 설정에까지 적용하면 상품→브랜드와 브랜드→상품 순서가 엇갈린다. 상품 행 잠금이 이미 수정 경로를 보호한다는 점을 따져 적용 범위를 등록으로 좁혔다.
- **위임은 계획대로 끝났다.** 계획에 삭제할 테스트 목록과 중단 조건을 구체적으로 적어 두어 검토에서 고칠 것이 없었다. 소요 시간(약 25분)은 커밋마다의 통합 테스트 실행과 대량 데이터 적재가 대부분이었다.
