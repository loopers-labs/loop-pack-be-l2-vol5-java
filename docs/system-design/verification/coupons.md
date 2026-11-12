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

## 구현 3단계 — Facade·고객/제어 API (2026-11-11)

### 범위

- application `CouponFacade`:
  - 상위 `@Transactional` 없이 생성자에서 받은 `PlatformTransactionManager`로 읽기(readOnly)·쓰기 `TransactionTemplate`을 한 번씩 만든다. 요청마다 설정을 바꾸지 않는다.
  - 순서: 사전 읽기 TX → 실행별 UUID token으로 Redis 획득 → 쓰기 TX → 필요한 후속 읽기 TX → 종료 확인 뒤 token 반환.
  - 쓰기 TX: `reserveOne`(첫 업무 SQL) → scalar `findIssueData` → `save`의 반환 객체에서 값을 복사한다.
  - 각 TX 호출은 콜백 첫 부분에서 진입을 표시하고, 업무 SQL 전에 afterCompletion을 등록한다. 이 관찰로 미시작·commit·rollback·종료 불명을 판정하며, 예외 종류보다 이 판정을 먼저 적용한다.
  - 판정 관찰은 private 중첩 클래스 하나에 둔다. 공유 Map·진행 수·불명 플래그·잠금은 없다.
- 후속 읽기:
  - UPDATE 0건이면 새 읽기에서 기존 쿠폰 → 캠페인 없음 404 → 비활성 409 → 품절 409 → 설명 불가 500 순으로 판별한다.
  - 정확한 `uk_coupon_campaign_user` 충돌만 기존 결과 조회로 연결한다.
  - 다른 제약·CHECK·기술 오류는 원래 오류로 끝낸다.
- 종료 불명이면 token을 유지하고 수용을 닫으며, 로그를 남기고 500을 반환한다. 그 밖에는 token을 한 번 반환한다.
  - 반환 실패나 정상 첫 반환 0은 확정된 업무 결과를 유지한다. 대신 token·userId·campaignId·결과를 로그로 남기고 수용을 닫는다.
  - 이를 위해 `CouponAdmissionService.release`가 정상 반환 여부(boolean)를 돌려주도록 바꿨다. 동작은 2단계와 같다.
- 수용 불가 503에서는 Facade가 token·userId·campaignId를 경고 로그로 남긴다. 획득 오류의 상세는 수용 서비스 로그에 같은 token으로 남는다.
- interfaces `CouponV1Controller`·`CouponV1ApiSpec`·`CouponV1Dto`:
  - `POST /api/v1/coupon-campaigns/{campaignId}/issues`: 본문 없음. created면 201, 아니면 200이며 다섯 필드를 반환한다.
  - `GET /api/v1/coupons`: 기존 `PageResponse`·`PageCommand`·`ListSort`를 쓰고, 각 항목에 `createdAt`(UTC·마이크로초 6자리·Z 문자열)을 더한다.
- interfaces `CouponAdmissionController`·`CouponAdmissionApiSpec`·`CouponAdmissionDto`: `GET /api/coupon/admission`, `POST .../close·cleanup·open`.
  - `@Profile`·키·로그인이 없고, Facade를 거쳐 같은 수용 서비스를 쓴다.
  - cleanup 본문은 `{"tokens":[...]}`다.
- `ErrorType`: `COUPON_CAMPAIGN_NOT_FOUND`(404), `COUPON_CAMPAIGN_INACTIVE`(409), `COUPON_CAMPAIGN_SOLD_OUT`(409).
- test:
  - TX 관리자 대역 `ScriptedTransactionManager`.
  - 실제 저장소에 위임하는 붙잡기 decorator `HoldingAdmissionRepository`.
  - fixture Redis 고정·대조 도우미 `CouponRedisTarget`(2단계 테스트의 대조 코드를 옮김).
  - `WorkerTermination` public화.
  - `CouponBeanIsolation`에 `couponFacade`·`couponV1Controller`·`couponAdmissionController`를 추가하고, [발행 W7.4-1](../features/order-events/publication.md)의 목록 설명도 고쳤다.
- 제외: main 준비 코드(4단계), Q9/Q10 pause·수동 복구, Q6 실제 응답 유실, Q11 실제 HTTP timeout/연결 종료(5단계).

### 실행 조건

- JDK 21 명령 옵션은 기존과 같다.
- 쿠폰 Redis 통합 테스트 3개 클래스는 `@DynamicPropertySource`로 fixture 컨테이너 주소를 넣는다. 매 테스트의 첫 Redis 명령 전에 바인딩 주소·DB·팩토리 MASTER 읽기·run_id(template 연결 vs 컨테이너 직접 조회)·role을 대조한다.
  - 전체 회귀에서 관찰한 값: `localhost:61375`, 컨테이너 `40217fcb…`, run_id `5e8d775d085cff36c9dc6be66e62af9ece564cfb`가 양쪽에서 같았다. Redis 8.10.1, master.
  - 공유 로컬 `redis-master`의 run_id(`79b929f4…`)는 실행 뒤에도 그대로였고, 전용 키는 none이었다.
- 앱 수용량은 기본 C=3이다. Facade 통합 테스트는 앱 bean과 별개인 수용 서비스 인스턴스를 사례별 C로 조립했다(Q4는 C=8, Q3은 C=2).
- **정리 순서**: 붙잡기 해제 → worker 종료 확인(`WorkerTermination`) → 이 테스트가 Redis에 보낸 token만 SREM → DB truncate. 종료를 확인하지 못하면 정리를 보류한다. HTTP 요청의 서버 측 처리 종료는 decorator가 관찰한 반환 호출(Facade의 마지막 단계)과 응답 수신으로 확인했다.

### 실제 관찰

| 확인 | 결과 |
|---|---|
| Q1·Q2 HTTP | 최초 201과 재요청 200이 같은 다섯 필드·같은 couponId였다. 쿠폰 1·발급 수 1이었고, 재요청은 Redis token을 쓰지 않았다. 비활성·품절·수용 닫힘 뒤에도 기존 쿠폰은 200이었다. 이름은 바뀐 현재 이름, 혜택은 저장된 FIXED_AMOUNT 5000이었다. |
| 업무 거절 | 캠페인 없음 404, 비활성 409, 품절 409였고 쿠폰은 생기지 않았다. token 3개를 판별 뒤 반환했고 수용은 열린 채였다. |
| 식별 | 헤더 없음 400 `INVALID_REQUEST`, 없는 사용자 404 `USER_NOT_FOUND`. 두 경우 모두 Redis 미호출이었다. |
| 우선순위 | 닫힌 상태에서는 없는 캠페인도 503(Redis 미호출)이었다. 자리 3개를 미리 채우면 없는 캠페인도 429였고 수용은 열린 채였다. |
| Q3 | 같은 사용자의 두 실행이 실제 ACQUIRED 뒤·쓰기 TX 전에 함께 붙잡혔다가 놓였다. 잔여량이 있을 때는 reserveOne `[1, 1]`이었고, 유일 제약 충돌 뒤 기존 결과 STORED가 같은 couponId로 돌아왔다. 마지막 한 장에서는 `[1, 0]`이었고 UPDATE 0건 뒤 새 읽기에서 STORED를 받았다. 두 경우 모두 쿠폰 1·발급 수 1·반환 2·키 비어 있음이었다. |
| Q4 | 총량 5·사용자 8·C=8에서 8개 실행이 모두 붙잡힌 상태로 Set 8개·발급 수 0을 확인한 뒤 놓았다. 결과는 발급 5, 품절 409 3, 발급 수 5, 쿠폰 5, 반환 8, 키 none이었다. Redis 거절은 없었다. |
| Q5 | 실제 reserveOne 1건 뒤 저장 단계에 실패를 주입했다. 주입한 예외가 그대로 전파됐고 발급 수 0·쿠폰 0(증가분 rollback)이었다. token은 반환됐고 수용은 열린 채였으며, 다음 요청은 정상 발급(발급 수 1)됐다. 실패는 save 호출 직전 test 저장소가 던진 것이며 실제 INSERT SQL 오류는 아니다. 실제 INSERT 실패 경로는 Q3의 유일 제약 충돌이다. |
| Q7 HTTP | 실제 Redis C=3. 서로 다른 사용자 세 요청이 실제 ACQUIRED 직후·쓰기 TX 전에 붙잡힌 동안 Set은 붙잡힌 token 3개와 같았고 발급 수·쿠폰은 0이었다. 넷째 요청은 429 `COUPON_ADMISSION_FULL`이었고 발급 수 0·그 사용자 쿠폰 0·Set 3이었다. 하나를 놓아 서버 측 반환 1건을 관찰한 뒤 Set은 2였고, 넷째 재요청은 201이었다. 나머지를 놓은 뒤 붙잡혔던 세 요청은 모두 201, 최종 쿠폰 4·발급 수 4·반환 4·키 none·수용 열림이었다. |
| FULL 뒤 | 429 이후 사전 조회 1회 외에 DB 조회·reserveOne은 없었다. 수용은 열린 채였고 반환 호출도 없었다. |
| 목록 Q1·Q15 | 본인 쿠폰 3개만 나왔고 타인 쿠폰은 없었다. latest는 저장 시각 내림차순이고 같은 시각이면 ID 내림차순, oldest는 그 반대였다. `createdAt`은 `2026-11-10T09:30:00.123456Z`로 DB 저장값에 `Z`를 붙인 값과 같았고, 반복 조회에서도 같았다. 이름은 현재 이름, 혜택은 저장값이었다. 빈 목록은 200, size 0과 101은 `INVALID_PAGE_REQUEST`, 정렬 오류는 `INVALID_SORT`였다. |
| 제어 Q14 HTTP | close·open은 반복할 수 있었고, GET이 같은 공유 값을 보였다. 닫힌 뒤 신규 발급은 503이고 Redis와 발급 TX를 호출하지 않았다. 열린 상태 cleanup은 409 `COUPON_ADMISSION_OPERATION_REJECTED`였고 Set은 그대로였다. 닫힌 상태 cleanup은 지정 token만 지웠고(removedCount 1, 반복 시 0, 다른 token 유지) 수용을 다시 열지 않았다. 빈 목록·빈 문자열·null 요소·tokens 없음은 400 `INVALID_REQUEST`, 깨진 JSON·타입 불일치는 400 `Bad Request`였다. |
| TX 대역 Q13·Q11 일부 | 쓰기 콜백에서 afterCompletion 등록이 수락됐다(대역 기록 `[0, 1]`). 분기별 결과는 아래와 같다. |

TX 대역 분기별 결과:

- TX 시작 실패(콜백 미진입): 저장소 미호출, 예외 전파, token 반환, 수용 유지.
- commit UNKNOWN, 통지 없는 commit 실패: 500, token 유지, 수용 닫힘. 로그에 token·userId·campaignId·단계·`result = 불명`이 남았다.
- COMMITTED 뒤 예외: 확보한 결과가 있으면 CREATED를 유지하고 token을 반환했다. 결과가 없으면 500이고 token을 반환했다.
- UPDATE 0건이나 정확한 유일 제약 신호가 있어도 rollback이 UNKNOWN이면 500·token 유지·닫힘이었다. 후속 읽기는 하지 않았다.
- CHECK 위반은 원래 예외 그대로였고 후속 읽기가 없었다.
- 후속 읽기 UNKNOWN: 500·token 유지·닫힘. 후속 읽기 시작 실패: 앞선 rollback 확인을 유지하고 token을 반환했다. 후속 읽기의 업무 거절은 409.
- Q8 대역: 반환 0이나 반환 예외에서도 CREATED는 유지되고 수용이 닫혔으며 로그에 사용자·캠페인·결과가 남았다. 업무 거절 뒤 반환 실패에서도 원래 404를 유지했다.

### 실행

- 대상(개발 중 단계별 실행): TX 대역 16건(시각 미기록), Facade 통합 6건(15:20:26~15:20:40), HTTP 12건(15:22:42~15:23:05). 모두 실패·skip 0이다.
- **전체 회귀 1차** (`checkstyleMain checkstyleTest test --rerun --no-build-cache`), 15:23:16~15:30:57 KST: **BUILD FAILED**.
  - 보고서 257개, 1136건 중 6건 실패, 오류·skip 0. `OrderOutboxRelaySchedulerLifecycleTest` 5건과 `PgPaymentMigrationIntegrationTest` 1건이다.
  - 원인은 모두 컨텍스트 기동 중 MySQL `Too many connections`였다. 쿠폰 통합 테스트 3개 클래스가 각자 별도 컨텍스트를 만들고, 각 컨텍스트가 연결 풀을 유지한 채 캐시되면서 테스트 MySQL의 연결 한도를 넘었다.
  - 원시 보고서: `docs/week8/verification/runs/2026-11-11-stage3/full/`.
- **수정**: 위 세 클래스에 `@DirtiesContext(classMode = AFTER_CLASS)`를 붙여, 클래스가 끝나면 컨텍스트를 닫고 풀을 반납하게 했다. 프로젝트의 기존 사용 예는 `PaymentHttpTestSupport`다. 테스트 기대값은 바꾸지 않았다.
- **전체 회귀 2차** (같은 명령), 15:31:43~15:39:39 KST: `BUILD SUCCESSFUL`.
  - 보고서 257개, 1148건 실행·실패 0·오류 0·skip 0. Checkstyle main/test 오류 0, `ArchitectureTest` 3건 통과.
  - 쿠폰 관련 건수: 서비스 25, 설정 9, 수용 Redis 14, TX 대역 16, Facade 통합 6, HTTP 12, 1단계 쿠폰 테스트들, `W6PreparationContextIntegrationTest` 1.
  - 1차의 1136건과의 차이는 실패한 클래스가 기동 오류로 일부 테스트만 집계된 데서 생겼다.
  - 원시 보고서: `runs/2026-11-11-stage3/full-2/`.
- **O11** (`w6OutboxRecoveryTest --rerun --no-build-cache`), 15:39:50~15:40:18 KST: `BUILD SUCCESSFUL`, 1건 실행·실패/오류/skip 0. 격리 목록이 바뀌어 다시 실행했다. 원시 보고서: `runs/2026-11-11-stage3/o11/`.

### 설계 Q 대응

| Q | 이번 근거 | 남은 검증 |
|---|---|---|
| Q1 | HTTP 식별 오류, 최초 201·쿠폰 1·발급 수 1, 본인 목록의 같은 ID, 타인 비노출 | — |
| Q2 | 201·200 다섯 필드·같은 ID·추가 효과 0, 비활성·품절·닫힘 뒤 기존 결과, 현재 이름·과거 혜택 | — |
| Q3 | 실제 MySQL·Redis에서 두 경로(`[1,1]` 유일 제약, `[1,0]` UPDATE 0) | — |
| Q4 | 총량 5·8명·C=8: 발급 5·품절 3·쿠폰 5·발급 수 5 | — |
| Q5 | 실제 증가 뒤 저장 단계 실패 주입·rollback·token 반환 | 실제 INSERT SQL 오류 주입은 아니다. 실제 INSERT 실패는 Q3 유일 제약 경로로 확인했다. |
| Q6 | 없음. 순차 재요청 200은 Q2 근거일 뿐 응답 유실 검증이 아니다. | 5단계 |
| Q7 | HTTP·실제 Redis C=3·barrier·429 DB 미진입·반환 뒤 재수용·최종 쿠폰 4/발급 수 4/token 0 | — |
| Q8 | 반복·없는 token·cleanup 0(2·3단계), 정상 첫 반환 0과 반환 예외의 결과 유지·닫힘·로그(대역) | 실제 Redis pause 반환 장애는 Q10(5단계) |
| Q11 | 대역의 UNKNOWN·통지 없음 → token 유지·로그·닫힘 | 실제 HTTP timeout·연결 종료(5단계) |
| Q12 | 2단계 수용 계층 관찰 유지 | HTTP 경로 시간 경과(5단계에서 함께) |
| Q13 | Redis 호출 중 TX 없음(구조), 증가+INSERT 동일 TX(Q5 rollback), INSERT 전 재조회 없음(Facade 쓰기 코드 확인. Facade 경로 SQL 캡처는 하지 않았고, 같은 순서의 SQL 관찰은 1단계 test 조합 기록), CHECK 위반 미변환·named unique 식별, 미시작·COMMITTED+예외 대역 | — |
| Q14 | 제어 HTTP, 열린 상태 cleanup 거절, 반복 정리, JSON/입력 오류 구분, 닫힌 신규 요청의 Redis·DB 미진입 | 진행 중 요청의 수동 완료 확인·장애 뒤 재개(5단계). 기본 닫힘은 서비스 단위 테스트로만 확인했다(공유 컨텍스트라 HTTP에서는 확인하지 않음). |
| Q15 | 응답 필드·createdAt 형식·안정 정렬·페이지 오류 | 준비 옵션 관련 항목(4단계) |
| Q16 | 2차 전체 회귀 1148건·Checkstyle·ArchUnit·O11 통과, 자기 자원만 정리 | 단계마다 다시 확인 |

### 한계

- "Redis 호출 중 TX 없음"은 코드 구조(사전 읽기 template 종료 뒤 획득)로 근거를 댄다. 연결 점유를 계측하지는 않았다.
- TX 대역 결과는 실제 드라이버·네트워크의 UNKNOWN 재현이 아니다.
- 로그에 사용자·캠페인·결과가 연결되는 것은 대역 테스트의 출력으로 확인했다. 운영 로그 수집은 다루지 않았다.
- 세 쿠폰 통합 클래스는 클래스마다 컨텍스트를 새로 띄운다. 연결 한도 문제를 피하는 대신 실행 시간이 늘어난다.

## 구현 3단계 후속 — HTTP 테스트 정리 근거와 Q13 Facade 관찰 (2026-11-11)

Codex 검토 뒤 사용자가 요청한 두 보완이다. 제품 코드와 격리 구성은 바꾸지 않았고 test만 수정했다. 위 3단계 절의 최초 회귀 실패와 수정 뒤 통과 기록은 그대로 둔다.

### 보완 1 — HTTP 테스트 실패 경로의 정리

- 문제: `CouponApiE2ETest`의 정리는 클라이언트 worker 종료만 확인했다. 연결 실패 등으로 client future가 끝나도 서버의 발급 TX는 계속될 수 있다. Q7의 반환 관찰은 정상 경로에만 있었다.
- 수정:
  - test `HoldingAdmissionRepository`가 token별 획득 결과(진행 중·ACQUIRED·FULL·예외)를 기록한다.
  - "서버 종료 근거가 없는 token"은 진행 중이거나 예외로 끝난 획득, 그리고 반환이 관찰되지 않은 ACQUIRED다.
  - 새 test `ServerSettlement`가 모든 HTTP 호출을 감싸고, 응답을 받지 못한 요청과 500 응답을 미확인으로 기록한다. 미확인 기록은 지워지지 않는다.
  - `@AfterEach` 순서: 붙잡기 해제 → 클라이언트 worker 종료 확인 → 서버 미확인 token이 사라지길 유한하게(10초) 대기 → 사유가 하나라도 남으면 token 제거·DB 초기화를 하지 않고 사유·보낸 token을 담은 예외로 끝낸다. JUnit은 원래 실패를 유지하고 이 예외를 함께 보고한다.
  - 테스트가 직접 넣은 점유 token은 서버 실행이 아니므로 판단에서 뺀다.
- 근거로 쓰지 않는 것: client future 실패·timeout·cancel, `shutdownNow`, 빈 Set, 500. 획득 예외로 끝난 503도 token이 늦게 추가될 수 있어 미확인으로 남는다. 닫힘 503은 획득 호출이 없으므로 미확인 사유가 생기지 않는다.
- 제품에 실행 Map·카운터·필터·hook은 추가하지 않았다. 관찰은 기존 test decorator와 응답 수신만 쓴다.
- 실패 분기 검증: `ServerSettlementTest` 8건. 실제 DB·Redis 없이 메모리 점유 대역으로 확인했으며, 확인한 분기는 아래와 같다.
  - 반환과 응답이 모두 있으면 정리 허용. FULL만 받은 실행도 허용.
  - 반환이 없으면 유한 대기 뒤 미확인. 대기 중 늦게 반환되면 허용.
  - 획득 예외는 뒤의 다른 실행이 성공해도 미확인으로 남는다.
  - 응답 없음은 원래 예외를 그대로 던지고, 이후 모든 token이 반환돼도 미확인으로 남는다.
  - 500은 미확인. 진행 중 획득은 미확인이었다가 끝나고 반환되면 허용.
  - 테스트 소유 token은 판단에서 제외.
- 한계: 실제 HTTP 연결 실패를 일으켜 E2E의 정리 보류가 실행되는 장면은 재현하지 않았다. 판단 로직은 위 단위 테스트로, 연결은 E2E 12건의 정상 경로(모든 사례에서 판단을 통과해 정리)로 확인했다. 뒤 사례의 차단 방식은 아래 "정리 보류 상태 유지" 절에서 정정했다. 처음 쓴 "빈 키 검사에서 실패한다"는 설명은 틀렸다. 응답을 잃은 요청이 아직 token을 만들지 않았으면 키가 비어 있어 차단되지 않는다.

### 보완 2 — Q13 실제 Facade 실행 근거

- `CouponFacadeIntegrationTest`에 실제 MySQL·fixture Redis로 Facade 정상 발급 한 건을 관찰하는 사례를 더했다. 컨텍스트 속성으로 기존 `SqlCapture`를 켰다.
- 획득·반환 위임 직전 스레드 상태는 `TransactionSynchronizationManager` 기준으로 두 번 모두 `isActualTransactionActive=false`, `isSynchronizationActive=false`, 바인딩 자원 0개였다.
- SQL 구간은 획득 시점과 반환 시점을 경계로 나눴다(소문자 정규화).
  - 사전 조회 1문장: `select … from coupon cm1_0 left join coupon_campaign ccm1_0 on … where cm1_0.user_id=? and cm1_0.campaign_id=?`
  - 쓰기 구간 3문장:
    1. `update coupon_campaign set issued_count = issued_count + 1, updated_at = ? where id = ? and status = 'active' and issued_count < total_quota`
    2. `select ccm1_0.id,ccm1_0.name,ccm1_0.benefit_type,ccm1_0.benefit_value from coupon_campaign ccm1_0 where ccm1_0.id=?`
    3. `insert into coupon (…) values (…)`
- 단언:
  - 조건부 UPDATE가 쓰기 구간의 첫 SQL이고, 이어서 scalar 조회와 INSERT가 온다.
  - INSERT 전에 `user_id=?` 조건의 쿠폰 재조회가 없다.
  - 쓰기 구간의 UPDATE는 조건부 UPDATE 하나뿐이다(캠페인 stale 덮어쓰기·쿠폰 UPDATE 없음).
- 이번 실행에서 `save`의 merge 경로 PK 조회(`where id=?`)는 관찰되지 않았다. 단언은 PK 조회와 금지한 사용자·캠페인 재조회를 구분한다. 즉 PK 조회가 생겨도 실패하지 않고, `user_id=?` 재조회만 실패로 본다.
- 한계: TX 비활성과 바인딩 자원 0은 스레드 상태 관찰이다. 연결 풀 점유량이나 대기 시간은 계측하지 않았고, 연결 풀 성능 검증이 아니다. 정상 발급 한 건의 관찰이며 실패 분기의 SQL은 캡처하지 않았다.

### 실행

- `./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest :apps:commerce-api:test --tests '*ServerSettlementTest' --tests '*CouponFacadeIntegrationTest' --tests '*CouponApiE2ETest' --tests '*ArchitectureTest' --rerun --no-build-cache`
- 2026-11-11 15:52:13~15:52:44 KST, `BUILD SUCCESSFUL`. 30건 실행·실패 0·오류 0·skip 0.
  - 정리 판단 단위 8, Facade 통합 7(기존 6 + Q13 1), HTTP 12, ArchUnit 3.
  - Checkstyle main/test 오류 0.
  - Q3·Q4·Q7 출력은 3단계 기록과 같았다.
- 원시 보고서: 로컬 `docs/week8/verification/runs/2026-11-11-stage3-fix/targeted/`.
- 전체 회귀·O11을 다시 돌리지 않은 근거:
  - 제품 코드·`CouponBeanIsolation`·공통 fixture는 바뀌지 않았다.
  - 바뀐 test 지원 코드(`HoldingAdmissionRepository`·`ServerSettlement`)는 이번에 실행한 세 클래스만 사용한다.
  - `CouponFacadeIntegrationTest`의 컨텍스트 속성 추가는 그 클래스 전용 컨텍스트(`@DirtiesContext(AFTER_CLASS)`)에만 영향을 준다.
  - 직전 전체 회귀(1148건)와 O11 결과는 3단계 절의 기록이다.

### Q 대응 갱신

- Q13: 위 3단계 표의 "Redis 호출 중 TX 없음(구조)"·"INSERT 전 재조회 없음(코드 확인)" 근거를 실제 Facade 실행 관찰로 보강했다. 연결 풀 계측은 여전히 없다.
- Q16: HTTP 테스트도 서버 측 종료 근거 없이는 정리하지 않도록 바꿨다. 실제 연결 실패에서 정리를 보류하는 장면은 단위 테스트 수준의 근거다.


## 구현 3단계 후속 — HTTP 테스트 정리 보류 상태 유지 (2026-11-11)

- **문제**: 이전 수정에서는 `CouponApiE2ETest.setUp`이 키가 비어 있으면 판단 기록을 새로 만들고 수용을 다시 열었다. 응답을 잃은 요청이 아직 사전 DB 조회 중이라 Redis token이 없으면, 앞 사례가 정리를 보류했어도 다음 사례가 그 기록을 지울 수 있었다.
- **수정**(test만, 제품 코드·격리 구성 불변):
  - `ServerSettlement`를 사례마다 새로 만들지 않고 HTTP 테스트 컨텍스트의 bean으로 두었다. 보류 사유 목록을 그 bean에 남긴다.
  - `confirmCleanupOrWithhold`가 정리 직전 판단을 맡는다. 다음 경우 보류 사유를 남기고 예외로 끝나며, 호출자는 token 제거와 DB 초기화를 하지 않는다.
    - 이미 보류된 컨텍스트
    - 클라이언트 worker 종료 미확인
    - 서버 종료 근거 없음
    - 확인 대기 중 interrupt(interrupt 상태는 복원한다)
  - `setUp`은 첫 줄에서 `requireNotWithheld()`를 호출해, 보류된 컨텍스트면 Redis 대상 확인·키 검사·fixture 준비·기록 초기화·수용 열기 전에 실패한다.
  - `@BeforeEach`가 실패해도 실행되는 `@AfterEach` 역시 같은 판단에서 바로 예외로 끝나 정리하지 않는다. `reset()`도 보류 상태에서는 실패해 기록을 지우지 않는다.
- **해제 조건**: 빈 Set이나 같은 컨텍스트의 다른 요청 성공으로는 풀리지 않는다. 새 컨텍스트는 새 bean이라 이전 컨텍스트의 보류와 섞이지 않는다. 이 클래스는 `@DirtiesContext(AFTER_CLASS)`로 클래스가 끝나면 컨텍스트를 닫는다. 보류 사유는 테스트 출력(예외 메시지)으로만 남고 컨텍스트 밖에 저장하지 않는다.
- **검증**: `ServerSettlementTest`에 4건을 더했다(총 12건). 대역만 쓰며 실제 HTTP 요청이나 DB 작업은 만들지 않는다.
  - 응답 없음이고 token도 없는 상태에서 첫 정리를 보류한다. 그 뒤 다른 요청이 정상 반환되고 응답 201을 받아도, 다음 사례의 시작 확인·기록 초기화·정리가 모두 막힌다.
  - worker 종료 미확인이면 보류하고 유지한다.
  - 대기 중 interrupt면 보류하고 interrupt 상태를 복원한다.
  - 근거가 모두 있으면 보류가 생기지 않고, 새 객체(새 컨텍스트)는 이전 보류와 무관하다.
  - 기존 8건(정상 정리·늦은 반환·획득 불명·응답 없음·500·진행 중 획득·테스트 token 제외)은 그대로다.
- **실행**: `./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest :apps:commerce-api:test --tests '*ServerSettlementTest' --tests '*CouponApiE2ETest' --tests '*ArchitectureTest' --rerun --no-build-cache`
  - 2026-11-11 15:56:44~15:57:13 KST, `BUILD SUCCESSFUL`.
  - 27건 실행·실패 0·오류 0·skip 0(정리 판단 12, HTTP 12, ArchUnit 3). Checkstyle main/test 오류 0.
  - 원시 보고서: 로컬 `docs/week8/verification/runs/2026-11-11-stage3-fix2/targeted/`.
- **재실행 범위의 근거**: 제품 코드·`CouponBeanIsolation`·공통 fixture가 그대로이고, 바뀐 test 코드는 위 두 클래스만 사용한다. 그래서 전체 회귀·O11·`CouponFacadeIntegrationTest`(Q13 포함, 이번 변경과 무관)는 다시 돌리지 않았다.
- **한계**: 실제 HTTP 연결 실패로 E2E 컨텍스트가 보류 상태에 들어가는 장면은 재현하지 않았다. 판단과 유지는 대역 단위 테스트로, E2E 연결은 정상 경로 12건으로 확인했다. 보류된 컨텍스트가 닫힌 뒤 남은 서버 작업의 종료는 이 장치의 범위가 아니다.

## 구현 4단계 — 직접 실행용 준비 코드 (2026-11-11)

### 범위

- main `config/CouponLocalFixtureConfig`: 클래스 수준 `@ConditionalOnProperty(name = "commerce.coupon.fixture.enabled", havingValue = "true")`.
  - 옵션을 생략하거나 false로 두면 구성 자체가 등록되지 않는다. 그래서 `CouponBeanIsolation`에 더할 이름이 없다.
- `ApplicationRunner` 하나가 `TransactionTemplate` 한 TX 안에서 다음을 저장한다.
  - 사용자 8명과 각 사용자의 잔액 0 Point(save 반환 ID 사용)
  - ACTIVE 캠페인 `로컬 쿠폰 캠페인`(totalQuota 5, issuedCount 0, FIXED_AMOUNT 5000)
- commit 뒤 캠페인 ID·사용자 ID·요청 예시를 로그 한 줄로 남긴다.
- 하지 않는 것: 쿠폰 생성, Redis 접근, 수용 열기, 이름으로 기존 캠페인 조회·재사용, 기존 데이터 수정.
- 준비 예외는 그대로 전파돼 앱 기동이 실패한다.
- 공통 `ddl-auto`(local/test create)와 프로필 설정은 바꾸지 않았다. test 소스의 fixture는 참조하지 않는다.

### 검증 구성

- `CouponLocalFixtureConfigIntegrationTest`(2건)는 test에서 `SpringApplicationBuilder`로 실제 앱을 여러 번 시작·종료한다(test 프로필, fixture MySQL, fixture Redis 주소 지정).
- `ddl-auto`는 시작 인자로 고른다. 첫 시작만 create로 빈 스키마를 만들고, 이후는 update로 같은 DB를 보존한다.
- 사례가 끝나면 한 번 더 시작해 `DatabaseCleanUp`으로 이 테스트가 남긴 행을 비운다. 같은 JVM의 다른 테스트 컨텍스트와 공유하는 DB이기 때문이다.

### 실제 관찰

| 확인 | 결과 |
|---|---|
| 옵션 생략(create) | Runner·구성 bean 없음, 사용자/Point/캠페인/쿠폰 0/0/0/0 |
| 명시 false(update) | Runner·구성 bean 없음, 0/0/0/0 |
| true(update) 첫 시작 | Runner 있음. 8/8/1/0. 캠페인 `로컬 쿠폰 캠페인`·ACTIVE·총량 5·발급 수 0·FIXED_AMOUNT·5000. Point 8개가 모두 잔액 0이고 user_id는 8명 모두 다름. 수용 닫힘 |
| 같은 시작에서 기존 효과 생성 | fixture Redis 대상 확인 뒤 Facade로 열고 첫 사용자 발급(created), 닫음. 전용 키 비어 있음 |
| 같은 DB 재시작(true, update) | 16/16/2/1. 기존 캠페인 발급 수 1·ACTIVE·5000 유지, 새 캠페인 발급 수 0. 기존 쿠폰 행(id·user·campaign·혜택·created_at)과 기존 사용자 8명 그대로. Point 모두 0. 수용 닫힘. 기존 사용자·캠페인 ID로 발급이 created(발급 수 2) |
| 같은 컨텍스트 Runner 재호출 | 24/24/3/2. 기존 캠페인 발급 수 2 유지. 재시작 근거와 별개인 반복 호출 관찰이다. |
| 준비 TX 중간 실패 | test 소스의 BeanPostProcessor가 캠페인 저장 직전에 실패를 주입했다. 실패 직전 같은 TX 연결에서 본 행은 8/8/0/0(사용자·Point INSERT 진행)이었다. 기동은 주입한 예외로 실패했고, 다음 시작(update)에서 확인한 DB는 0/0/0/0(rollback)이었다. |

### 실행

- 개발 중 1차(16:04:00~16:04:21)는 BUILD FAILED, 2건 실패. 원인은 둘 다 test 코드 오류였다.
  - master template을 bean 이름으로 조회한 오류(qualifier 조회로 수정).
  - 기동 실패 예외를 root cause 단언으로 확인한 오류. 실제로는 주입 예외가 감싸지지 않고 그대로 전달됐다. 원인 사슬에 주입 예외가 포함됐는지로 단언을 바꿨다.
  - 제품 코드는 바꾸지 않았다.
- 2차(16:04:44~16:05:07): 5건 통과(준비 2, ArchUnit 3).
- 정리 단계(`@AfterEach`)를 더한 뒤 전체 회귀를 시작했지만, 사용자 요청("이번 구현에 해당하는 테스트만")으로 도중에 중단했다. 결과가 없으므로 통과로도 실패로도 기록하지 않는다(`runs/2026-11-11-stage4/full/STOPPED.txt`).
- 최종: `./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest :apps:commerce-api:test --tests '*CouponLocalFixtureConfigIntegrationTest' --tests '*ArchitectureTest' --rerun --no-build-cache`
  - 2026-11-11 17:03:52~17:04:17 KST, `BUILD SUCCESSFUL`.
  - 5건 실행·실패 0·오류 0·skip 0. Checkstyle main/test 오류 0. 관찰값은 위 표와 같았다.
  - 원시 보고서: 로컬 `docs/week8/verification/runs/2026-11-11-stage4/targeted-3/`. 개발 중 실행은 `targeted/`·`targeted-2/`.
- 실행하지 않은 것:
  - 전체 일반 회귀(사용자 결정).
  - O11: 별도 DB를 쓰는 작업이고, 옵션이 꺼지면 새 bean이 없어 격리 목록이 바뀌지 않는다.

### Q15 준비 옵션 부분

- 충족: 옵션 기본 off(생략·false에서 미등록·데이터 증가 없음), on에서 8명·5장 캠페인 생성, 보존 DB 재실행 시 새 데이터만 추가하고 기존 효과 불변, 준비 후 수용 닫힘.
- Q15의 나머지(응답·정렬·모델 경계·C 설정)는 앞 단계 기록과 같다.

### 한계

- 전체 회귀를 돌리지 않았다. 이 테스트는 같은 JVM의 다른 테스트와 공유하는 fixture MySQL에서 create로 스키마를 다시 만들고 데이터를 넣었다가 사례 끝에 비운다. 다른 테스트 클래스와의 실행 순서에 따른 상호작용은 확인하지 않았다.
- 실제 로컬 앱(`bootRun`, local 프로필, 로컬 MySQL)에서 준비 옵션을 켜고 시작하는 수동 확인은 하지 않았다. 검증은 test 프로필·fixture DB에서 앱을 직접 시작한 결과다.
- update를 쓴 재시작 검증은 운영 마이그레이션이나 장애 복구를 보장한다는 뜻이 아니다.

## 구현 5단계 — 실제 HTTP·로컬 Redis 장애 검증 (2026-11-11)

제품 코드는 바꾸지 않았다. 결함은 발견되지 않았다. test 소스만 추가·수정했다.

### 변경

- test `HoldingAdmissionRepository`: 반환 명령 위임 직전의 유한 붙잡기(`holdNextReleases`)를 추가했다. 이 지점은 쓰기 TX 종료 확인 뒤·HTTP 응답 전이다.
- test `CouponHttpFaultE2ETest`(Q6·Q11·Q12, 3건):
  - 클라이언트는 test의 raw socket HTTP/1.1이다. timeout과 RST 종료가 실제 TCP 동작이다.
  - 서버 진행 위치는 수용 decorator(반환 직전)와 `ReserveHold`(실제 조건부 UPDATE 직후, 쓰기 TX 안)의 유한 barrier로 통제한다.
  - 정리는 3단계 후속의 `ServerSettlement` 판단(컨텍스트 bean, 보류 유지)을 그대로 쓴다.
- test `com.loopers.verification.localredis.CouponLocalRedisFaultIntegrationTest`(Q9·Q10, 2건):
  - 클래스 수준 `@EnabledIfEnvironmentVariable(COUPON_LOCAL_REDIS_FAULT_TEST=true)`.
  - static nested `Bootstrap`: `@SpringBootConfiguration`·`@EnableAutoConfiguration`·`@ConfigurationPropertiesScan("com.loopers")`·`@ComponentScan("com.loopers")`. 스캔에서 Boot의 TypeExcludeFilter·AutoConfigurationExcludeFilter를 유지하고 `RedisTestContainersConfig`·`CommerceApiApplication`을 제외한다.
  - 로컬 master `localhost:6379`·replica `localhost:6380`·database 0을 `@DynamicPropertySource`로 넣는다.
  - JSONL을 쓰는 획득·반환 decorator가 Redis 명령 전에 append하고 DSYNC로 기록한다. 기록 실패 시 위임하지 않는다.
  - docker CLI로 컨테이너를 식별하고 pause/unpause한다.

### A — 실제 HTTP

| Q | 제어 지점·방법 | 관찰 |
|---|---|---|
| Q6 응답 유실 | 반환 위임 직전(쓰기 TX commit 확인 뒤·응답 쓰기 전)에 서버를 붙잡았다. DB에 쿠폰 1·발급 수 1이 commit된 것을 다른 연결로 확인했다. 그 뒤 클라이언트 소켓을 응답 바이트를 하나도 읽지 않은 채 RST(SO_LINGER 0)로 닫고 서버를 놓았다. | 클라이언트가 받은 응답: 없음(201 상태줄·본문 전체 유실). 서버 측 반환이 관찰돼 Facade 처리가 끝났다. 같은 사용자·캠페인 재요청은 200·같은 couponId였고 발급 수 1·쿠폰 1(추가 효과 0)이었다. 재요청은 수용 자리를 쓰지 않았다(반환 1회). |
| Q11 timeout·연결 종료 | 실제 조건부 UPDATE 뒤 쓰기 TX 안에서 서버를 붙잡았다. 클라이언트 read timeout(500ms, `SocketTimeoutException`) 뒤 RST로 연결을 끊었다. | 끊은 뒤에도 서버 스레드는 barrier에 있었다. 행을 바꾼 열린 InnoDB TX가 1개(`trx_rows_modified > 0`, root로 `information_schema.innodb_trx` 조회)였다. 다른 연결에서 본 발급 수는 0(commit 전)이었다. token은 Set에 남아 있었고 반환 호출은 0이었다(조기 반환 없음). 놓은 뒤 서버 반환이 관찰됐고, 쿠폰 1·발급 수 1·쓰기 TX 0·키 비어 있음·수용 열림이었다. |
| Q12 시간 경과 | C=3 자리를 실제 HTTP 처리 세 건이 ACQUIRED 직후에 쥔 상태로 5초(측정 5000~5004ms) 기다렸다. | 전후 TTL이 -1, Set 구성원은 그대로, 넷째 요청은 429, 발급 수 0이었다. 놓은 뒤 세 건 모두 201, 발급 수 3, 키 비어 있음. |

- Q11 처음 실행의 실패 2회는 test 관찰 코드의 오류였다(제품 결함 아님).
  - 1회: 모든 열린 InnoDB TX를 셌다.
  - 2회: 행을 바꾼 TX만 세도록 좁혔지만, commit 직후 조회에서 여전히 1이 나왔다.
  - 원인은 `innodb_trx`가 캐시라는 점이다. MySQL 8.0 Reference Manual의 "InnoDB INFORMATION_SCHEMA Transaction and Locking Information" 절에 따르면 이 정보는 최대 0.1초 주기로 갱신된다. 진단 실행에서 같은 TX가 114~119ms 뒤 사라졌다.
  - 그래서 commit 뒤 확인을 유한(최대 10초) 재조회로 바꿨다. 기대값은 그대로다.
- 원시 보고서: `runs/2026-11-11-stage5/http-a1`(실패)·`http-a2`(실패)·`http-a3-debug`·`http-a4-debug`·`http-a5`(통과).
- 한계:
  - Q6의 유실 범위는 "클라이언트가 응답을 받지 못함"이다. 서버의 응답 쓰기 실패 여부는 관찰하지 않았다.
  - Q11은 열린 TX를 행 변경 수로 식별했다. 이 TX가 정확히 이 요청의 연결이라는 대조(스레드 ID ↔ 요청)는 출력 기록으로만 남겼다.
  - Q12의 5초는 짧은 관찰이며 장시간 운영 검증이 아니다. TTL 부재는 `TTL = -1`과 코드에 만료 설정이 없다는 점(2단계)에 근거한다.
  - 실제 DB 통신 단절로 UNKNOWN을 만드는 재현은 하지 않았다. UNKNOWN 분기는 3단계 TX 대역 결과다.

### B — 실제 로컬 Redis(Q9·Q10)

- 대상: Docker Compose 프로젝트 `docker`, 서비스 `redis-master`, 컨테이너 `41734fb0f04c…`, image `redis:7.0`, Redis 7.0.15, run_id `79b929f45a37ace4a78ccfd0486141331b1eec9c`, 호스트 포트 6379, database 0, 키 `commerce:coupon-admission:v1:permits`. replica `redis-readonly`(6380)는 pause하지 않았다.
- 각 사례 시작 시 확인한 것:
  - 바인딩 주소·database
  - fixture Redis 구성 bean 부재
  - 컨테이너 유일성·running·not paused·compose 서비스·호스트 포트
  - 앱 master 연결의 `INFO server` run_id와 컨테이너 안 redis-cli run_id의 일치
- 간섭 확인(pause 직전):
  - 호스트 `lsof`로 master 포트에 연결한 프로세스가 이 테스트 JVM PID뿐인지 확인했다.
  - 서버 `CLIENT LIST TYPE NORMAL`의 클라이언트가 앱 연결 주소이거나 관찰용 redis-cli인지 확인했다.
  - 두 실행의 Q9·Q10 모두 외부 클라이언트는 0이었다.
  - 실행 전 서버의 NORMAL 클라이언트는 관찰용 redis-cli 하나였다. test 작업은 클래스 필터로 이 클래스만 실행했다.
- 스캔 격리 확인(임시 관찰 테스트, 실행 뒤 삭제): 일반 `@SpringBootTest` 컨텍스트와 `CommerceApiApplication`을 직접 시작한 컨텍스트 모두에 이 중첩 구성의 bean이 없었다.
- 기본 실행(환경변수 없음)에서는 2건 skip이다. 이는 미실행이다.
- 명시 실행 명령(저장소 루트):

```sh
COUPON_LOCAL_REDIS_FAULT_TEST=true \
COUPON_LOCAL_REDIS_FAULT_LOG="$PWD/docs/week8/verification/coupon-local-redis-fault.jsonl" \
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:test --tests '*CouponLocalRedisFaultIntegrationTest' --rerun --no-build-cache
```

| 실행 | 시각(KST) | 결과 |
|---|---|---|
| 1차 | 17:21:17~17:21:33 | BUILD SUCCESSFUL, 2건 실행·실패/skip 0. 단 기록에 test 코드 결함 2개가 있었다: MySQL 컨테이너 ID 미기록(`docker ps --filter publish`가 임의 호스트 포트를 찾지 못함), 정리 명령의 반환 이벤트에 이전 요청 사용자가 붙음. |
| 2차 | 17:22:20~17:22:37 | 기록 결함을 고친 뒤 BUILD SUCCESSFUL, 2건 실행·실패/skip 0, Checkstyle test 0. JSONL 36→72줄 누적(1차 기록 보존). MySQL 테스트 컨테이너 `37427b8a32b4`(호스트 51417) 기록. |

- 기록 파일(로컬): `docs/week8/verification/coupon-local-redis-fault.jsonl`. 원시 보고서: `runs/2026-11-11-stage5/local-redis-fault-1`, `local-redis-fault-2`.
- 아래 관찰은 2차 실행 기준이다. 1차도 같은 결과였고, 1차의 CLIENT ID는 2536이다.

| Q | 관찰 |
|---|---|
| Q9 | 장애 전 같은 master 경로의 CLIENT ID 2570·run_id를 확인했다. 실제 Lua 발급·정상 반환 뒤 별도 관찰 연결의 `CLIENT LIST ID 2570`에서 마지막 명령이 `srem`이었다. pause 중 신규 사용자 요청은 503 `COUPON_ADMISSION_UNAVAILABLE`이었다(획득 `QueryTimeoutException`, 200ms 뒤). 수용이 닫혔고 발급 수 1 유지·그 사용자 쿠폰 0(DB 발급 미진입)이었다. 기존 사용자 POST는 200·같은 couponId, 목록에 그 쿠폰이 있었다. unpause 뒤 같은 경로의 CLIENT ID 2570→2570, run_id 같음. 이때 Set에 늦게 실행된 획득(EVAL)으로 그 실패 요청의 token이 들어 있었다. 기록된 자기 token만 cleanup(removed 1)해 Set 0이 됐고 수용은 닫힘을 유지했다. |
| Q10 | 반환 위임 직전에 같은 컨테이너를 pause했다. 반환 `QueryTimeoutException`에도 201, 그 사용자 쿠폰 1·발급 수 1, 수용 닫힘이었다. unpause 뒤 같은 경로 CLIENT ID·run_id 일치, Set은 이미 비어 있었다(늦은 SREM이 실행됨). pause 이후 이 앱이 보낸 Redis 명령은 그 token의 SREM 하나뿐(기록)이어서, 자기 token cleanup(removed 0)·Set 0을 확인한 뒤 명시적으로 열었다. 새 요청 201, 종료 뒤 Set 0, 발급 수 2. 테스트 끝에 닫았다. |

- 실패 뒤 사후 수동 정리: 두 실행 모두 정상 정리 근거가 있어 사용하지 않았다. 실행 뒤 `redis-master`는 running·not paused, 전용 키는 비어 있었다.
- 한계:
  - 동일 로컬 앱 하나·알려진 요청·순차 운영 조건의 검증이다. 다중 인스턴스·앱 재시작 복구는 다루지 않았다.
  - 간섭 확인은 실행 시점의 호스트 연결·서버 클라이언트 관찰이다.
  - pause 동안 replica 복제 연결이 끊겼다가 다시 붙는 영향은 관찰하지 않았다.
  - 요청별 사용자·캠페인 기록은 테스트가 순차 요청마다 지정한 값이다.

### C — 관련 테스트

- `./gradlew -Dorg.gradle.java.home="$JAVA_HOME" :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest :apps:commerce-api:test --tests '*CouponHttpFaultE2ETest' --tests '*CouponApiE2ETest' --tests '*CouponFacadeIntegrationTest' --tests '*ServerSettlementTest' --tests '*CouponLocalRedisFaultIntegrationTest' --tests '*ArchitectureTest' --rerun --no-build-cache`
- 2026-11-11 17:22:50~17:23:29 KST, `BUILD SUCCESSFUL`.
  - 39건 중 37건 실행·실패 0·오류 0. skip 2는 환경변수 없는 기본 실행의 로컬 Redis 장애 테스트(미실행)이며, 그 실행 근거는 위 명시 실행이다.
  - Checkstyle main/test 0, ArchUnit 3.
  - 원시 보고서: `runs/2026-11-11-stage5/related/`.
- 범위 근거: 바뀐 test 지원 코드(`HoldingAdmissionRepository`)를 쓰는 클래스, 이번 신규 클래스, ArchUnit만 실행했다(사용자 결정: 이번 구현에 해당하는 테스트만). 로컬 Redis 장애 실행과 다른 Redis 테스트는 겹치지 않게 별도 명령으로 실행했다.
- 실행하지 않음: 전체 일반 회귀·전체 check·O11. 제품 코드·격리 목록·공통 fixture가 바뀌지 않았다. 필요하면 커밋 전 확인 대상으로 별도 판단한다.

### Q1~Q16 최종 대조

| Q | 기존 근거(단계) | 이번 근거 | 미완료·한계 |
|---|---|---|---|
| Q1 | 3단계 HTTP 식별·201·본인 목록·타인 비노출 | Q9의 기존 결과·목록 유지 | — |
| Q2 | 3단계 201/200 다섯 필드·같은 ID·현재 이름/과거 혜택 | Q6 재요청 200 | — |
| Q3 | 3단계 실제 DB 두 경로 | — | — |
| Q4 | 3단계 총량 5·8명 | — | — |
| Q5 | 3단계 증가 뒤 저장 단계 실패 rollback | — | 실제 INSERT SQL 오류 주입은 아님(유일 제약 충돌로 대신 확인) |
| Q6 | 없음 | 실제 HTTP 응답 유실·재요청 200·추가 효과 0 | 서버 응답 쓰기 실패 여부 미관찰 |
| Q7 | 3단계 HTTP·C=3·barrier·429·재수용·token 0 | — | — |
| Q8 | 2단계 반복·없는 token 0, 3단계 대역 반환 0·예외 | Q10 실제 반환 timeout 뒤 201 유지·닫힘·자기 token cleanup 0 정상 | — |
| Q9 | 없음 | 실제 로컬 Redis pause·503·DB 미진입·같은 연결 ID·run_id·자기 token 정리·닫힘 유지 | 단일 앱·순차 조건 |
| Q10 | 없음 | 실제 commit 뒤 반환 장애·201·닫힘·늦은 SREM 조건의 정리·명시 재개·새 요청 수용 | 단일 앱·순차 조건 |
| Q11 | 3단계 TX 대역 UNKNOWN·통지 없음 | 실제 HTTP timeout·RST 뒤 서버 TX 생존·조기 반환 없음·놓은 뒤 commit·반환 | 실제 DB 통신 단절의 UNKNOWN 재현 없음 |
| Q12 | 2단계 수용 계층 TTL -1·2초 | 실제 HTTP 처리 중 5초 TTL -1·429 | 짧은 관찰 |
| Q13 | 1·3단계 SQL·TX·named unique·대역, 3단계 후속 Facade 경로 관찰 | 관련 테스트 재실행에서 같은 관찰 | 연결 풀 점유 미계측 |
| Q14 | 3단계 제어 HTTP·서비스 단위 기본 닫힘 | Q9·Q10에서 닫힘 확인 뒤 신규 Redis/DB 미진입, 수동 확인 뒤 cleanup·open | 기본 닫힘의 HTTP 직접 확인은 공유 컨텍스트라 하지 않음 |
| Q15 | 2·3·4단계 응답·정렬·C·준비 옵션 | — | 실제 bootRun 수동 확인 없음 |
| Q16 | 각 단계 관련 회귀·Checkstyle·ArchUnit·자원 정리 | 관련 37건·명시 장애 2건, 자기 token만 정리·Redis 복구 확인 | 이번 단계 전체 회귀·O11 미실행(사용자 결정). 마지막 전체 회귀는 3단계(1148건) |

모든 Q에 실제 실행 근거가 있다. 다만 위 한계(전체 회귀 미실행, 실제 bootRun 수동 확인 없음, 실제 DB 통신 단절 UNKNOWN 미재현, 단일 앱 조건)가 남아 있어 "전체 완료"로 쓰지 않는다.

## 구현 5단계 후속 — 테스트 실패 처리 보완 (2026-11-11)

Codex 최종 코드 검토에서 지적된 test 실패 처리 3곳만 고쳤다. 제품 코드·계약·기존 기대값은 바꾸지 않았다. 위 5단계 절의 실행 결과(1·2차 명시 실행, Q11 처음 실패 이력)는 그대로 둔다.

### 1. raw HTTP 요청의 정리 판단

- 문제: `CouponHttpFaultE2ETest`의 raw 요청은 정리 판단(`ServerSettlement`)에 등록되지 않았다. 서버가 아직 사전 조회 중이라 token이 없을 때 barrier 대기가 실패하면, 진행 중인 요청을 놓칠 수 있었다.
- 수정:
  - `ServerSettlement.registerRaw`가 소켓 연결 전에 요청을 미확인으로 등록한다.
  - `settleRaw(key, token)`은 그 요청의 token 반환이 수용 decorator에 관찰됐을 때만 해소하고, 근거가 없으면 예외로 미확인을 유지한다.
  - 미해소 등록은 정리 판단의 사유가 돼, 기존 컨텍스트 수명의 보류로 이어진다.
  - `sendIssue`는 연결·전송에 실패하면 만든 소켓을 닫고 예외를 던지되, 등록은 남긴다(요청이 서버에 닿았는지 모르기 때문).
  - Q6·Q11 정상 경로는 "이 요청 하나만 반환 직전에 있다 / 이 요청 하나만 Redis를 썼다"를 단언한 뒤, 관찰된 반환 token으로 명시적으로 해소한다.
- 근거로 쓰지 않는 것: socket 전송 실패, timeout, RST, 일반 `observe`의 응답 없음 기록을 우회하는 처리.
- 검증: `ServerSettlementTest` 2건 추가(총 14건).
  - 등록된 raw 요청은 token이 없어도 정리를 막고, 다른 요청이 끝나도 풀리지 않는다.
  - 반환 관찰 전이나 token 없이 해소하려 하면 거절된다. 반환이 관찰된 뒤에만 해소되고, 그때 정리가 허용된다.

### 2. 로컬 Redis 장애 테스트의 사례 간 보류 유지

- 문제: 사례별 필드 `finishedCleanly`만 있어서, 실패한 사례는 DB 정리만 생략됐다. 다음 사례는 Set이 비어 있으면 준비를 통과해 수용을 다시 열 수 있었다.
- 수정:
  - 컨텍스트 bean인 `RecordingAdmissionRepository`에 보류 사유 목록을 두었다.
  - 정상 종료·정리 근거 없이 끝난 사례는 `@AfterEach`에서 보류를 남긴다(JSONL `context-withheld`).
  - 이후 사례의 `prepare`는 첫 줄에서 막혀, 준비·DB 변경·수용 열기·점유 정리를 하지 못한다.
  - 빈 Set이나 다른 요청 성공으로 풀리지 않는다. Redis 복구(unpause) 시도는 보류 중에도 계속한다.
- 검증: 신규 `CouponLocalRedisFaultGuardTest`(일반 실행, Docker·Redis 없음). token이 만들어지지 않은 실패를 보류한 뒤 다른 요청이 획득·반환해도 다음 사례 시작이 막혔고, 보류가 없으면 시작할 수 있었다.

### 3. pause 결과가 불명일 때의 실제 상태 복구

- 문제: `paused=true`가 docker pause 명령이 정상 반환한 뒤에야 설정됐다. pause가 실제로 적용됐지만 CLI 대기·응답이 실패하면 복구를 건너뛸 수 있었다.
- 수정:
  - `PauseGuard`(테스트 클래스 안 static nested, docker CLI 함수 주입)가 확인된 대상 컨테이너 ID만 다룬다.
  - pause 명령 전에 시도 사실(`pause-attempt`)을 남긴다.
  - 복구는 CLI 결과가 아니라 그 컨테이너의 `docker inspect -f {{.State.Paused}}` 실제 상태로 판단해, true면 unpause하고 아니면 `restore-not-paused`를 남긴다.
  - 상태 조회나 unpause 실패는 기록(`restore-state-unknown`·`unpause-failed`)하고 예외로 던진다. 원래 실패가 있으면 함께 보고된다.
  - 대상이 정해지기 전에는 pause하지 않는다.
- 검증: `CouponLocalRedisFaultGuardTest` 명령 대역 4건. 공유 Redis를 실제로 멈춘 상태로 남기는 실험은 하지 않았다.
  - pause CLI 실패(대기 초과) 뒤에도 실제 상태가 paused면 unpause했고, 모든 명령이 확인된 대상 ID에만 갔다.
  - 실제로 멈춰 있지 않으면 unpause하지 않았다.
  - 조회·unpause 실패는 기록하고 던졌다.
  - 대상 없음·시도 없음이면 명령을 보내지 않았다.

### 실행

| 실행 | 시각(KST) | 결과 |
|---|---|---|
| 대역·HTTP 관련: `checkstyleMain checkstyleTest test --tests '*CouponLocalRedisFaultGuardTest' --tests '*ServerSettlementTest' --tests '*CouponHttpFaultE2ETest' --tests '*ArchitectureTest' --rerun --no-build-cache` | 17:32:36~17:33:01 | BUILD SUCCESSFUL. 26건 실행(가드 6·정리 판단 14·HTTP 장애 3·ArchUnit 3), 실패/오류/skip 0, Checkstyle main/test 0. Q6·Q11·Q12 관찰은 5단계 기록과 같았다(Q11 innodb_trx 재조회 123ms). |
| 로컬 Redis 명시 실행 3차(5단계 명령과 같음) | 17:33:19~17:33:36 | 실행 직전 확인: 호스트 6379 연결 0, 서버 NORMAL 클라이언트(관찰 cli 제외) 0, 전용 키 비어 있음, not paused. BUILD SUCCESSFUL, 2건 실행·실패/skip 0. Q9: CLIENT ID 2723→2723, run_id 같음, 503, 늦은 EVAL token cleanup 1, 닫힘 유지. Q10: 반환 timeout에도 201·닫힘, 늦은 SREM 뒤 cleanup 0, 열기 뒤 새 요청 201. 사례별로 `pause-attempt → pause → unpause`가 기록됐고, 두 사례 모두 간섭 외부 클라이언트 0. JSONL 72→110줄(이전 기록 보존). 실행 뒤 redis-master running·not paused·키 비어 있음. |

- 원시 보고서: 로컬 `docs/week8/verification/runs/2026-11-11-stage5-fix/guards-and-http/`, `local-redis-fault-3/`.
- 실행하지 않음: 전체 일반 회귀·O11(제품 코드·격리 구성 불변, 사용자 결정).
- 한계:
  - 실제 HTTP 연결 실패나 실제 docker CLI 대기 초과로 보류·복구가 일어나는 장면은 재현하지 않았다. 그 분기는 대역 단위 테스트로 확인했다.
  - raw 요청과 token의 연결은 "이 사례에서 그 요청 하나만 Redis를 썼다"는 단언에 근거한다. 동시 raw 요청에는 적용하지 않는다.

## 최종 커밋 전체 검사 (2026-11-11, Codex)

- 사용자 승인으로 최종 제품/테스트 커밋 `4711dfe`에서 전체 검사를 한 번 실행했다. 제품·테스트 코드와 기대값은 수정하지 않았다.
- 명령: `./gradlew -Dorg.gradle.java.home=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home :apps:commerce-api:check --rerun-tasks --no-build-cache --console=plain`. JDK 21.0.12를 사용했다.
- 실행 시각: 2026-11-11 17:44:52 KST ~ 2026-11-11 17:53:38 KST. `BUILD SUCCESSFUL in 8m 46s`, 35개 작업 모두 실제 실행됐다.
- 일반 `test`: XML 262개, 총 1176건 중 **1174건 실행·실패 0·오류 0, skip 2**. skip은 기본 비활성 `CouponLocalRedisFaultIntegrationTest`의 Q9/Q10이며 이번 통과로 세지 않는다. 해당 두 사례의 실제 장애 실행 근거는 앞선 5단계 명시 실행 3차(2건 통과)다. 이번에 pause 실험은 반복하지 않았다.
- 별도 `w6OutboxRecoveryTest`: XML 1개, 1건 실행·실패/오류/skip 0. `check`에 연결된 O11 작업이 실제 실행됐다.
- `ArchitectureTest`: 일반 test 안에서 3건 실행·실패/오류/skip 0. Checkstyle main/test XML 오류 각 0개.
- 실행 전 추적 파일 변경 없음, 공유 Redis DBSIZE 0·관찰 연결 외 NORMAL 클라이언트 없음·호스트 6379 외부 연결 없음 확인. 이전 중단 실행의 test worker PID 56631이 fixture MySQL handshake와 shutdown hook에서 멈춰 있어 thread dump를 보존하고 그 worker만 강제 종료했다. 새 검사와 중첩시키지 않았다.
- 실행 후 새 test worker·streamer 자식이 남지 않았고 이번 테스트 소유 컨테이너가 종료됐다. 기존 공유 Redis는 running·paused=false, 쿠폰 전용 키 none·DBSIZE 0이었다. 공유 Kafka·개발 MySQL 등 기존 서버는 유지했다.
- 원시 로그·XML/HTML·Checkstyle 보고서·집계 JSON·사전 worker 관찰은 로컬 `docs/week8/verification/runs/2026-11-11-final-check/`에 보존했다. 제출 필수 요약은 이 절에 둔다.

### 최종 판단과 남은 범위

- 앞선 기록의 “최종 전체 회귀 미실행” 한계는 이번 실행으로 해소됐다. 과거 실패·미실행 기록은 당시 실행 이력으로 보존한다.
- 로컬 보관 실습 원문·발제 문서·Sam 세션 정리본을 제품 코드와 Q1~Q16 실행 근거에 대조했으며 필수 제품 기능의 누락은 발견하지 못했다. 단계별 승인 구현과 요구 시나리오 검증, 최종 전체 검사를 마쳤다.
- 실제 DB 통신 단절 UNKNOWN 재현·다중 인스턴스/failover·운영 부하/최적 C·수동 bootRun 확인을 수행했다고 주장하지 않는다. 이들은 합의 범위의 검증 한계와 구분한다.
- PR·기술 글·WIL 등 과제의 글 산출물·실제 제출 여부는 이 구현/검증 완료 판단과 별개다. 최종 제출 판단은 사용자가 담당한다.

## 대기실 확장 1단위 — 시작 설정·오류 정의 (2026-11-12)

- 기준: volume-8 / a2fee9f에서 수행한 확장 첫 단위다. 기본 제품 구현 4711dfe의 과거 검사와 구분한다. 작성·검사 실행은 Claude Code, 실제 diff·보존 XML/로그 검토는 Codex가 수행했다. Codex는 제품 검사를 재실행하지 않았다.
- 제품 변경: CouponWaitingProperties(campaignId 생략 허용·명시 시 양수, schedulerDelay=1s·admissionDuration=30s, 두 Duration의 양의 밀리초 표현 검증), CouponRateLimitProperties(limitForPeriod=10·양의 int, refreshPeriod=1s·양의 Duration), 등록 요청률/대기 권한 ErrorType 6개와 기존 OPERATION_REJECTED 메시지 일반화다. 기존 HTTP/오류 코드·capacity 설정·YAML·의존성은 유지했다.
- 검증: ApplicationContextRunner에서 기본값·명시 값·ID/숫자/기간 형식과 범위 거절, Waiting의 밀리초 변환 결과 0·overflow를 확인했다. 실제 limiter 생성/표현 범위 검증은 포함하지 않았다.
- 실행: JDK 21.0.12, 2026-11-12 14:08:28~14:08:44 KST. 아래 명령에서 JAVA_HOME은 `/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home`이었다.

```sh
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  :apps:commerce-api:test \
  --tests '*CouponWaitingPropertiesTest' --tests '*CouponRateLimitPropertiesTest' \
  --tests '*CouponAdmissionPropertiesTest' --tests '*ArchitectureTest' \
  --rerun-tasks --no-build-cache --console=plain
```

- 결과: BUILD SUCCESSFUL, 34 actionable tasks 모두 executed. XML 7개 합계 62건 실행·실패/오류/skip 0. Waiting은 최상위 1·CampaignId 8·SchedulerDelay 13·AdmissionDuration 13, RateLimit 15, 기존 Admission 설정 9, ArchitectureTest 3이다. Checkstyle main/test XML 오류 각 0개다. streamer bootJar·pg-simulator 컴파일은 기존 작업 의존성이며 해당 앱의 실행 검증은 아니다.
- 원시 근거는 로컬 `docs/week8/verification/runs/20261112-unit1-config-errortype/`의 gradle.log·timing.txt·test-results XML·checkstyle XML에 보존했다. Codex가 보고와 실제 파일의 집계·시각·작업 실행 결과를 대조했다.
- 완료 범위는 WQ1의 설정 부분이다. 실제 대기실 비활성/open/close·운영 ID 검사·Service/HTTP 연결·실제 RateLimiter·스케줄러·W6/O11·전체 회귀·장애/부하 실험은 이번에 검증하지 않았다. 새 ErrorType은 아직 사용처가 없고 실제 HTTP 오류 응답 검증은 후속이다.

## 대기실 확장 2단위 — 포트·Lua·Redis 저장소 (2026-11-12)

- 기준: volume-8 / e7c7fc7(확장 1단위 커밋). Claude Code가 구현·실행했고 Codex가 실제 소스와 보존 XML/로그를 읽어 검토했다. Codex는 제품 검사를 재실행하지 않았다.
- 변경: CouponWaitingState/Snapshot, CouponAdmissionRepository의 대기실 5메서드/결과 타입, Redis adapter와 등록·조회·입장 처리·시작·종료 Lua다. 공통 검증 Lua를 각 작업 앞에 합쳐 작업별 한 스크립트로 실행한다. 기존 master String template을 쓰고 기존 token 포트·Service/Facade·HTTP 동작은 이 단계에서 유지했다. 기존 test 대역 5곳은 test 전용 TokenOnlyAdmissionRepository를 구현하도록 최소 변경했다.
- 실제 fixture Redis에서 WQ2~WQ6/WQ10의 저장소 부분(동시 등록 하나·순번·C=3/앞순번 승격·기한 비연장/회수·PROCESSING 미회수·만료/시작 경합·동시 STARTED 하나·finish 대조/자기 자리 반환·품절/배정 중단)을 확인했다. 잘못된 키/기록/색인·정확 정수 범위도 검사했다. HTTP/DB 발급 경로 전체의 충족 근거는 아니다.
- 실행 환경: JDK 21.0.12, fixture Redis 8.10.1 master, CouponRedisTarget의 endpoint/DB/master/run_id 대조. 각 테스트는 자기 캠페인 키만 사용하고 worker 종료 확인 뒤 정리했다. 공유 로컬 Redis pause·FLUSHALL·장애/부하 실험은 하지 않았다.

| 실행(KST) | 검사와 실제 결과 |
|---|---|
| 14:26:33~14:27:26 | Checkstyle main/test 및 관련 클래스 13개 필터. XML 22개, 165건 실행·실패/오류/skip 0, Checkstyle 오류 각 0. BUILD SUCCESSFUL, 34 actionable tasks executed |
| 14:27:51~14:28:14 | 만료/start 경합 신호 시점을 기한 -4~+3ms로 조정한 뒤 Redis 클래스 재실행. 18건 실패/오류/skip 0·Checkstyle test 오류 0, 33 actionable tasks executed. STARTED 2·EXPIRED(회수 전) 1·EXPIRED(회수 후) 5 관찰 |
| 14:35:09~14:35:34 | adapter의 필수 sequence/PROCESSING 기한 검증 누락 보완 뒤 변환 8·실제 Redis 18·ArchUnit 3, 총 29건 실패/오류/skip 0. Checkstyle main/test 오류 각 0, 34 actionable tasks executed. 경합 STARTED 3·EXPIRED(회수 후) 5 관찰 |

- 첫 실행 클래스/건수: CouponWaitingRedisIntegrationTest 18, CouponWaitingReplyConversionTest 4, CouponAdmissionRedisIntegrationTest 14, CouponAdmissionServiceTest 25, CouponFacadeIntegrationTest 7, ServerSettlementTest 14, CouponApiE2ETest 12, CouponHttpFaultE2ETest 3, CouponLocalRedisFaultGuardTest 6, Properties 3클래스 59, ArchitectureTest 3. 마지막 보완 후 나머지 클래스를 재실행한 것으로 기록하지 않는다.
- 세 실행 모두 `-Dorg.gradle.java.home`으로 확인한 JDK 21을 사용하고 `--rerun-tasks --no-build-cache --console=plain`으로 실행했다. 마지막 실행의 작업/필터는 다음과 같다.

```sh
./gradlew -Dorg.gradle.java.home="$JAVA_HOME" \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  :apps:commerce-api:test --tests '*CouponWaitingReplyConversionTest' \
  --tests '*CouponWaitingRedisIntegrationTest' --tests '*ArchitectureTest' \
  --rerun-tasks --no-build-cache --console=plain
```

- 보완: adapter는 모든 snapshot의 sequence, READY/PROCESSING의 필수 expiresAtMillis와 다른 상태에 존재하는 기한의 문자열/양수/2^53-1 범위를 검사한다. 실제 응답 expiresAt은 READY만 채운다. Lua/상태 전이는 그대로며 기존 잘못된 fixture는 다른 필드를 정상화해 원래 오류를 검사하도록 유지했다. Codex 최종 검토에서 추가 필수 수정은 발견하지 못했다.
- 로컬 원시 근거: `docs/week8/verification/runs/20261112-unit2-waiting-redis/`, `20261112-unit2-waiting-redis-race-rerun/`, `20261112-unit2-reply-validation-fix/`의 gradle.log·timing.txt·XML/Checkstyle 사본. 각 경합 분포는 해당 실행 관찰이며 매번 양쪽 결과 출현을 보장하지 않는다.
- 미완료: Service/Facade·수용 닫기·DB 종료와 finish 연결·HTTP/RateLimiter·내부 스케줄러·W6/O11 확장 조립은 후속이다. 전체 회귀/check·장애/부하 실험은 미실행이며 opt-in 로컬 Redis 장애 소스는 컴파일만 확인했다. 기존 기본 실행을 확장 검증으로 대체하지 않는다.

## 대기실 확장 3단위 — Service/Facade와 DB 종료 연결 (2026-11-12)

- 기준: volume-8 / 4639565(2단위 커밋). Claude Code가 구현/검사했고 Codex는 실제 코드·변경·보존 XML/로그를 읽어 검토했다. Codex의 제품 검사 재실행은 없었다.
- 제품 변경: CouponAdmissionService의 운영 캠페인/열림 조건·대기 포트 결과/실패 처리, CouponFacade의 registerWaiting/getWaiting/issue/processAdmissions, CouponWaitingResult·CouponIssueOutcome이다. 새 발급 경로는 기존 쿠폰 → 헤더 → 운영 ID/열림 → start → 기존 DB TX/후속 읽기 → finish 순서며 token을 다시 획득하지 않는다.
- TX 관찰은 기존 콜백 진입/완료 통지를 재사용했다. 종료 확인된 새/기존 쿠폰 또는 쓰기 COMMITTED는 ISSUED, 종료 확인된 실패는 FAILED로 기록한다. 종료 불명은 finish하지 않고 PROCESSING 유지·닫힘이다. finish 실패는 확정된 DB 결과를 유지하고 정상 UNCHANGED는 수용을 닫지 않는다. 입장 처리는 짧은 캠페인 읽기 TX 뒤 Redis만 호출하고 DB를 쓰지 않는다.
- 실제 MySQL/fixture Redis Facade 검사 11건: 총량 C=8·8명에서 5 발급/3 품절·ISSUED 5/FAILED 3·점유0, INSERT 실패 rollback·FAILED·권한 재사용 거절/명시 재등록, READY 뒤 비활성/품절, C=3 넷째 NOT_READY·DB 추가 진입 없음·종료 뒤 재배정, 동일 권한 PROCESSING, close 뒤 진행 작업 finish, 기존 결과/조회 보존, Redis 호출 중 TX 없음과 SQL 순서를 확인했다. 같은 사용자 UNIQUE/마지막 수량 Facade 경쟁 2건은 STARTED 대역+실제 DB이며 실제 자리 상한 근거가 아니다. 기존 직접 DB 경쟁도 별도 실행했다.
- 실행: JDK 21.0.12, 2026-11-12 14:52:47~14:53:25 KST, Checkstyle main/test와 지정 클래스 필터의 test 작업에 `--rerun-tasks --no-build-cache --console=plain`을 사용했다. BUILD SUCCESSFUL, 34 actionable tasks 모두 executed. 실제 Redis는 fixture 8.10.1 master이며 endpoint/DB/run_id 대조를 통과했다.
- XML 합계 200건 실행·실패/오류/skip 0: Service 41, FacadeWaiting 22, FacadeTransactionOutcome 17, FacadeIntegration 11, IssueTransactionIntegration 7, 기존 AdmissionRedis 14, WaitingRedis 18, WaitingReplyConversion 8, Properties 3클래스 59, ArchitectureTest 3. Checkstyle main/test 오류 각 0. IssueTransactionIntegration의 최상위 XML 0건은 실행 건수에 더하지 않았고 중첩 3+4건만 집계했다.
- 원시 근거: 로컬 `docs/week8/verification/runs/20261112-unit3-service-facade/`의 gradle.log·timing.txt·test-results XML·Checkstyle XML. 실제 명령/필터는 gradle.log에 보존했다. Facade 테스트는 worker 종료 확인 뒤 자기 캠페인 대기 키만 정리했고 기본 token 키는 시작/끝 NONE 확인만 했다.
- 임시 호환/한계: Controller는 아직 issue(user,campaign) → 새 issue(...,null) 경로라 기존 쿠폰만 반환하고 신규는 ID_REQUIRED다. campaign-id 미설정 open은409다. CouponApiE2ETest·CouponHttpFaultE2ETest·CouponLocalFixtureConfigIntegrationTest·opt-in CouponLocalRedisFaultIntegrationTest는 4단위 전환 전 예상 실패로 미실행이며 통과로 기록하지 않는다. HTTP 헤더/DTO/202·등록 limiter/새 W6/O11 조립·자동 scheduler·전체 회귀/check·장애/부하 실험은 미실행이다.
- 4단위에서 제거할 것은 임시 issue 2인수 메서드, cleanup API/DTO와 전용 경로, 구 acquire/release 포트·Set/Lua·전용 test 대역이다. 유효한 TX/DB/응답 유실/자원 정리 검증은 새 경로로 유지한다. Codex 검토에서 필수 수정·불필요한 제품 계층 추가는 발견하지 못했다.

## 대기실 확장 4단위 — HTTP·등록 요청률 제한·구 경로 제거 (2026-11-12)

- 기준: volume-8 / 0ca8ff5(3단위 커밋). Claude Code가 구현/검사했고 Codex가 코드·보존 XML/로그를 검토했다. Codex의 제품 검사 재실행은 없었다.
- waiting POST/GET·선택 X-WAITING-ID·발급 200/201/202·WaitingResponse와 API 명세를 연결했다. 등록 handler 앞의 singleton Resilience4j interceptor는 사용자 식별/인자 변환보다 먼저 허가하며 timeout=0이다. 발급/조회/목록/제어에는 적용하지 않는다. 기존 WebMvcConfig는 유지하고 별도 CouponWaitingConfig가 등록한다.
- 제거: cleanup API/전용 DTO·Facade 임시 issue(user,campaign)·구 acquire/release 포트/Set/Lua·token 전용 대역/검사. 유효한 DB/TX·응답 유실/timeout·종료 불명/정리 보류 검증은 start/finish 경로로 전환했다. 기존 master·앱 C=3 확인은 WaitingRedisIntegrationTest로 옮겼다. W6/O11 제외 목록에는 interceptor/config 두 동작 bean만 추가하고 관리 타입·DDL/행 보존 기대값은 유지했다.
- HTTP 검사는 시작 설정과 고정 캠페인 fixture를 맞추고 수동 processAdmissions로 READY를 준비한다. 목록의 과거 캠페인 쿠폰은 DB fixture로 준비한다. 준비 코드 검사는 출력 ID와 같은 campaign-id·보존 DB로 재시작한 뒤 새 발급 흐름을 검사한다. RateLimiter 전용 검사는 긴 주기 안의 소진/동시 허가·즉시 거절/재허용·설정 범위·실제 MVC 선행429·등록 한도 소진 중 발급/조회/목록/제어 유지 등을 확인했다.

| 실행(KST) | 결과와 범위 |
|---|---|
| 15:31:50~15:33:34 | 관련 23클래스 필터·Checkstyle main/test, XML 38개 합계 233건 실패/오류/skip 0·Checkstyle 오류 각 0. BUILD SUCCESSFUL, 34 actionable tasks executed |
| 15:33:42~15:34:17 | 별도 w6OutboxRecoveryTest O11, 1건 실패/오류/skip 0. BUILD SUCCESSFUL, 32 actionable tasks executed |
| 15:42:28~15:43:28 | 정리 조건/명세 보완 뒤 HTTP/정리 guard/ArchUnit, XML 9개 합계 39건 실패/오류/skip 0·Checkstyle 오류 각 0. BUILD SUCCESSFUL, 34 actionable tasks executed |

- JDK21.0.12와 `--rerun-tasks --no-build-cache`를 사용했다. 관련 실행에는 W6 준비/직접 발행·W7 relay lifecycle·쿠폰 Service/Facade/TX/Redis/HTTP·설정 검사가 포함됐다. 보완 후 재실행은 CouponApiE2ETest 17·CouponHttpFaultE2ETest 4·CouponWaitingRateLimitE2ETest 2·ServerSettlementTest 13·ArchitectureTest 3뿐이며 나머지/O11을 다시 실행한 것으로 쓰지 않는다.
- 보완 1: 세 HTTP 테스트는 test 전용 OwnedWaitingKeys를 통해 키 5개가 모두 비었을 때만 자기 키 정리를 허용한다. 첫/마지막 키 marker로 준비 실패 뒤 보존과 정상 소유 뒤 정리를 검사했다. 기존 서버/worker 종료·sticky 보류는 유지한다.
- 보완 2: 발급 200/201/202의 생성 OpenAPI가 ApiResponse의 meta/data 구조와 각 data 타입을 표현하도록 수정했다. 실제 /v3/api-docs의 참조 스키마를 검사했다. 제품 wrapper 타입/실제 JSON은 추가·변경하지 않았다. Codex 재검토에서 두 지적의 해소와 추가 필수 수정 없음을 확인했다.
- 원시 근거: 로컬 `docs/week8/verification/runs/20261112-unit4-http-ratelimit/a-related/`, `b-o11/`, `docs/week8/verification/runs/20261112-unit4-cleanup-schema-fix/`의 명령/로그·timing·XML/Checkstyle. 실제 Redis는 fixture 8.10.1 master/run_id를 대조했다. opt-in 로컬 Redis 장애 소스의 포트/키/관찰 대응은 컴파일만 확인했고 실제 pause/키 무효화/재개는 실행하지 않았다. 그 테스트 코드를 승인된 장애별 복구 절차/검증 근거로 사용하지 않는다.
- 남은 범위: 5단위 자동 scheduler/실제 자동 주기·종료 검사와 scheduler bean의 W6/O11 격리, 이후 별도 전체 회귀가 남는다. 전체 check·부하/장애 실험은 이번에 수행하지 않았다. 현재 입장은 수동 processAdmissions에 의존한다.

## 대기실 확장 5단위 — 내부 실행기 자동 입장·종료 (2026-11-12)

- 기준: volume-8 / 02de22a(4단위 커밋). Claude Code가 구현/검사, Codex가 실제 코드·조립 diff·보존 XML/로그를 검토했다. Codex의 제품 검사 재실행은 없었다.
- 신규 CouponWaitingScheduler는 SmartLifecycle의 기본 phase로 내부 단일 ScheduledExecutorService를 관리한다. 캠페인 미설정이면 실행기/예약이 없고, 설정됐으면 최초/회차 종료 후 schedulerDelay를 두고 Facade.processAdmissions만 호출한다. RuntimeException은 로그 후 다음 회차 유지, Error는 로그 후 재던짐으로 중단한다. stop은 cancel(false)·shutdown·기존 phase 종료 한도 내 대기 순서며 timeout/interrupt는 미완료로 기록한다. W7 실행기/설정·공통 scheduling 인프라는 변경하지 않았다.
- 일반 HTTP/opt-in 컨텍스트는 test의 MockitoBean 대체, 직접 실행 준비 코드 컨텍스트는 test 전용 bean 정의 제거로 자동 회차를 통제했다. W6/O11 CouponBeanIsolation에는 실제 scheduler 이름 하나만 추가했다. 실제 자동 주기 전용 컨텍스트는 real scheduler를 사용했다.
- WQ11 관찰: 최초 delay·회차 비중첩/fixed delay·단일 스레드·중복 start/stop·진행 회차 종료 대기/스레드 종료·timeout/interrupt·RuntimeException/Error, 닫힘에서 DB/Redis 업무0·open 뒤 처리·Redis 실패 뒤 닫힘/이후 업무0. 실제 fixture Redis/MySQL에서 수동 processAdmissions/GET 없이 4명 중 3명 승격·미사용 READY 회수/넷째 승격·PROCESSING 미회수·쿠폰/issuedCount 변화0을 확인했다.

| 실행(KST) | 실제 결과와 범위 |
|---|---|
| 15:55:03~15:56:54 | 관련 23클래스 필터·Checkstyle main/test, XML 38개 합계 234건 실패/오류/skip 0·Checkstyle 오류 각 0. 34 actionable tasks executed |
| 15:57:01~15:57:35 | 별도 O11 w6OutboxRecoveryTest 1건 실패/오류/skip 0. 32 actionable tasks executed |
| 16:05:25~16:05:57 | 테스트 종료 확인 보완 뒤 scheduler lifecycle 11·자동 통합1·ArchUnit3, 총15건 실패/오류/skip 0·Checkstyle main/test 오류 각 0. 34 actionable tasks executed |

- 모두 JDK21.0.12, `--rerun-tasks --no-build-cache`로 BUILD SUCCESSFUL이었다. 원시 명령·timing·XML/Checkstyle은 로컬 `docs/week8/verification/runs/20261112-unit5-scheduler/a-related/`, `b-o11/`, `docs/week8/verification/runs/20261112-unit5-settlement-fix/`에 보존했다. 보완 뒤 O11/다른 클래스를 재실행한 것으로 기록하지 않는다.
- 보완: 자동 통합 테스트가 원래 실행기 참조를 보관하고 stop 반환 후 실제 종료가 확인돼야만 키/DB 정리를 실행한다. 참조 없음·timeout·interrupt는 실패/정리 보류이며 interrupt를 복원한다. 같은 test 내부 ExecutorSettlement를 실제 scheduler/latch 경계와 재사용해 실패 시 정리0·본문 선행 stop 후 원래 참조 유지·정상 종료 후 정리를 검사했다. 제품 scheduler/API와 별도 파일은 추가하지 않았다. Codex 재검토에서 지적 해소·추가 필수 수정 없음을 확인했다.
- 실제 Redis는 fixture8.10.1 master/run_id 대조를 통과했다. 시작 전 자기 캠페인 키 공백/소유 확인, 종료 확인 뒤 자기 키 정리를 유지했다. WQ12의 이번 W6/O11 조립·기존 W7 lifecycle 검사는 통과했지만 전체 회귀/check는 아직 별도 실행 전이다. 로컬 Redis pause·부하·장애별 복구/재시작/다중 인스턴스는 이번 실행 근거가 아니다.
