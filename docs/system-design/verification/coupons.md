# 쿠폰 발급 구현·실행 기록

계약의 원본은 [쿠폰 상세설계](../features/coupons.md)와 [쿠폰 판단 근거](../decisions/coupons.md)다. 이 문서는 구현 단위별 실행 조건·관찰값·한계를 남긴다. 설계의 Q1~Q16 전체 완료 기록이 아니다.

## 구현 1단계 — DB 저장·조회 계약 (2026-11-11)

### 범위

- 구현: `CouponCampaignModel`·`CouponModel`, `CouponCampaignStatus`·`CouponBenefitType`과 기존 `EnumNameConverter` 방식 변환기, `CouponCampaignRepository`(`save`·`reserveOne`·`findIssueData`·`findById`)·`CouponRepository.save`·`CouponQueryRepository`(`findOwned`·`findOwnedPage`)와 infrastructure 구현, 불변 조회 결과 `OwnedCoupon`·`CouponCampaignIssueData`, `ConstraintViolationClassifier`의 `uk_coupon_campaign_user` 식별.
- 테스트: 모델 단위 테스트, 분류기 단위 테스트, 실제 MySQL의 스키마·쓰기 TX·직접 DB 경쟁·조회 통합 테스트, 조건별 test fixture `CouponFixture`.
- 제외: `CouponFacade`, Redis Lua·수용 서비스, 고객·제어 API, main 준비 코드. 쓰기 TX는 test 소스의 `TransactionTemplate`으로 설계 §6.2 순서를 조합했다. 제품에 별도 TX 서비스·테스트 hook을 추가하지 않았다.

### 실행 조건

- 기준 revision: `541b451`(브랜치 `volume-8`)과 이 단계의 미커밋 변경. 커밋 전 작업 트리에서 실행했다.
- JDK 21.0.12(Homebrew). 로컬 `~/.gradle/gradle.properties`가 Gradle daemon을 JDK 17로 지정하고 있어 subproject 컴파일이 실패하므로, 명령에 `-Dorg.gradle.java.home=<JDK 21 경로>`를 붙여 실행했다. 저장소 설정은 바꾸지 않았다.
- Docker 29.7.2, 기존 `MySqlTestContainersConfig`의 `mysql:8.0` 테스트 컨테이너, `ddl-auto=create`. Redis는 이 단계 테스트의 검증 대상이 아니다.

```sh
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:test \
  --tests '*Coupon*' --tests '*ConstraintViolationClassifierTest' --rerun --no-build-cache
```

- 실행: 2026-11-11 13:58:46~13:59:07 KST, `BUILD SUCCESSFUL`. 34건 실행·실패 0·skip 0. 보고서는 `apps/commerce-api/build/reports/tests/test/` (이후 실행으로 덮어써지는 빌드 산출물이다).
- 같은 명령의 직전 실행(13:58:14~13:58:35)은 34건 중 1건 실패였다. `persistGuard` 테스트가 guard 예외를 원인으로 감싼 형태로 기대했으나 실제로는 `CoreException`이 그대로 전파됐다. guard 동작이 아니라 테스트의 예외 형태 기대가 틀린 것이며, 기대를 `isInstanceOf(CoreException)`로 고친 뒤 위 실행에서 통과했다.

| 테스트 클래스 | 건수 |
|---|---|
| `CouponCampaignModelTest` | 11 |
| `CouponModelTest` | 3 |
| `ConstraintViolationClassifierTest` | 2 |
| `CouponSchemaIntegrationTest` | 5 |
| `CouponIssueTransactionIntegrationTest` (단일 TX 4·직접 DB 경쟁 3) | 7 |
| `CouponQueryRepositoryIntegrationTest` | 6 |

회귀 실행 결과는 아래 [회귀·Checkstyle·ArchUnit](#회귀checkstylearchunit)에 둔다.

### 실제 관찰

- **DDL:** `coupon_campaign`의 `name varchar(100)`, `status`·`benefit_type varchar(16)`, `total_quota`·`issued_count`·`benefit_value bigint`, `coupon`의 `campaign_id`·`user_id`·`benefit_value bigint`, `benefit_type varchar(16)`, `created_at datetime(6)` 모두 NOT NULL. `uk_coupon_campaign_user(campaign_id, user_id)`, 목록 인덱스 `idx_coupon_user_created(user_id, created_at, id)`, `ck_coupon_campaign_quota = ((total_quota >= 1) and (issued_count >= 0) and (issued_count <= total_quota))`, 두 테이블 관련 물리 FK 0개. 인덱스는 존재·열 순서만 확인했으며 실행계획·성능은 확인하지 않았다. 목록 인덱스 이름은 설계에 이름이 없어 구현에서 정했다.
- **제약 적용:** CHECK 위반(총량 0·발급 수 -1·총량 초과)은 MySQL 3819와 제약 이름, 같은 캠페인·사용자 두 번째 INSERT는 `uk_coupon_campaign_user` 위반, NULL user_id는 NOT NULL 위반으로 실패했다.
- **SQL 순서·격리:** 한 쓰기 TX의 Hibernate SQL 캡처는 `update coupon_campaign set issued_count = issued_count + 1, updated_at = ? where id = ? and status = 'ACTIVE' and issued_count < total_quota` → `select id, name, benefit_type, benefit_value from coupon_campaign where id=?` → `insert into coupon (...)` 세 문장이었다(캡처 문자열은 비교를 위해 소문자·공백 정규화). INSERT 전 `(campaign_id, user_id)` 조회는 없었다. 콜백 안의 `@@transaction_isolation`은 `REPEATABLE-READ`였다.
- **save 반환 객체:** 반환 객체는 인자와 다른 인스턴스였고 인자 ID는 0으로 남았다. guard 실패 스택에서 `DefaultMergeEventListener` 경로가 보였다. BaseEntity 초기 ID 때문에 merge 경로를 탄 것으로 해석한다. 다만 이번 조건에서는 merge에 따른 `coupon` PK SELECT가 SQL 캡처에 나타나지 않았다.
- **commit/rollback:** 정상 commit 뒤 새 조회에서 쿠폰 1·issuedCount 1, DB 쿠폰 ID와 반환 객체 ID 일치. UPDATE 1건 뒤 save 지점 실패 주입과 실제 INSERT의 named unique 충돌 모두 TX 종료 뒤 새 조회에서 증가분이 남지 않았다. 충돌 예외는 분류기가 `uk_coupon_campaign_user`로 식별했다.
- **수량 미확보 0건:** 비활성·품절·없는 캠페인의 조건부 UPDATE는 0건이며 DB 효과가 없었다.
- **총량 5·사용자 8 직접 DB 경쟁:** 시작 latch 뒤 8개 worker가 동시에 쓰기 TX를 수행해 성공 5·수량 미확보(UPDATE 0건) 3, 실제 쿠폰 5(서로 다른 사용자 5)·issuedCount 5. 이 3건은 DB 수량 미확보 관찰이며 HTTP 409 품절 응답 검증이 아니다.
- **같은 사용자 동시 경쟁:** 앞 TX를 INSERT 뒤 commit 전에 붙잡고 뒤 TX가 `update coupon_campaign` 잠금을 기다리는 것을 InnoDB `innodb_trx`의 LOCK WAIT로 확인한 뒤 놓았다. 잔여량 10에서는 뒤 TX도 UPDATE 1건 뒤 INSERT가 named unique로 rollback, 총량 1에서는 뒤 TX가 UPDATE 0건으로 INSERT에 도달하지 않았다. 두 경우 모두 worker 종료 확인 뒤 새 읽기에서 쿠폰 1·issuedCount 1이고 `findOwned`가 앞 TX의 쿠폰 ID를 반환했다.
- **조회:** 본인 필터, 빈 목록 총건수 0, 페이지 경계, latest/oldest와 같은 `created_at` 세 건의 ID 보조 정렬, 현재 캠페인 이름·발급 당시 혜택, 비활성 캠페인 쿠폰의 목록 유지, 조회 시각과 DB `created_at` 원문의 마이크로초 일치·반복 조회 일치를 확인했다. 캠페인 행을 지운 손상 데이터는 `findOwned`·`findOwnedPage` 모두 INTERNAL_ERROR였고 쿠폰 행은 남았다.

### 설계 Q 대응 — 부분 근거

| Q | 이번 근거 | 남은 검증 |
|---|---|---|
| Q1 | 본인/타인 조회 분리, 쓰기 TX 정상 commit의 쿠폰 1·issuedCount 1 | 사용자 식별 오류, HTTP 201·본인 목록 API |
| Q2 | 현재 이름/과거 혜택, 비활성 뒤 기존 쿠폰 조회 | 순차 재요청 200·응답 필드, 추가 효과 0의 API 경로 |
| Q3 | 같은 사용자 named unique 충돌과 마지막 수량 UPDATE 0건 두 경로의 DB 효과 1·새 읽기 결과 | Redis 수용·사전 조회 통과를 포함한 HTTP 경로와 200 응답 |
| Q4 | 총량 5·사용자 8 직접 DB 경쟁 5/3, 쿠폰 5·issuedCount 5 | HTTP 409 품절 응답, Redis 거절과의 분리 집계 |
| Q5 | UPDATE 뒤 save 지점 실패·실제 INSERT 충돌의 전체 rollback과 새 조회 | 자기 token 반환 |
| Q13 | 증가+INSERT 동일 TX, SQL 순서, INSERT 전 중복 재조회 없음, CHECK 위반이 named unique로 분류되지 않음, named unique 식별 | Redis 호출 중 TX 없음, TX 대역의 미시작·COMMITTED+예외 분기 |
| Q15 | 이름·정액/정률·총량 경계, 안정 정렬·총건수, 현재 이름/과거 혜택, 연결 손상의 명시적 실패, 저장 시각 일치·반복 일치 | 페이지 오류·DTO, `createdAt` UTC·6자리·Z JSON 형식, C 설정·준비 옵션 |
| Q16 | Checkstyle·ArchUnit 통과, worker 종료 확인 뒤 테스트 DB 정리. 최초 회귀 9건 실패 → 아래 후속 수정 뒤 전체 회귀·O11 통과 | 이후 단계의 회귀 |

Q6~Q12·Q14는 이번 단계에서 검증하지 않았다.

### 한계

- commit 시점 예외는 별도로 재현하지 않았다. IDENTITY라 INSERT 실패는 save 호출 중에 드러났고, 테스트는 `transactionTemplate.execute` 바깥에서 예외를 받았다. commit 예외·종료 불명 분기는 Facade 단계와 TX 대역 검증 대상이다.
- 쓰기 TX 흐름은 test 소스의 조합이며 제품 Facade의 동작 검증이 아니다. HTTP 201/200/409, Redis token, 응답 시각 JSON 형식은 검증하지 않았다.
- 직접 경쟁은 한 번 실행한 결과다. 반복 실행 안정성·처리량·잠금 대기 시간은 측정하지 않았다.
- merge 경로에서 PK SELECT가 나타나지 않은 원인은 확인하지 않았다(미확인 가설은 로컬 글감 후보 W8-I02).

### 회귀·Checkstyle·ArchUnit

```sh
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  :apps:commerce-api:test --rerun --no-build-cache
```

- 실행: 2026-11-11 13:59:26~14:07:04 KST, **`BUILD FAILED`**. Checkstyle main/test는 통과했다. 테스트 보고서 242개, 1063건 실행·실패 9·오류 0·skip 0. `ArchitectureTest` 3건은 통과했다. 쿠폰 관련 34건은 이 실행에서도 통과했다.
- **실패 9건은 이번 변경이 일으킨 회귀다.** `W6PreparationContextIntegrationTest` 1건과 `W6OrderConfirmedPublicationIntegrationTest` 8건이 컨텍스트 기동에 실패했다. 원인은 `Not a managed type: class com.loopers.domain.coupon.CouponCampaignModel`이다. 이 W6 보존 컨텍스트는 [발행 W7.4-1](../features/order-events/publication.md)에 따라 `53f912b`의 관리 타입 15개만 허용하고 W7 bean을 이름으로 제거한다. 새 쿠폰 JPA repository(제품 코드)와 test `CouponFixture`가 스캔돼 관리되지 않는 Entity를 요구했다.
- 수정은 W7.4-1의 승인된 테스트 구성 계약을 바꾸는 일이므로 사용자 결정 전까지 보류했다. 회귀는 **미통과**이며 Q16은 충족되지 않았다. 별도 명시 작업인 W6 복구 O11(`w6OutboxRecoveryTest`)도 같은 initializer를 쓰지만 이번에는 실행하지 않았다.

## 구현 1단계 후속 수정 — W6/W7 재현 컨텍스트의 쿠폰 격리 (2026-11-11)

위 회귀 9건 실패는 당시 실행 기록으로 보존한다. Codex 검토 뒤 사용자가 아래 수정을 승인했다.

### 수정

- **W6 준비 컨텍스트:** test `CouponBeanIsolation`이 쿠폰 bean 정의 6개(`couponCampaignJpaRepository`·`couponCampaignRepositoryImpl`·`couponJpaRepository`·`couponRepositoryImpl`·`couponQueryRepositoryImpl`·`couponFixture`)의 정확한 이름 목록과 제거를 제공한다. `W6PreparationContextInitializer`의 Replacer가 W7 제거 뒤 이 목록을 제거하며, 이름이 하나라도 없으면 기동을 거절한다. 관리 타입 15개·Entity 12개·12개 테이블 기대값은 그대로이고, 조립 단언에 쿠폰 bean 부재를 더했다.
- **O11 W7 기동:** `bootW7Api`에만 test `W7CouponIsolationInitializer`를 붙였다. 관리 타입을 W6 15개+`OrderConfirmedOutboxModel`로 제한하고 CCPP 뒤 BDRPP로 같은 쿠폰 정의만 제거한다. W7 제품 recorder·Outbox repository·relay·운영·전송은 그대로다. `assertW7Assembly`에 관리 타입 일치와 쿠폰 bean 부재를 더했다. 근거·경계는 [발행 W7.4-1·W7.4-2](../features/order-events/publication.md)에 반영했다.
- **동시성 테스트 종료 처리:** test `WorkerTermination`이 executor 생성 즉시 미확인으로 세고, `awaitTermination`이 true일 때만 확인으로 친다. 시간 초과·대기 중 interrupt는 미확인을 영구히 남기고(interrupt 상태 복원), 뒤의 다른 종료 확인이 지우지 않는다. `shutdownNow`는 중단 요청으로만 다룬다. `CouponIssueTransactionIntegrationTest`의 @AfterEach는 이 상태가 안전할 때만 DB를 정리한다.
- **주석:** 설계 절 번호·코드로 드러나는 설명을 줄이고, UPDATE 0건의 의미·stale 방지·LEFT JOIN 손상 감지·save 반환 객체 이유는 남겼다. `CouponFixture`는 기본 전파라 상위 TX에 참여할 수 있으므로 상위 TX 없이 호출해야 준비 commit이 보장된다고 바로잡았다.

일반 테스트·제품 컨텍스트에는 위 초기화기를 붙이지 않는다. 일반 컨텍스트에서 쿠폰 repository·Entity를 사용하는 통합 테스트를 포함해 쿠폰·분류기 관련 34건이 통과했다.

### 실행

- 대상 실행: 2026-11-11 14:17:09~14:17:31 KST, `BUILD SUCCESSFUL`. `W6PreparationContextIntegrationTest` 1, `W6OrderConfirmedPublicationIntegrationTest` 8, 쿠폰 32, 분류기 2, `WorkerTerminationTest` 3 — 46건 실행·실패 0·skip 0. W6 준비 관리 타입 15개·테이블 12개(Outbox·쿠폰 없음)를 출력으로 확인했다.

```sh
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:test --tests '*W6PreparationContextIntegrationTest' \
  --tests '*W6OrderConfirmedPublicationIntegrationTest' --tests '*Coupon*' --tests '*ConstraintViolationClassifierTest' \
  --tests '*WorkerTerminationTest' --rerun --no-build-cache
```

- O11: `./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:w6OutboxRecoveryTest`, 2026-11-11 14:17:40~14:18:08 KST, `BUILD SUCCESSFUL`. 작업이 실제 실행됐고(up-to-date 아님) 보고서 `apps/commerce-api/build/test-results/w6OutboxRecoveryTest/`에서 1건 실행·실패 0·skip 0. 새 worker의 자기 MySQL에서 시작 테이블 0개, W6 준비 뒤 API 12+streamer 2, W7 update 뒤 15개(기존 14 + `order_confirmed_outbox`)로 쿠폰 테이블이 없었다. PG 호출은 생성 2건 외 조회·callback 0이었고 실제 streamer 자식은 graceful 종료됐다. 기존 기대값(Outbox만 추가·14개 DDL/행 보존·멱등 복구)은 바꾸지 않았다.
- 격리 없이 O11을 실행하면 쿠폰 테이블이 생겨 실패한다는 판단은 코드 경로에 근거한 예상이며, 격리 없이 실행해 관찰하지는 않았다.
- 전체 일반 회귀: `./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest :apps:commerce-api:test --rerun --no-build-cache`, 2026-11-11 14:18:35~14:26:08 KST, `BUILD SUCCESSFUL`. 보고서 243개, 1066건 실행·실패 0·오류 0·skip 0. Checkstyle main/test 오류 0, `ArchitectureTest` 3건 통과. 이전 9건 실패 클래스도 통과했다. O11은 일반 test에서 제외되는 별도 작업이라 위 O11 실행으로 따로 확인했고 `check`로 중복 실행하지 않았다.

### 1단계 충족 범위

- Q16: 관련 기존 회귀·Checkstyle·ArchUnit·O11이 최종 코드에서 통과했고, 직접 DB 경쟁 worker는 종료 확인 뒤에만 테스트 DB를 정리한다. 1단계 범위의 Q16은 충족이며 후속 단계의 회귀는 각 단계에서 다시 확인한다.
- Q1~Q5·Q13·Q15는 위 표의 DB 부분 근거만 확보했다. HTTP·Redis·Facade·응답 형식·TX 대역 분기와 Q6~Q12·Q14는 후속 단계다.
