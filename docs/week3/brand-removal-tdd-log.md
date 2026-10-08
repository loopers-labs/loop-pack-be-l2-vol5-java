# W3 브랜드 일괄 삭제 — 첫 증분 TDD 기록

2026-10-08 KST. [TDD 계획](../week2/commerce-tdd-plan.md#w3-브랜드상품-일괄-논리-삭제의-tdd-계획)의 `W3-BRAND-01`을 먼저 구현했다. 이 기록은 정상 처리·기술 경계와 기존 회귀의 증거이며, W3 브랜드 과제 전체 완료를 뜻하지 않는다.

## 이번에 확인한 행동

| 준비·실행 | 검증 결과 |
| --- | --- |
| 대상 브랜드에 상품 2개(재고 0 포함), 다른 브랜드·상품, 이미 확정한 주문을 준비한 뒤 관리자 DELETE | `200`, `meta.result=SUCCESS`, 대상 브랜드 ID·`deleted=true` |
| 삭제 후 별도 JDBC 조회 | 브랜드·상품 행은 남고 대상 상품 2개의 삭제 시각은 브랜드와 일치. 상품 수정 시각도 삭제 시각과 일치 |
| 변경 전후 저장값 비교 | 다른 브랜드·상품, 상품 이름·가격·재고·생성 시각, 기존 주문·주문항목·결제 결과·사용자 잔액 보존 |
| 삭제 요청 동안 Hibernate 통계·SQL 수집 | 상품 엔티티 로딩 0회·개별 엔티티 UPDATE 0회, 상품 bulk UPDATE 1회 |
| 외부 READ_COMMITTED 트랜잭션에서 사용자→브랜드→상품을 잠그고 미반영 변경을 가진 채 삭제 호출 | 상품 객체도 삭제 상태로 동기화. 사용자·브랜드·상품이 관리 상태를 유지하고 삭제 전 상품 수정과 전후 포인트 변경도 함께 저장 |

실제 Spring 서비스·JPA·MySQL과 관리자 Security·CSRF를 포함한 MockMvc 경계를 사용했다. 테스트 메서드 전체를 자동 롤백 트랜잭션으로 감싸지 않았으며, 서비스 또는 명시적 외부 트랜잭션 종료 후 JDBC로 저장 결과를 확인했다.

## Red → Green → 정리

| 단계 | 실제 관찰·처리 |
| --- | --- |
| 실행 환경 준비 | 처음에는 Gradle 캐시 접근 권한과 중지된 Docker 때문에 실행이 막혔다. 허용된 실행 권한과 Docker 시작으로 해결했다. 업무 테스트의 Red로 세지 않는다 |
| Red | 정상 삭제 테스트를 먼저 실행했다. 기존 코드는 미삭제 상품이 있어 `409`를 반환했고 `expected: 200 but was: 409`로 실패했다 |
| Green 구현 | 상품 존재 시 거절을 제거하고, 같은 트랜잭션에서 상품 bulk 삭제 → 브랜드 삭제·저장을 연결했다. 더 이상 쓰지 않는 `NON_DELETED_PRODUCTS_EXIST`와 `BRAND_HAS_PRODUCTS` 매핑을 제거했다 |
| 기술 검증 보완 | bulk 쿼리 실행 횟수를 Hibernate QueryStatistics로 검사하자 0으로 집계됐다. 측정 수단을 StatementInspector의 실제 SQL 수집으로 바꾸고 **상품 UPDATE 1회** 기대값은 유지했다 |
| 기존 회귀 정리 | 재고 0 상품이 있으면 거절하던 HTTP 테스트와 상품 등록 경합의 기대값을 W3 계약으로 변경했다. 기존 상태 보존 검증을 유지·확장했으며, W2 과거 실행 로그는 수정하지 않았다 |
| 최종 검증 | 관련 24개 테스트·Checkstyle 통과 후 외부 트랜잭션 테스트에 READ_COMMITTED를 명시했다. 최신 코드로 전체 `check`를 통과했다 |

## 변경 책임과 기술 선택

| 파일·책임 | 구현 |
| --- | --- |
| [BrandDeletionService](../../apps/commerce-api/src/main/java/com/loopers/domain/brand/BrandDeletionService.java) | 없음·재삭제 분기를 유지하고 상품 저장소의 일괄 삭제 후 Brand 상태 변경 |
| [ProductRepository](../../apps/commerce-api/src/main/java/com/loopers/domain/product/ProductRepository.java) | 브랜드 쓰기 잠금을 보유한 READ_COMMITTED 호출자를 전제로 일괄 논리 삭제 계약 추가 |
| [ProductRepositoryImpl](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/product/ProductRepositoryImpl.java) | 대상 ID 정렬 조회 → ID별 기본 키 `FOR UPDATE` → flush → 대상 ID bulk UPDATE → 이미 로딩된 상품만 refresh. `MANDATORY`로 기존 트랜잭션 참여 |
| [AdminBrandService](../../apps/commerce-api/src/main/java/com/loopers/application/brand/AdminBrandService.java) | 기존 권한 확인·READ_COMMITTED·브랜드 잠금·브랜드 저장 유지. 이 파일 자체는 변경하지 않음 |
| [BrandDeletionException](../../apps/commerce-api/src/main/java/com/loopers/domain/brand/BrandDeletionException.java) · [CommerceErrors](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/commerce/CommerceErrors.java) | 상품 존재 거절 사유·409 매핑 제거. 없는 브랜드의 기존 404 유지 |

상품별 `delete()`·`saveAndFlush()` 대신 bulk UPDATE를 선택했다. 다만 UPDATE의 실행 계획이 P13 잠금 순서를 보장한다고 가정하지 않고 **상품마다 잠금 SELECT를 먼저 실행**한다. 전체 SQL이 한 번이라는 뜻이 아니며 처리 시간·대량 데이터 성능은 측정하지 않았다.

영속성 컨텍스트 전체 `clear()`는 외부 호출자의 다른 관리 객체까지 분리하므로 사용하지 않았다. 잠금 조회의 자동 flush를 늦추고 잠금 확보 후 명시적으로 flush하여 미반영 변경을 보존한 다음, 이미 로딩된 삭제 대상만 refresh한다. 임의의 외부 격리 수준이나 이미 역전된 잠금 순서까지 지원한다는 의미는 아니다.

## 테스트와 실행 결과

| 테스트 | 사례 수·이번 범위 |
| --- | --- |
| [BrandRemovalTransactionTest](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandRemovalTransactionTest.java) | 2: 정상 삭제·확정 주문 보존·SQL 확인, 외부 트랜잭션의 객체 동기화 |
| [BrandDeletionServiceTest](../../apps/commerce-api/src/test/java/com/loopers/domain/brand/BrandDeletionServiceTest.java) | 8: 도메인 협력·호출 순서·실패 전달·재삭제. 대역 테스트이며 실제 DB 롤백 증거는 아님 |
| [AdminBrandMutationApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/AdminBrandMutationApiE2ETest.java) | 11: 기존 관리자 변경 회귀, 재고 0 상품 동반 삭제·재삭제 시 DB 불변 |
| [BrandConcurrencyIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandConcurrencyIntegrationTest.java) | 3: 상품 등록과 삭제의 새 기대값, 기존 타임아웃·교착 회귀. 두 선후관계를 강제로 각각 재현한 증거는 아님 |

관련 24개와 정적 검사는 다음 명령으로 통과했다.

```bash
./gradlew :apps:commerce-api:test \
  --tests '*BrandRemovalTransactionTest' \
  --tests '*BrandDeletionServiceTest' \
  --tests '*AdminBrandMutationApiE2ETest' \
  --tests '*BrandConcurrencyIntegrationTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain -q
```

최신 코드 전체 검증: `./gradlew :apps:commerce-api:check --console=plain -q` — 2026-10-08 11:09 KST 종료 코드 0. XML 기준 **52개 suite·509개 테스트, 실패·오류·건너뜀 0**이며 ArchUnit 1개를 포함한다. 연결된 Java 모듈의 Checkstyle 보고서 11개도 위반 0이다. 다른 모듈의 별도 통합 테스트까지 실행했다는 뜻은 아니다. 실행 결과는 `apps/commerce-api/build/test-results/test/`와 각 모듈의 `build/reports/checkstyle/`에 생성되며, 재실행하면 갱신된다.

## 남은 증분

| 계획 | 남은 검증 |
| --- | --- |
| W3-BRAND-02 | 경계 사례 전체를 묶어 확인. 특히 미삭제·이미 삭제된 상품이 섞인 경우의 최초 삭제 시각 보존 추가 |
| W3-BRAND-03 | **실제 상품 변경 SQL 이후** 다음 브랜드 저장 단계에서 예외를 내고 서비스 종료 후 브랜드·상품 전체 롤백 확인 |
| W3-BRAND-04~06 | 일괄 삭제 후 고객 조회·좋아요·관리자 변경·새 주문 제한, 삭제 전 DRAFT의 최초 확정 거절 연결 검증 |
| W3-BRAND-07 | 삭제와 주문 생성·최초 확정의 경합에서 선후관계별 결과 검증 |
| W3-BRAND-08 | 기존 상품 등록 경합 회귀는 통과. 각 선후관계의 확정적 재현은 추가 검증으로 남김 |

W2의 506개 통과 기록과 당시 상품 존재 시 삭제 거절 검증은 과거 계약의 증거로 보존한다. 새 업무 계약의 전체 완료 증거로 재사용하지 않는다.
