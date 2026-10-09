# 도메인 레이어 테스트

## 1. 개요

도메인 레이어 테스트는 `com.loopers.domain.**`에 있는 순수 자바 객체(애그리거트, 값 객체, 정책)만 검증한다. Spring 컨텍스트, JPA, Docker(Testcontainers) 없이 순수 단위 테스트로만 구성되어 있고, `mall.model`(재고 포함), `ordering.model`/`ordering.policy`, `pay.model`, `shared`, `shopping.model`, `support.error` 6개 컨텍스트에 걸쳐 있다. `domain.example`은 Week 1 이전 레퍼런스 스캐폴딩이라 이 문서 범위에서 제외한다.

실행 명령.

```bash
./gradlew :apps:commerce-api:test --tests "com.loopers.domain.*"
```

전체 규모는 테스트 클래스 12개, `@Test`/`@ParameterizedTest` 메서드 71개다(파라미터화 테스트의 개별 값 실행까지 펼치면 79회 실행). 클래스·시나리오별 상세는 2절 표를 참고한다.

## 2. 컨텍스트별 테스트

### mall

| 테스트 클래스 | 중첩 그룹 | 검증 시나리오 | 테스트 수 |
|---|---|---|---|
| [BrandTest](../../apps/commerce-api/src/test/java/com/loopers/domain/mall/model/BrandTest.java) | 없음 | 이름의 앞뒤 공백을 제거하고 브랜드를 생성한다 / 잘못된 수정은 기존 상태를 유지한다 / 삭제된 브랜드는 수정할 수 없다 / 삭제하면 브랜드만 삭제 상태가 된다(소속 상품 삭제는 `BrandService`의 일괄 삭제가 담당) / 이미 삭제된 브랜드는 다시 삭제할 수 없다 | 5 |
| [ProductTest](../../apps/commerce-api/src/test/java/com/loopers/domain/mall/model/ProductTest.java) | 없음 | 상품 생성 시 이름을 정규화하고 가격과 재고를 보관한다 / 잘못된 가격 수정은 상품 정보를 유지한다 / 삭제 상품의 정보와 재고는 변경할 수 없다 | 3 |
| [StockTest](../../apps/commerce-api/src/test/java/com/loopers/domain/mall/model/StockTest.java) | SetStock / DecreaseStock | (SetStock) 0과 int 최댓값을 재고로 설정한다 / 음수 재고를 거절하고 기존 값을 유지한다, (DecreaseStock) 정확한 수량을 차감하면 재고가 0이 된다 / 재고보다 하나 많은 수량을 거절하고 기존 값을 유지한다 | 4 |

### ordering

| 테스트 클래스 | 중첩 그룹 | 검증 시나리오 | 테스트 수 |
|---|---|---|---|
| [OrderItemTest](../../apps/commerce-api/src/test/java/com/loopers/domain/ordering/model/OrderItemTest.java) | Create / Restore | (Create) 단가와 수량으로 품목 금액을 계산한다 / 0 이하 수량은 거절한다 / 금액 계산이 범위를 초과하면 거절한다 / 0 이하 상품 ID는 거절한다, (Restore) 저장된 금액이 단가·수량과 일치하면 복원한다 / 저장된 금액이 단가·수량과 다르면 거절한다 | 6 |
| [OrderRecordTest](../../apps/commerce-api/src/test/java/com/loopers/domain/ordering/model/OrderRecordTest.java) | Paid / Restore | (Paid) 성공한 결제만 PAID 상태로 생성한다 / 0 이하 결제액은 거절한다, (Restore) 저장된 상태를 복원한다 / id가 없거나 생성 시각이 없으면 거절한다 | 4 |
| [OrderTest](../../apps/commerce-api/src/test/java/com/loopers/domain/ordering/model/OrderTest.java) | Create / Restore / Confirm / QuantitiesByProductId | (Create) 품목 금액의 합으로 총액을 계산하고 DRAFT로 생성한다 / 품목이 없으면 거절한다 / 총액 계산이 범위를 초과하면 거절한다 / 0 이하 사용자 ID는 거절한다, (Restore) 저장된 합계가 품목 금액의 합과 일치하면 복원한다 / 저장된 합계가 품목 금액의 합과 다르면 거절한다 / id가 없거나 생성 시각이 없으면 거절한다 / CONFIRMED인데 결제 기록이 없으면 거절한다 / DRAFT인데 결제 기록이 있으면 거절한다, (Confirm) DRAFT 주문을 CONFIRMED로 전환한다 / 확정하면 사용자·총액을 담은 PAID 상태의 주문 기록을 보유하고 DRAFT는 기록이 없다 / 이미 CONFIRMED인 주문은 재확정을 거절하고 상태를 유지한다, (QuantitiesByProductId) 같은 상품 품목의 수량을 합산한다 / 상품의 첫 등장 순서를 유지한다 / 합산 수량이 계산 범위를 초과하면 거절한다 | 15 |

### pay

| 테스트 클래스 | 중첩 그룹 | 검증 시나리오 | 테스트 수 |
|---|---|---|---|
| [PointBillTest](../../apps/commerce-api/src/test/java/com/loopers/domain/pay/model/PointBillTest.java) | Charge / Use / Restore | (Charge) 사용자·충전액으로 CHARGE 기록을 생성한다 / 0 이하 사용자 ID로 기록을 만들 수 없다(파라미터화) / 0 이하 충전액으로 기록을 만들 수 없다(파라미터화), (Use) 사용자·주문·사용액으로 USE 기록을 생성한다 / 0 이하 주문 ID로 기록을 만들 수 없다(파라미터화), (Restore) 저장된 값으로 기록을 복원한다 / 주문 ID가 있는 사용 기록도 복원한다 / 0 이하 ID 또는 저장 시각이 없으면 복원할 수 없다 / 충전 기록에 주문 ID가 있으면 복원할 수 없다 | 9 |
| [WalletTest](../../apps/commerce-api/src/test/java/com/loopers/domain/pay/model/WalletTest.java) | Create / Charge / Use | (Create) 사용자별 초기 잔액 0의 지갑을 생성한다 / 저장된 잔액으로 지갑을 복원한다 / 0 이하 사용자 ID로 지갑을 만들 수 없다(파라미터화), (Charge) 충전액만큼 잔액을 더하고 충전 기록을 반환한다 / 충전 후 잔액이 범위를 초과하면 거절하고 기존 잔액을 유지한다, (Use) 사용액만큼 잔액을 빼고 주문 ID를 담은 사용 기록을 반환한다 / 잔액이 사용액보다 1 부족하면 거절하고 기존 잔액을 유지한다 | 7 |

### shared

| 테스트 클래스 | 중첩 그룹 | 검증 시나리오 | 테스트 수 |
|---|---|---|---|
| [MoneyTest](../../apps/commerce-api/src/test/java/com/loopers/domain/shared/MoneyTest.java) | Create / Calculate | (Create) 0과 long 최댓값을 금액으로 표현한다 / 음수 또는 양수가 아닌 가격을 거절한다, (Calculate) 금액을 더하고 양수 수량을 곱한다 / 덧셈과 곱셈 범위 초과를 거절하고 원래 금액을 유지한다 | 4 |

### shopping

| 테스트 클래스 | 중첩 그룹 | 검증 시나리오 | 테스트 수 |
|---|---|---|---|
| [UserTest](../../apps/commerce-api/src/test/java/com/loopers/domain/shopping/model/UserTest.java) | 없음 | 양수 ID로 사용자를 생성한다 / 저장된 양수 ID로 사용자를 복원한다 / 0 이하 ID로 사용자를 생성할 수 없다(파라미터화) / 0 이하 ID로 사용자를 복원할 수 없다(파라미터화) | 4 |
| [LikeTest](../../apps/commerce-api/src/test/java/com/loopers/domain/shopping/model/LikeTest.java) | 없음 | 생성한 좋아요는 id와 likedAt이 비어 있다 / 저장된 값으로 좋아요를 복원한다 / userId가 양수가 아니면 생성할 수 없다(파라미터화) / productId가 양수가 아니면 생성할 수 없다(파라미터화) / 복원 시 id·userId·productId가 양수가 아니거나 likedAt이 없으면 거절한다 | 5 |

### support

| 테스트 클래스 | 중첩 그룹 | 검증 시나리오 | 테스트 수 |
|---|---|---|---|
| [DomainExceptionTest](../../apps/commerce-api/src/test/java/com/loopers/domain/support/error/DomainExceptionTest.java) | 없음 | 별도 메시지가 없으면 도메인 오류 코드의 메시지를 사용한다 / 별도 메시지가 있으면 해당 메시지를 사용한다 | 2 |

### architecture (도메인 순수성 검증)

| 테스트 클래스 | 중첩 그룹 | 검증 시나리오 | 규칙 수 |
|---|---|---|---|
| [DomainPurityArchitectureTest](../../apps/commerce-api/src/test/java/com/loopers/architecture/DomainPurityArchitectureTest.java) | 없음 | mall/shopping/ordering/pay/shared 패키지가 Spring·JPA·Servlet 패키지에 의존하지 않는다 / 위 패키지가 `BaseEntity`에 의존하지 않는다 | 2 (`@ArchTest` ArchRule) |

`domain.example`(`ExampleModelTest`, `ExampleServiceIntegrationTest`)은 레거시 참고용 스캐폴딩이라 위 집계와 표에서 제외했다.

## 3. 다른 레이어와 겹치는 검증

R09에서 `OrderConfirmationPolicy`와 그 테스트(10건)를 제거했다. 확정·삭제·잔액 규칙은 `OrderTest`(수량 합산·overflow)와 application 레이어의 `OrderServiceTest`·`ProductServiceTest`·`WalletServiceTest`·`ConfirmOrderFacadeTest`가 나눠 검증한다. 아래 표는 경량화 때 삭제된 `ConfirmOrderServiceTest` 케이스와 그 시점의 대응 도메인 테스트를 보존한 이력이며, 대응 테스트는 위 application 테스트로 옮겨졌다.

| 상위 레이어 테스트 | 대응하는 도메인 테스트 | 비고 |
|---|---|---|
| `BrandApiE2ETest#deletesOnlyUndeletedProducts_whenMixedWithAlreadyDeletedProduct` | `ProductRepositoryIntegrationTest#deletesOnlyActiveProductsOfBrand` | R07에서 상품 일괄 삭제 규칙이 저장소 계층으로 이동 |
| `WalletApiE2ETest#returnsBadRequest_whenBalanceOverflows` | `WalletTest#rejectsOverflow_andKeepsOriginalBalance` | 메서드명 일치 확인 |

위 표의 `BrandTest`/`WalletTest` 메서드는 모두 이 문서에서 실제 소스를 읽어 이름을 직접 확인했고, 표기된 이름과 실제 메서드명이 일치해 불일치 사례는 없었다. `BrandApiE2ETest`/`WalletApiE2ETest`는 application·interfaces 레이어 소관이라 이 문서 범위 밖이며, 이름 대조는 해당 레이어 문서(`application.md`, `interfaces.md`) 작성자가 재확인해야 한다.

## 4. 경량화 결과

도메인 테스트 자체는 이번 경량화로 바뀌지 않았다. 오히려 이 테스트들이 상위 레이어의 중복 케이스(3절)를 안전하게 삭제할 수 있는 근거(base)가 됐다. 마지막으로 측정한 실행 시간은 도메인 테스트 전체 합산 0.3초 미만이고, `DomainPurityArchitectureTest`만 약 3.6초가 걸리는데 이는 ArchUnit이 클래스를 임포트하는 비용이며 같은 비용을 쓰는 `LayerArchitectureTest`와 공유되어(한 번만 지불) 별도 절감 대상이 아니다.

경량화는 상위 레이어(application/interfaces)의 컨텍스트 통합(`@IntegrationTest`로 4개 → 2개), `slow` 태그 분리, `DatabaseCleanUp`의 빈 테이블 스킵, MySQL Testcontainers 재사용, Redis 테스트 컨테이너 제거 위주로 이뤄졌다. 자세한 내용은 `docs/test/application.md`, `docs/test/infrastructure.md`를 참고한다. 경량화 후 `./gradlew :apps:commerce-api:check`는 BUILD SUCCESSFUL, 4분 33초로 끝났다. `test`는 211개, `slowTest`는 17개 테스트를 실행했고 실패는 0건이다. 경량화 이전 마지막 전체 `check`는 247개 테스트였다(`docs/week3/r02-order-consistency/result.md` 기준). 경량화 이전 전체 `check`의 실행 시간은 별도로 측정된 적이 없어 직접 비교할 수 없다.

### 실행 방법

```bash
./gradlew :apps:commerce-api:test      # 빠른 기본(slow·example 태그 제외)
./gradlew :apps:commerce-api:slowTest  # slow·example 태그만
./gradlew :apps:commerce-api:check     # 전체(빌드 + 모든 테스트를 test 1회로 + Checkstyle + ArchUnit)
```

## 5. 작성 규칙

- 구조적 불변 조건(예: null/0 이하 id, null 생성 시각) 위반은 `IllegalArgumentException`으로 던진다.
- 비즈니스 규칙(가격·재고·잔액 등 외부에 안정적인 에러 코드가 필요한 경우) 위반은 `DomainException` + `DomainErrorCode`로 던진다(`User`의 구조적 id 검증만 예외적으로 `DomainException(INVALID_USER_ID)`를 쓰며 새 코드의 템플릿으로 삼지 않는다).
- `@DisplayName`은 한국어로 시나리오를 서술한다.
- 검증은 AssertJ(`assertThat`, `assertThatThrownBy`)를 사용한다.
