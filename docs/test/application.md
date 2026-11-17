# 애플리케이션 테스트

업무 조율의 단위 검사와 실제 트랜잭션 통합 검사를 구분한다.
[도메인](domain.md) · [저장·외부 기술](infrastructure.md) · [HTTP](interfaces.md) · [현재 컨벤션](../week2/conventions.md)

## 실행 명령

저장소 루트 PowerShell에서 실행한다. 아래 태그 동작은 commerce-api의 실제 Gradle 설정 기준이다.

```powershell
./gradlew.bat :apps:commerce-api:test
./gradlew.bat :apps:commerce-api:slowTest
./gradlew.bat :apps:commerce-api:test --tests 'com.loopers.application.*'
./gradlew.bat :apps:commerce-api:check
```

- `test` 단독은 slow/example 태그를 제외한다. 필터를 넣어도 제외 태그가 자동 포함되지는 않는다.
- `slowTest`는 slow/example만 실행한다. `check`/`build`를 요청하면 test의 태그 제외가 풀려 test 한 번에 전부 실행하며 slowTest를 별도로 묶지 않는다.
- `check`는 Checkstyle과 architecture 테스트도 포함한다. 저장소 전체 검사는 `./gradlew.bat check --continue`다.
- Docker가 필요한 테스트와 순수 단위 검사를 구분한다. Testcontainers의 독립 DB 준비와 로컬 compose DB의 수동 준비는 별개다.

## 대표 검증 경계

| 종류·대표 테스트 | 확인할 내용 |
|---|---|
| [ConfirmOrderFacadeTest](../../apps/commerce-api/src/test/java/com/loopers/application/ordering/facade/ConfirmOrderFacadeTest.java) | TX 밖 PG 조율, 준비·관찰·반영·자동 회복의 협력과 후속 처리 중단 |
| [ApplyOrderPaymentIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/ordering/service/ApplyOrderPaymentIntegrationTest.java) | 주문 CAS·재고·기록·결제 결과의 실제 원자성 |
| [ConfirmOrderConcurrencyIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/ordering/service/ConfirmOrderConcurrencyIntegrationTest.java) | 실제 경합과 최종 DB 상태. POINT 이력 검증을 현행 PG HTTP와 혼동하지 않음 |
| [IssueCouponIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/coupon/service/IssueCouponIntegrationTest.java) | 동시 중복·슬롯 경합·품절·마지막 업무 재검증·전체 rollback |
| [CouponAdmissionIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/coupon/service/CouponAdmissionIntegrationTest.java) | 실제 Semaphore·Spring TX·MySQL의 반환/종료 불명/복구 |
| [CouponCounterIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/coupon/service/CouponCounterIntegrationTest.java) | 실제 Redis·MySQL의 집계 중복·접속 실패·재집계 경합과 DB 성공 보존 |

단위 테스트는 실제 도메인과 mock 계약으로 협력·순서·실패 후 미호출을 확인한다.
통합 테스트는 독립 DB의 실제 commit/rollback과 관련 없는 데이터 보존을 확인하며 필요한 경우 flush/clear 뒤 재조회한다.
DB 오류 주입·완료 신호 주입과 실제 네트워크 commit 응답 유실은 서로 다른 검증이다.

## Spring fixture와 실행 근거

공용 [IntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/support/test/IntegrationTest.java)는 MOCK,
[E2ETest](../../apps/commerce-api/src/test/java/com/loopers/support/test/E2ETest.java)는 RANDOM_PORT다.
두 공용 구성은 OrderRepository·PaymentProviderGateway·ProductJpaRepository·JdbcLikeCountAggregationDao의 spy 구성을 공유한다.
독립 스키마·Redis·프로세스가 필요한 시험은 자체 구성을 가지므로 전체 Spring context 수를 두 개로 단정하지 않는다.

실행 근거에는 태스크·필터·revision·환경·tests/failures/errors/skipped를 함께 남긴다.
단위/통합/HTTP에 중복되는 건수를 합산하거나 이전 XML과 이번 실행을 같은 단일 실행으로 표현하지 않는다.
쿠폰의 실제 근거와 미확인 조건은 [검증 매핑](../week8/verification-plan.md), 결제의 당시 근거는 [최종 점검](../week5/final-audit.md)에 있다.
