# HTTP 경계 테스트

실제 Controller·application·저장소를 연결해 요청·접근·응답과 필요한 최종 상태를 검증한다.
[애플리케이션](application.md) · [저장·외부 기술](infrastructure.md) · [현재 컨벤션](../week2/conventions.md)

## 실행과 fixture

```powershell
./gradlew.bat :apps:commerce-api:test --tests 'com.loopers.interfaces.*'
./gradlew.bat :apps:commerce-api:test --tests 'com.loopers.interfaces.api.coupon.controller.CouponApiE2ETest'
```

필터와 slow/example 태그 제외를 구분한다. 전체 태그와 구조·스타일 검사는 [실행 안내](application.md#실행-명령)를 따른다.
공용 E2ETest는 RANDOM_PORT와 공유 spy 구성이다. 쿠폰 독립 JVM·스키마 등 특수 시험의 구성을 공용 context로 억지 통합하지 않는다.
현재 fixture 사용자 식별은 실제 인증과 다르며 관리자 fixture·고객 소유권·테스트 PG 제공자 헤더도 구분한다.

## 대표 시나리오

| 대표 테스트 | 확인할 HTTP 계약 |
|---|---|
| [XUserIdArgumentResolverTest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/support/XUserIdArgumentResolverTest.java) | 헤더 정상·누락/형식·없는 사용자와 형식 오류 시 DAO 미호출 |
| [ApiControllerAdviceTest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/ApiControllerAdviceTest.java) | domain/application/legacy 오류의 ApiResponse·HTTP 매핑 |
| [OrderApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/ordering/controller/OrderApiE2ETest.java) | 생성·PG 확정·조회·고객 소유권·관리자 접근·결과 상태 |
| [PaymentCallbackApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/ordering/controller/PaymentCallbackApiE2ETest.java) | 동기 callback·공통 결과 반영·충돌과 부수 효과 |
| [CouponApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/controller/CouponApiE2ETest.java), [CouponErrorMappingTest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/controller/CouponErrorMappingTest.java) | 최초201/기존200·같은 결과·본인 목록·429/503·관리자 개방/복구·집계 |
| [CouponMultiProcessE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/coupon/controller/CouponMultiProcessE2ETest.java) | 실제 OS JVM 두 개·같은 DB에서 인스턴스별 수용/close/반환 독립성 |
| [ProductDetailCacheHttpE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/mall/controller/ProductDetailCacheHttpE2ETest.java) | 캐시 miss/hit과 동일 고객 응답·쓰기 후 조회 |

하위 계층과 모든 경계값을 반복하기보다 HTTP에서만 드러나는 입력·코드·헤더·envelope·접근 범위를 확인한다.
대표 실패는 응답뿐 아니라 후속 호출과 DB 부수 효과가 계약대로 보존되는지도 확인한다.
오류 mapper의 예외 주입 성공을 실제 DB 장애나 네트워크 단절 재현으로 표현하지 않는다.

현재 API는 과거 주차 계약과 다를 수 있다. 예를 들어 주문 고객 상세·확정은 사용자와 소유권을 검사하며 PG 미완료는 202다.
8주차 쿠폰 발급과 1주차의 미구현 주문 할인 API 설계를 같은 기능으로 취급하지 않는다.
전체 클래스 수·고정 시간 대신 현재 코드와 [쿠폰 검증 한계](../week8/verification-plan.md)를 확인한다.
