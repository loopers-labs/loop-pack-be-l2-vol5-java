# 도메인 테스트

현재 도메인의 값·상태·행동을 Spring·DB 없이 검증하는 안내다. 전체 클래스 목록과 고정 테스트 총수는 관리하지 않는다.
[현재 컨벤션](../week2/conventions.md) · [애플리케이션 테스트](application.md) · [당시 경량화 결과](../refactor/test-slimming.md)

## 실행과 책임

저장소 루트 PowerShell에서 실행한다.

```powershell
./gradlew.bat :apps:commerce-api:test --tests 'com.loopers.domain.*'
```

이 명령의 domain 범위와 별도 architecture 패키지의 ArchUnit을 구분한다.
현재 순수성 대상은 mall/shopping/ordering/pay/coupon/shared이며 example 참고 코드는 별도다.

| 대표 테스트 | 확인할 손실 위험 |
|---|---|
| [StockTest](../../apps/commerce-api/src/test/java/com/loopers/domain/mall/model/StockTest.java), [MoneyTest](../../apps/commerce-api/src/test/java/com/loopers/domain/shared/MoneyTest.java) | 0·정확한 값·부족·최댓값·overflow와 실패 뒤 값 보존 |
| [OrderTest](../../apps/commerce-api/src/test/java/com/loopers/domain/ordering/model/OrderTest.java) | 품목·금액·상태·복원·중복 수량 합산 |
| [PaymentAttemptTest](../../apps/commerce-api/src/test/java/com/loopers/domain/pay/model/PaymentAttemptTest.java), [PaymentRecoveryTaskTest](../../apps/commerce-api/src/test/java/com/loopers/domain/pay/model/PaymentRecoveryTaskTest.java) | 관찰 결과·재시도 회차·claim·종결 경계 |
| [CouponCampaignTest](../../apps/commerce-api/src/test/java/com/loopers/domain/coupon/model/CouponCampaignTest.java), [CouponTest](../../apps/commerce-api/src/test/java/com/loopers/domain/coupon/model/CouponTest.java) | 준비·기간·총량·혜택 snapshot·복원 조건 |

한글 DisplayName, 필요한 Nested, arrange/act/assert와 기존 AssertJ를 사용한다.
create뿐 아니라 restore와 실패 뒤 원래 상태를 확인한다. 단순 getter·필드 복사만 반복하는 검사는 추가하지 않는다.
구조 오류와 업무 코드가 필요한 오류는 실제 계약을 따르며 하나의 예외 형식으로 일괄 바꾸지 않는다.
도메인 성공만으로 SQL rollback·유일 키·외래 키·동시 발급을 검증했다고 하지 않는다.

## 구조 검사

[DomainPurityArchitectureTest](../../apps/commerce-api/src/test/java/com/loopers/architecture/DomainPurityArchitectureTest.java)는
업무 domain의 Spring·JPA·Servlet 및 BaseEntity 의존을 검사한다.
[LayerArchitectureTest](../../apps/commerce-api/src/test/java/com/loopers/architecture/LayerArchitectureTest.java)는
계층·Service 구현·운영 JDBC 의존을 검사한다. 두 검사 모두 테스트 클래스를 제외한다.

```powershell
./gradlew.bat :apps:commerce-api:test --tests 'com.loopers.architecture.*'
```

과거 경량화의 실행 수·시간·중복 제거 근거는 당시 기록으로 보존한다. 현재 전체 검사 수나 최신 성능으로 재사용하지 않는다.
