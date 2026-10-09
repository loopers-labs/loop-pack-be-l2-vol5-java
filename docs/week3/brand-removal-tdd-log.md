# W3 브랜드 일괄 삭제 — TDD 실행 기록

2026-10-08 KST. [TDD 계획](../week2/commerce-tdd-plan.md#w3-브랜드상품-일괄-논리-삭제의-tdd-계획)의 `W3-BRAND-01~08` 구현·검증을 완료했다. 정상 삭제·기본 경계·전체 롤백에 이어 삭제 후 사용 제한·권한·DRAFT 최초 확정 거절, 주문/상품 등록의 순차 선후 계약과 시작만 맞춘 실제 서비스 경합을 검증했다. 각 증분의 당시 결과는 보존하며, 이번 완료 범위는 **브랜드 일괄 삭제와 그 연관 영향**이다. 주문 확정 재요청 정책 등 다른 W3 과제 전체 완료를 뜻하지 않는다.

## 첫 증분에서 확인한 행동 — W3-BRAND-01

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

첫 증분 당시 전체 검증: `./gradlew :apps:commerce-api:check --console=plain -q` — 2026-10-08 11:09 KST 종료 코드 0. XML 기준 **52개 suite·509개 테스트, 실패·오류·건너뜀 0**이며 ArchUnit 1개를 포함한다. 연결된 Java 모듈의 Checkstyle 보고서 11개도 위반 0이다. 다른 모듈의 별도 통합 테스트까지 실행했다는 뜻은 아니다. 실행 결과는 `apps/commerce-api/build/test-results/test/`와 각 모듈의 `build/reports/checkstyle/`에 생성되며, 재실행하면 갱신된다.

## 두 번째 증분 — W3-BRAND-02 경계 검증

[BrandRemovalTransactionTest](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandRemovalTransactionTest.java)에 3개 테스트 메서드·4개 실행 사례를 추가했다. 실제 관리자 DELETE의 응답과 트랜잭션 종료 후 JDBC 저장값을 함께 검증한다.

| 추가 사례 | 검증한 결과 |
| --- | --- |
| 상품 없는 브랜드 | `200 SUCCESS`, 대상 ID·`deleted=true`, 오류 필드 생략. 브랜드 삭제 필드 외 기존 값과 다른 브랜드·상품 보존 |
| 존재하지 않는 브랜드 | `404 FAIL`, `BRAND_NOT_FOUND`, 계약의 오류 메시지, `data` 생략. 준비한 브랜드·상품·사용자를 포함한 6개 테이블의 전후 상태 동일 |
| 미삭제·기삭제 상품 혼합 | 미삭제 상품만 브랜드와 같은 시각으로 삭제. 기삭제 상품은 고정한 과거 삭제·수정 시각을 포함해 전체 행 보존. 다른 브랜드·상품 및 상품 ID·FK·가격·재고·생성 시각 보존 |
| 연결 상품 전부 기삭제 | 갱신할 상품이 없어도 브랜드 삭제 성공. 기존 상품 전체 행 보존 |
| 위 두 기삭제 상품 사례의 재요청 | 다시 `200 SUCCESS`와 같은 삭제 응답. 브랜드·상품을 포함한 6개 테이블의 전체 상태 불변 |

기존 코드의 `deletedAt is null` 대상 선택과 재삭제 분기가 이미 기대값을 만족해 **최초 실행부터 통과했다.** 따라서 업무 Red가 있었다고 기록하지 않으며, 서비스·저장소 코드나 기대값을 바꾸지 않았다. 이전 삭제 이력은 SQL fixture의 고정 시각으로 준비했고 `sleep`이나 시계 진행에 의존하지 않았다. 테스트 안의 기존 기삭제 데이터 준비는 삭제 API 구현 검증과 구분한다.

2026-10-08 11:31 KST, 다음 명령의 **6개 사례(기존 2 + 신규 4)**와 Checkstyle main/test가 종료 코드 0으로 통과했다. 처음의 Gradle 캐시 권한 오류는 실행 환경 문제였고 허용된 권한으로 다시 실행했다.

```bash
./gradlew :apps:commerce-api:test --tests '*BrandRemovalTransactionTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain -q
```

이후 `./gradlew :apps:commerce-api:check --console=plain -q`도 2026-10-08 11:33 KST 종료 코드 0으로 통과했다. XML 기준 **52개 suite·513개 테스트, 실패·오류·건너뜀 0**, ArchUnit 1개 포함, Checkstyle 보고서 11개 위반 0이다. `W3-BRAND-02`는 완료이며, 아래 롤백·연결 API·주문 경합까지 검증했다는 뜻은 아니다.

## 세 번째 증분 — W3-BRAND-03 롤백 검증

[BrandRemovalTransactionTest](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandRemovalTransactionTest.java)의 `rollsBackProductAndBrandUpdatesWhenBrandSaveFailsAfterFlush`를 추가했다. 대상 상품 2개(재고 0 포함), 다른 브랜드·상품, 확정 주문·주문항목, 사용자 잔액과 좋아요를 먼저 저장·커밋하고 6개 테이블의 전체 행을 비교 기준으로 잡았다.

| 검증 단계 | 실제 관찰 |
| --- | --- |
| 실패 주입 | 테스트의 `@MockitoSpyBean BrandRepository`에서 실제 `save()`를 호출한 다음 테스트 전용 `IllegalStateException` 발생. fixture 준비가 끝난 뒤 설정하며 상품 저장소는 대체하지 않음 |
| 상품 SQL 이후 | 브랜드 `save()` 진입 시 같은 트랜잭션의 JDBC 조회로 상품 2개의 삭제·수정 시각이 반영된 상태를 수집 |
| 브랜드 SQL 이후 | `callRealMethod()`가 실제 `saveAndFlush()`를 실행한 뒤 JDBC로 브랜드 삭제 시각을 수집. 상품·브랜드 UPDATE 각각 1회도 확인. flush는 commit이 아님 |
| 요청 종료 | `500`, `meta.result=FAIL`, `meta.errorCode=INTERNAL_ERROR`, 공통 오류 메시지, `data` 생략. 테스트 예외 상세는 응답에 노출하지 않음 |
| 전체 롤백 | 요청 종료 후 트랜잭션 밖에서 JDBC로 6개 테이블을 다시 조회. 브랜드·상품의 삭제/수정 시각을 포함한 모든 행이 이전 스냅샷과 동일하며, 기존 주문·결제 결과·잔액·좋아요와 다른 대상도 보존 |

테스트 전체를 `@Transactional`이나 별도 외부 트랜잭션으로 감싸지 않았다. 실제 `AdminBrandService` 프록시가 롤백을 수행하며 저장 단계에서는 트랜잭션 활성, 요청 전후에는 비활성임을 확인했다. 중간 상태를 `AtomicReference`에 수집하고 assertion은 HTTP 요청 밖에서 실행한다. 조기 실패나 assertion 오류가 500 응답으로 변환되어 테스트가 잘못 통과하는 것을 방지한다.

이번 사례는 **실제 DB 변경 뒤 저장 경계에서 발생한 런타임 예외**의 롤백 검증이다. DB 접속 단절·커밋 실패·교착 등 모든 장애 유형을 재현한 것은 아니다. 기존 트랜잭션 구현에서 최초 실행부터 통과했으므로 생산 코드·계약·기대값은 변경하지 않았고 별도의 Red가 있었다고 기록하지 않는다.

2026-10-08 11:39 KST, 다음 명령에서 해당 클래스 **7개 사례(이전 6 + 신규 1)**와 Checkstyle main/test가 종료 코드 0으로 통과했다.

```bash
./gradlew :apps:commerce-api:test --tests '*BrandRemovalTransactionTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain -q
```

이후 `./gradlew :apps:commerce-api:check --console=plain -q`도 2026-10-08 11:41 KST 종료 코드 0으로 통과했다. XML 기준 **52개 suite·514개 테스트, 실패·오류·건너뜀 0**, ArchUnit 1개 포함, Checkstyle 보고서 11개 위반 0이다. `W3-BRAND-03`은 완료이며, 삭제 후 연결 API·DRAFT·주문 경합은 다음 증분으로 남긴다.

## 네 번째 증분 — W3-BRAND-04~08 연결·동시성 검증

실제 관리자 DELETE로 삭제 상태를 준비한 뒤 고객·관리자 API와 독립 트랜잭션 경합을 연결했다. 신규 테스트 3개 클래스의 **24개 실행 사례**를 추가했다. 생산 코드는 변경하지 않았다.

| 테스트 | 실행 수·검증 범위 |
| --- | --- |
| [BrandRemovalCustomerApiTest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/BrandRemovalCustomerApiTest.java) | 6: 고객 조회 3종 정렬·페이지·브랜드 필터, 좋아요/새 주문 제한과 본인 좋아요 취소, 삭제 전 DRAFT 최초 확정 거절, 고객·관리자의 과거 확정 주문 조회 보존 |
| [BrandRemovalAccessApiTest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/BrandRemovalAccessApiTest.java) | 9: 삭제 후 브랜드/상품 수정·재고 변경·상품 등록 거절, 일반·미식별 요청 거절, CSRF 누락·불일치 거절, 관리자 목록·상세의 삭제 행 조회 |
| [BrandRemovalConcurrencyTest](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandRemovalConcurrencyTest.java) | 9: 삭제 ↔ 주문 생성, 삭제 ↔ DRAFT 최초 확정, 삭제 ↔ 상품 등록의 순차 선후 계약 6개와 worker 시작만 맞춘 실제 서비스 경합 3개. 순차 사례를 락 대기 증거로 사용하지 않음 |

### 외부 응답과 보존 확인

| 사례 | 관찰·검증 결과 |
| --- | --- |
| W3-BRAND-04 · 고객 조회 | 삭제 브랜드·상품 상세는 기존 `404` 오류. 상품의 세 정렬·페이지·브랜드 필터에서 삭제 대상을 제외하며 다른 브랜드·상품은 정상 노출. 조회 전후 6개 테이블 상태 불변 |
| W3-BRAND-04 · 좋아요·새 주문 | 삭제 상품의 새 좋아요·기존 좋아요 재등록·새 주문은 `404 PRODUCT_NOT_FOUND`, 실패 본문·`data` 생략. 내 좋아요 목록에서 삭제 상품 제외. 기존 좋아요는 삭제로 함께 지우지 않으며 본인 취소만 성공. 다른 사용자의 관계와 다른 상품의 관계 보존, 재취소도 추가 변경 없음 |
| W3-BRAND-05 · 관리자 경계 | 삭제 후 브랜드·상품 이름/가격·재고 변경과 해당 브랜드의 상품 등록은 기존 `404` 계약. 일반·미식별 DELETE와 관리자 CSRF 누락·불일치는 `403 FORBIDDEN`. 각 거절 요청 뒤 6개 테이블의 전체 행 불변 |
| W3-BRAND-05 · 관리자 조회 | 관리자 목록·상세에서는 삭제 브랜드와 연결 상품을 삭제 시각과 함께 조회. 고객 비노출과 관리자 조회 가능 여부를 구분하고 조회로 DB를 변경하지 않음 |
| W3-BRAND-06 · DRAFT 최초 확정 | 실제 HTTP로 삭제 전에 만든 주문은 이후 최초 확정에서 `404 PRODUCT_NOT_FOUND`. DRAFT·저장 스냅샷 유지, 결제액·확정 시각은 null, 재고·잔액 차감 없음. 요청 전후 6개 테이블 전체 행 동일 |
| 과거 확정 주문 보존 | 확정 뒤 현재 상품 이름·가격을 바꾼 상태에서 브랜드를 삭제. 본인 상세/목록과 관리자 상세/목록은 기존 상품명·단가·총액·결제 결과를 그대로 반환. 다른 사용자의 상세 접근은 거절하며 기존 잔액과 DB 상태 보존 |

고객 API는 실제 HTTP 포트의 `TestRestTemplate`, 관리자 API는 Security·CSRF를 포함한 `MockMvc`를 사용했다. 처음 DRAFT 테스트는 생성 응답의 `+09:00`과 재조회 응답의 `Z` 시각 문자열을 그대로 비교하여 실패했다. 같은 순간의 표현 차이를 삭제 영향으로 오인한 **테스트 비교 기준 문제**였으며 업무 Red로 기록하지 않는다. 삭제 전 GET 응답과 삭제 후 GET 응답 전체를 비교하도록 보완했고, HTTP 상태·오류 본문·DRAFT/차감 보존 기대값은 유지했다. 경합 테스트의 내부 `OrderInfo` 비교도 UTC 정규화 전후 시각을 같은 `Instant`로 비교한다.

### 순차 선후 계약과 실제 서비스 경합의 구분

| 대상 | 순차 선후 계약 · 각각 2개 사례 | 시작만 맞춘 경합 · 각각 1개 사례 |
| --- | --- | --- |
| 삭제 ↔ 주문 생성 | 삭제 후 생성은 `PRODUCT_NOT_FOUND`·주문/항목/차감 없음. 생성 후 삭제는 DRAFT·스냅샷 보존, 이후 최초 확정 거절 | 삭제는 성공. 생성이 거절되면 주문/항목 없음, 생성이 성공하면 DRAFT 1개·항목 2개와 원래 재고/잔액 유지. 성공한 DRAFT도 삭제 후 최초 확정 거절 |
| 삭제 ↔ DRAFT 최초 확정 | 삭제 후 최초 확정은 거절하고 기존 주문·항목·사용자 전체 행 보존. 최초 확정 후 삭제는 주문·결제 결과와 한 번의 차감 보존 | 삭제는 성공. 확정 거절이면 DRAFT·기존 재고/잔액 유지, 확정 성공이면 CONFIRMED·결제액 4000·정확한 재고 차감·잔액 6000. 항목 스냅샷은 항상 불변 |
| 삭제 ↔ 상품 등록 | 삭제 후 등록은 `BRAND_NOT_FOUND`·추가 상품 없음. 등록 후 삭제는 새 상품도 동반 삭제 | 삭제는 성공. 등록 거절이면 기존 상품 2개만 삭제, 등록 성공이면 새 상품까지 3개 삭제. 삭제 브랜드에 미삭제 상품이 남지 않음 |

과제의 **실제 서비스는 worker 시작만 맞추고 잠금 구간에 테스트용 장벽·sleep을 넣지 않는다**는 조건을 따른다. 처음 작성한 테스트는 repository spy로 락 획득 후 처리를 보류해 선후관계를 고정했으나, 과제 조건과 맞지 않아 최종 검증에서 제거했다. 그 버전의 통과 결과를 최종 증거로 재사용하지 않는다. 검증 방식을 교체한 것이며 허용 업무 결과나 실패 시 상태 보존 기준을 완화한 것은 아니다.

최종 테스트의 검증 방식은 다음과 같다.

1. **순차 경계 6개:** 실제 서비스 호출이 끝나 커밋된 뒤 6개 테이블의 상태를 저장하고 다음 서비스를 호출한다. 삭제 선행 거절은 DB 전체 무변경, 후속 삭제는 기존 주문·항목·사용자·좋아요 전체 행과 확정 차감 효과 보존을 확인한다. 이 검증을 동시 요청이나 락 대기 재현이라고 쓰지 않는다.
2. **경합 3개:** 두 worker의 서비스 진입 전 `CountDownLatch`로 시작만 맞춘다. 각 worker가 실제 Spring 서비스 프록시를 호출하며 서비스가 자신의 트랜잭션을 시작·종료한다. spy·mock·내부 장벽·sleep·별도 root 연결은 사용하지 않는다. 기존 P13의 READ_COMMITTED·잠금 순서·3초 락 제한·자동 재시도 없음도 변경하지 않는다.
3. 모든 worker가 끝난 뒤 JDBC로 최종 상태를 조회한다. 삭제는 반드시 성공하고 다른 요청은 성공 또는 정확한 `ProductQueryException` 사유만 허용한다. 각 분기의 주문·결제·재고·잔액·상품 수와 다른 대상 보존을 검증하며 기술 예외·타임아웃·교착은 성공이나 업무 거절로 숨기지 않는다.
4. 시작 latch와 future에 제한 시간을 두고 `finally`에서 시작 대기를 해제하고 worker 취소·executor 종료를 처리한다. 모든 worker 종료 후에만 DB fixture를 정리한다.

실제 경합에서 어느 순서가 발생하는지는 스케줄링과 DB에 맡긴다. 한 번의 경합 실행에서 두 선후관계가 모두 발생했다거나 특정 PK의 DB 락 대기를 관찰했다고 주장하지 않는다. 양쪽 완료 순서의 업무 계약은 별도 순차 사례로 보완한다.

### 실행 결과와 완료 범위

2026-10-08 11:59 KST, 최종 검증 방식으로 바꾼 신규 **24개 사례와 Checkstyle main/test**가 다음 명령으로 종료 코드 0을 반환했다. XML 3개 스위트의 실패·오류·건너뜀은 모두 0이다.

```bash
./gradlew :apps:commerce-api:test \
  --tests '*BrandRemovalCustomerApiTest' \
  --tests '*BrandRemovalAccessApiTest' \
  --tests '*BrandRemovalConcurrencyTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain -q
```

최종 전체 `./gradlew :apps:commerce-api:check --console=plain -q`는 **2026-10-08 12:01 KST 종료 코드 0**으로 통과했다. XML 기준 **55개 suite·538개 테스트, 실패·오류·건너뜀 0**, `ArchitectureTest` 1개 포함, Checkstyle 보고서 11개 위반 0이다. 이번 브랜드 삭제 전용 4개 클래스는 `Transaction` 7개·`Customer` 6개·`Access` 9개·`Concurrency` 9개로 총 31개 사례가 모두 통과했다. 다른 모듈의 별도 통합 테스트까지 실행했다는 뜻은 아니다.

`W3-BRAND-01~08`의 사례별 검증을 완료했다. W2의 506개 통과 기록과 당시 상품 존재 시 삭제 거절 검증은 과거 계약의 증거로 보존하며 새 업무 계약의 완료 근거로 재사용하지 않는다. 대량 데이터 처리 시간·락 유지 시간은 미측정이고, DB 접속 단절·커밋 실패 등 모든 장애 유형을 검증한 것은 아니다. 다른 W3 과제의 정책 변경을 이번 브랜드 범위에 섞어 완료로 기록하지 않는다.
