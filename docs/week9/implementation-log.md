# 9주차 구현 기록 — 주간 구매 수량 랭킹 배치

현재 정책은 [design.md](design.md), 입력과 실행부터 늦은 구매 감지까지의 결정은 [ADR 0079](../adr/0079-identify-weekly-snapshot-by-explicit-revision.md)~[0086](../adr/0086-run-late-arrival-check-as-batch-app-runner.md), 현재 점수 Step은 [ADR 0089](../adr/0089-read-product-ids-and-insert-weekly-scores.md), 이후 운영 보완은 [ADR 0090](../adr/0090-reject-non-positive-quantity-in-frozen-input.md)~[0095](../adr/0095-reject-restart-with-different-chunk-size.md)를 따른다.

## 현재 흐름과 검증

주문 확정 → Outbox → Kafka → 구매 활동 저장 → 입력 고정 → 점수 계산 → 공개 → 랭킹 API를 연결했다.

| 과제 항목 | 확인한 결과 | 근거 |
| --- | --- | --- |
| 실제 입력과 늦은 구매 | 같은 주문 이벤트를 재발행해도 구매 활동 수를 유지했다. 늦은 주문이 와도 r1 입력·공개본을 유지했고, r2 공개 후 API에서 새 랭킹을 확인했다 | `WeeklyRankingFlowE2ETest` |
| 같은 입력에서 재시작 | 7·8 커밋 뒤 실패한 실행을 같은 JobInstance에서 이어 상품 9만 저장했다. 한 번에 끝낸 실행과 점수·순위·입력 및 결과 checksum이 같았다 | `RankingJobRestartTest` |
| 2-2번 점수 계산 | 상품 ID 페이지를 읽고 상품마다 합계를 조회한 뒤 Chunk의 전체 점수를 INSERT했다. 중복 키에 다른 점수를 넣으면 실패하고 기존 점수를 유지했다 | `ReaderSqlExperimentTest`, `WeeklySnapshotFreezeTest` |
| 공개와 복구 | 계산·공개 실패 중 이전 공개본을 유지했다. 공개 후 Batch 기록 저장이 실패한 경우에는 실행과 공개 상태를 확인한 뒤 같은 파라미터로 재시작해 공개본을 유지했다. 이전 revision이 새 공개본을 덮는 경우도 막았다 | `WeeklyRankingPublishTest` |
| 잘못된 입력·순위 중복 | 수량이 0 이하인 입력은 고정하지 않고 실패했다. 같은 snapshot에 같은 순위를 두 번 넣으면 실패했다 | `WeeklySnapshotFreezeTest` |
| 멈춘 실행과 chunk 크기 | `STARTED`로 남은 실행을 정리 SQL로 표시한 뒤 이어서 끝냈고, 정리했지만 살아 있던 실행의 다음 chunk는 version 불일치로 rollback됐다. chunk 크기를 바꾼 재시작은 읽기 전에 실패했다 | `StuckExecutionRecoveryTest`, `RankingJobRestartTest` |
| API 계약 | 미공개, 비어 있는 공개본, 삭제 상품 제외와 계산 순위를 확인했다 | `WeeklyRankingApiTest` |

- 제출 전 회귀로 `:apps:commerce-api:check :apps:commerce-batch:check :apps:commerce-streamer:check --continue`를 실행했다. API 684건, 배치 33건, streamer 91건이 실패·오류·건너뜀 없이 통과했다. 이 실행에서 API 테스트는 새로 돌았고, 배치·streamer 테스트와 세 모듈의 Checkstyle은 마지막 실행 뒤 입력이 바뀌지 않아 Gradle이 최신 상태(`UP-TO-DATE`)로 확인했다. 별도 실험(`experiment` 태그)은 이 회귀에 포함되지 않는다.
- 외부 스케줄러 배포는 과제 구현 범위 밖이다. 늦은 구매 감지의 판단과 감지기가 선택한 revision의 실행·공개는 테스트로 확인했다.

## 1단계: 기존 Batch 기반 확인

- `./gradlew :apps:commerce-batch:dependencyInsight --dependency spring-batch-core --configuration runtimeClasspath`: `spring-batch-core:5.2.2`.
- 기존 테스트 `CommerceBatchApplicationTest` 1개, `DemoJobE2ETest` 2개 통과.
- `DemoJobConfig`는 `RunIdIncrementer`와 `ResourcelessTransactionManager`를 쓴다. 새 작업 예시이므로 랭킹 job에는 가져오지 않는다.
- 트랜잭션 관리자: JPA 자동 설정의 `JpaTransactionManager` 하나이며 primary `mySqlMainDataSource`를 쓴다. Boot의 JDBC JobRepository도 같은 관리자를 쓴다.

## 2단계: DB chunk 재시작 연결 확인

`PurchaseInputRestartProbeTest`(commerce-batch, 1개). 과제 6절 입력 4행을 snapshotId `probe-1`로 넣고, 상품 7·8·9를 chunk=2로 읽어 확인 테이블 `batch_restart_probe`에 INSERT한다. 세 번째 상품 직전에 한 번 실패시키고 같은 parameter로 다시 실행한다. 랭킹 점수·순위·공개 구현이 아니다.

- 입력 테이블 `ranking_snapshot_input`은 테스트가 만드는 임시 스키마다. 입력 고정 단계에서 실제 테이블을 설계하면 그에 맞춘다.
- 실패 스위치는 job parameter가 아니며 `run.id`·현재 시각을 붙이지 않는다. 두 실행 사이에 입력·확인 행·Batch metadata를 지우지 않는다. 시작할 때 같은 JobInstance가 없는지 확인한다.

| 시점 | 확인한 것 | 결과 |
| --- | --- | --- |
| 실행 전 | 주입된 트랜잭션 관리자 | `JpaTransactionManager`, DataSource가 `mySqlMainDataSource`와 같은 객체 |
| 첫 실행 뒤 | JobExecution·StepExecution 상태 | FAILED |
| 첫 실행 뒤 | `JobExplorer.getStepExecution`으로 다시 읽은 `purchaseProductProbe.read.count` | 2 |
| 첫 실행 뒤 | commit 수 | 1 |
| 첫 실행 뒤 | 별도 조회한 확인 테이블 | 상품 7, 8 |
| 재실행 뒤 | JobInstance ID | 첫 실행과 같음 |
| 재실행 뒤 | JobExecution ID·상태 | 첫 실행과 다름, COMPLETED |
| 재실행 뒤 | 이번 StepExecution의 read·write 수 | 1, 1 |
| 재실행 뒤 | 확인 테이블 | 상품 7, 8, 9 |
| 재실행 뒤 | 같은 instance의 실행 수 | 2 |

확인 테이블은 PK가 있는 INSERT라 재실행이 상품 7·8을 다시 쓰면 실패한다.

기존 `DemoJobE2ETest`의 쓰지 않는 import(`lombok.RequiredArgsConstructor`) 때문에 `checkstyleTest`가 실패해 그 import를 지웠다.

검증: `./gradlew :apps:commerce-batch:check --rerun-tasks` 성공(테스트 4개, Checkstyle main·test). ArchUnit 테스트는 commerce-batch에 없다.

## 입력 고정 방식 비교 실험

- `SnapshotFreezeExperimentTest`(commerce-batch, `@Tag("experiment")`)를 추가했다. 결과는 [experiments.md](experiments.md) 실험 1.
- commerce-batch에 `experimentTest` task를 추가하고 일반 `test`에서 `experiment` 태그를 뺐다. commerce-api와 같은 방식이다.

## 3·4단계: 입력 고정과 상품별 점수 계산

이 단계에서는 입력 고정과 점수 계산까지 연결했다. 전체 Job의 현재 구조는 [design.md](design.md) 5·6절에 있다.

- 스키마 `schema/weekly-ranking.sql`(테이블 5개, `CREATE TABLE IF NOT EXISTS`)을 `spring.sql.init`으로 local·test에서만 실행한다. commerce-batch에 엔티티는 없다.
- `weeklyRanking` job: `WeeklyRankingJobConfig`, `WeeklyRankingParametersValidator`, `WeeklySnapshotId`, `InputChecksum`, `WeeklyRankingStore`(SQL), `ProductScore`, `step/FreezeSnapshotTasklet`·`SnapshotScoreProcessor`·`ScoreStageWriter`.
- 입력 고정 Step의 TX만 READ COMMITTED(`transactionAttribute`). 실험 1의 C이며 임시 선택이다.
- 2단계 probe 테스트는 임시 스키마 대신 실제 `ranking_snapshot_input`을 쓰도록 바꿨다(확인 테이블만 테스트가 만든다).
- batch 테스트는 같은 MySQL 컨테이너와 Batch metadata를 공유한다. 테스트끼리 데이터를 지우지 않고, 서로 다른 주와 주문 ID를 쓴다. 처음 작성 때 경계 확인용 행(앞 주 9/6, 다음 주 9/14 00:00)이 다른 테스트가 쓰던 이웃 주에 들어가 두 번 실패했고, 이웃 주를 피하도록 주를 다시 골랐다.

| 테스트 | 확인한 것 |
| --- | --- |
| `RankingJobRestartTest` (4개, chunk=2) | 과제 6절 입력. spy로 상품 9 계산 직전에 한 번 실패 → 첫 실행 FAILED, 입력 고정 Step COMPLETED, 계산 Step FAILED·commit 1·저장된 `snapshotProductReader.read.count=2`·`start.after={product_id=8}`, stage 7:5·8:1, snapshot 정보 4건·상품 3·checksum `3e90276e…c357a298`. 그 뒤 같은 주의 늦은 구매(주문 45, 상품 9 ×2)를 원본에 넣고 같은 parameter로 재실행 → 같은 JobInstance·다른 JobExecution·COMPLETED, 두 번째 실행에는 계산 Step만 있음(입력 고정 다시 안 함), read 1·write 1, stage 7:5·8:1·9:5(늦은 구매 미포함), snapshot 입력 요약 그대로, 실행 2개. 완료 뒤 같은 parameter는 `JobInstanceAlreadyCompleteException` |
| | writer가 상품 9 점수를 쓴 뒤 commit 전 실패 → 그 chunk의 상품 9 점수가 남지 않고 read.count 2, 재시작이 9:5를 씀(점수와 reader 위치가 같은 TX) |
| | 복사 중 세션 격리 수준 `READ-COMMITTED`, 점수 계산 중 `REPEATABLE-READ`(같은 스레드 JdbcTemplate으로 Step TX 연결의 `@@transaction_isolation`을 읽음) |
| | 입력 복사 뒤 snapshot 정보 저장 전 실패 → 입력 고정 Step FAILED, snapshot 정보·입력·stage 없음. 그 뒤 같은 주 구매가 도착하고 같은 parameter로 재실행 → 같은 JobInstance에서 입력 고정부터 다시 실행, 새 구매 포함 3건 고정, 점수 7:2·8:1·9:4 |
| `WeeklySnapshotFreezeTest` (5개) | 시작 시각 포함·끝 시각 제외·앞 주 제외, 같은 주문의 두 품목 포함, snapshot 정보(FROZEN, r1, 3건, 상품 3, checksum, 고정 시각) / r1 없이 r2 → FAILED·snapshot 없음, r1 뒤 늦은 구매 → r2 입력에만 포함·r1 입력 그대로·점수 r1 2, r2 6 / 이미 있는 snapshot: 요약이 맞으면 다시 복사하지 않고 그 입력으로 계산, 맞지 않으면 FAILED·입력 복사 없음·snapshot 정보 그대로 / 빈 주: 0건·상품 0·빈 입력 checksum, stage 없음 / 같은 상품 점수를 두 번 써도 5 |
| `WeeklySnapshotIdTest` (4개) | snapshotId 형식·월요일·revision 검사, 기간 경계, checksum 직렬화(과제 6절 입력의 기대값을 `shasum -a 256`으로 따로 계산), job parameter 검사(날짜 불일치·월요일 아님·`run.id` 추가·비식별 parameter 거부) |

검증: `./gradlew :apps:commerce-batch:check` 성공(테스트 17개: 위 13개 + probe 1 + 기존 3, Checkstyle main·test). 실험 테스트는 일반 test에서 빠진다. commerce-batch에는 ArchUnit 테스트가 없다.

## 5단계: 완성본 공개

현재 구조는 [design.md](design.md) 6절 "공개", 결정은 ADR 0082.

- `PublishRankingTasklet`(공개 Step)을 job 끝에 붙였다.
  - 한 TX에서 완성 조건 확인 → 순위(`ROW_NUMBER`) 저장 → `COMPLETED` 표시 → pointer를 `FOR UPDATE`로 잠그고 판단한다.
  - 이미 `COMPLETED`면 결과 checksum만 확인하고 pointer 판단을 다시 한다.
- 입력 고정에 "그 기간에 `COMPLETED`가 아닌 revision이 있으면 실패"를 더했다.
- 스키마 `ranking_snapshot`에 `result_checksum`·`completed_at`을 더했다. `CREATE TABLE IF NOT EXISTS`라 이미 만든 local 테이블에는 반영되지 않는다.
- checksum 도구를 `InputChecksum`에서 `RowChecksum`(행 값을 TAB·LF로 직렬화한 SHA-256)으로 일반화해 입력·결과에 함께 쓴다.
- `JobRepository` 빈은 인터페이스 프록시라 Mockito spy로 실제 메서드를 부를 수 없었다(`MockitoException`으로 입력 고정 Step부터 실패). 테스트에서만 `BeanPostProcessor`로 감싸 공개 Step의 최종 상태 저장을 한 번 실패시킨다.

| 테스트 | 확인한 것 |
| --- | --- |
| `WeeklyRankingPublishTest` (6개) | ① r1 공개 뒤 r2 계산 실패 → pointer r1·r2 랭킹 없음·r2 `FROZEN`, 재시작(같은 instance) → pointer r2, r1 랭킹 그대로 ② pointer 교체 직전 실패 → 공개 Step FAILED, pointer r1, r2 랭킹 없음·`FROZEN`(완성 표시까지 rollback), 재시작은 공개 Step만 실행해 r2 공개 ③ 상품 2 점수를 1 크게 계산 → 공개 Step FAILED("완성 조건"), pointer 없음, 랭킹 없음 ④ pointer가 r2인 상태에서 r1 실행 → Job COMPLETED·종료 코드 `NOT_PUBLISHED_OLDER_REVISION`, pointer r2 그대로, r1은 `COMPLETED`·랭킹 저장 ⑤ r1 계산 실패 중 r2 실행 → 입력 고정 FAILED("이전 revision이 아직 완성되지 않았습니다")·r2 snapshot 없음, r1 재시작 완료 뒤 r2 완료·pointer r2 ⑥ 공개 commit 뒤 공개 Step 최종 상태 저장 실패 → DB의 Job `UNKNOWN`·공개 Step `STARTED`, pointer·`COMPLETED`·랭킹 `[1:3:1]`·결과 checksum 그대로, 같은 parameter 재실행은 `JobExecutionAlreadyRunningException`. 실행을 `FAILED`로 표시하고 재시작 → 같은 instance, 공개 Step만 실행, COMPLETED, pointer·랭킹·결과 checksum·`completed_at` 그대로 |
| `RankingJobRestartTest` (추가 확인) | 과제 6절 첫 실행 실패 뒤 pointer·랭킹 없음. 재시작 뒤 랭킹 7:5:1, 9:5:2, 8:1:3, pointer r1, 결과 checksum `745d50bb…3b0a67d2`(`shasum -a 256`으로 따로 계산). 다른 주에 같은 구매 구성을 넣고 실패 없이 한 번에 실행(실행 1개) → 같은 랭킹·같은 결과 checksum |

검증: `./gradlew :apps:commerce-batch:check` 성공(테스트 23개, Checkstyle main·test).

### 완성 조건 실패의 복구 (ADR 0083)

- snapshot 상태 `ABANDONED`를 더했다. 직렬 실행 검사는 `FROZEN`만 막고, 공개 Step은 `ABANDONED`를 거부한다.
- `WeeklyRankingPublishTest` ③을 늘렸다. 상품 2 점수 +1 → r1 공개 거부·`FROZEN` → r2 막힘 → spy 해제 후 r1을 `ABANDONED`로 표시 → r3 거부(건너뛰기) → 막혔던 r2 instance 재시작 COMPLETED·pointer r2·랭킹 2:4:1, 1:3:2 → 포기한 r1 재시작 FAILED·pointer r2 그대로.

검증: `./gradlew :apps:commerce-batch:check` 성공(테스트 23개).

## 6단계: 주간 랭킹 API (commerce-api)

현재 계약은 [design.md](design.md) 7절, 결정은 ADR 0084.

- `RankingV1Controller`(`GET /api/v1/rankings/weekly`) → `WeeklyRankingQueryService` → `WeeklyRankingReader`(domain) / `JdbcWeeklyRankingReader`(infrastructure, SQL).
- 공개 조건은 기존 `ProductQueryService.getActiveByIdsInOrder`를 그대로 쓴다.
- `WeeklyRankingApiTest`는 commerce-batch의 `schema/weekly-ranking.sql`을 실행해 테이블을 만든다. 랭킹 행은 테스트마다 다른 주로 직접 넣고 지우지 않는다. 상품·브랜드는 기존 `DatabaseCleanUp`으로 비운다.
- 처음에는 상품을 `ProductService.create`로 만들었다. 좋아요 수 카운터 행이 없어 상품 조회가 500을 냈다(테스트 3개 실패). 실제 생성 경로인 `ProductFacade.create`로 바꿨다. 삭제 브랜드는 상품을 남긴 채 브랜드만 지워 브랜드 조건을 따로 확인하려고 `BrandService.delete`를 쓴다.

| 테스트 | 확인한 것 |
| --- | --- |
| `WeeklyRankingApiTest` (6개) | 공개본 없는 주(앞 주에는 있음) → `NOT_PUBLISHED`·items 0 / 0건 완성본 → `PUBLISHED`·snapshotId·items 0 / 정상 → 7(5,1)·9(5,2)·8(1,3), periodEnd·revision·inputFrozenAt(UTC)·상품명·브랜드명 / 삭제 상품(2위)·삭제 브랜드 상품(3위), limit 2 → 1위·4위, ranking_entry 4행 유지 / r2 완성 랭킹·stage가 있어도 pointer r1이면 r1, pointer를 r2로 바꾸면 r2 / 파라미터 없음·월요일 아님·형식 오류·limit 0·101·문자 → 400, limit 100 → 200 |
| `LayeredArchitectureTest` | 통과 |

검증: `./gradlew :apps:commerce-api:test --tests '*WeeklyRankingApiTest' --tests '*LayeredArchitectureTest'` 성공, `:apps:commerce-api:checkstyleMain checkstyleTest` 성공. commerce-api 전체 회귀는 아직 실행하지 않았다.

## 7단계: 실제 이벤트 흐름 통합 테스트

`WeeklyRankingFlowE2ETest`(commerce-api, 1개). 한 테스트에서 실제 경로를 잇는다.

1. 주문 API로 주문을 만들고 가짜 PG를 SUCCESS로 확정한다.
2. Outbox relay가 실제 Kafka(Testcontainers)에 발행한다.
3. 실제 commerce-streamer(별도 JVM)가 구매 활동을 저장한다.
4. 실제 commerce-batch(별도 JVM)가 그 DB 활동으로 `weeklyRanking`을 실행한다.
5. 랭킹 API로 결과를 확인한다.

주간 기간은 테스트가 만든 주문의 실제 `confirmed_at`(UTC)이 속한 주다.

- commerce-api `build.gradle.kts`: commerce-batch의 실행 classpath를 `-De2e.batchClasspath`로 넘긴다. streamer와 같은 방식이며 제품 의존성이 아니다.
- `SpringAppProcess.batch`·`waitForExit`를 추가했다. batch는 job 하나를 실행하고 종료 코드로 끝난다. 같은 MySQL을 써서 Batch metadata와 랭킹 테이블이 남는다.
- 첫 실행에서 r1 실행 전 API 호출이 500이었다. 랭킹 테이블은 batch가 뜰 때(`spring.sql.init`) 처음 생기는데, 아직 batch를 실행하기 전이었다. 운영은 스키마를 미리 만든다는 전제(ADR 0080)에 맞춰 테스트 시작 때 batch 스키마 파일을 실행한다.

| 단계 | 확인한 것 |
| --- | --- |
| 주문 1(양말 2, 모자 1) | relay가 PUBLISHED 저장 전에 멈춘 뒤 다시 보내 같은 이벤트가 두 번 발행됨 → consumer lag 0, 구매 활동 2행·처리 기록 1건 |
| 주문 2(장갑 3, 양말 1) | 구매 활동 2행. 두 주문이 같은 UTC 주 |
| batch 실행 전 | API `NOT_PUBLISHED` |
| batch r1(종료 코드 0) | API `PUBLISHED`, snapshotId r1, 양말 3·1위, 장갑 3·2위(동점은 productId), 모자 1·3위. r1 입력 4행(재발행이 늘리지 않음) |
| r1 공개 뒤 늦은 주문(모자 5) | 같은 주에 확정. API 결과와 r1 입력 4행 그대로 |
| batch r2(종료 코드 0) | API snapshotId r2·revision 2, 모자 6·1위, 양말 3·2위, 장갑 3·3위. r2 입력 5행. r1의 ranking_entry 그대로 |
| 다음 주·상품 삭제 | 다음 주 `NOT_PUBLISHED`. 모자를 삭제하면 양말 2위·장갑 3위(계산 순위 유지) |

실행 시간(로컬 관찰, 테스트 본문):

- 첫 통과 실행은 1,044초였다. 원인은 확인하지 못했다. 단계별 시간을 기록하기 전이었다.
- 단계별 시간을 출력하도록 고친 뒤 다시 실행하니 22.5초였다.
  - streamer 시작·partition 배정 11.6초
  - 주문 1 1.0초
  - 주문 2 0.35초
  - batch r1 4.1초(별도 JVM 시작부터 종료까지)
  - 늦은 주문 0.39초
  - batch r2 4.2초

검증: `./gradlew :apps:commerce-api:test --tests '*WeeklyRankingFlowE2ETest'` 성공. commerce-api 전체 회귀는 아직 실행하지 않았다.

## 추가 실험

- `ScoreWriteModeExperimentTest`(experiment)를 추가했다. 절대값·증분 쓰기를 같은 TX·별도 TX·새 instance 조건으로 비교한다. 결과는 [experiments.md](experiments.md) 실험 2.
- `SnapshotFreezeExperimentTest`에 `compareWithAccumulatedHistory`를 추가했다. 기본 준비 데이터(12만 행)는 `compareFreezeMethods` 안으로 옮겼고, 누적 실험은 9.2만 행을 따로 넣는다. 결과는 실험 1-2.

## 입력 고정 방식 확정 (ADR 0085)

- 실험 1·1-2·1-3 결과로 C(READ COMMITTED TX의 `INSERT … SELECT` 한 문장)를 확정하고, 원본에 `confirmed_at` 인덱스는 추가하지 않기로 했다. 코드는 바뀌지 않았고 "임시" 표기만 지웠다.

## 늦은 구매 감지 (ADR 0086)

- `LateArrivalDetector`(판단, `Clock` 주입), `LateArrivalRevisionRunner`(ApplicationRunner, 같은 프로세스에서 weeklyRanking 실행·`JobExecutionEvent` 발행), `LateArrivalCheckConfig`(`commerce.ranking.late-check.enabled=true`일 때만)를 추가했다.
- `WeeklyRankingStore`에 `findLatestSnapshot`·`sourceRows`·`lastStoredAt`을 더했다.
- 테스트 fixture에 저장 시각을 정해 넣는 `insertStoredAt`을 더했다.

| 테스트 | 확인한 것 |
| --- | --- |
| `LateArrivalDetectorTest` (4개) | 2027-05-12 10:00 UTC → 대상 주 2027-05-03, snapshot 없음 → NONE / FROZEN → RESTART(같은 snapshotId), ABANDONED → NONE / r1 공개 뒤 원본 그대로 → NONE, 09:50 저장 늦은 구매 → 10:00 NONE("최근 30분"), 10:30 NEW_REVISION r2 / 실행기가 r2 실행 → COMPLETED·pointer r2, 다음 확인 NONE |

감지 모드 설정 조합으로 실제 프로세스를 띄워 보지는 않았다.

검증: `./gradlew :apps:commerce-batch:check` 성공(테스트 27개, Checkstyle main·test).

## 선택 실험과 전체 회귀

- `ScoreStepStrategyExperimentTest`(experiment)를 추가했다. 점수 계산의 chunk 크기(10·100·1000)와 GROUP BY tasklet을 비교한다. 결과는 [experiments.md](experiments.md) 실험 3.
- 전체 회귀: `./gradlew :apps:commerce-api:check :apps:commerce-batch:check :apps:commerce-streamer:check --continue`
  - commerce-api 684개, commerce-streamer 91개 통과(Checkstyle·ArchUnit 포함).
  - 같은 실행에서 commerce-batch는 테스트 컴파일에 실패했다. 회귀가 도는 도중 추가한 실험 파일의 타입 오류 때문이었다.
  - 고친 뒤 `:apps:commerce-batch:check`를 따로 다시 실행해 27개가 통과했다.

## 같은 고정 입력으로 정상 실행과 재시작 비교

`RankingJobRestartTest`에 테스트 하나를 추가했다. 이전 비교는 다른 주에 같은 구매 구성을 넣어 정상 실행했다. 그래서 점수·순위·결과 checksum은 같았지만 confirmedAt이 달라 입력 checksum은 비교할 수 없었다.

이번에는 같은 주를 두 번 계산했다.

1. r1을 상품 9 직전에 실패시키고 같은 parameter로 재시작했다(재실행 read 1).
2. 원본에 구매를 더 넣지 않고, r2를 실패 없이 한 번에 실행했다(실행 1개).

| 비교 | r1(실패 → 재시작) | r2(한 번에) |
| --- | --- | --- |
| 입력 요약(건수·상품 수·입력 checksum) | 같음 | 같음 |
| 결과 checksum | `745d50bb…3b0a67d2` | 같음 |
| 랭킹 | 7:5:1, 9:5:2, 8:1:3 | 같음 |

결과 checksum은 과제 6절 기대값(`shasum -a 256`으로 따로 계산)과 같다.

검증: `./gradlew :apps:commerce-batch:check` 성공(테스트 28개, Checkstyle main·test).


## Reader SQL·실제 복사 도중 잠금 실험

- `ReaderSqlExperimentTest`(experiment): MySQL general log로 점수 계산 Reader의 실제 SQL을 확인한다. OFFSET 없이 `product_id > 마지막 값`으로 다음 페이지를 읽었다(실험 4).
- `SnapshotFreezeExperimentTest`의 `compareInsertWaitDuringRealCopy`, `compareInsertWaitDuringRealCopyWithHistory`: 실제 복사 문장 도중 다른 연결의 INSERT를 넣는다(실험 1-3, 원본 10만 행 이하).
  - C는 복사 도중 원본 행 잠금·잠금 대기가 없었고, 도중 commit된 늦은 구매를 넣지 않았다.
  - B는 전체 스캔이면 잠금 대기를 관찰하지 못했고, 범위 스캔이면 이미 읽은 범위의 늦은 구매가 commit까지 기다렸다. 도중 commit된 늦은 구매 일부가 snapshot에 들어갔다.
- `SnapshotFreezeExperimentTest.compareContinuousInsertsDuringCopy`: INSERT를 쉬지 않고 넣으며 B·C로 복사한다(실험 1-4). B는 복사 도중 끝난 늦은 구매를 모두 snapshot에 넣었고 행 잠금 대기가 생겼다. C는 행 잠금 대기 0회였다.
- `StatsFreshnessExperimentTest`: 입력을 넣은 직후의 통계 상태(빈 테이블 / ANALYZE 뒤 / 이전 주가 쌓인 테이블)에 따라 점수 계산의 실행 계획과 검사한 행을 비교한다(실험 13, 이번 주 5만 행). 빈 테이블에 넣은 직후에는 두 방식 모두 검사한 행이 수백 배로 늘었고, 이전 주가 쌓인 테이블에서는 18번 모두 정상 계획이었다.
- `StatsFreshnessExperimentTest.comparesInputSizesAfterAnalyze`: 통계를 갱신한 상태에서 이번 주 입력을 2.5만·5만·10만 행으로 바꿔 잰다(실험 14). 구매 행 방식은 구매 행 수에, 상품 ID 방식은 상품 수에 비례했다.
- `ScoreWriterBatchRewriteExperimentTest.compareCurrentInsertWriterWithAndWithoutRewrite`: 현재 INSERT Writer로 rewrite 여부를 비교한다(실험 8-2). 켜면 100개가 INSERT 1문장으로 도착했고, 2,000개 저장+커밋 중앙값은 762ms → 92ms였다.
- 공개 commit 뒤 Batch 기록 실패의 운영 절차는 "실행이 정말 끝났는지 확인"부터 시작한다(설계 6절, ADR 0082).

## 입력 복사와 점수 Reader 비교

- 지연 없는 잠금 실험 두 건을 재실행했다. 복사 스레드에는 대기를 두지 않고 복사 SQL 뒤 바로 commit한다. 동시 INSERT 호출 시점과 관측 주기만 조절했다. 결과는 [실험 1-3](experiments.md#실험-1-3-실제-복사-도중의-insert-대기)에 남겼다.
- `RankingReaderStrategyExperimentTest`에서 현재 점수 조회, 집계 Paging, 집계 Cursor를 같은 JDBC Writer·chunk로 비교했다. 구매 10만·20만 행과 상품 1만 개 조건의 정상 완료 시간, Reader/Processor/Writer 구간, 9,001번째 상품 실패 뒤의 복구 및 실행 계획은 [실험 5](experiments.md#실험-5-상품별-합계를-읽는-세-가지-방식)에 기록했다.
- 작은 입력은 세 방식 모두 동일 instance에서 상품 9만 처리하고 기대 순위로 수렴했다. 대형 입력의 두 집계 방식은 stage 9,000건과 영속 checkpoint를 확인한 뒤 각각 남은 1,000개만 처리했으며 기대 점수 checksum과 일치했다.
- 집계 Cursor가 두 입력 크기에서 점수 계산을 가장 빨리 끝냈다. 비교 코드는 실험 테스트에 두고, 실제 Job에 집계 Reader를 연결하는 결정은 기존 checkpoint 호환성 및 확장 조건 검증 뒤에 내린다.
- 검증: 잠금 실험 2건, Reader 실험 2건, `:apps:commerce-batch:check`의 일반 테스트 28건과 Checkstyle main·test 통과.

## 집계 Cursor 채택 당시 기록

- 고정 입력에 상품별 `GROUP BY`·상품 ID 정렬을 적용해 집계 Cursor로 읽는다. 각 행은 검증된 `ProductScore`가 되며 Writer는 chunk 단위로 절대 점수를 저장한다.
- Cursor는 Batch DB 풀에서 읽기 연결을 별도로 쓴다. 점수와 `snapshotProductReader.read.count`는 기존과 같은 chunk 쓰기 TX에서 저장한다. Batch 앱에 `useCursorFetch=true`를 두고 fetch size에는 chunk 크기를 사용한다.
- `RankingJobRestartTest`에서 새 Cursor 실행의 writer 전·후 실패, 고정 입력, 같은 instance 재시작과 동일 결과를 확인했다. Paging으로 상품 7·8을 커밋하고 실패한 뒤 실제 Job의 Cursor로 교체해, 같은 instance에서 상품 9만 읽고 저장한 결과도 확인했다.
- `ReaderSqlExperimentTest`에서 실제 Job이 상품별 합계 SELECT를 한 번 실행하고 MySQL의 `Com_stmt_fetch`를 증가시키는 것을 확인했다. 과거 페이징 SQL과 성능 비교는 `experiments.md` 실험 4·5에 보존했다. 선택 근거는 [ADR 0087](../adr/0087-read-weekly-product-totals-with-cursor.md).
- 검증: `:apps:commerce-batch:check` 29건과 Checkstyle main·test, 실제 Reader SQL 실험 1건, commerce-api의 주간 랭킹 API·Kafka 연동 E2E·ArchUnit 8건 통과.

## 상품 키로 집계 결과를 페이지 조회하던 구성

- 주간 랭킹은 기존 인덱스를 쓰는 집계 Paging을 채택했다. 페이지마다 고정 입력에서 필요한 상품 범위를 집계해 읽는다. 같은 Chunk에서 절대 점수와 상품 키·처리 수·페이지 크기를 저장한다. 결정 근거는 [ADR 0088](../adr/0088-page-weekly-product-totals-by-indexed-key.md)에 기록했다.
- Cursor의 checkpoint와 이전 Paging 키가 남은 checkpoint 모두 `read.count`와 고정 입력에서 키를 복원해 이어 갔다. 같은 JobInstance의 새 Execution은 7·8을 다시 쓰지 않고 9만 저장했다.
- [실험 7](experiments.md#실험-7-첫-chunk-커밋-후-연결과-재시작-sql)에서 첫 Chunk 커밋 직후 풀의 사용 중 연결 증가량은 Paging 0, Cursor 1이었다. 실패 뒤 범위 Paging의 첫 쿼리에서 `product_id > 8`을 확인했다. 실제 Job의 정상 실행에서도 후속 페이지가 마지막 키를 사용하는지 SQL로 확인했다.
- 검증: `:apps:commerce-batch:check` 일반 테스트 30건 및 Checkstyle, Paging SQL·연결/재시작 실험 2건, 랭킹 API·Kafka 연동·ArchUnit 8건 통과.

## 점수 Writer의 JDBC Batch 재작성 확인

`ScoreWriterBatchRewriteExperimentTest`에서 당시의 절대값 UPSERT와 기존 Connector/J 설정을 확인했다. 100건 batch마다 서버에서 실행된 UPSERT 수는 rewrite를 끈 경우 100, 켠 경우 1이었다. 재작성 후에도 동일 batch를 다시 쓴 결과와 COMMIT 전 rollback을 확인했다. Writer 실험 1건과 Checkstyle 테스트 검사를 통과했다. 측정 조건과 결과는 [실험 8](experiments.md#실험-8-jdbc-batch-upsert는-서버에-몇-문장으로-도착할까)에 남겼다.

## 2-2번을 적용한 점수 계산 Step

- Reader는 스냅샷의 `DISTINCT product_id`를 페이지로 읽고, Processor는 각 ID의 `SUM(quantity)`를 조회한다. Writer는 `JdbcTemplate.batchUpdate`로 점수를 INSERT한다. 과제에서 따르는 흐름과 쓰기 경계를 [ADR 0089](../adr/0089-read-product-ids-and-insert-weekly-scores.md)에 기록했다.
- 기존 checkpoint 이름과 상품 ID 순서, 테이블·키를 사용한다. 상품 7·8 커밋 뒤 실패하면 같은 JobInstance에서 9만 썼다. 9 INSERT 직후 커밋 전에 실패하면 점수와 checkpoint를 함께 롤백했다. 비교용 Cursor가 남긴 체크포인트에서도 9만 이어 썼다.
- 같은 점수 키에 다른 값을 직접 INSERT하자 중복 키로 실패했고 먼저 넣은 값이 유지됐다. 실제 Job에서 구매 5행을 상품 4개로 읽어 각 상품의 합계를 한 번씩 구했고, Chunk당 두 점수를 한 INSERT에 묶어 전송했다. [현재 SQL 관찰](experiments.md#실험-9-2-2번-점수-step의-sql)
- 검증: `:apps:commerce-batch:check` 일반 테스트 30건 및 Checkstyle, 랭킹 API·Kafka 연결·ArchUnit 8건 통과. 현재 SQL 검사와 이전 Reader의 작은 입력 재시작 비교, 이전 UPSERT 재작성 비교까지 실험 테스트 3건을 실행해 통과했다.

## 구매 행 기준 실험 Job의 재시작

과제용 2-2번이 고정한 스냅샷을 1번 실험 Job에서 구매 행 단위로 읽었다. 두 번째 Chunk의 쓰기 직후 실패하자 주문 1702의 상품 8까지 커밋돼 있었다. 재시작 후 남은 두 행을 읽어 2-2번과 동일한 순위를 얻었고, 새 JobInstance로 네 행을 다시 읽어도 적용 기록과 점수가 유지됐다. `PurchaseRowScoreExperimentTest`, 과제용 `RankingJobRestartTest`, 배치 테스트 Checkstyle과 API ArchUnit 검사를 통과했다.

## 구매 10만 행의 점수 계산 검증

`ScoreDistributionExperimentTest`에서 구매 행 기준 실험 Job과 과제의 상품 ID 기준 Step을 세 가지 분포로 두 번씩 실행했다. 총 12개 실행에서 모든 상품의 점수와 동점 순위가 독립 집계 SQL 결과와 일치했다. 회차별 Step 시간과 조회·쓰기·커밋 횟수는 [실험 10](experiments.md#실험-10-구매-10만-행을-구매-행과-상품-id로-읽었을-때)에 기록했다.

상품 하나의 합계를 따로 측정한 후속 실험과 배치 테스트 Checkstyle이 통과했다. 통계 갱신 후 구매 100행·9만 행 상품의 JDBC 응답 중앙값은 각각 1.286ms·26.816ms였다. [단일 SUM 측정 조건과 실행 계획](experiments.md#실험-11-구매-100행과-9만-행인-상품의-sum-한-번)

## 구매 행 비교 Writer의 적용 기록 제거와 DB 부하 재측정

비교용 Writer에서 구매별 적용 기록 테이블 생성과 INSERT를 제거했다. 고정 스냅샷을 복합 키 순서로 읽어 Chunk마다 상품별 증가분만 UPSERT한다. 네 줄 재시작 테스트에서 상품 7의 점수는 실패 직후 2점, 같은 JobInstance 복구 후 5점, 같은 결과 영역에 새 JobInstance로 전량 재실행한 뒤 10점이었다.

Docker Desktop에서 세 가지 10만 행 분포를 두 방식으로 두 번씩 실행했다. 12회 모두 전 상품 점수와 순위가 독립 SQL과 일치했고 적용 기록 INSERT는 0회였다. 점수 계산 SQL의 CPU·검사 행, InnoDB 읽기·redo와 실행 순서를 함께 기록했다. 배치 실험 테스트 두 건, 테스트 Checkstyle과 API ArchUnit이 통과했다. [실험 12](experiments.md#실험-12-구매별-적용-기록을-제거한-뒤의-db-부하)

## 잘못된 입력 확인과 상품별 합계의 인덱스 지정

- 입력 고정 Step이 복사 직후 같은 TX에서 복사본의 수량 0 이하 행을 세고, 있으면 실패한다([ADR 0090](../adr/0090-reject-non-positive-quantity-in-frozen-input.md)). `WeeklySnapshotFreezeTest.rejectsNonPositiveQuantity`가 실패, 메시지, snapshot·입력·점수가 남지 않음을 확인한다.
- 상품별 합계 조회(`WeeklyRankingStore.productQuantityTotal`)에 `idx_ranking_snapshot_input_product`를 지정했다([ADR 0091](../adr/0091-force-product-index-for-product-total.md)). 비교용 구매 행 Job에는 PRIMARY를 지정하는 선택지를 추가했다.
- `StatsFreshnessExperimentTest.comparesAfterIndexHint`(실험 15): 빈 테이블에 넣은 직후에도 1천 상품 × 50행의 상품 ID 방식이 약 0.5초, 검사한 행 100,009행으로 `ANALYZE` 뒤와 같았다.
- 검증: `:apps:commerce-batch:check` 31건과 Checkstyle 통과. `ReaderSqlExperimentTest`, 실험 15 통과.

## 멈춘 실행 정리, Step 안 재시도 정책, 한국 시간 경계

- 정리 SQL `apps/commerce-batch/src/main/resources/runbook/mark-execution-failed.sql`을 추가했다. 실행 기록을 `FAILED`로 바꾸고 `VERSION`을 1 올린다([ADR 0092](../adr/0092-clear-stuck-execution-with-version-bump.md)).
- `StuckExecutionRecoveryTest`(2개)는 이 파일을 그대로 실행한다.
  - `STARTED`로 남은 실행 → 재실행 거부 → 정리 SQL → 같은 JobInstance가 상품 9만 읽고 완료
  - 정리했지만 살아 있던 실행 → 다음 chunk commit이 "wrong version" → "JobRepository failure forcing rollback"으로 실패, 상품 9 점수 없음 → 그 실행은 `UNKNOWN`으로 끝남 → 정리 SQL을 한 번 더 실행하고 재시작
- Step 안에서 재시도·건너뛰기를 쓰지 않는 이유를 문서로 정했다([ADR 0093](../adr/0093-no-retry-or-skip-inside-steps.md)). 코드 변경은 없다.
- 기간이 UTC라 한국 시간으로는 월요일 09:00에 주가 바뀐다는 점을 설계 1절과 API 계약에 적었다. 기간을 한국 시간으로 자를지, 공개 전 메인 화면에 무엇을 보여 줄지는 정하지 않았다.

## 순위 중복 방지

- `ranking_entry`의 순위 인덱스를 `UNIQUE KEY uk_ranking_entry_rank (snapshot_id, ranking)`로 바꿨다([ADR 0094](../adr/0094-enforce-unique-rank-per-snapshot.md)).
- `WeeklySnapshotFreezeTest.rejectsDuplicateRanking`: 같은 snapshot에 순위 1을 두 번 넣으면 `DuplicateKeyException`.
- 검증: commerce-batch `check` 34개 통과(skip 0), commerce-api `WeeklyRanking*` 8개 통과.


## 공개 검증 범위와 실험 재현 SQL 정리

- 공개 검증은 상품 수·전체 점수 합·입력 checksum까지 확인하고 상품별 점수는 대조하지 않는다. 이 범위를 유지하고 설계 6절에 명시했다.
- `ScoreDistributionExperimentTest`·`StatsFreshnessExperimentTest`(실험 13·14)는 인덱스 지정 전 측정이다. 실제 Step의 상품별 SUM을 힌트 없는 SQL로 바꿔 실행하고, 실행 계획도 같은 SQL로 보도록 재현 코드를 맞췄다. 실험 15는 실제 Step의 SQL을 그대로 쓴다. 기록된 측정값은 당시 실행 결과이며, 이 변경 뒤 실험을 다시 실행하지는 않았다(컴파일과 commerce-batch `check` 통과).

## 점수 Reader의 재시작 복원 정리

- `SnapshotProductPagingReader`에서 이전 Cursor checkpoint를 페이지 키로 복원하던 코드와 `OFFSET` 조회를 지웠다. 저장된 페이지 크기와 chunk 크기가 다르면 읽기 전에 실패한다([ADR 0095](../adr/0095-reject-restart-with-different-chunk-size.md)).
- `RankingJobRestartTest.pagingResumesAJobThatFailedWithCursor`(2개)를 지우고 `rejectsRestartWithDifferentChunkSize`를 추가했다. chunk 2로 상품 7·8을 커밋하고 실패 → chunk 3 Reader로 재실행하면 "원래 chunk 크기로" 메시지로 FAILED, stage 7·8 그대로 → 원래 Job으로 재실행하면 상품 9만 읽고(readCount 1) 순위 7:5:1, 9:5:2, 8:1:3.
- 검증: commerce-batch `check` 33개 통과(skip 0).

