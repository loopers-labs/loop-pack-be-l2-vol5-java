# Green 구현 계획

Green 에이전트가 Red 전체를 실행하고 실패를 분석한 뒤, production 코드를 수정하기 전에 현재 Red를 해결할 작업을 구현 순서대로 기록한다.

일반적인 절차가 아니라 현재 실패를 해결하는 실제 계획을 적는다. 관련 테스트가 완료 조건대로 Green이 된 뒤에만 `[x]`로 바꾼다.

## 구현

- [x] 1. 불변 값 객체 `Stock`, `Point`, `PaymentResult` 구현
  - 요구사항 ID: R-ADMIN-08, R-ORDER-08, R-ORDER-09, R-ORDER-10, R-ORDER-11, R-POINT-03, R-POINT-04, R-POINT-05, R-POINT-06, R-POINT-07, R-ORDER-12, P-ORDER-06, ADR-003
  - 관찰한 Red와 실패 원인: `Stock.decrease`, `Point.charge/pay`가 항상 자기 자신을 반환하고 생성 검증이 없으며, `PaymentResult`가 null 필드를 허용해 관련 경계값·오류 코드 테스트가 실패한다.
  - 필요한 최소 동작: 음수 재고 거절, 양수 차감 및 부족 거절, 0 이하 차감 내부 오류; 양수 충전·overflow 검사·잔액 결제 및 부족 거절; 결제액/시점 null 거절을 구현하고 불변 객체 원본을 유지한다.
  - 변경할 production 파일: `product/domain/Stock.java`, `user/domain/Point.java`, `order/domain/PaymentResult.java`
  - 선행 작업: 없음
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.product.domain.StockTest' --tests 'com.loopers.user.domain.PointTest' --tests 'com.loopers.order.domain.PaymentResultTest'`
  - 완료 조건: 위 세 테스트 클래스가 모두 Green이고 예외 시 원본 값이 유지된다.

- [x] 2. 브랜드 불변식과 브랜드 정책 검증 구현
  - 요구사항 ID: R-ADMIN-02, R-ADMIN-03, R-ADMIN-13, R-ADMIN-14, R-ADMIN-15, P-ADMIN-01, P-ADMIN-06
  - 관찰한 Red와 실패 원인: `Brand`가 이름을 그대로 저장하고 삭제 여부를 항상 false로 반환하며 수정/재삭제를 막지 않는다. 두 validator도 repository 결과를 검사하지 않아 활성 상품·중복 이름을 허용한다.
  - 필요한 최소 동작: 브랜드 이름 trim/1~50자 검증, 논리 삭제 상태 조회와 재삭제·삭제 후 수정 거절; 활성 브랜드 이름 중복(수정 시 자기 ID 제외) 및 활성 연결 상품 존재 시 삭제 거절을 구현한다.
  - 변경할 production 파일: `brand/domain/Brand.java`, `brand/domain/BrandNameValidator.java`, `brand/domain/BrandDeletionValidator.java`
  - 선행 작업: 3번의 `Product.isDeleted()` (삭제된 연결 상품 판정)
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.brand.domain.*'`
  - 완료 조건: 브랜드 도메인 테스트 3개 클래스가 모두 Green이다.

- [x] 3. 상품 불변식과 상품 이름 정책 구현
  - 요구사항 ID: R-ADMIN-05, R-ADMIN-06, R-ADMIN-07, R-ADMIN-08, R-ADMIN-13, R-ADMIN-14, R-ORDER-07, R-ORDER-08, P-ADMIN-02, P-ADMIN-03, P-ADMIN-04, P-ADMIN-05, P-ADMIN-06
  - 관찰한 Red와 실패 원인: `Product`는 이름·가격·초기 재고를 검증/정규화하지 않고 수정·재고 변경·차감·삭제 상태 동작이 비어 있다. 이름 validator도 중복 결과를 검사하지 않는다.
  - 필요한 최소 동작: 상품명 trim/1~100자 및 가격 1~10억 검증, 초기 재고 0, 같은 브랜드 수정만 원자적으로 반영, 삭제 후 모든 변경과 재삭제 거절, `Stock` 결과 반영; 같은 브랜드의 활성 동명 상품(수정 시 자기 ID 제외)을 거절한다.
  - 변경할 production 파일: `product/domain/Product.java`, `product/domain/ProductNameValidator.java`
  - 선행 작업: 1번 `Stock`
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.product.domain.ProductTest' --tests 'com.loopers.product.domain.ProductNameValidatorTest' --tests 'com.loopers.product.domain.StockTest'`
  - 완료 조건: 상품 도메인 테스트 3개 클래스가 모두 Green이고 실패한 수정에서 기존 상태가 유지된다.

- [x] 4. 좋아요 소유권과 중복 판정 구현
  - 요구사항 ID: R-LIKE-01, R-LIKE-02, R-LIKE-04
  - 관찰한 Red와 실패 원인: `Like.cancel`이 요청자 소유권을 검사하지 않고 `LikeDuplicationChecker`가 항상 false를 반환한다.
  - 필요한 최소 동작: 요청자와 소유자가 다르면 `LIKE_NOT_FOUND`, repository에 동일 사용자·상품 좋아요가 있으면 true를 반환한다.
  - 변경할 production 파일: `like/domain/Like.java`, `like/domain/LikeDuplicationChecker.java`
  - 선행 작업: 없음
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.like.domain.*'`
  - 완료 조건: 좋아요 도메인 테스트 2개 클래스가 모두 Green이다.

- [x] 5. 사용자 포인트 상태 위임 구현
  - 요구사항 ID: R-POINT-08, R-ORDER-10, P-POINT-01
  - 관찰한 Red와 실패 원인: 새 `User`의 point가 null이고 `charge/pay`가 비어 있어 초기 잔액·충전 실패 보존·결제 부족 테스트가 실패한다.
  - 필요한 최소 동작: 사용자를 잔액 0으로 초기화하고 `Point`가 성공적으로 반환한 새 값만 필드에 반영해 예외 시 기존 잔액을 유지한다.
  - 변경할 production 파일: `user/domain/User.java`
  - 선행 작업: 1번 `Point`
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.user.domain.*'`
  - 완료 조건: `UserTest`와 `PointTest`가 모두 Green이다.

- [x] 6. 주문 품목 스냅샷과 주문 상태 머신 구현
  - 요구사항 ID: R-ACCESS-03, R-ORDER-01, R-ORDER-02, R-ORDER-03, R-ORDER-06, R-ORDER-12, R-ORDER-15, P-ORDER-01, P-ORDER-02, P-ORDER-03, P-ORDER-04, P-ORDER-05, P-ORDER-06, P-ORDER-07
  - 관찰한 Red와 실패 원인: `OrderItem`은 상품 스냅샷/금액/수량 변경을 구현하지 않았고 수량을 검증하지 않는다. `Order`는 빈 품목, 중복 병합, 합계, 소유권, 초기 상태, 수량 변경, 확정/재확정 로직이 전부 스텁이다.
  - 필요한 최소 동작: 양수 수량 검증과 상품 필드 복사·금액 계산·불변 수량 변경; 주문 생성 시 빈 목록 거절 및 입력 순서를 유지한 productId별 수량 병합, DRAFT/결제 없음 초기화, 합계·소유권, 소유자 우선 확인 후 DRAFT에서만 수량 변경, 한 번만 결제 결과와 CONFIRMED 상태 반영을 구현한다.
  - 변경할 production 파일: `order/domain/OrderItem.java`, `order/domain/Order.java`
  - 선행 작업: 1번 `PaymentResult`, 3번 `Product`
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.order.domain.OrderItemTest' --tests 'com.loopers.order.domain.OrderTest'`
  - 완료 조건: `OrderItemTest`, `OrderTest`가 모두 Green이고 거절 시 품목·상태·결제 결과가 유지된다.

- [x] 7. 주문 확정의 선검증과 일괄 상태 변경 구현
  - 요구사항 ID: R-ACCESS-03, R-ORDER-07, R-ORDER-08, R-ORDER-09, R-ORDER-10, R-ORDER-11, R-ORDER-12, P-ORDER-04, ADR-004
  - 관찰한 Red와 실패 원인: `OrderConfirmService.confirm`이 비어 있어 성공 시 어떤 상태도 바뀌지 않고 모든 거절 시나리오도 예외를 내지 않는다.
  - 필요한 최소 동작: 소유권을 가장 먼저, 이어 확정 여부·상품 가용성·재고·포인트를 실제 변경 없이 검증한 뒤에만 모든 상품 재고와 구매자 포인트를 차감하고 주문을 확정한다. 주문 품목은 productId로 상품과 대응한다.
  - 변경할 production 파일: `order/domain/OrderConfirmService.java` (필요한 조회성 최소 메서드가 있으면 기존 domain 객체에만 추가)
  - 선행 작업: 1, 3, 5, 6번
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.order.domain.OrderConfirmServiceTest'`
  - 완료 조건: `OrderConfirmServiceTest`가 모두 Green이고 각 거절에서 주문·재고·포인트가 전부 유지된다.

## 최종 검증

- [x] 관련 테스트 전체 Green
- [x] 전체 테스트 Green
- [x] 테스트 파일 목록과 해시가 시작 시점과 동일
- [x] Checkstyle 통과
- [x] ArchitectureTest 통과
- [x] production diff 확인

## Application · JPA 통합 Red 50개 (687c69b)

- [x] 8. 사용자 JPA 저장소와 포인트 유스케이스 구현 (6개 Red)
  - 요구사항 ID: R-POINT-01, R-POINT-02, R-POINT-06, R-POINT-08, P-POINT-01, ADR-006
  - 관찰한 실패: `PointUseCaseIntegrationTest` 5개와 `UserRepositoryIntegrationTest` 1개가 모두 `UnsupportedOperationException`으로 실패한다. `UserRepositoryAdapter`가 Spring Data 저장소를 보관·호출하지 않고, `PointUseCase`도 사용자 조회·충전·저장을 하지 않는다.
  - 필요한 최소 동작: JPA adapter가 `save/findById`를 위임하고, 유스케이스가 사용자를 조회하여 충전 후 저장·잔액 반환 및 저장 잔액 조회를 수행한다. 없는 사용자는 `USER_NOT_IDENTIFIED`로 거절한다.
  - 변경할 production 파일: `user/infrastructure/UserRepositoryAdapter.java`, `user/application/PointUseCase.java`
  - 선행 작업: 기존 완료 TODO 1, 5의 `Point`, `User`; Spring Data `UserJpaRepository`
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.user.application.PointUseCaseIntegrationTest' --tests 'com.loopers.user.infrastructure.UserRepositoryIntegrationTest'`
  - 완료 조건: 사용자/포인트 통합 테스트 6개가 Green이고 flush·clear 뒤 충전 잔액과 최초 잔액 0이 재조회되며 거절 시 기존 잔액이 유지된다.

- [x] 9. 브랜드 JPA 저장소 구현 (1개 Red)
  - 요구사항 ID: R-ADMIN-14, ADR-001, ADR-006
  - 관찰한 실패: `BrandRepositoryIntegrationTest` 1개가 `BrandRepositoryAdapter.save`의 `UnsupportedOperationException`으로 실패한다.
  - 필요한 최소 동작: adapter가 Spring Data 저장소를 보관하고 저장·식별자 조회·이름 조회·최신순 페이지 조회를 위임하여 논리 삭제 상태를 그대로 영속화한다.
  - 변경할 production 파일: `brand/infrastructure/BrandJpaRepository.java`, `brand/infrastructure/BrandRepositoryAdapter.java`
  - 선행 작업: 기존 완료 TODO 2의 JPA Entity `Brand`
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.brand.infrastructure.BrandRepositoryIntegrationTest'`
  - 완료 조건: 저장 후 논리 삭제한 브랜드를 flush·clear 뒤 포트로 재조회했을 때 삭제 상태가 유지되어 테스트 1개가 Green이다.

- [x] 10. 상품 JPA 조회·정렬·페이지 구현 (6개 Red)
  - 요구사항 ID: R-ADMIN-12, R-CATALOG-04, R-CATALOG-05, R-CATALOG-06, P-CATALOG-02, P-CATALOG-04, ADR-006
  - 관찰한 실패: `ProductRepositoryIntegrationTest` 6개가 adapter의 미구현 예외로 실패한다. 고객 목록에는 활성 상품 필터, 세 정렬, ID 보조 정렬과 페이지가 필요하다.
  - 필요한 최소 동작: 기본 CRUD/브랜드·이름 조건 조회를 Spring Data에 위임하고, 고객 조회는 삭제되지 않은 상품을 대상으로 `LATEST(createdAt desc)`, `PRICE_ASC(price asc)`, `LIKES_DESC(관계 count desc)` 뒤 `id asc`를 적용해 0 기반 페이지를 반환한다. 브랜드 가용성은 application이 검사하고, 관리자 목록은 삭제 여부와 관계없이 최신순·ID 오름차순으로 조회한다.
  - 변경할 production 파일: `product/infrastructure/ProductJpaRepository.java`, `product/infrastructure/ProductRepositoryAdapter.java`, `modules/jpa/.../BaseEntity.java`(flush·clear 전후 Entity 식별자 동등성)
  - 선행 작업: 9번 브랜드 영속 매핑, 기존 완료 TODO 3의 `Product`
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.product.infrastructure.ProductRepositoryIntegrationTest'`
  - 완료 조건: 상품 repository 통합 테스트 6개가 Green이고 flush·clear 이후 삭제 제외, 브랜드 필터, 세 정렬, 안정적인 페이지, 생성 시점 최신순이 모두 유지된다.

- [x] 11. 좋아요 JPA 관계 저장·집계·활성 상품 목록 구현 (2개 Red)
  - 요구사항 ID: R-LIKE-05, R-LIKE-07, ADR-006
  - 관찰한 실패: `LikeRepositoryIntegrationTest` 2개가 adapter의 미구현 예외로 실패한다.
  - 필요한 최소 동작: 동일 사용자·상품 관계 조회, 저장·삭제, 상품별 관계 수 집계를 위임하고, 내 좋아요는 삭제되지 않은 상품과 연결된 관계만 좋아요 생성 최신순·ID 오름차순으로 페이지 조회한다. 관계 자체는 상품 삭제 시 남긴다.
  - 변경할 production 파일: `like/infrastructure/LikeJpaRepository.java`, `like/infrastructure/LikeRepositoryAdapter.java`
  - 선행 작업: 10번 상품 JPA 조회
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.like.infrastructure.LikeRepositoryIntegrationTest'`
  - 완료 조건: 좋아요 repository 통합 테스트 2개가 Green이고 flush·clear 후 집계는 2이며 삭제 상품 관계는 DB에 남되 내 목록에서는 제외된다.

- [x] 12. 주문 JPA 컬렉션 저장과 최신순 조회 구현 (3개 Red)
  - 요구사항 ID: R-ADMIN-14, P-ORDER-07, P-ORDER-09, ADR-006
  - 관찰한 실패: `OrderRepositoryIntegrationTest` 3개가 adapter의 미구현 예외로 실패한다.
  - 필요한 최소 동작: `Order`와 `OrderItem` 값 컬렉션을 JPA로 저장·재조회하고, 구매자 목록과 관리자 선택 필터 목록을 생성 시점 내림차순·ID 오름차순으로 페이지 조회한다.
  - 변경할 production 파일: `order/infrastructure/OrderJpaRepository.java`, `order/infrastructure/OrderRepositoryAdapter.java`
  - 선행 작업: 기존 완료 TODO 6의 `Order`, `OrderItem`, `PaymentResult` JPA 매핑
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.order.infrastructure.OrderRepositoryIntegrationTest'`
  - 완료 조건: 주문 repository 통합 테스트 3개가 Green이고 상품 수정·삭제 뒤에도 주문 품목 스냅샷이 유지되며 최신 주문이 첫 페이지에 나온다.

- [x] 13. 브랜드 application CRUD와 정책 조합 구현 (5개 Red)
  - 요구사항 ID: R-ADMIN-01, R-ADMIN-02, R-ADMIN-15, P-ADMIN-01, P-ADMIN-06
  - 관찰한 실패: `BrandUseCaseIntegrationTest` 5개가 `BrandUseCase`의 미구현 예외로 실패한다.
  - 필요한 최소 동작: 포트로 CRUD/페이지 조회를 수행하고, 생성·수정 전 정규화된 이름 중복을 검사하며, 삭제 전 활성 여부와 연결된 활성 상품 여부를 검사한다. 변경 메서드는 한 트랜잭션에서 동작한다.
  - 변경할 production 파일: `brand/application/BrandUseCase.java`
  - 선행 작업: 9, 10번 repository adapter; 기존 완료 TODO 2의 validator
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.brand.application.BrandUseCaseIntegrationTest'`
  - 완료 조건: 브랜드 application 통합 테스트 5개가 Green이고 CRUD 상태가 저장되며 중복·연결 상품·재삭제 거절 때 DB 상태가 유지된다.

- [x] 14. 상품 application CRUD·재고·고객 조회 조합 구현 (10개 Red)
  - 요구사항 ID: R-ADMIN-04, R-ADMIN-05, R-ADMIN-08, R-LIKE-05, P-ADMIN-02, P-ADMIN-05, P-CATALOG-03, P-CATALOG-08
  - 관찰한 실패: `ProductUseCaseIntegrationTest` 10개가 `ProductUseCase`의 미구현 예외로 실패한다.
  - 필요한 최소 동작: 활성 브랜드 확인 후 중복 이름을 검사해 생성하고, 상품 CRUD·재고 변경을 포트로 저장한다. 고객 목록은 정렬 null을 `LATEST`로 바꾸고 없는/삭제된 브랜드 필터에는 빈 목록을 반환하며 각 상품의 좋아요 관계 수를 조합한다.
  - 변경할 production 파일: `product/application/ProductUseCase.java`
  - 선행 작업: 9~11번 repository adapter; 기존 완료 TODO 3의 validator
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.product.application.ProductUseCaseIntegrationTest'`
  - 완료 조건: 상품 application 통합 테스트 10개가 Green이고 CRUD·재고·중복·브랜드 가용성·기본 최신순·좋아요 수 조합 결과가 DB 상태와 일치한다.

- [x] 15. 좋아요 application 멱등 등록·취소·내 목록 구현 (6개 Red)
  - 요구사항 ID: R-LIKE-02, R-LIKE-03, R-LIKE-06, R-LIKE-08, P-LIKE-01
  - 관찰한 실패: `LikeUseCaseIntegrationTest` 6개가 `LikeUseCase`의 미구현 예외로 실패한다.
  - 필요한 최소 동작: 사용자와 활성 상품을 확인하고 기존 관계면 저장하지 않는 멱등 등록, 상품 삭제 여부와 무관하게 소유 관계가 있으면 삭제하는 멱등 취소, 활성 상품에 대한 요청자 관계 목록과 관계 기반 count 조회를 구현한다.
  - 변경할 production 파일: `like/application/LikeUseCase.java`
  - 선행 작업: 8, 10, 11번 repository adapter; 기존 완료 TODO 4의 소유권·중복 규칙
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.like.application.LikeUseCaseIntegrationTest'`
  - 완료 조건: 좋아요 application 통합 테스트 6개가 Green이고 반복 요청은 관계 수를 바꾸지 않으며 삭제 상품 관계 취소와 요청자 전용 목록이 동작한다.

- [x] 16. 주문 application 생성·확정·고객/관리자 조회 구현 (11개 Red)
  - 요구사항 ID: R-ORDER-04, R-ORDER-05, R-ORDER-10, R-ORDER-11, R-ORDER-13, R-ADMIN-10, P-ADMIN-10
  - 관찰한 실패: `OrderUseCaseIntegrationTest` 11개가 `OrderUseCase`의 미구현 예외로 실패한다.
  - 필요한 최소 동작: 구매자와 활성 상품을 포트로 조회해 스냅샷 품목으로 DRAFT 주문만 저장한다. 확정은 주문·전체 상품·구매자를 조회해 기존 도메인 서비스를 호출한 뒤 주문·상품·사용자를 한 트랜잭션에서 저장한다. 고객 조회는 소유권을 숨김 오류로 확인하고, 관리자는 구매자 선택 필터와 전체 목록 및 상세를 조회한다.
  - 변경할 production 파일: `order/application/OrderUseCase.java`
  - 선행 작업: 8, 10, 12번 repository adapter; 기존 완료 TODO 6, 7의 주문·확정 규칙
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.order.application.OrderUseCaseIntegrationTest'`
  - 완료 조건: 주문 application 통합 테스트 11개가 Green이고 생성은 재고·포인트를 유지하며 확정 성공은 세 aggregate를 함께 저장하고 모든 거절은 세 저장 상태를 유지한다.

- [x] 17. application/JPA Red 50개와 전체 회귀 검증
  - 요구사항 ID: 위 8~16번 전체, ADR-006, 아키텍처 의존 방향
  - 관찰한 실패: 기준 실행에서 통합 Red 50개 중 50개가 실패했다.
  - 필요한 최소 동작: 8~16번 구현만으로 50개를 모두 Green으로 만들고 기존 domain·Example·오류 계약을 회귀시키지 않는다. 테스트·기대값·검사 규칙은 변경하지 않는다.
  - 변경할 production 파일: 8~16번에 열거한 application/infrastructure 파일만 해당하며 HTTP/controller는 제외한다.
  - 선행 작업: 8~16번 완료
  - 테스트 명령: 통합 10개 클래스 50개 대상 실행, `./gradlew :apps:commerce-api:test`, `./gradlew :apps:commerce-api:check`, `./gradlew :apps:commerce-api:test --tests 'com.loopers.architecture.ArchitectureTest'`
  - 완료 조건: 관련 50개·전체 테스트·Checkstyle·ArchitectureTest가 모두 Green이고, `687c69b` 대비 모든 `src/test` 파일 SHA-256이 동일하며 `git diff --check`가 통과한다.

## HTTP 계층·이름 대소문자 Red 262개 (e1fef44)

기준 실행 `./gradlew :apps:commerce-api:test --continue`: 449개 중 262개 실패. HTTP 7개 클래스 258개는 모두 컨트롤러와 관리자 접근 필터가 없어 `404 NOT_FOUND`(`NoResourceFoundException`)로 끝난다. 나머지 4개는 이름 중복 검사가 MySQL 기본 콜레이션(`utf8mb4_general_ci`)의 대소문자 무시 조회 결과를 그대로 중복으로 판단해 실패한다.

- [x] 18. 이름 중복을 대소문자를 구분해 판단 (4개 Red)
  - 요구사항 ID: P-ADMIN-01, P-ADMIN-02
  - 관찰한 실패: `BrandUseCaseIntegrationTest$RejectDuplicatedBrandName`의 `savesNameDifferentOnlyByCase`·`updatesToNameDifferentOnlyByCase`, `ProductUseCaseIntegrationTest$RejectDuplicatedProductName`의 같은 이름 두 테스트가 `DUPLICATE_BRAND_NAME`·`DUPLICATE_PRODUCT_NAME` 예외로 실패한다. `findAllByName("NIKE")`가 `Nike`를 돌려주고 validator가 이름을 다시 비교하지 않는다.
  - 필요한 최소 동작: 두 validator가 조회 결과 중 이름이 정확히 같은(`String.equals`) 활성 대상만 중복으로 본다. 규칙이 DB 콜레이션에 기대지 않게 domain에서 비교한다.
  - 변경할 production 파일: `brand/domain/BrandNameValidator.java`, `product/domain/ProductNameValidator.java`
  - 선행 작업: 없음
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.brand.application.BrandUseCaseIntegrationTest' --tests 'com.loopers.product.application.ProductUseCaseIntegrationTest' --tests 'com.loopers.brand.domain.BrandNameValidatorTest' --tests 'com.loopers.product.domain.ProductNameValidatorTest'`
  - 완료 조건: 네 클래스가 모두 Green이고, 앞뒤 공백만 다른 이름은 여전히 중복으로 거절된다.

- [x] 19. HTTP 공통 기반: 관리자 접근 필터, 요청자 식별, 페이지 조건, 목록 응답, 입력 형식 검사
  - 요구사항 ID: R-ACCESS-04, R-ACCESS-05, P-ACCESS-01, P-CATALOG-05, P-CATALOG-06, P-CATALOG-07, R-POINT-04
  - 관찰한 실패: `RequesterAccessHttpTest$AdminOnly`의 비관리자 조회·변경이 403이 아니라 404이고, 고객 API의 식별 실패가 401이 아니라 404이다. 목록 응답의 `page`·`size`·`totalElements`를 만들 곳이 없고, `1.5` 같은 실수 입력을 Jackson 기본값(`ACCEPT_FLOAT_AS_INT`)이 정수로 받아들인다.
  - 필요한 최소 동작:
    - 과제 문서의 `spring-boot-starter-security` 의존성과 `AdminBoundaryConfig`로 `/api-admin/**`에 `ADMIN` 역할을 요구하고, 미인증·권한 없음을 본문 없는 `403`으로 거절한다.
    - `X-USER-ID`가 없거나 숫자가 아니거나 없는 사용자이면 같은 `401 USER_NOT_IDENTIFIED`를 준다. 컨트롤러 인자 `Requester`를 resolver가 채우고, 사용자 존재 확인은 application(`UserUseCase`)이 한다. 인터셉터로 막지 않아 매핑되지 않은 경로의 `404 NOT_FOUND` 계약을 유지한다.
    - `page`는 0 이상, `size`는 1~100(기본 0, 20)이 아니면 `400 INVALID_REQUEST`로 거절한다. 목록 응답은 `content`·`page`·`size`·`totalElements`다. application은 목록과 전체 수를 한 트랜잭션에서 `PageResult`로 돌려준다.
    - 요청 본문의 필수 필드가 없으면 `400 INVALID_REQUEST`로 거절하고, 실수를 정수 필드로 받지 않게 `ACCEPT_FLOAT_AS_INT`를 끈다(공용 `supports/jackson`은 고치지 않고 앱 설정에서 끈다).
  - 변경할 production 파일: `apps/commerce-api/build.gradle.kts`, `config/AdminBoundaryConfig.java`, `config/WebConfig.java`, `interfaces/api/Requester.java`, `interfaces/api/RequesterArgumentResolver.java`, `interfaces/api/PageQuery.java`, `interfaces/api/ListResponse.java`, `interfaces/api/RequestFields.java`, `support/page/PageResult.java`, `user/application/UserUseCase.java`
  - 선행 작업: 없음
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.CommerceApiContextTest' --tests 'com.loopers.interfaces.api.ContractClassificationTest' --tests 'com.loopers.interfaces.api.ExampleV1ApiE2ETest' --tests '*RequesterAccessHttpTest*rejectsNonAdminReads*'`
  - 완료 조건: 기존 컨텍스트·Example·계약 분류 테스트가 그대로 Green이고, 비관리자·미식별 관리자 조회 12개가 오류 코드 없는 403으로 Green이다.

- [x] 20. 브랜드 고객·관리자 API (26개 Red)
  - 요구사항 ID: R-CATALOG-01, R-CATALOG-07, R-ADMIN-01, R-ADMIN-12, P-ADMIN-07, P-ADMIN-08 (C-01, A-01~A-05)
  - 관찰한 실패: `BrandHttpTest` 26개가 모두 404 `NOT_FOUND`이다.
  - 필요한 최소 동작: 고객 브랜드 상세(`id`, `name`, 없거나 삭제되면 `BRAND_NOT_FOUND`), 관리자 목록(삭제 포함 최신순 페이지)·생성(201)·상세(삭제 포함)·수정·삭제(`data` 없음)를 연결한다. application에 활성 브랜드 조회와 페이지 조회를 더한다. 저장소 포트에 전체 수 조회를 더한다.
  - 변경할 production 파일: `brand/interfaces/BrandV1Controller.java`, `brand/interfaces/BrandAdminV1Controller.java`, `brand/interfaces/BrandV1Dto.java`, `brand/application/BrandUseCase.java`, `brand/domain/BrandRepository.java`, `brand/infrastructure/BrandRepositoryAdapter.java`
  - 선행 작업: 19번
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.brand.interfaces.BrandHttpTest'`
  - 완료 조건: `BrandHttpTest` 26개가 Green이다.

- [x] 21. 상품 고객·관리자 API와 재고 변경 (75개 Red)
  - 요구사항 ID: R-CATALOG-02, R-CATALOG-03, R-CATALOG-07, R-CATALOG-08, R-ACCESS-06, R-ADMIN-04, R-ADMIN-06, R-ADMIN-08, R-ADMIN-09, R-ADMIN-13, P-CATALOG-01, P-CATALOG-05, P-CATALOG-06, P-CATALOG-07, P-ADMIN-03, P-ADMIN-04, P-ADMIN-07, P-ADMIN-08, P-ADMIN-09 (C-02, C-03, A-06~A-11)
  - 관찰한 실패: `ProductHttpTest` 75개가 모두 404 `NOT_FOUND`이다.
  - 필요한 최소 동작: 고객 상품(`id`, `name`, `price`, `brand{id,name}`, `likeCount`, `soldOut`)의 목록·상세, 관리자 상품(`id`, `name`, `price`, `brand{id,name}`, `stock`, `deleted`)의 목록·생성·상세·수정·삭제·재고 변경을 연결한다. `sort`는 `latest`·`price_asc`·`likes_desc`만 받고(없으면 `latest`) 그 밖은 `INVALID_REQUEST`다. application이 상품·브랜드·좋아요 수를 함께 조합하고(대표 흐름 1), 고객 목록은 없거나 삭제된 브랜드 필터에서 빈 페이지를 준다. 저장소 포트에 관리자·고객 목록의 전체 수 조회를 더한다.
  - 변경할 production 파일: `product/interfaces/ProductV1Controller.java`, `product/interfaces/ProductAdminV1Controller.java`, `product/interfaces/ProductV1Dto.java`, `product/application/ProductUseCase.java`, `product/domain/ProductRepository.java`, `product/infrastructure/ProductJpaRepository.java`, `product/infrastructure/ProductRepositoryAdapter.java`
  - 선행 작업: 19, 20번
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.product.interfaces.ProductHttpTest'`
  - 완료 조건: `ProductHttpTest` 75개가 Green이고, 거절된 생성·수정·재고 변경 뒤 상품이 그대로다.

- [x] 22. 포인트 충전·잔액 API (18개 Red)
  - 요구사항 ID: R-POINT-01, R-POINT-02, R-POINT-04, R-POINT-06, R-POINT-07, R-POINT-08 (C-07, C-08)
  - 관찰한 실패: `PointHttpTest` 18개가 모두 404 `NOT_FOUND`이다.
  - 필요한 최소 동작: 요청자의 잔액을 충전해 `{balance}`를 주고, 저장된 잔액을 조회한다. `amount` 누락·정수 아님·64비트 범위 초과는 `INVALID_REQUEST`, 0 이하·한도 초과는 domain 오류를 그대로 준다.
  - 변경할 production 파일: `user/interfaces/PointV1Controller.java`, `user/interfaces/PointV1Dto.java`
  - 선행 작업: 19번
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.user.interfaces.PointHttpTest'`
  - 완료 조건: `PointHttpTest` 18개가 Green이고 거절 뒤 잔액이 그대로다.

- [x] 23. 좋아요 등록·취소·내 좋아요 목록 API (10개 Red)
  - 요구사항 ID: R-LIKE-01, R-LIKE-04, R-LIKE-06, P-ACCESS-02, P-LIKE-01, P-LIKE-02 (C-04~C-06)
  - 관찰한 실패: `LikeHttpTest` 10개가 모두 404 `NOT_FOUND`이다.
  - 필요한 최소 동작: 처음 등록은 201, 반복 등록은 200(둘 다 `data` 없음), 취소는 항상 200이다. 내 좋아요 목록은 경로의 `userId`가 요청자가 아니면 application이 `404 USER_NOT_FOUND`로 거절하고, 요청자의 활성 상품을 고객 상품 형식의 페이지로 준다. 저장소 포트에 활성 상품 좋아요 수 조회를 더한다.
  - 변경할 production 파일: `like/interfaces/LikeV1Controller.java`, `like/application/LikeUseCase.java`, `like/domain/LikeRepository.java`, `like/infrastructure/LikeJpaRepository.java`, `like/infrastructure/LikeRepositoryAdapter.java`
  - 선행 작업: 19, 21번(고객 상품 응답)
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.like.interfaces.LikeHttpTest'`
  - 완료 조건: `LikeHttpTest` 10개가 Green이다.

- [x] 24. 주문 고객·관리자 API (37개 Red)
  - 요구사항 ID: R-ORDER-01, R-ORDER-05, R-ORDER-06, R-ORDER-13, R-ORDER-14, R-ADMIN-10, R-ADMIN-11, P-ACCESS-02, P-ADMIN-10, P-ORDER-01, P-ORDER-04, P-ORDER-07, P-ORDER-08, P-ORDER-09 (C-09~C-12, A-12, A-13)
  - 관찰한 실패: `OrderHttpTest` 37개가 모두 404 `NOT_FOUND`이다.
  - 이어받은 시점의 관찰(작업 트리, 전체 449개 중 25개 실패): 컨트롤러·DTO·목록 전체 수 조회는 이미 연결되어 있다. `OrderHttpTest` 19개, `RequesterAccessHttpTest` 5개, `ChargeOrderFlowHttpTest` 1개가 모두 주문을 읽는 요청에서 `500 INTERNAL_ERROR`로 실패한다. 원인은 `LazyInitializationException: Order.items ... no Session`이다. 트랜잭션이 끝난 뒤 컨트롤러가 응답을 만들며 지연 로딩 컬렉션 `items`를 읽는다. 남은 일은 아래의 `FetchType.EAGER` 한 가지다.
  - 필요한 최소 동작: 주문 생성(201, 주문 상세)·확정(200, 주문 상세)·내 주문 목록(요약)·상세, 관리자 주문 목록(요약 + `buyerId`, `buyerId` 선택 필터)·상세(상세 + `buyerId`)를 연결한다. `items`·`productId`·`quantity` 누락은 `INVALID_REQUEST`이다. `open-in-view: false`에서 트랜잭션 밖에서 품목을 읽을 수 있도록, 주문 애그리거트의 값 컬렉션 `items`를 주문과 함께 읽는다(`FetchType.EAGER`). 저장소 포트에 목록 전체 수 조회를 더한다.
  - 변경할 production 파일: `order/interfaces/OrderV1Controller.java`, `order/interfaces/OrderAdminV1Controller.java`, `order/interfaces/OrderV1Dto.java`, `order/application/OrderUseCase.java`, `order/domain/Order.java`, `order/domain/OrderRepository.java`, `order/infrastructure/OrderJpaRepository.java`, `order/infrastructure/OrderRepositoryAdapter.java`
  - 선행 작업: 19, 21번(재고 확인용 관리자 상품), 22번(잔액 확인)
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.order.interfaces.OrderHttpTest'`
  - 완료 조건: `OrderHttpTest` 37개가 Green이고, 거절된 생성·확정 뒤 주문 수·주문 상태·재고·잔액이 그대로다.

- [x] 25. 요청자 구분과 충전→주문 확정 연결 흐름 검증 (92개 Red)
  - 요구사항 ID: R-ACCESS-01, R-ACCESS-02, R-ACCESS-04, R-ACCESS-05, P-ACCESS-01, R-POINT-02, R-POINT-06, R-ORDER-11, R-ORDER-12, R-ORDER-14
  - 관찰한 실패: `RequesterAccessHttpTest` 87개(401·403·200·201 기대에 404), `ChargeOrderFlowHttpTest` 5개(200 기대에 404).
  - 이어받은 시점의 관찰: 남은 실패는 `RequesterAccessHttpTest` 5개(`CustomerFeatures.usesOrders`, `AdminFeatures.readsBuyersOrders`, `RejectUnidentifiedCustomer.keepsStateWhenCustomerIsUnidentified` 3개)와 `ChargeOrderFlowHttpTest` 1개(`ReadOrderAfterFlow`)다. 모두 주문 조회에서 24번과 같은 `LazyInitializationException`으로 500이 난다.
  - 필요한 최소 동작: 19~24번 외 추가 구현 없음. 모든 고객 API가 식별 실패에 같은 401 본문을 주고, 비관리자 변경 요청이 상태를 바꾸지 않는지 확인한다.
  - 변경할 production 파일: 없음(부족하면 19~24번 파일 안에서만 보완)
  - 선행 작업: 19~24번
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.interfaces.api.RequesterAccessHttpTest' --tests 'com.loopers.interfaces.api.ChargeOrderFlowHttpTest'`
  - 완료 조건: 두 클래스 92개가 Green이다.

- [x] 26. HTTP·이름 Red 262개와 전체 회귀 검증
  - 요구사항 ID: 위 18~25번 전체, 아키텍처 의존 방향
  - 관찰한 실패: 기준 실행에서 262개 실패.
  - 필요한 최소 동작: 18~25번 구현만으로 262개를 Green으로 만들고 domain·application·repository·Example 테스트를 회귀시키지 않는다. 테스트·기대값·검사 규칙과 공용 모듈(`modules/`, `supports/`)은 바꾸지 않는다.
  - 변경할 production 파일: 18~25번에 열거한 파일만
  - 선행 작업: 18~25번
  - 테스트 명령: 위 HTTP 7개 클래스와 이름 관련 2개 클래스 실행, `./gradlew :apps:commerce-api:test --continue`, `./gradlew :apps:commerce-api:check`, `./gradlew :apps:commerce-api:test --tests 'com.loopers.architecture.ArchitectureTest'`, `git diff --check`
  - 완료 조건: 관련·전체 테스트, Checkstyle, ArchitectureTest가 모두 Green이고, `e1fef44` 시작 시점과 `src/test` 파일 목록·SHA-256이 같다.

## 좋아요 DB 중복 관계 Red 1개

- [x] 27. 고객·상품 좋아요 관계의 DB 복합 유일성 보장
  - 요구사항 ID: R-LIKE-02, ADR-006
  - 관찰한 실패: `LikeRepositoryIntegrationTest` 3개 중 `rejectsDuplicateRelationAtDatabase` 1개가 실패한다. 같은 `userId=1`, `productId=10`인 두 `Like`를 저장하고 `flush`해도 예외가 나지 않아 DB 중복 관계가 허용된다.
  - 필요한 최소 동작: `product_like` 테이블에 `(user_id, product_id)` 복합 유니크 제약을 두어 같은 고객의 같은 상품 관계를 DB가 거절한다. 서로 다른 고객이나 상품의 관계는 계속 허용한다.
  - 변경할 production 파일: `apps/commerce-api/src/main/java/com/loopers/like/domain/Like.java`
  - 선행 작업: 없음
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.like.infrastructure.LikeRepositoryIntegrationTest' --rerun-tasks`
  - 완료 조건: 통합 테스트 3개가 모두 Green이고, 중복 관계를 두 번 저장하고 반영하는 과정이 `PersistenceException`으로 거절된다.

## 포인트 음수 잔액 Red 1개

- [x] 28. Point 생성 시 음수 잔액 거절
  - 요구사항 ID: R-POINT-05, R-ORDER-09, INV-POINT-16
  - 관찰한 실패: 전달된 Red 3개 클래스 41개 중 `PointTest`의 `throwsInvalidPointBalance_whenBalanceIsNegative` 1개가 실패한다. `Point(long balance)`가 `-1`을 그대로 저장해 `CoreException`을 던지지 않는다. `BrandDeletionValidatorTest`의 연결 상품 없음 허용과 `ErrorStatusTest`의 `INVALID_POINT_BALANCE` 400 매핑은 이미 Green이다.
  - 필요한 최소 동작: `Point` 생성자에서 잔액이 0보다 작으면 `INVALID_POINT_BALANCE`인 `CoreException`으로 거절하고, 0 이상은 기존처럼 저장한다.
  - 변경할 production 파일: `apps/commerce-api/src/main/java/com/loopers/user/domain/Point.java`
  - 선행 작업: `INVALID_POINT_BALANCE` 오류 코드와 `ErrorStatus`의 `BAD_REQUEST` 매핑이 이미 존재함
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.user.domain.PointTest' --tests 'com.loopers.brand.domain.BrandDeletionValidatorTest' --tests 'com.loopers.interfaces.api.ErrorStatusTest'`
  - 완료 조건: 위 3개 테스트 클래스 41개가 모두 Green이고 `Point(-1)`은 `INVALID_POINT_BALANCE`로 거절되며 `Point(0)`과 `Point(1)`은 허용된다.

## 브랜드 연관 상품 일괄 삭제 Red 2개

- [x] 29. 브랜드와 연결된 활성 상품의 도메인 상태를 함께 삭제
  - 요구사항 ID: R-ADMIN-16, INV-BRAND-03
  - 관찰한 실패: 전체 Red 실행의 `compileTestJava`가 새 `BrandRemovalServiceTest`에서 `BrandRemovalService` 심볼을 찾지 못해 실패한다.
  - 필요한 최소 동작: 전달된 상품 중 삭제되지 않은 상품만 삭제하고, 브랜드를 삭제한다. 이미 삭제된 상품의 삭제 시점은 보존한다.
  - 변경할 production 파일: `apps/commerce-api/src/main/java/com/loopers/brand/domain/BrandRemovalService.java`
  - 선행 작업: 기존 `Brand.delete()`와 `Product.delete()`의 논리 삭제 구현
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.brand.domain.BrandRemovalServiceTest'`
  - 완료 조건: 새 도메인 테스트 2개가 Green이고, 기존 삭제 상품의 삭제 시점이 유지된다.

## 브랜드 일괄 삭제 통합·HTTP Red 2개

- [x] 30. 브랜드 삭제 유스케이스에서 연결 상품을 함께 논리 삭제
  - 요구사항 ID: R-ADMIN-16, R-ADMIN-14, INV-BRAND-03
  - 관찰한 실패: `BrandUseCaseIntegrationTest`의 `removesLinkedProductsOnly`가 기존 `BrandDeletionValidator`의 `CoreException`으로 실패하고, `BrandHttpTest`의 `deletesBrandWithLinkedProduct`가 삭제 거절 응답으로 OpenAPI 검증에 실패한다. 대상 두 클래스를 실행한 결과 38개 중 이 2개가 Red다.
  - 필요한 최소 동작: 기존 `BrandUseCase.delete()`의 `@Transactional` 경계 안에서 해당 브랜드의 상품을 조회해 `BrandRemovalService`로 활성 상품과 브랜드를 삭제하고, 저장한다. 이미 삭제된 상품과 다른 브랜드·기존 주문은 변경하지 않는다.
  - 변경할 production 파일: `apps/commerce-api/src/main/java/com/loopers/brand/application/BrandUseCase.java`
  - 선행 작업: 기존 `BrandRemovalService`와 `ProductRepository.findAllByBrandId` 사용
  - 테스트 명령: `./gradlew :apps:commerce-api:test --tests 'com.loopers.brand.application.BrandUseCaseIntegrationTest' --tests 'com.loopers.brand.interfaces.BrandHttpTest' --rerun-tasks`
  - 완료 조건: 두 클래스 전체가 Green이고 새 두 사례에서 브랜드·연결 상품의 삭제와 다른 대상·기존 주문 보존을 확인한다.
