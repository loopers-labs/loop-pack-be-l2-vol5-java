# 애플리케이션 레이어 테스트

## 1. 개요

`application` 레이어 테스트는 두 종류로 나뉜다.

| 종류 | 특징 | 예시 |
|---|---|---|
| 서비스 단위 테스트 | Mockito만 사용, Spring 컨텍스트·Docker 불필요, 순수 자바 객체(POJO) 조립 | `ConfirmOrderFacadeTest`, `OrderServiceTest`, `WalletServiceTest`, `ProductServiceTest`, `LikeServiceTest`, `LikeCountDeltaBufferTest`, `LikeCountDeltaFlushServiceTest`, `ApplicationExceptionTest` |
| 통합 테스트 | `@SpringBootTest`(MOCK 환경) + Testcontainers MySQL, 실제 트랜잭션·롤백·비관적 락·동시성까지 검증 | `ConfirmOrderIntegrationTest`, `ConfirmOrderConcurrencyIntegrationTest`, `ConfirmOrderSqlRollbackIntegrationTest`, `DeleteBrandRollbackIntegrationTest`, `LikeCountAggregationIntegrationTest`, `LikeCountDeltaBufferIntegrationTest` |

통합 테스트는 실행 전 Docker가 떠 있어야 하며(`docker-compose -f ./docker/infra-compose.yml up`), `modules/jpa`의 `MySqlTestContainersConfig`를 통해 Testcontainers MySQL을 띄운다. 각 통합 테스트 클래스는 `@AfterEach`에서 `DatabaseCleanUp.truncateAllTables()`로 모든 테이블을 비운다.

6개 통합 테스트 클래스는 모두 공용 메타 애노테이션 [`@IntegrationTest`](../../apps/commerce-api/src/test/java/com/loopers/support/test/IntegrationTest.java)(`@SpringBootTest`(MOCK) + 타입 레벨 `@MockitoSpyBean(OrderRepository, ProductJpaRepository, JdbcLikeCountAggregationDao)`)를 붙여 하나의 Spring 컨텍스트를 공유한다. 각 클래스는 필요한 스파이를 `@Autowired`로 주입받아 쓴다.

실행 방법.

```bash
# 빠른 기본 실행(slow 태그 제외)
./gradlew :apps:commerce-api:test

# slow(ConfirmOrderConcurrencyIntegrationTest)·example 태그만 실행
./gradlew :apps:commerce-api:slowTest

# 전체(test 태스크 1회가 slow·example 포함 모든 테스트 실행 + Checkstyle + ArchUnit)
./gradlew :apps:commerce-api:check

# application 패키지만
./gradlew :apps:commerce-api:test --tests "com.loopers.application.*"

# 클래스 단위
./gradlew :apps:commerce-api:test --tests "com.loopers.application.ordering.service.ConfirmOrderConcurrencyIntegrationTest"
```

`ConfirmOrderConcurrencyIntegrationTest`는 `@Tag("slow")`가 붙어 있어 `test` 태스크에서는 제외되고 `slowTest` 태스크에서만 실행된다. `check`로 실행하면 `test` 태스크가 태그 제외 없이 모든 테스트를 한 JVM에서 실행하고 `slowTest`는 따로 돌지 않는다.

집계: 클래스 15개(테스트 헬퍼 `ConcurrentRequests` 제외), 테스트 메서드 총 70개 — 단위 테스트 클래스 9개(52개 메서드), 통합 테스트 클래스 6개(18개 메서드).

## 2. 컨텍스트별 테스트

### mall

| 테스트 클래스 | 종류 | Spring 컨텍스트 | Docker | 검증 시나리오 | 테스트 수 | 최근 측정 시간 |
|---|---|---|---|---|---|---|
| [BrandServiceTest](../../apps/commerce-api/src/test/java/com/loopers/application/mall/service/BrandServiceTest.java) | 단위(Mockito) | 없음 | 불필요 | 삭제가 쓰기 잠금 조회 → 브랜드 저장 → 상품 일괄 삭제 순으로 호출 / 없는 브랜드는 `BRAND_NOT_FOUND`, 이미 삭제된 브랜드는 `DELETED_BRAND`이고 저장·상품 삭제 미호출 | 3 | 측정 시간 미기록 |
| [ProductServiceTest](../../apps/commerce-api/src/test/java/com/loopers/application/mall/service/ProductServiceTest.java) | 단위(Mockito) | 없음 | 불필요 | 등록이 브랜드를 공유 잠금으로 읽고 저장 / 삭제된 브랜드는 `DELETED_BRAND`, 없는 브랜드는 `BRAND_NOT_FOUND`이고 저장 미호출 / 재고 차감(`decreaseStocks`)이 상품 id 오름차순으로 잠가 조회·저장, 없는 상품·삭제된 상품·재고 부족 거절, 뒤쪽 품목 실패 시 앞쪽 재고 유지·저장 없음, 여러 품목 실패 시 품목 등장 순서 우선 | 10 | 측정 시간 미기록 |
| [DeleteBrandRollbackIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/mall/service/DeleteBrandRollbackIntegrationTest.java) | 통합 | MOCK(공유 컨텍스트) | 필요 | 상품 일괄 삭제가 실패하면 브랜드·상품 변경 전체를 롤백하고 다른 대상(과거 확정 주문 등)은 영향받지 않는다 | 1 | 측정 시간 미기록 |

### ordering

| 테스트 클래스 | 종류 | Spring 컨텍스트 | Docker | 검증 시나리오 | 테스트 수 | 최근 측정 시간 |
|---|---|---|---|---|---|---|
| [ConfirmOrderFacadeTest](../../apps/commerce-api/src/test/java/com/loopers/application/ordering/facade/ConfirmOrderFacadeTest.java) | 단위(Mockito) | 없음 | 불필요 | 주문 → 지갑 → 재고 → 결제 → 확정 순서 호출(`InOrder`)과 확정 결과, 품목 등장 순서의 합산 수량 전달, 주문·상품·결제 단계 실패 시 후속 단계 미호출 | 5 | 측정 시간 미기록 |
| [OrderServiceTest](../../apps/commerce-api/src/test/java/com/loopers/application/ordering/service/OrderServiceTest.java) | 단위 | none | 불필요 | 품목별 스냅샷·합계 저장, 동일 상품 수량 합산, 없는 상품 거절, 삭제된 상품 거절, 중복 수량 합산 오버플로 거절, 확정용 잠금 조회(없는 주문·이미 확정된 주문 거절), 확정 시 저장과 주문 기록 일치 | 10 | 약 0.19초 |
| [ConfirmOrderIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/ordering/service/ConfirmOrderIntegrationTest.java) | 통합 | MOCK(공유 컨텍스트) | 필요 | 재고·잔액 차감 후 USE/PAID 기록을 남기고 CONFIRMED 저장, 두 번째 품목 재고 부족 시 전체 롤백, 포인트 부족 시 전체 롤백, 브랜드 일괄 삭제로 상품이 삭제되면 확정 거절, 이미 확정된 주문 재확정 거절 | 5 | 13.8초 |
| [ConfirmOrderConcurrencyIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/ordering/service/ConfirmOrderConcurrencyIntegrationTest.java) | 통합 | MOCK(공유 컨텍스트) | 필요 | 같은 주문 동시 재확정 시 1회만 성공, 재고 5에 8명이 동시 주문하면 재고만큼만 성공, 잔액이 허용하는 만큼만 동시 결제 성공, 충전과 결제 동시 실행 시 둘 다 성공, 관리자 재고 설정과 주문 확정 동시 실행 시 순차 실행과 동일한 결과, 두 상품을 반대 순서로 담은 두 주문이 동시에 확정돼도 데드락 없이 둘 다 성공 (R02 필수 6개 시나리오) | 6 | `@Tag("slow")`, 23.1초 |
| [ConfirmOrderSqlRollbackIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/ordering/service/ConfirmOrderSqlRollbackIntegrationTest.java) | 통합 | MOCK(공유 컨텍스트) | 필요 | 재고·잔액·기록까지 실제 SQL로 반영된 뒤 마지막 저장 단계에서 실패해도 확정 전체가 롤백된다 | 1 | 측정 시간 미기록 |

### pay

| 테스트 클래스 | 종류 | Spring 컨텍스트 | Docker | 검증 시나리오 | 테스트 수 | 최근 측정 시간 |
|---|---|---|---|---|---|---|
| [WalletServiceTest](../../apps/commerce-api/src/test/java/com/loopers/application/pay/service/WalletServiceTest.java) | 단위 | none | 불필요 | 충전 시 잔액 저장 후 CHARGE 기록을 순서대로 저장, 충전 후 잔액이 범위를 초과하면 저장 없이 거절, 비양수 충전액은 저장 없이 거절, 확정용 지갑 잠금 조회, 주문 합계로 결제해 지갑·사용 기록 순서로 저장(저장된 주문 합계 기준), 잔액 부족 시 거절·저장 없음 | 7 | 미기록(단위 테스트 수준으로 빠름) |

### shopping

| 테스트 클래스 | 종류 | Spring 컨텍스트 | Docker | 검증 시나리오 | 테스트 수 | 최근 측정 시간 |
|---|---|---|---|---|---|---|
| [LikeServiceTest](../../apps/commerce-api/src/test/java/com/loopers/application/shopping/service/LikeServiceTest.java) | 단위 | none | 불필요 | 등록 시 상품이 없으면 PRODUCT_NOT_FOUND·삭제된 상품이면 DELETED_PRODUCT로 거절하며 저장하지 않음, 활성 상품이면 해당 사용자·상품으로 저장, 새로 저장되면 +1 변경 이벤트 발행·이미 있으면 미발행, 취소는 상품 조회 없이 삭제 호출, 실제로 지웠으면 -1 이벤트 발행·없으면 미발행 | 8 | 미기록(단위 테스트 수준으로 빠름) |
| [LikeCountAggregationIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/shopping/service/LikeCountAggregationIntegrationTest.java) | 통합 | MOCK(공유 컨텍스트) | 필요 | 전체 관계 COUNT를 `products.like_count`에 저장하고 관계가 사라진 기존 값은 0으로 갱신, 증감분 반영(addDeltas)은 기존 값에 더함·음수 결과는 0으로 맞춤·없는 상품 id는 무시 | 4 | 측정 시간 미기록 |
| [LikeCountDeltaBufferTest](../../apps/commerce-api/src/test/java/com/loopers/application/shopping/service/LikeCountDeltaBufferTest.java) | 단위 | none | 불필요 | 같은 상품의 증감 누적, drain이 값을 돌려주고 비움, 합이 0인 상품 제외, restore 후 새 증감과 합산 | 4 | 미기록(단위 테스트 수준으로 빠름) |
| [LikeCountDeltaBufferIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/shopping/service/LikeCountDeltaBufferIntegrationTest.java) | 통합 | MOCK(공유 컨텍스트) | 필요 | AFTER_COMMIT 연결 확인: 등록은 커밋 후 +1, 같은 등록 반복은 추가 증감 없음, 취소는 -1 (테스트를 트랜잭션으로 감싸지 않음) | 1 | 측정 시간 미기록 |
| [LikeCountDeltaFlushServiceTest](../../apps/commerce-api/src/test/java/com/loopers/application/shopping/service/LikeCountDeltaFlushServiceTest.java) | 단위 | none | 불필요 | 누적분이 없으면 DAO 미호출, 있으면 그대로 addDeltas 호출 후 버퍼 비움, DAO 실패 시 버퍼에 복원하고 예외 전파 | 3 | 미기록(단위 테스트 수준으로 빠름) |

### support

| 테스트 클래스 | 종류 | Spring 컨텍스트 | Docker | 검증 시나리오 | 테스트 수 | 최근 측정 시간 |
|---|---|---|---|---|---|---|
| [ApplicationExceptionTest](../../apps/commerce-api/src/test/java/com/loopers/application/support/error/ApplicationExceptionTest.java) | 단위 | none | 불필요 | 별도 메시지가 없으면 오류 코드 메시지를 사용, 별도 메시지가 있으면 해당 메시지를 사용 | 2 | 미기록 |

테스트 헬퍼(테스트 클래스가 아니므로 위 표에 포함하지 않음).

| 파일 | 역할 |
|---|---|
| [support/concurrency/ConcurrentRequests.java](../../apps/commerce-api/src/test/java/com/loopers/support/concurrency/ConcurrentRequests.java) | 여러 `Callable` 작업을 `CountDownLatch`로 시작 시점을 맞춰 동시에 실행하고, 각 결과를 `Outcome`(성공값 또는 예외)으로 모아 반환하는 동시성 테스트 전용 유틸리티. `ConfirmOrderConcurrencyIntegrationTest`에서 사용 |

## 3. 다른 레이어와 겹치는 검증

`ConfirmOrderServiceTest`는 R09에서 파사드 전환과 함께 삭제됐다. 경량화 때 남겼던 `usesStoredTotalAmount_ignoringCurrentProductPrice`(저장된 주문 합계로 결제)는 `WalletServiceTest.Pay`(주문 합계 기준 차감)로, 도메인 정책 테스트와 겹치던 규칙은 `OrderTest`·`ProductServiceTest.DecreaseStocks`·`WalletServiceTest.Pay`로 이전했다. [OrderApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/ordering/controller/OrderApiE2ETest.java)와 `ConfirmOrderIntegrationTest`는 그대로 상태·오류 검증을 맡는다.

그 밖의 레이어 간 중복.

- `ConfirmOrderIntegrationTest`의 상태 검증(재고·잔액·기록·주문 상태를 DB까지 확인)은 `OrderApiE2ETest`의 확정 관련 테스트가 HTTP 상태 코드·오류 코드 확인만으로 축소될 수 있게 하는 기준(reference) 역할을 한다.
- `ConfirmOrderSqlRollbackIntegrationTest`와 겹치던 `OrderApiE2ETest#returnsInternalServerError_whenSaveFailsAfterRealSql`은 삭제됐다.
- `WalletServiceTest#rejectsOverflow_withoutSavingAnything`, `#rejectsNonPositiveAmount_withoutSavingAnything`과 겹치던 [WalletApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/pay/controller/WalletApiE2ETest.java)의 상태 검증 케이스도 삭제됐다.

공백(gap): `Brand`/`Product` 애플리케이션 서비스 단위 테스트는 R07에서 추가한 `BrandServiceTest`(삭제)·`ProductServiceTest`(등록)만 있다. 그 외 분기(브랜드 생성·수정, 상품 수정·삭제·재고 설정)는 현재 E2E 테스트에서만 커버된다.

## 4. 경량화 결과

- `ConfirmOrderServiceTest`에서 6개 중복 케이스를 삭제해 1개로 줄였다가, R09에서 클래스를 `ConfirmOrderFacadeTest`(5개)로 대체했다.
- 공용 메타 애노테이션 [`@IntegrationTest`](../../apps/commerce-api/src/test/java/com/loopers/support/test/IntegrationTest.java)를 도입했다. `@SpringBootTest`(MOCK) + 타입 레벨 `@MockitoSpyBean`(`OrderRepository`, `ProductJpaRepository`, `JdbcLikeCountAggregationDao`)을 묶어, 기존에 4개로 분리돼 있던 Spring 컨텍스트를 2개(공유 컨텍스트 + `DeleteBrandRollbackIntegrationTest` 등도 같은 컨텍스트 사용)로 줄였다. 각 통합 테스트 클래스는 필요한 스파이를 `@Autowired`로 받아 쓴다.
- `ConfirmOrderConcurrencyIntegrationTest`에 `@Tag("slow")`를 붙여 `test` 태스크(`excludeTags("slow", "example")`)에서 제외하고, `slowTest` 태스크(`includeTags("slow", "example")`)에서만 실행하도록 했다. `check`는 `test`와 `slowTest`를 모두 실행한다.
- `DatabaseCleanUp.truncateAllTables()`가 각 테이블을 `TRUNCATE`하기 전에 비어 있는지 확인하고, 비어 있으면 건너뛰도록 했다.
- MySQL Testcontainers에 `withReuse(true)`를 적용했다(로컬에서 재사용을 활성화하려면 `~/.testcontainers.properties`에 `testcontainers.reuse.enable=true` 설정이 추가로 필요하다). 자세한 내용은 `docs/test/infrastructure.md`를 참고한다.
- Redis 테스트 컨테이너를 제거했다.

### 실행 방법

```bash
./gradlew :apps:commerce-api:test      # 빠른 기본(slow·example 태그 제외)
./gradlew :apps:commerce-api:slowTest  # slow·example 태그만
./gradlew :apps:commerce-api:check     # 전체(빌드 + 모든 테스트를 test 1회로 + Checkstyle + ArchUnit)
```

### 결과

경량화 후 `./gradlew :apps:commerce-api:check`는 BUILD SUCCESSFUL, 4분 33초로 끝났다. `test`는 211개, `slowTest`는 17개 테스트를 실행했고 실패는 0건이다. 경량화 이전 마지막 전체 `check`는 247개 테스트였다(`docs/week3/r02-order-consistency/result.md` 기준). 경량화 이전 전체 `check`의 실행 시간은 별도로 측정된 적이 없어 직접 비교할 수 없다.
