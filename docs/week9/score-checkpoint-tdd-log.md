# W9 점수 계산·checkpoint 재시작 TDD 기록

> 이 문서는 해당 단계 당시의 기록이다. 이후 단계와 세 앱 전체 회귀의 최신 판정은 [6단계 최종 검증](final-verification.md)을 따른다.

2026-11-19, `volume-9`의 **3단계** 구현이다. [2단계 입력 고정](snapshot-freeze-tdd-log.md)을 실제 Spring Batch에 연결하고, 상품별 점수와 영속 처리 위치를 같은 DB TX에서 저장·복구한다. 사용자 선택 A·고정 기간/revision·단일 동기 실행 범위는 유지한다.

현재 `weeklyRanking`은 **입력 고정 → 점수 stage 저장**의 두 Step이다. Job `COMPLETED`는 이 두 단계의 완료이며 고객에게 랭킹이 공개됐다는 뜻이 아니다. 순위·결과 checksum·공개 pointer·HTTP는 4단계 이후다.

## 구현과 책임

| 책임 | 파일·동작 |
| --- | --- |
| Job 조립 | [WeeklyRankingJobConfig](../../apps/commerce-batch/src/main/java/com/loopers/batch/job/weeklyranking/WeeklyRankingJobConfig.java): `freezeWeeklyInput` → `calculateWeeklyScores`. 기존 Boot JDBC JobRepository와 동일 DataSource·JpaTransactionManager 사용 |
| 고정 작업 식별 | [WeeklyRankingJobParameters](../../apps/commerce-batch/src/main/java/com/loopers/batch/job/weeklyranking/WeeklyRankingJobParameters.java): identifying `periodStart` String·`revision` Long·`snapshotId` String 세 값. 기간·양의 revision에서 파생한 ID와 일치해야 함. 임의 run.id·시각·실패 플래그는 받지 않음 |
| Reader | 이름 `weeklyRankingProducts`, `saveState=true`, frozen 입력의 `DISTINCT product_id ASC`. 실제 영속 key는 `weeklyRankingProducts.read.count` |
| Processor | [RankingScoreStore](../../apps/commerce-batch/src/main/java/com/loopers/domain/ranking/RankingScoreStore.java)의 합산 책임. 현재 source가 아닌 같은 snapshot의 quantity만 합산 |
| Writer | [JdbcRankingScoreStore](../../apps/commerce-batch/src/main/java/com/loopers/infrastructure/ranking/JdbcRankingScoreStore.java): manifest `FOR UPDATE` → `FROZEN/CALCULATING` 확인 → 해당 frozen 합계와 대조 → 절대 점수 upsert |
| 점수·DDL | [ProductScore](../../apps/commerce-batch/src/main/java/com/loopers/domain/ranking/ProductScore.java), [002 DDL](../../apps/commerce-batch/src/main/resources/db/schema/002-weekly-ranking-score.sql): 양수 long/BIGINT, `(snapshot_id,product_id)` PK·FK·CHECK, 기존 입력을 보존하는 추가 schema |
| 재시작 설정 | [application-weekly-ranking.yml](../../apps/commerce-batch/src/main/resources/application-weekly-ranking.yml): 명시적으로 선택하는 profile. Batch schema 초기화 never·JPA none·SQL 초기화 never |

manifest의 새 `calculation_state`는 기본 `CALCULATING`이다. 두 Step이 성공해도 이 값은 유지하며, `COMPLETE` 전환과 공개는 후속 publisher가 맡는다. Writer는 `COMPLETE` snapshot에 같은 값이라도 다시 쓰지 않는다. 기존 입력과 완료 결과를 수정할 수 있는 일반 API를 추가하지 않았다.

SQL SUM은 BigDecimal로 받은 뒤 `longValueExact()`로 변환한다. int 범위를 넘는 정상 합은 보존하고 long 범위 초과는 실패로 드러낸다. `score += total`이 아니라 `score = total`로 저장하므로 같은 상품 재처리도 `5 → 5`로 수렴한다. Writer의 합계 재검사는 상품마다 추가 조회 비용이 들며 이번 작업에서 성능 최적화를 측정한 것은 아니다.

## 실제 TX와 실패 경계

```text
freeze Step: 한 RR TX → 입력 + manifest commit

calculate Step (chunk=2):
  상품 7·8 읽기 → 점수 5·1 저장 + reader 위치 2 → 함께 commit
  상품 9 처리 → 실패 → 이번 chunk만 rollback

새 Spring context, 같은 DB·동일 parameter:
  완료한 freeze Step 건너뜀
  저장된 reader 위치 2 읽기 → 상품 9 점수 5 저장 → commit
```

freeze의 바깥 Step TX부터 RR을 설정해 내부 annotation으로 기존 격리를 바꿀 수 있다고 가정하지 않는다. 계산 Step도 같은 실제 transaction manager를 사용한다. Cursor reader가 별도 읽기 연결을 사용하는 것과 **writer·ExecutionContext SQL이 같은 TX 연결에 참여해야 하는 것**은 구분한다.

실제 Job 테스트는 Boot의 `JpaTransactionManager`와 그 DataSource를 확인하고, DataSource 관찰기가 실제 PreparedStatement를 실행한 뒤 연결을 기록한다. 점수 INSERT와 `BATCH_STEP_EXECUTION_CONTEXT` UPDATE가 같은 checkout 연결에서 실행되는지 확인했다. 실제 commit/rollback 때 관찰을 종료해 rollback 후 별도로 저장하는 실패 metadata를 이전 chunk와 섞지 않는다.

RR 설정에는 Spring Batch `TaskletStep`의 **모든 실패에 대한 rollback 규칙**도 유지했다. 일반 `DefaultTransactionAttribute`만 사용하면 checked exception에 대해 rollback하지 않아 Job이 잘못 완료될 수 있었다. 실제 writer SQL 뒤 `IOException`을 전달하는 테스트에서 이를 재현한 뒤 `rollbackOn(Throwable)=true`를 보존하도록 수정했다.

## TDD 과정

| 증분 | 실제 관찰 |
| --- | --- |
| 첫 환경 확인 | Docker가 꺼져 DB/probe가 초기화에 실패했다. 이는 Red 증거로 세지 않으며 Docker 시작 뒤 재실행 |
| 작은 probe | 실제 MySQL·Boot JDBC JobRepository에서 7·8 저장 후 9 직전 실패. 위치 2를 JobExplorer로 읽고 새 reader·같은 instance로 9만 INSERT. probe의 유일 키가 앞 두 상품의 재처리를 오류로 드러냄 |
| 점수 Red | 도메인 12개 실패, DB 11개 실패. DB 중 10개는 stub 동작, CHECK 테스트 1개는 Spring 예외 분류에 대한 잘못된 테스트 가정 |
| 고정 parameter Red | 구현 stub에서 35개 실패. 구현 후 동일 기대값 유지 |
| 실제 Job Red | 작업을 하지 않는 조립 scaffold에서 5개 실패. 실제 DB 준비와 Boot 연결은 성공했고 점수·재시작·입력 검증 기대값이 실패 |
| 관찰·DB 제약 보완 | MySQL CHECK는 `HY000/3819`로 전달돼 일반 DataIntegrityViolationException 가정이 맞지 않았다. 제약 이름과 실제 MySQL 코드 3819·FK 1452를 검증하도록 구체화. 잘못된 값 거절과 저장 0건 조건 유지 |
| checked exception Red | 최초 repository 경유 주입은 UndeclaredThrowableException으로 감싸져 위험을 드러내지 못했다. 테스트 전용 ItemWriter wrapper가 **기존 production writer 실행 후** IOException을 직접 전달하자 `FAILED` 기대에 `COMPLETED`가 관찰됨. RR 설정에 Batch 기본 rollback 규칙을 보존하도록 수정 |

실패 스위치·SQL 관찰기·writer wrapper는 테스트에만 있다. 생산 API나 Job 식별 parameter에 우회 기능을 추가하지 않았다. 기존 기대값·시간 제한·lint·ArchUnit 규칙을 삭제하거나 완화하지 않았다.

## 검증한 결과

| 장면 | 기대값·관찰 |
| --- | --- |
| 정상 합산 | 원문 4개 활동·3개 상품, `7=5`, `8=1`, `9=5`. 같은 주문의 두 품목 모두 반영 |
| 같은 작업 재시작 | 첫 실패에서 stage `(7,5),(8,1)`, 영속 위치 2. context를 닫고 새로 열어 동일 instance·새 execution·첫 처리 상품 9만 확인. 재시작의 StepExecution에는 계산 Step만 존재 |
| 입력 불변 | 실패 후 지난주 구매를 추가해 source 5행이 돼도 재시작 입력은 원래 4행·checksum 그대로. 새 context의 원본 복사 호출 0, 정상 비교 snapshot과 최종 점수 같음 |
| writer SQL 뒤 RuntimeException | 상품 9의 실제 INSERT를 같은 TX에서 확인한 뒤 실패. 바깥 조회에는 7·8만 남고 checkpoint 2. 9를 쓴 연결 rollback 확인 |
| writer SQL 뒤 IOException | 실제 writer가 완료한 직후 checked exception 전달. 직접 IOException 실패 기록·FAILED·점수 rollback·checkpoint 2·같은 instance 재개 확인 |
| checkpoint SQL 뒤 실패 | 실제 UPDATE 실행 후, commit 전에 SQLException 주입. 점수와 위치 모두 직전 commit 상태. writer/checkpoint 연결 일치·rollback 확인 |
| 절대 점수 재쓰기 | 같은 snapshot·상품을 두 번 쓰면 한 행·같은 5점. 다른 snapshot의 같은 상품은 독립 |
| 수치·데이터 오류 | `Integer.MAX_VALUE+1` 정상 저장. SQL SUM의 Long.MAX_VALUE 초과, 없는 상품·다른 snapshot·틀린 합·완료 snapshot·무TX를 거절 |
| 빈 주·완료 instance | 입력 0건·점수 0건으로 두 Step 완료. 이미 완료한 instance를 재실행하면 기존 완료 오류, 새 execution 추가 없음 |
| 초기화 방지 | weekly-ranking / local,weekly-ranking / test,weekly-ranking의 실제 ConfigData 로딩에서 초기화 OFF 확인. DB bean을 만들지 않는 설정 테스트 |

큰 합계 경계 테스트에서는 소유한 테스트 DB의 입력 quantity만 잠시 BIGINT로 넓혀 SQL SUM 범위 초과를 만들고 원래 INT로 복원한다. 생산 입력 DDL·수량 규칙을 넓힌 것이 아니다. 입력 checksum은 원문 4행의 독립 기대값 `4a00f7d7a6421c8e457a59012754f53ca6069dc391e14c582fc7e551bab60863`과 대조한다. 순위와 결과 checksum 검증은 후속이다.

주요 테스트: [작은 probe](../../apps/commerce-batch/src/test/java/com/loopers/job/ranking/WeeklyRankingCheckpointProbeIntegrationTest.java), [실제 Job·새 context 재시작](../../apps/commerce-batch/src/test/java/com/loopers/job/ranking/WeeklyRankingJobIntegrationTest.java), [점수 DB](../../apps/commerce-batch/src/test/java/com/loopers/application/ranking/WeeklyRankingScoreStoreIntegrationTest.java), [고정 parameter](../../apps/commerce-batch/src/test/java/com/loopers/job/ranking/WeeklyRankingJobParametersTest.java), [profile](../../apps/commerce-batch/src/test/java/com/loopers/job/ranking/WeeklyRankingProfileTest.java).

## 최종 검사와 실행 증거

```bash
./gradlew :apps:commerce-batch:check --console=plain
```

2026-11-19 21:26 KST 검사에서 **13개 suite·117개 테스트**, 실패·오류·skip **모두 0**, Batch main/test Checkstyle **위반 0**, ArchUnit **통과**, **BUILD SUCCESSFUL·종료 코드 0**을 확인했다. 2단계까지의 49개와 이번 점수·parameter·probe·Job·profile의 68개가 포함된다. Gradle 출력의 42초는 검사 실행 시간이며 처리 성능 수치가 아니다. API·streamer 전체 회귀를 이번에 다시 실행한 것은 아니다.

같은 실행의 실제 재시작 로그는 다음과 같다. ID는 이 격리 실행의 관찰값이며 다음 실행에 같은 숫자를 강제하지 않는다.

| 항목 | 첫 실패 | 재시작 |
| --- | --- | --- |
| snapshot | `weekly-20260817-r2` | 동일 |
| JobInstance ID | 6 | 6 |
| JobExecution ID | 9 | 10 |
| 계산 StepExecution ID | 15 | 16 |
| 상태 | FAILED | COMPLETED (두 Step 범위) |
| 저장 위치·처리 | `weeklyRankingProducts.read.count=2`, stage 7·8 | 처리 상품 `[9]`, 최종 stage 7·8·9 |
| SQL 연결 | 앞 chunk의 writer/checkpoint 연결 일치 | writer/checkpoint 관찰 번호 `15/15` |

세 rollback 사례에서 상품 9를 쓴 연결 관찰 번호는 33이었고 모두 rollback을 확인했다. checkpoint 뒤 실패 사례는 점수/checkpoint 모두 `33/33`에서 실제 SQL이 실행된 뒤 rollback됐으며, 영속 위치는 2·보존 상품은 7·8이었다. 이 번호는 테스트 관찰기가 각 connection checkout에 붙인 값이며 MySQL 서버의 CONNECTION_ID가 아니다.

로그는 `/private/tmp/w9-score-checkpoint-final-check.log`, suite 요약은 `/private/tmp/w9-score-checkpoint-final-check.json`, [HTML 보고서](../../apps/commerce-batch/build/reports/tests/test/index.html)에 남겼다. 실제 ID·rollback 로그는 해당 Job 테스트의 JUnit XML system-out에도 있다. 임시 로그와 build 보고서는 후속 실행에서 지워지거나 바뀔 수 있어 위 핵심 값을 문서에 보존했다.

테스트는 소유한 MySQL과 기존 Batch의 Testcontainers fixture만 사용했다. 사용자 DB·공유 Redis·기존 topic/group에 접근하지 않았고 데이터·볼륨 일괄 삭제도 하지 않았다. 각 새 context와 연결 풀을 닫고 소유 컨테이너를 정리했다. 커밋·푸시는 하지 않았다.

## 실행 준비와 남은 범위

재시작할 DB에는 기존 구매 활동 schema, `001-weekly-ranking-input.sql`, `002-weekly-ranking-score.sql`, 사용 라이브러리 Spring Batch 5.2.2의 `org/springframework/batch/core/schema-mysql.sql`이 최초 한 번 준비돼 있어야 한다. 이번 테스트도 처음에만 준비하고 **같은 작업의 두 실행 사이에는 입력·stage·metadata를 지우지 않았다.** 사용자 DB에는 적용하지 않았다.

운영 명령을 별도로 실행한 것은 아니다. 앞으로 명시 실행을 연결할 때는 의도한 **구매 활동 DB** 설정과 `weekly-ranking` profile을 사용한다. 여러 profile이면 마지막에 두며 기존 local/demo 기본값을 재시작용으로 착각하지 않는다. Job 인자의 형태는 다음과 같고, snapshotId는 기간·revision의 파생값과 일치해야 한다.

```text
periodStart=2026-08-17
revision=1,java.lang.Long
snapshotId=weekly-20260817-r1
```

기존 demo·공유 JPA 설정은 변경하지 않았다. 한 전용 실행 주체가 동기 JobLauncher로 순서대로 실행하는 초기 범위이며, 다중 JVM의 같은 주 동시 실행을 자동 조율한다고 주장하지 않는다. JVM 강제 종료로 STARTED/UNKNOWN에 남은 execution의 운영 복구와 공개 commit 뒤 metadata 공백은 이번 FAILED 재시작 증거와 별개다.

남은 단계는 **4단계 완성 순위·공개 pointer·고객 API → 5단계 실제 PG/Outbox/Kafka/streamer부터의 전체 연결 → 6단계 최종 회귀·제출 정리**다. 현재 두 Step의 COMPLETED를 W9 전체 완료나 공개 완료로 해석하지 않는다.
