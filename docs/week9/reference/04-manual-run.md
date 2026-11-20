# 9주차 · 로컬 수동 실행 절차 (로컬 전용)

목적: 로컬에서 commerce-api 와 commerce-batch 를 실제로 띄워 주간 랭킹 API 응답을 눈으로 확인한다. 이 상태가 이후 kill 실험의 기반이다.
코드 변경 없음. 2026-11-20 에 1~6절을 실제로 실행해 확인했다(결과는 8절). 남은 미확인은 `[미확인]` 로 표시.

전제: Docker 데몬, JDK 21, 저장소 루트에서 실행. 모든 SQL 은 아래 `mysql` 셸 명령으로 실행한다.

```bash
# MySQL 셸 (compose 서비스 이름 mysql, 계정은 docker/infra-compose.yml 의 application/application, DB loopers)
docker compose -f docker/infra-compose.yml exec mysql mysql --default-character-set=utf8mb4 -uapplication -papplication loopers
```

---

## 1. 인프라 기동

루트 README 가 안내하는 방식 그대로다 (`README.md`, `docker/infra-compose.yml`).

```bash
docker compose -f docker/infra-compose.yml up -d mysql
```

- MySQL 8.0, 호스트 포트 3306, DB `loopers`, 계정 `application`/`application` (`infra-compose.yml:3-13`). local 프로필의 `jpa.yml:45-47` 과 같은 값이다.
- commerce-api 는 Redis 설정(`redis.yml` local = localhost:6379/6380)을 읽으므로 Redis 도 함께 띄운다. 서비스 이름은 `redis-master`·`redis-readonly` 다(`infra-compose.yml:17,37`). 실행에서는 아래처럼 셋을 함께 올렸고 api 는 정상 기동했다. Redis 없이 기동되는지는 `[미확인]`.

```bash
docker compose -f docker/infra-compose.yml up -d mysql redis-master redis-readonly
```

- **mysql 클라이언트는 `--default-character-set=utf8mb4` 를 붙인다.** 실행에서 이 옵션 없이 한글 이름을 INSERT 했더니 `ìƒí’ˆ Z` 처럼 깨져 저장됐다(클라이언트 기본 latin1). 위 `mysql` 셸 명령에 반영해 둔다.

---

## 2. commerce-api 첫 기동과 기준 데이터

```bash
./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=local'
```

- 포트 **8080** (api `application.yml` 에 `server.port` 없음 → Boot 기본값). local 은 `server.address=127.0.0.1` 로 루프백에만 바인딩한다 (`application.yml:144-145`).
- local 은 `spring.jpa.hibernate.ddl-auto=create` (`modules/jpa/src/main/resources/jpa.yml:40`) → 기동 시 **모든 엔티티 테이블을 DROP 후 CREATE** 한다. api 엔티티(brands, products, …)만 만들어진다. `weekly_ranking_*` 와 `product_purchase_activity` 는 api 에 엔티티가 없어 생기지 않는다.

**브랜드 1개·상품 3개 — SQL 로 넣는다.** 관리자 API(`POST /api-admin/v1/brands`, `/api-admin/v1/products`)가 있지만 `ROLE_ADMIN` 인증(`AdminBoundaryConfig.java:17`)과 CSRF 토큰(`:22`, `/api/**` 만 CSRF 제외)이 필요하고 로컬에 ADMIN 계정 설정이 없어 curl 로는 비실용적이다. 테이블·컬럼은 `Brand.java`(`brands.name`), `Product.java`(`products.brand_id, name`), `Price.amount`, `Stock.quantity` 에서 확인.

```sql
INSERT INTO brands (name, created_at, updated_at) VALUES ('루퍼스', NOW(6), NOW(6));
SET @brand := LAST_INSERT_ID();
INSERT INTO products (brand_id, name, amount, quantity, created_at, updated_at) VALUES
  (@brand, '상품 X', 1000, 100, NOW(6), NOW(6)),
  (@brand, '상품 Y', 1000, 100, NOW(6), NOW(6)),
  (@brand, '상품 Z', 1000, 100, NOW(6), NOW(6));
SELECT id, name FROM products ORDER BY id;   -- X < Y < Z 순 id 를 기록해 둔다 (아래 fixture 에 치환)
```

확인:

```bash
curl -s 'http://127.0.0.1:8080/api/v1/products' | jq .
curl -s 'http://127.0.0.1:8080/api/v1/rankings/weekly?periodStart=2026-08-17' | jq .meta
```

> **실측(첫 실행, 해결됨)**: 처음 실행에서는 이 응답이 `NOT_PUBLISHED` 가 아니라 **500**(`Table 'loopers.weekly_ranking_publications' doesn't exist`)이었다. 원인: 당시 `weekly_ranking_*` 엔티티가 batch 에만 있어 첫 batch 실행 전에는 테이블이 없었다. 조치(2026-11-20): 엔티티 4개를 공유 모듈 `modules/ranking-schema` 로 옮겨 api·batch 가 함께 의존하게 했다. 이제 api 단독 기동(`ddl-auto`)에서도 네 테이블이 생기고 이 curl 은 `NOT_PUBLISHED` 를 돌려준다(8절 재현 기록).

> 관리자 API 로 넣고 싶다면 Boot 기본 사용자에 역할을 주는 `--spring.security.user.name=admin --spring.security.user.password=admin --spring.security.user.roles=ADMIN` 기동 옵션과 CSRF 토큰 처리가 필요하다. 코드에 커스텀 `UserDetailsService` 는 없다(확인). CSRF 왕복은 시도하지 않았다 `[미확인]`. 이 문서는 SQL 경로를 쓴다.

---

## 3. 입력 원본 테이블과 §6 fixture

`product_purchase_activity` 는 **commerce-streamer 의 엔티티**라(`apps/commerce-streamer/.../domain/purchase/ProductPurchaseActivity.java`) api·batch 의 `ddl-auto` 로는 생기지 않는다.
두 방법 중 **DDL 직접 CREATE 를 택한다.** streamer 를 local 로 띄우면 Kafka 기동(`kafka.yml` local `localhost:19092`)과 8080 포트 충돌 회피까지 필요해 더 복잡하다. 컬럼은 설계 3절·batch 테스트 `RankingTestSupport.PRODUCT_PURCHASE_ACTIVITY_DDL` 과 같다.

```sql
CREATE TABLE IF NOT EXISTS product_purchase_activity (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  order_id     BIGINT      NOT NULL,
  product_id   BIGINT      NOT NULL,
  quantity     INT         NOT NULL,
  confirmed_at DATETIME(6) NOT NULL,
  created_at   DATETIME(6) NOT NULL,
  updated_at   DATETIME(6) NOT NULL,
  deleted_at   DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ppa_order_product (order_id, product_id)
) ENGINE=InnoDB;
```

§6 fixture 4행. `confirmed_at` 은 **UTC 벽시계 리터럴**로 넣는다(DB 저장 규칙 D12. 세션 시간대 변환이 없는 `DATETIME` 이라 리터럴 그대로 들어간다). `:X :Y :Z` 는 2절에서 기록한 실제 상품 id 로 치환.

```sql
INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at, created_at, updated_at) VALUES
  (42, :X, 2, '2026-08-17 01:00:00.000000', NOW(6), NOW(6)),
  (42, :Y, 1, '2026-08-17 01:00:00.000000', NOW(6), NOW(6)),
  (43, :X, 3, '2026-08-18 01:00:00.000000', NOW(6), NOW(6)),
  (44, :Z, 5, '2026-08-19 01:00:00.000000', NOW(6), NOW(6));
```

기대 결과(설계 D13): X=5점 1위, Z=5점 2위, Y=1점 3위 (동점은 productId 오름차순이므로 X < Z 가 전제).

---

## 4. commerce-batch 실행

### 4-1. 첫 실행 (r1)

```bash
./gradlew :apps:commerce-batch:bootRun --args='--spring.profiles.active=local --job.name=weeklyRanking periodStart=2026-08-17 snapshotId=weekly-20260817-r1'
```

- **`--job.name`** 은 Boot 옵션 인자 → 속성 `job.name` → `application.yml:16` 의 `spring.batch.job.name=${job.name:NONE}` 로 들어가 `WeeklyRankingJobConfig`(`@ConditionalOnProperty havingValue=weeklyRanking`)가 켜진다.
- **JobParameters** 는 `--` 가 없는 **non-option 인자**로 넘긴다. Boot 3.4.4 `JobLauncherApplicationRunner.run` 이 `ApplicationArguments.getNonOptionArgs()` 를 `=` 로 나눠 `DefaultJobParametersConverter.getJobParameters(Properties)` 에 넘기는 것을 bytecode 로 확인했다. Batch 5.2.2 변환기 형식은 `name=value,type,identifying` 이며 type 기본 `java.lang.String`, identifying 기본 `true` 다. 따라서 `periodStart=2026-08-17` 은 `periodStart=2026-08-17,java.lang.String,true` 와 같다. **실측 로그**: `completed with the following parameters: [{'periodStart':'{value=2026-08-17, type=class java.lang.String, identifying=true}','snapshotId':'{value=weekly-20260817-r1, type=class java.lang.String, identifying=true}'}] and the following status: [COMPLETED]`. `BATCH_JOB_EXECUTION_PARAMS.IDENTIFYING` 도 두 파라미터 모두 `Y`.
- `spring.batch.job.enabled` 는 local 에서 설정이 없어 기본 `true` → 기동 시 Job 이 자동 실행되고 `CommerceBatchApplication.main` 이 `SpringApplication.exit` 로 종료한다.
- Validator(D1)가 먼저 돈다. `periodStart` 가 월요일이 아니거나 `snapshotId` 날짜와 다르면 Job 시작 전 `JobParametersInvalidException` 으로 실패하고 JobInstance 가 남지 않는다.
- Step 1 의 `frozen_at` 은 **시스템 시계**(local 은 `BatchClockConfig` 의 `Clock.systemDefaultZone()`)라 실행 시각이 들어간다. 2026-08-17 주 데이터를 다른 날 실행해도 입력 범위는 `periodStart` 로만 정해지므로 결과는 같다.

**종료 코드 확인**

```bash
./gradlew :apps:commerce-batch:bootRun --args='…' ; echo "exit=$?"
```

- `CommerceBatchApplication.main` 은 `System.exit(SpringApplication.exit(...))` 다. Boot `JobExecutionExitCodeGenerator` 는 실행된 JobExecution 의 `BatchStatus.ordinal()` 을 종료 코드로 쓴다(bytecode 확인). **실측**: r1·r2 모두 COMPLETED 이고 `gradlew bootRun` 의 `$?` 는 0 이었다. FAILED 일 때의 값(BatchStatus 순서상 5)과 gradle 이 그 값을 그대로 전달하는지는 이번 실행에 실패 사례가 없어 `[미확인]` — kill 실험에서 확인한다. 확실한 판정은 6절의 `BATCH_JOB_EXECUTION.STATUS` 다.
- bootRun 대신 jar 로 돌리면 종료 코드가 그대로 나온다: `./gradlew :apps:commerce-batch:bootJar && java -jar apps/commerce-batch/build/libs/commerce-batch-*.jar --spring.profiles.active=local --job.name=weeklyRanking periodStart=… snapshotId=…` (jar 이름은 `build/libs` 에서 확인).

### 4-2. 두 번째 기동부터 — 반드시 `ddl-auto=update`

> **batch 와 api 모두, 두 번째 기동부터는 `--spring.jpa.hibernate.ddl-auto=update` 를 붙여야 한다.**
> local 프로필의 `ddl-auto=create` 는 기동마다 **엔티티 테이블을 전부 DROP 후 CREATE** 한다. batch 를 다시 띄우면 `weekly_ranking_inputs`·`revisions`·`scores`·`publications` 4개가 사라져 r1 결과·회차·pointer 가 모두 없어지고, api 를 다시 띄우면 `brands`·`products` 가 사라진다. `BATCH_*` 메타 테이블은 JPA 엔티티가 아니라 `ddl-auto` 와 무관하고, `product_purchase_activity` 는 두 앱 어디에도 엔티티가 없어 역시 살아남는다. 즉 **메타데이터는 남고 업무 테이블만 지워지는** 어긋남이 생기므로 `update` 로 막는다.

```bash
# batch 재실행 예 (r2)
./gradlew :apps:commerce-batch:bootRun --args='--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=update --job.name=weeklyRanking periodStart=2026-08-17 snapshotId=weekly-20260817-r2'

# api 재기동 예
./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=update'
```

- api 를 **batch 보다 먼저** 띄웠고 그 뒤 batch 가 r1 을 돌렸다면, api 는 재기동 없이 그대로 쓰면 된다(api 는 `weekly_ranking_*` 에 엔티티가 없어 테이블을 건드리지 않고 JDBC 로 읽기만 한다).
- 같은 파라미터로 다시 돌리면 COMPLETED 인 JobInstance 라 `JobInstanceAlreadyCompleteException` 으로 시작 전에 끝난다. 재계산은 `snapshotId` 의 revision 을 올린다.

---

## 5. curl 확인 3단계

```bash
R='http://127.0.0.1:8080/api/v1/rankings/weekly?periodStart=2026-08-17'
```

### (1) r1 결과 — `rank == calcRank`

```bash
curl -s "$R" | jq '.data | {status, snapshotId, frozenAt, items: [.items[] | {productId, score, rank, calcRank}]}'
```

기대: `status PUBLISHED`, `snapshotId weekly-20260817-r1`, `frozenAt` 은 실행 시각(UTC, 초 단위 `…Z`), items `[X 5 1 1, Z 5 2 2, Y 1 3 3]`.

### (2) 상품 1개 삭제 — `rank` 연속, `calcRank` 간격

Z(2위)를 소프트 삭제한다(관리자 DELETE API 도 ROLE_ADMIN 이 필요해 SQL 로).

```sql
UPDATE products SET deleted_at = NOW(6) WHERE id = :Z;
```

기대: items `[X 5 rank 1 calcRank 1, Y 1 rank 2 calcRank 3]`. `snapshotId` 는 그대로 r1 (DB 의 ranking 은 바뀌지 않고 API 가 제외만 한다). 되돌리려면 `UPDATE products SET deleted_at = NULL WHERE id = :Z`.

### (3) 늦은 주문 → r2 → `snapshotId` 가 r2

```sql
-- 그 주 안의 늦은 구매 (Y × 4). 복원한 Z 기준으로 X=5, Y=5, Z=5 동점이 된다
INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at, created_at, updated_at)
VALUES (45, :Y, 4, '2026-08-20 01:00:00.000000', NOW(6), NOW(6));
```

```bash
curl -s "$R" | jq .data.snapshotId      # 아직 "weekly-20260817-r1" — 고정 뒤 도착한 구매는 현재 공개본에 없다 (D15)
./gradlew :apps:commerce-batch:bootRun --args='--spring.profiles.active=local --spring.jpa.hibernate.ddl-auto=update --job.name=weeklyRanking periodStart=2026-08-17 snapshotId=weekly-20260817-r2'
curl -s "$R" | jq '.data | {snapshotId, items: [.items[] | {productId, score, rank, calcRank}]}'
```

기대: `snapshotId weekly-20260817-r2`, items `[X 5 1 1, Y 5 2 2, Z 5 3 3]` (동점 → productId 오름차순). Z 를 삭제 상태로 두었다면 `[X 5 1 1, Y 5 2 2]`.

---

## 6. BATCH_ 테이블 조회 (kill 실험에서 재사용)

```sql
-- (1) JobInstance 와 그 파라미터: 어느 periodStart·snapshotId 가 어떤 instance 인지
SELECT ji.JOB_INSTANCE_ID, ji.JOB_NAME, p.PARAMETER_NAME, p.PARAMETER_VALUE, p.IDENTIFYING
FROM BATCH_JOB_INSTANCE ji
JOIN BATCH_JOB_EXECUTION je ON je.JOB_INSTANCE_ID = ji.JOB_INSTANCE_ID
JOIN BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID = je.JOB_EXECUTION_ID
WHERE ji.JOB_NAME = 'weeklyRanking'
ORDER BY ji.JOB_INSTANCE_ID, je.JOB_EXECUTION_ID, p.PARAMETER_NAME;

-- (2) JobExecution·StepExecution 상태와 카운트: 같은 instance 의 실행 시도들, 어느 Step 까지 갔나
SELECT je.JOB_INSTANCE_ID, je.JOB_EXECUTION_ID, je.STATUS AS JOB_STATUS, je.EXIT_CODE, je.EXIT_MESSAGE, je.START_TIME, je.END_TIME,
       se.STEP_EXECUTION_ID, se.STEP_NAME, se.STATUS AS STEP_STATUS, se.READ_COUNT, se.WRITE_COUNT, se.COMMIT_COUNT, se.ROLLBACK_COUNT, se.EXIT_MESSAGE AS STEP_EXIT
FROM BATCH_JOB_EXECUTION je
LEFT JOIN BATCH_STEP_EXECUTION se ON se.JOB_EXECUTION_ID = je.JOB_EXECUTION_ID
ORDER BY je.JOB_EXECUTION_ID, se.STEP_EXECUTION_ID;

-- (3) Step 2 의 reader checkpoint: ExecutionContext 에 저장된 weeklyProductIdReader.read.count
SELECT se.JOB_EXECUTION_ID, se.STEP_EXECUTION_ID, se.STEP_NAME, c.SHORT_CONTEXT
FROM BATCH_STEP_EXECUTION se
JOIN BATCH_STEP_EXECUTION_CONTEXT c ON c.STEP_EXECUTION_ID = se.STEP_EXECUTION_ID
WHERE se.STEP_NAME = 'calculateWeeklyScoresStep'
ORDER BY se.STEP_EXECUTION_ID;
```

- (3)의 `SHORT_CONTEXT` 는 **JSON 이 아니라 Java 직렬화를 Base64 로 감싼 문자열**이다(`rO0AB…`, 매직 `aced`. Batch 5 기본 `DefaultExecutionContextSerializer`). SQL 로는 키를 바로 읽을 수 없고 디코드가 필요하다. 실측(r1·r2 모두): 디코드하면 `weeklyProductIdReader.read.count` = **4**, `batch.taskletType`·`batch.version=5.2.2`·`batch.stepType` 이 들어 있다. 설계 9-1 의 중간 실패 상태에서는 2 였다. 빠른 확인용:

```bash
echo '<SHORT_CONTEXT>' | base64 -d | strings | grep -A1 read.count   # 값은 바이너리라 python 으로 int 디코드가 정확하다
```
- `BATCH_JOB_EXECUTION.START_TIME/END_TIME` 은 JVM 기본 시간대(Asia/Seoul)의 벽시계로 저장된다(실측 `13:05:18`). 업무 테이블의 `frozen_at`·`published_at` 은 UTC(`04:05:18`)다. 두 시각을 비교할 때 9시간 차이를 감안한다.
- kill 실험에서는 (2)의 `JOB_STATUS = 'STARTED'` 이고 `END_TIME IS NULL` 인 행이 복구 대상이다 (설계 7-2 수동 복구 절차).

---

## 7. 정리 — 지우면 안 되는 것 / 지워도 되는 것

| 구분 | 대상 | 이유 |
|---|---|---|
| **지우면 안 됨** | `BATCH_*` 9개 | 재시작 판정의 근거. 지우면 "같은 JobInstance 재시작" 실험이 불가능하고 업무 테이블과 어긋난다 |
| **지우면 안 됨** | `weekly_ranking_revisions`·`scores`·`publications`·`inputs` | 회차 이력(R7). kill 실험은 이 위에 r3 이상을 쌓는다 |
| **지우면 안 됨** | `product_purchase_activity` | 입력 원본. 같은 입력 → 같은 checksum 비교의 전제 |
| 지워도 됨 | 2절에서 넣은 `brands`·`products` 의 **추가** 행 | 단, 삭제하면 API 가 그 상품을 제외만 한다. 복원은 `deleted_at = NULL` |
| 지워도 됨 | `products.deleted_at` 표시 | (2) 실험 되돌리기 |
| 하지 말 것 | `ddl-auto=create` 로 재기동 | 4-2 절. 업무 테이블만 날아가고 BATCH_ 는 남아 어긋난다 |
| 전부 초기화하려면 | `docker compose -f docker/infra-compose.yml down -v` 뒤 1절부터 | 볼륨 `mysql-8-data` 를 지우므로 BATCH_ 포함 전부 사라진다 |

다음 실험(kill)은 r3 이상을 Step 2 도중에 `kill -9` 로 끊는 것부터 시작한다. 그 전에 6절 (2)로 현재 `BATCH_JOB_EXECUTION` 에 `STARTED` 가 없는지 확인한다.

---

## 8. 실행 기록 (2026-11-20, 1~6절 실제 수행)

| 단계 | 결과 |
|---|---|
| 1 인프라 | mysql·redis-master·redis-readonly 기동. 볼륨에 이전 api 테이블(brands, products 등 8개)만 있었고 랭킹·입력·BATCH_ 테이블은 없었다 |
| 2 api 첫 기동 | `create` 로 테이블 13개 재생성, 8080 기동 ~12초. 브랜드 1·상품 X=1·Y=2·Z=3. 상품 조회 200. **랭킹 조회는 500**(당시 테이블 없음) → ranking-schema 모듈로 **해결됨**(2절 메모·아래 재현) |
| 3 입력 | `product_purchase_activity` 생성, fixture 4행 (order 42·42·43·44) |
| 4-1 r1 (`create`) | gradle exit **0**, Job COMPLETED 202ms. `weekly_ranking_*` 4개 생성. input_count 4, expected 3, input_checksum `8900c1f854cf…`(상품 id 가 1·2·3 이라 §6 상수 `3e90…`과 다름 — 정상), result_checksum `b35024cb37fe…`(crossAppTest E2E r1 과 동일), pointer INSERTED |
| 5-(1) | `PUBLISHED`, `weekly-20260817-r1`, `frozenAt 2026-11-20T04:05:18Z`, items `[X 5 1/1, Z 5 2/2, Y 1 3/3]` |
| 5-(2) Z 삭제 | items `[X 5 1/1, Y 1 2/3]` (rank 연속, calcRank 간격). Z 복원 후 `deleted_at NULL` 확인 |
| 5-(3) | 늦은 주문(45, Y×4) INSERT → curl 여전히 r1 → r2(`ddl-auto=update`) gradle exit **0**, input_count 5, expected 3, input `af088255e36c…`, result `5d4ab09a492b…`(E2E r3 과 동일), pointer UPDATED → curl `weekly-20260817-r2`, `frozenAt 04:25:16Z`, items `[X 5 1/1, Y 5 2/2, Z 5 3/3]` |
| 6 SELECT (1) | JobInstance 1(r1)·2(r2), 파라미터 4행 모두 `IDENTIFYING=Y` |
| 6 SELECT (2) | JobExecution 1·2 COMPLETED/COMPLETED. Step 별 read/write/commit/rollback: freeze 0/0/1/0, calculate **3/3/2/0**, open 0/0/1/0 (두 실행 동일) |
| 6 SELECT (3) | `SHORT_CONTEXT` Base64(Java 직렬화), `read.count=4` (r1·r2) |
| frozenAt 검증 | 실행 당시 UTC 04:05:18 ↔ `frozenAt 2026-11-20T04:05:18Z` 일치. **9시간 어긋남 없음**. MySQL `@@time_zone=SYSTEM`, `NOW()=UTC_TIMESTAMP()` (컨테이너가 UTC) |
| published_at | r2 뒤에도 `04:05:18.438230`(r1 첫 공개 시각) 유지, `updated_at` 만 `04:25:16` 로 갱신 — D5 CASE 동작 확인 |
| `ddl-auto=update` 부작용 | r2 기동 시 `alter table weekly_ranking_revisions modify column input_checksum CHAR(64) not null` / `result_checksum CHAR(64)` 2문장이 실행됐다. `columnDefinition` 컬럼은 Hibernate 가 매번 차이로 인식한다. 데이터 영향 없음 |
| 정리 | api 프로세스 종료(8080 해제). 컨테이너·DB 데이터 유지 |

JSON 전문 (5-(1)):

```json
{"meta":{"result":"SUCCESS"},"data":{"status":"PUBLISHED","periodStart":"2026-08-17","periodEnd":"2026-08-24","snapshotId":"weekly-20260817-r1","frozenAt":"2026-11-20T04:05:18Z","items":[{"productId":1,"productName":"상품 X","score":5,"rank":1,"calcRank":1},{"productId":3,"productName":"상품 Z","score":5,"rank":2,"calcRank":2},{"productId":2,"productName":"상품 Y","score":1,"rank":3,"calcRank":3}]}}
```

JSON 전문 (5-(2), Z 삭제 중):

```json
{"meta":{"result":"SUCCESS"},"data":{"status":"PUBLISHED","periodStart":"2026-08-17","periodEnd":"2026-08-24","snapshotId":"weekly-20260817-r1","frozenAt":"2026-11-20T04:05:18Z","items":[{"productId":1,"productName":"상품 X","score":5,"rank":1,"calcRank":1},{"productId":2,"productName":"상품 Y","score":1,"rank":2,"calcRank":3}]}}
```

JSON 전문 (5-(3), r2):

```json
{"meta":{"result":"SUCCESS"},"data":{"status":"PUBLISHED","periodStart":"2026-08-17","periodEnd":"2026-08-24","snapshotId":"weekly-20260817-r2","frozenAt":"2026-11-20T04:25:16Z","items":[{"productId":1,"productName":"상품 X","score":5,"rank":1,"calcRank":1},{"productId":2,"productName":"상품 Y","score":5,"rank":2,"calcRank":2},{"productId":3,"productName":"상품 Z","score":5,"rank":3,"calcRank":3}]}}
```

**재현 (2026-11-20, ranking-schema 모듈 적용 뒤)**: api 만 `--spring.jpa.hibernate.ddl-auto=update` 로 단독 기동(기존 DB 유지) → `periodStart=2026-08-03`(회차 없는 주) 응답 `{"status":"NOT_PUBLISHED","periodStart":"2026-08-03","periodEnd":"2026-08-10","items":[]}` (200), `2026-08-17` 은 `PUBLISHED` r2 그대로. 기동 로그에 `alter table weekly_ranking_revisions modify column …CHAR(64)` 2문장이 보였다(이미 있던 테이블에 대한 `update` 비교 결과이며, 테이블 생성은 아니다). api 단독 기동이 테이블을 **새로** 만드는 것은 기존 DB 라 이 재현으로는 보이지 않고, `WeeklyRankingV1ApiTest` 가 DDL 상수 없이 통과한 것(ddl-auto=create 컨텍스트)이 그 증거다. 참고: NOT_PUBLISHED 응답에 `snapshotId`·`frozenAt` 키가 **보이지 않는다**(null 필드 생략 직렬화) — 설계 D14 예시의 `"snapshotId": null` 표기와 다르다.

현재 DB 상태(kill 실험 출발점): JobInstance 2개(COMPLETED), `weekly_ranking_revisions` r1·r2 COMPLETED, pointer → r2, 입력 원본 5행, 상품 3개 모두 활성. `BATCH_JOB_EXECUTION` 에 `STARTED` 없음.
