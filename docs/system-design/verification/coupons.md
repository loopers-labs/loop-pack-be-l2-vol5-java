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

## 구현 2단계 — Redis 수용 계층 (2026-11-11)

### 범위

- domain `CouponAdmissionRepository`(`tryAcquire(token, capacity)` → `Acquisition.ACQUIRED/FULL`, `release(token)` → 제거 수). Redis 오류·timeout·null·1/0 이외 응답은 결과값이 아니라 예외로 전달한다.
- infrastructure `CouponAdmissionRedisRepository`: 기존 `redisTemplateMaster`(`RedisTemplate<String, String>`)를 qualifier로 주입한다. 키는 `commerce:coupon-admission:v1:permits` 하나이며 TTL을 두지 않는다. 획득은 `src/main/resources/redis/coupon-admission-acquire.lua`, 반환은 `SREM key token`이다.
- Lua: 인자 수·빈 token·양의 정수 C를 검증하고, SISMEMBER → SCARD → SADD를 한 스크립트로 실행한다. 같은 token이면 1, `SCARD >= C`이면 0, 그 외 SADD 후 1이다. 입력·키 타입 오류는 첫 쓰기 전에 오류 응답이 된다.
- application `CouponAdmissionService`: 공유 `AtomicBoolean accepting`(초기 false) 하나.
  - `acquire`는 닫혀 있으면 저장소를 호출하지 않고 503을 반환한다. FULL은 429이며 수용을 닫지 않는다. 예외와 비정상 결과는 닫고 503이다.
  - `release`(정상 실행의 첫 반환)는 예외를 던지지 않는다. 결과가 1이 아니거나 예외가 나면 로그를 남기고 닫는다.
  - `status`·`close`·`open`을 제공한다.
  - `cleanup(tokens)`는 열린 상태면 409를 반환하고, 입력 오류는 400 `INVALID_REQUEST`다. 지정 token을 차례로 SREM하고 제거 수를 합산한다. 도중 실패하면 닫고 503이며, 앞서 제거한 token은 되돌리지 않는다.
  - 오류 로그에는 단계·token·확인된 결과(불명이면 불명)를 남긴다.
- `ErrorType`: `COUPON_ADMISSION_FULL`(429), `COUPON_ADMISSION_UNAVAILABLE`(503), `COUPON_ADMISSION_OPERATION_REJECTED`(409).
- 설정: `CouponAdmissionProperties`는 `commerce.coupon.admission.capacity`를 받는 record다(`@DefaultValue("3")`, 1 미만이면 기동 오류). `application.yml`에 공통 값 3을 명시했다. 기존 `@ConfigurationPropertiesScan`으로 등록된다.
- 테스트 격리: test `CouponBeanIsolation` 목록에 `couponAdmissionRedisRepository`·`couponAdmissionService`를 더했다. 이 두 bean은 기동을 깨지 않지만 쿠폰 동작을 가진 bean이라 W6/O11 컨텍스트의 "쿠폰 bean 없음" 단언을 유지하기 위해 넣었다. 동작 없는 설정 바인딩은 제외 대상이 아니다. [발행 W7.4-1](../features/order-events/publication.md)의 목록 설명을 함께 고쳤다.
- 제외: Facade·Controller·DTO·main 준비 코드·TTL·자동 재시도·우회·전체 실행 추적·Q9/Q10 로컬 Redis 장애 실험.

### 실행 조건

- JDK 21. 기존과 같이 명령에 `-Dorg.gradle.java.home="$JAVA_HOME"`을 붙였고, 전역 Gradle 설정은 바꾸지 않았다. Docker 29.7.2.
- **실제 Redis endpoint:** 수용 통합 테스트는 실제 master template으로 `localhost:6379`, database 0, `redis_version` 7.0.15, `role:master`에 연결됐다. 이 주소는 Docker Compose 서비스 `redis-master`(image `redis:7.0`)의 기존 로컬 Redis다. 같은 JVM에서 `RedisTestContainersConfig`가 띄운 테스트 컨테이너는 이 컨텍스트에서 사용되지 않았다.
  - 추정 원인: 이 fixture는 생성자에서 System property로 주소를 덮어쓰는데, `RedisProperties`가 그보다 먼저 바인딩되는 bean 생성 순서로 보인다. 실행으로 원인을 확인하지는 않았다.
  - 상세설계 §7의 "테스트 fixture는 실제 생성한 컨테이너 주소로 덮어쓴다"는 문장과 다른 관찰이다. 기존 fixture라 이번 범위에서 수정하지 않았다.
- 공유 로컬 Redis 보호:
  - 각 테스트 시작 시 전용 키가 비어 있는지(`TYPE none`) 확인하고, 비어 있지 않으면 지우지 않고 실패하게 했다.
  - 테스트가 만든 UUID token만 SREM했다. worker 종료를 확인하지 못하면 정리를 보류한다(`WorkerTermination`).
  - 잘못된 키 타입 검증은 비어 있던 전용 키에 자기 marker 문자열을 쓰고, 값이 같을 때만 DEL했다.
  - FLUSHALL·SCRIPT FLUSH·다른 키 삭제·pause는 하지 않았다.
  - 실행 뒤 전용 키 `TYPE none`, `DBSIZE 0`, run_id `79b929f4…`를 확인했다. 수용 Lua 한 개가 서버 스크립트 캐시에 남는다.
  - 테스트 중 8080 포트에 실행 중인 앱은 없었다.

### 실제 관찰

| 확인 | 결과 |
|---|---|
| 실제 Lua 실행 | 획득 뒤 스크립트 파일 SHA1(`a8c14a3e…`)에 대한 `SCRIPT EXISTS`가 true이고, Set에 해당 token이 생겼다. 반환은 실제 SREM 결과 1/0으로 확인했다. |
| C=3 | 서로 다른 token 3개가 ACQUIRED, 4번째는 FULL이었다. 하나를 반환한 뒤 4번째가 ACQUIRED였다. |
| 동시 경쟁 | 16개 token을 시작 신호로 동시 실행하는 회차를 5번 반복했다. 매 회차 ACQUIRED 3, FULL 13이었고, Set 구성원은 ACQUIRED token과 정확히 같았다. 회차마다 반환 후 키가 비었다. |
| 같은 token | 재획득은 ACQUIRED이며 SCARD가 늘지 않았다. 가득 찬 상태에서도 자기 token은 ACQUIRED였다. |
| 반환 반복 안전성 | 같은 token의 두 번째 반환과 없는 token 반환은 0이었고, 다른 활성 token 두 개는 남았다. |
| TTL·시간 경과 | 키 TTL이 -1이었다. 2초 뒤에도 세 token이 유지됐고 새 token은 FULL이었다. |
| Lua 입력 오류 | 빈 token, C=0, C=-1은 오류 응답이었고 키는 생기지 않았다. |
| 잘못된 키 타입 | 실제 WRONGTYPE 오류로 서비스는 503을 반환하고 닫혔다. 키는 문자열 marker 그대로였다. |
| 서비스(실제 Redis) | 기본 닫힘 상태에서는 Redis에 쓰지 않았다. 열린 상태에서 4번째 요청은 429였고 열림이 유지됐다. 반환 뒤 재획득이 됐고, 정상 첫 반환 1은 수용을 닫지 않았다. 외부에서 token을 제거한 뒤의 첫 반환 0은 닫았다. 닫힌 상태의 cleanup은 지정 token만 지웠고 반복 시 0이었으며 수용을 다시 열지 않았다. 열린 상태의 cleanup은 409였고 Set은 그대로였다. |
| 서비스 분기(대역) | timeout(`QueryTimeoutException`), Redis 오류, 비정상 응답 예외, null 결과로 획득하면 503이고 닫히며 로그를 남겼다. 닫힌 뒤에는 저장소를 호출하지 않았다. 반환 0·예외는 예외 없이 로그를 남기고 닫았으며, 뒤늦은 반환 1은 수용을 다시 열지 않았다. cleanup은 열린 상태 거절이 입력 검증보다 먼저였다. null·빈 목록·null/빈/공백 token은 400이었다. 도중 실패하면 503이고 닫혔으며 뒤 token은 시도하지 않았다. |
| 설정 | 생략하면 3, 1과 5는 그대로 바인딩됐다. 0, -1, 3.5, abc, 빈 값, 2147483648은 기동에 실패했다. 앱 YAML의 실제 값은 3이었다. |

timeout과 불명 분기는 저장소 대역의 예외로 확인했다. 실제 Redis에서 관찰한 오류 경로는 WRONGTYPE과 Lua 입력 오류뿐이다. 실제 Redis 지연·timeout과 pause는 Q9·Q10 단계에서 다룬다.

### 실행

- 대상: `./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:test --tests '*CouponAdmission*' --rerun --no-build-cache`, 2026-11-11 14:43:09~14:43:27 KST. 서비스 단위 25, 설정 9, 실제 Redis 통합 14 — 48건 실행, 실패 0, skip 0.
- O11: `./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:w6OutboxRecoveryTest --rerun --no-build-cache`, 14:44:10~14:44:38 KST, `BUILD SUCCESSFUL`. 작업이 실제 실행됐고 보고서 `apps/commerce-api/build/test-results/w6OutboxRecoveryTest/`에서 1건 실행, 실패 0, skip 0이었다. 격리 목록이 바뀌어 다시 실행했다.
- 전체 일반 회귀 + Checkstyle: `./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest :apps:commerce-api:test --rerun --no-build-cache`, 14:44:48~14:52:29 KST.
  - test 작업은 보고서 249개, 1114건 실행·실패 0·오류 0·skip 0이었다. `W6PreparationContextIntegrationTest` 1건과 `ArchitectureTest` 3건이 포함된다.
  - **빌드 결과는 `BUILD FAILED`였다.** 원인은 `checkstyleMain`의 미사용 import 1건(`CouponAdmissionRedisRepository`의 `DefaultRedisScript`)이며, checkstyleTest 오류는 0이었다.
  - import 한 줄을 지운 뒤 `checkstyleMain`·`checkstyleTest`와 `--tests '*CouponAdmission*' --tests '*ArchitectureTest'`를 `--rerun --no-build-cache`로 다시 실행했다(14:52:46~14:53:04 KST). Checkstyle main/test 오류 0, 51건 실행·실패 0·skip 0이었다.
  - import 삭제는 바이트코드를 바꾸지 않으므로 1114건 전체 회귀는 다시 돌리지 않았다.

### 설계 Q 대응 — 부분 근거

| Q | 이번 근거 | 남은 검증 |
|---|---|---|
| Q7 | 실제 Redis에서 C=3 유지, 4번째 FULL, 반환 후 재획득, 동시 경쟁에서 한도 미초과를 확인했다. 수용 계층만의 부분 근거다. | 실제 발급 HTTP 요청, DB TX 전 barrier, 429의 DB 미진입, 쿠폰·발급 수, 최종 token 0 — Facade/API 단계 |
| Q8 | 반복 반환·없는 token 반환·cleanup의 0이 정상이고 다른 token이 그대로임을 확인했다. 정상 첫 반환 0은 닫힘과 로그로 처리했다(실제 Redis와 대역 모두). | 업무 결과 유지, 사용자·캠페인과의 로그 연결 — Facade 단계 |
| Q12 | TTL -1, 2초 경과 뒤에도 점유가 유지되고 새 token은 FULL이었다. | HTTP 경로의 시간 경과 확인은 후속 단계다. 이번 2초는 짧은 관찰이다. |
| Q14 | 기본 닫힘, 닫힌 뒤 Redis 미호출, 열린 상태 cleanup 409, 반복 cleanup 안전성, 입력 검증 400, Redis 실패 후 닫힘 유지를 서비스 수준에서 확인했다. | 제어 API HTTP 경로, JSON 매핑 오류와의 구분, 진행 중 요청의 완료 확인 — API 단계 |
| Q15 | C 생략 시 3, 잘못된 값의 기동 실패, 앱 YAML 값 3을 확인했다. | 준비 옵션 관련 항목 — 준비 코드 단계 |
| Q16 | 전체 일반 회귀 1114건, O11, ArchUnit이 통과했고, 수정 뒤 Checkstyle 오류 0이다. 수용 테스트는 worker 종료를 확인한 뒤 자기 token만 정리했다. | 이후 단계마다 다시 확인 |

Q9·Q10(로컬 Redis pause/unpause, 실제 앱 장애)은 이번에 수행하지 않았다. Q1~Q6·Q11·Q13은 이번 단계에서 새로 확인한 것이 없다.

### 한계

- 실제 Redis는 공유 로컬 master였다. 테스트 컨테이너 격리를 전제로 한 검증이 아니며, 같은 키를 쓰는 다른 프로세스가 없다는 것은 실행 시점의 관찰(8080 미사용, 시작 시 키 비어 있음)에 근거한다.
- 서비스 로그에는 token·단계·결과만 있다. userId·campaignId·DB 결과와의 연결은 Facade 단계에서 추가하고 검증한다.
- 동시 경쟁은 단일 JVM의 16개 스레드, 5회차다. C=3 이외의 용량이나 성능에 대한 주장이 아니다.

## 구현 2단계 후속 — 수용 테스트의 Redis 연결 고정 (2026-11-11)

위 2단계 실행은 공유 로컬 `redis-master`에서 실제로 관찰한 결과로 보존한다. 테스트 컨테이너 격리 검증으로는 취급하지 않는다. Codex 검토에 따라 아래와 같이 보완했다.

### 원인 확인

- 임시 관찰 테스트(실행 뒤 삭제)를 실제 컨텍스트에 넣고, BeanPostProcessor로 bean 초기화 순서와 그 시점의 System property를 기록했다.
- 관찰 결과, `RedisProperties`가 `localhost:6379`로 바인딩되고 `redisConfig`, `masterRedisConnectionFactory`, `masterRedisTemplate`, `couponAdmissionRedisRepository`가 먼저 만들어졌다. 그때 `datasource.redis.master.port` System property는 비어 있었다.
- 그 뒤에 `redisTestContainersConfig` 생성자가 실행되어 컨테이너 포트(관찰 시 57682)를 System property에 넣었지만, 이미 바인딩된 값은 바뀌지 않았다.
- 컨테이너 자체는 static 초기화로 시작되지만 주소 덮어쓰기는 생성자에서 일어난다. 그래서 바인딩보다 늦은 경우 일반 컨텍스트는 test 프로필 값 localhost:6379를 쓴다. 비교하면 `MySqlTestContainersConfig`는 static 블록에서 System property를 설정한다.
- 일반 컨텍스트에서 같은 순서가 늘 재현되는지는 이 한 번의 관찰로만 확인했다. 다른 Redis 사용 테스트의 연결 대상은 이번에 바꾸지 않았다.

### 수정

- `modules/redis` testFixtures의 `RedisTestContainersConfig`에 읽기용 static 메서드를 추가했다: `host()`, `port()`, `containerId()`, 그리고 컨테이너 안 `redis-cli INFO server`로 읽는 `serverRunId()`. 컨테이너 수명과 기존 생성자의 덮어쓰기 동작은 바꾸지 않았다.
- `CouponAdmissionRedisIntegrationTest`:
  - `@DynamicPropertySource`로 같은 fixture 컨테이너의 host·port를 master·replica 주소로 넣고, database는 0으로 넣는다. 새 컨테이너나 template은 만들지 않는다.
  - 매 테스트의 첫 Redis 명령 전에 연결 대상을 확인한다.
    - 바인딩된 master·replica 주소와 database가 컨테이너와 일치하는지
    - master template의 팩토리가 `ReadFrom.MASTER`이고 database가 0인지
    - template 연결로 받은 `run_id`가 컨테이너 안에서 직접 읽은 `run_id`와 같은지
    - `role`이 master인지
  - 정적 master/replica 팩토리는 노드 목록 getter가 없다. 그래서 주소는 바인딩 값으로, 실제 연결 서버는 `run_id`로 대조한다.
  - 하나라도 다르면 Redis에 쓰지 않고 준비 실패(예외)로 끝난다. 로컬 대상으로 대체 연결하거나 skip하지 않는다.
  - 대상 확인 전에 끝난 경우 `@AfterEach`는 어떤 Redis 명령도 보내지 않는다.
- 주석과 표시 이름의 "실제 Redis(테스트 컨테이너)"를 실제 근거에 맞게 "fixture 컨테이너"로 고쳤다. 기존 검증 항목과 기대값은 바꾸지 않았다.

### 실행

- 명령:

```sh
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest :apps:commerce-api:test \
  --tests '*CouponAdmission*' --tests '*ProductCache*' --tests '*ProductFacadeIntegrationTest' --tests '*BrandFacadeIntegrationTest' \
  --tests '*ProductV1ApiE2ETest' --tests '*LikeV1ApiE2ETest' --tests '*CustomerPurchaseFlowE2ETest' \
  --tests '*AfterCommitRunnerIntegrationTest' --tests '*ArchitectureTest' --rerun --no-build-cache
```

- 결과: 2026-11-11 15:04:13~15:05:27 KST, `BUILD SUCCESSFUL`.
  - 보고서 42개, 142건 실행·실패 0·오류 0·skip 0.
  - 이 중 쿠폰 수용 테스트는 서비스 25, 설정 9, fixture Redis 14건이다.
  - `ArchitectureTest` 3건 통과, Checkstyle main/test 오류 0.
- 연결 근거(테스트 출력):
  - `master=localhost:57778`, database 0
  - 컨테이너 `24a8405421188473…`
  - `containerRunId`와 `connectedRunId`가 모두 `917757b13e0068bde867b2d6405c0cb2dbdba824`로 같았다
  - `redis_version` 8.10.1, `role` master
  - 같은 시각 공유 로컬 `redis-master`의 run_id는 `79b929f4…`로 이와 다르다.
- 공유 로컬 Redis 상태: 실행 뒤 run_id가 그대로였고, 전용 키는 none, DBSIZE 0이었다. 정리·중단·FLUSHALL·SCRIPT FLUSH는 하지 않았다.
- 재실행 범위의 근거:
  - fixture 변경은 읽기용 static 메서드 추가뿐이다. 다른 테스트의 컨텍스트 구성과 연결 대상은 바뀌지 않는다.
  - 그래서 Redis를 쓰는 관련 테스트(상품 캐시·상품/브랜드 Facade·상품/좋아요/구매 E2E·AfterCommit)와 Checkstyle·ArchUnit만 다시 실행했다.
  - 전체 일반 회귀는 2단계 실행(1114건, 실패 0)과 비교해 제품 코드 변화가 미사용 import 삭제뿐이라 다시 돌리지 않았다.
  - O11은 이 fixture 메서드나 쿠폰 테스트를 사용하지 않아 다시 돌리지 않았다.
- Checkstyle은 commerce-api에만 적용된다. `modules/redis` testFixtures의 변경은 Checkstyle 대상이 아니며, 컴파일과 위 실행으로만 확인했다.

### 2단계 충족 범위 재판단

- 수용 계층의 Q7·Q8·Q12 부분 근거는 이제 테스트 소유 컨테이너에서 확인됐다. 공유 로컬 Redis 결과에 의존하지 않는다.
- Q16(2단계):
  - 관련 Redis 회귀·Checkstyle·ArchUnit은 이번 실행으로 통과했다. 쿠폰 수용 테스트는 worker 종료 확인 뒤 자기 token만 정리했다.
  - 전체 일반 회귀는 앞 실행의 1114건 통과를 근거로 한다. 그 보고서는 이후 필터 실행에 덮어써져 원본 XML이 남아 있지 않다. 집계와 시각은 위 2단계 절의 기록이다.
  - 따라서 2단계 Q16은 "관련 범위 충족, 전체 회귀 원본 보고서 미보존"으로 판단한다.
- 남은 점:
  - 다른 일반 Redis 사용 테스트도 같은 순서로 공유 로컬 6379에 연결될 수 있다. 공통 fixture의 동작을 고치면 모든 테스트에 전역 영향이 있어 이번 범위에서 다루지 않았다.
  - 상세설계 §7의 "테스트 fixture는 실제 생성한 컨테이너 주소로 덮어쓴다" 문장도 일반 컨텍스트에서는 관찰과 다르다.
- 앞으로 실행별 요약·보고서는 로컬 `docs/week8/verification/runs/<날짜-단위>/`에 복사해 보존한다.
