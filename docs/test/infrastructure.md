# 저장·외부 기술 테스트

repository·조회·DDL·외부 provider/Redis/Kafka adapter를 실제 기술 경계에서 검증한다.
[도메인](domain.md) · [애플리케이션](application.md) · [HTTP](interfaces.md)

## 환경과 실행

```powershell
./gradlew.bat :apps:commerce-api:test --tests 'com.loopers.infrastructure.*'
./gradlew.bat :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest
```

DB/Redis/Kafka Testcontainers 시험은 현재 세션에서 Docker 엔진 접근이 가능해야 한다.
로컬 compose를 켜는 것과 테스트의 독립 컨테이너 준비를 구분한다. 현재 commerce-api는 Redis 컨테이너도 사용한다.
MySQL 공용 fixture는 [MySqlTestContainersConfig](../../modules/jpa/src/testFixtures/java/com/loopers/testcontainers/MySqlTestContainersConfig.java),
정리는 [DatabaseCleanUp](../../modules/jpa/src/testFixtures/java/com/loopers/utils/DatabaseCleanUp.java)을 확인한다.
로컬 reuse 옵션은 사용자 환경에 따라 적용되며 CI/새 컨테이너의 성공을 대신하지 않는다.

Docker API 400이 재현되면 현재 endpoint·API 호환 설정·JDK/Gradle 환경을 먼저 구분한다.
[쿠폰 운영의 실행 환경](../week8/completed/operations.md)과 [과거 환경 재실행 근거](../week8/completed/README.md)를 참고한다.
전역 설정·기존 데이터·컨테이너를 일괄 삭제하거나 다른 세션의 접근 권한을 가정하지 않는다.

## 대표 검증

| 대상 | 대표 테스트와 기준 |
|---|---|
| 일반 repository·QueryDao | [ProductRepositoryIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/persistence/mall/repository/ProductRepositoryIntegrationTest.java), [QueryDslOrderQueryDaoIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/query/ordering/QueryDslOrderQueryDaoIntegrationTest.java): 저장 후 재조회·삭제·사용자 필터·snapshot·정렬 |
| 명시 DDL | [CouponCampaignSchemaIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/persistence/coupon/CouponCampaignSchemaIntegrationTest.java), [CouponIssueSchemaIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/persistence/coupon/CouponIssueSchemaIntegrationTest.java): 실제 SQL 적용·validate·FK/유일 키/인덱스/CHECK |
| 외부 PG·회로 | [PaymentProviderContractTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/gateway/pay/PaymentProviderContractTest.java), [PaymentLookupCircuitIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/resilience/pay/PaymentLookupCircuitIntegrationTest.java): 응답·장애 분류·fallback과 요청 효과 |
| 로컬 캐시 | [ProductDetailCacheInvalidationIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/query/mall/ProductDetailCacheInvalidationIntegrationTest.java): commit/rollback·대상/무관 캐시·실패 반환 재고 |
| 종료 증거 adapter | [JdbcCouponExecutionEvidenceTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/persistence/coupon/JdbcCouponExecutionEvidenceTest.java): DB/세션/정합성의 보수적 판단. 실제 운영 계정 권한 변경 실험과 구분 |

DDL 시험에서 Hibernate 자동 생성 스키마만 확인하고 실제 SQL 제약이 검증됐다고 하지 않는다.
Mapper는 품목·금액·메타데이터 보존이 손실될 수 있는 경우를 검사한다. 조회는 실제 projection·조건·정렬을 확인한다.
외부 기술 실패가 이미 commit된 업무를 되돌리는지 여부는 해당 기능의 application 통합과 함께 검증한다.
Kafka의 앱 간 JSON 계약·Inbox·offset·Outbox 전달 검증은 [6~7주차 안내](../week6-7/README.md)를 따른다.

## 명세와 현재 코드의 차이

R08의 테스트 JDBC 제거는 당시 결정이다. 현재 쿠폰 통합 시험에는 JdbcTemplate이 사용되며
운영 `LayerArchitectureTest`는 테스트를 제외한다. 문서에서 이 차이를 감추거나 검사 규칙을 임의로 바꾸지 않는다.
기존의 모든 클래스·시간·테스트 수 목록 대신 필요한 대표 경계와 실제 실행 근거를 유지한다.
