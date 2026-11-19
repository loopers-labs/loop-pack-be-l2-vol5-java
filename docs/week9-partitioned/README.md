# 주문 파티션별 주간 구매 랭킹

기준 구현은 C05 `7cd9f0b`다. C07 `5e27dcb`의 단일 Job과 주문 이벤트 기반 실행 요청은 원본 브랜치 `volume-9/weekly-ranking`에서 비교할 수 있다. 이 구현에서는 Kafka 소비자가 구매 활동을 적재하고 Batch 인스턴스들의 스케줄러가 주간 계산을 시작한다.

두 브랜치 구현의 차이는 다음 명령으로 확인한다.

```powershell
git diff --stat volume-9/weekly-ranking volume-9/partitioned-weekly-ranking
```

기존 DB와 Spring Batch 재시작 이력을 전환할 때는 [명칭 변경과 기존 데이터 이관](cutover.md)을 따른다.

## 입력과 점수

주간 소속은 최초 주문 확정 시각의 UTC 월요일 00:00 이상, 다음 월요일 00:00 미만이다. 월요일 00:01 UTC부터 지난주를 계획한다. 첫 기동은 최근 마감 주부터, 이후에는 자동 계획이 빠진 주를 따라잡는다. 계획 생성 때 가동 중인 서버 수와 서버별 로컬 상품 파티션 수를 저장한다. 주문당 구매 품목 행 수를 고려한 주문 파티션의 주문번호 경계가 같은 주문의 품목을 함께 묶는다. 새 서버는 기본 담당자의 생존 신호가 만료된 READY 주문 파티션을 인수할 수 있다. 다음 revision에서는 현재 가동 수로 주문 파티션 수를 다시 정한다.

각 주문 파티션은 자기 읽기 시점에 독립적으로 입력을 고정한다. A가 고정된 뒤 저장된 A 주문은 원래 주의 다음 revision에 들어간다. B가 아직 고정 전이라면 같은 시각에 저장된 B 주문은 현재 revision에 들어갈 수 있다. 주간 snapshot은 이 고정 입력들의 합집합이다. 공개 API의 `frozenAt`은 마지막 주문 파티션 고정 시각이다. 입력 checksum은 전체 입력을 주문번호·상품번호 순으로 직렬화해 구하므로 주문 파티션 개수에 영향을 받지 않는다.

| 단계 | 저장 및 재실행 기준 |
|---|---|
| 스케줄러 | MySQL에 주차·revision별 snapshot 하나와 N개의 주문 파티션을 등록한다. 실행권에는 담당자와 세대 번호가 있다. |
| 주문 파티션 Job | snapshot ID와 주문 파티션 번호가 식별 parameter다. 성공한 freeze는 건수·checksum과 입력 행을 보존한다. |
| 로컬 worker | 고정된 주문 파티션의 상품을 상품 ID 경계에 따라 로컬 상품 파티션으로 나눈다. 로컬 Step마다 영속 reader 체크포인트가 있고, 부분 점수와 같은 DB 트랜잭션에서 커밋한다. |
| 부분 점수 | 키는 `(snapshot_id, order_partition_id, product_id)`다. 최초 값을 유지하며, 동일 값 재시도는 성공한다. 다른 값 재시도는 별도 트랜잭션의 충돌 원장에 남고 해당 revision의 공개가 보류된다. |
| 전역 공개 | 모든 주문 파티션이 완료된 후 입력과 주문 파티션별 checksum을 확인한다. 상품별 부분합을 합쳐 입력의 상품별 실제 수량과 검산하고 순위·완성 기록·포인터를 함께 커밋한다. 동점은 상품번호 오름차순이다. |

## 실행 준비

Java 21, MySQL 8.0과 기존 구매 활동 스키마를 사용한다. 새 전용 DB에는 상거래 DDL 013까지 적용한 뒤 `docker/week9/001-batch-metadata-mysql.sql`, `docker/payment-lab/014`~`018`의 랭킹 DDL을 순서대로 한 번 적용한다. C05 스키마가 준비된 DB에는 새 017·018만 적용한다. 원본 C06/C07의 `017-weekly-ranking-requests.sql`과 새 `017-weekly-ranking-plans.sql`의 용도가 다르므로 파일명을 확인한다. 구버전과 새 버전의 비교 실행은 각각 격리된 DB를 사용한다.

`COMMERCE_MYSQL_JDBC_URL`, `COMMERCE_MYSQL_USER`, `COMMERCE_MYSQL_PASSWORD`에 같은 보존 DB의 연결 정보를 제공한다. 각 인스턴스에서 Batch JAR를 다음과 같이 실행한다. Redis 연결은 기존 `ranking-preserve` 프로필 설정을 따른다.

```powershell
.\gradlew.bat :apps:commerce-batch:bootJar
$batchJar = Get-ChildItem 'apps/commerce-batch/build/libs/commerce-batch-*.jar' |
    Where-Object Name -NotLike '*-plain.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$profiles = '--spring.profiles.active=ranking-preserve,ranking-scheduler'
java -jar $batchJar.FullName $profiles --ranking.scheduler.local-grid=2 --ranking.weekly.local-workers=2
```

기본 node ID는 실행마다 생성한다. 이름을 지정할 때는 인스턴스마다 구분되는 `--ranking.scheduler.node-id`를 사용한다. `local-grid`는 계획에 저장할 로컬 상품 파티션 수, `local-workers`는 이 JVM에서 동시에 실행할 스레드 수다. 커서 reader와 청크 트랜잭션은 별도의 연결을 사용할 수 있다. 상주 모드의 Hikari 최대 풀 크기는 `2 × local-workers + 3` 이상으로 설정한다. 예를 들어 worker 2개면 최소 7개다. 이는 streaming reader와 청크 트랜잭션 외에 heartbeat·조율·독립 충돌 기록에 필요한 연결을 확보하기 위한 시작 조건이다.

실행 중 새 서버가 합류해도 기존 revision의 주문 파티션 수는 고정된다. 두 노드 동시 처리 실험에서는 두 프로세스에 `--ranking.scheduler.auto-plan=false`를 주고 `weekly_ranking_members`에서 두 생존 신호를 먼저 확인한 뒤 아래 request 모드로 해당 주 revision 1을 생성한다. 일반 자동 운영에서는 정해진 시각에 이미 등록된 노드가 그 주의 분배에 참여한다.

### 새 revision 요청

상주 인스턴스가 등록된 동일 DB에서 실행한다. 기본 자동 실행은 revision 1을 만든다. 늦은 활동을 반영할 때는 마지막 공개 revision의 다음 번호를 명시한다. 충돌로 종료된 revision은 증거를 보존한 채 원인을 확인하고, 새 입력으로 다시 계산할 때 그 다음 번호를 명시한다. 동일 기간의 열린 계획은 먼저 완료하거나 처리 결과를 확인한다. 예시 주차는 2026-08-17에 시작한다.

```powershell
java -jar $batchJar.FullName $profiles --ranking.scheduler.mode=request --ranking.scheduler.request-period=2026-08-17 --ranking.scheduler.request-revision=2
```

고객 조회는 `GET /api/v1/rankings/weekly?periodStart=2026-08-17&limit=10`이다. limit은 기본 10, 범위 1~100이다. 미공개는 `NOT_PUBLISHED`, 구매 0건 완성본은 snapshot ID가 있는 `PUBLISHED`다. 상품·브랜드가 비공개인 경우 저장 순위의 간격을 유지한다.

## 중단된 주문 파티션 인수

1. `weekly_ranking_plans`, `weekly_ranking_order_partitions`, `weekly_ranking_score_conflicts`에서 대상 주·revision·주문 파티션·담당자·세대·실행 ID·고정 여부를 확인한다. 운영 환경에서 이전 JVM의 종료를 독립적으로 확인한다. heartbeat 만료만으로 실행 종료를 판정하지 않는다.
2. 스케줄러는 최초 Job 생성에서 MySQL 교착 롤백(1213)이 확정되고 생존한 담당자·세대가 일치하며 Batch 실행, 고정 입력, 부분 점수, 충돌 기록이 모두 없을 때만 다음 tick에서 자동으로 다시 시작한다. 최초 시도를 포함해 세 번까지 허용한다. 그 밖의 불확정 상태에서는 `BATCH_JOB_EXECUTION`과 `BATCH_STEP_EXECUTION`에서 마지막 실행과 로컬 Step 상태를 대조한다. 충돌이 기록되면 기존 부분 점수, 시도값, 해당 고정 입력을 보존해 원인을 조사한다. 재실행 가능한 일반 실패는 같은 JobInstance에서 자동 재시도한다.
3. 종료가 확인된 대상에 한해 아래 단건 명령을 실행한다. 예시의 변수는 위 DB 조회에서 확인한 실제 값이다. 코드가 소유권·세대·최신 실행 ID·영속 체크포인트와 저장 점수를 다시 대조한다. 실행 ID 0은 아직 Batch 실행이 생성되지 않은 경우다.

```powershell
java -jar $batchJar.FullName $profiles --ranking.scheduler.mode=recover `
  "--ranking.scheduler.snapshot-id=$snapshotId" "--ranking.scheduler.order-partition-id=$orderPartitionId" `
  "--ranking.scheduler.owner-id=$oldOwner" "--ranking.scheduler.generation=$generation" `
  "--ranking.scheduler.execution-id=$executionId" --ranking.scheduler.termination-confirmed=true `
  "--ranking.scheduler.reason=$terminationEvidence"
```

4. 다음 스케줄러가 같은 주문 파티션을 인수했는지, JobInstance ID가 유지되고 JobExecution만 새로 생겼는지 확인한다. 이전 공개본은 새 revision의 모든 주문 파티션이 완료되어 검산을 통과할 때까지 조회할 수 있다.

```sql
SELECT p.period_start, p.revision, p.state AS plan_state, s.*
FROM weekly_ranking_plans p JOIN weekly_ranking_order_partitions s USING (snapshot_id)
WHERE p.period_start = '2026-08-17' ORDER BY p.revision, s.order_partition_id;

SET @snapshot_id = (SELECT snapshot_id FROM weekly_ranking_plans
                    WHERE period_start = '2026-08-17' AND revision = 1);
SELECT snapshot_id, order_partition_id, product_id, stored_score, attempted_score, generation, recorded_at
FROM weekly_ranking_score_conflicts WHERE snapshot_id = @snapshot_id;
```

## 검증

[점수·재시작·공개 검증 기록](verification.md)과 [세 가지 JVM·worker 구성의 성능 측정](benchmark.md)에서 실측값과 재현 방법을 확인할 수 있다.

```powershell
.\gradlew.bat :apps:commerce-batch:check :apps:commerce-api:check :apps:commerce-streamer:check --continue
```

두 JVM의 같은 상품 2점·3점과 B의 동일값 재전달은 `WeeklyRankingEqualScoreReplayProcessTest`에서 재현한다. [실행 ID와 MySQL 쓰기 기록](verification.md)을 확인할 수 있다.

`OrderPartitionScoreConsistencyTest`는 서로 다른 MySQL 트랜잭션에서 같은 상품의 2점·3점을 쓰고, 같은 값과 다른 값의 재시도 후 저장된 두 행과 충돌 원장을 검사한다. `OrderPartitionedPublicationTest`는 동시 공개·검산 실패·기존 포인터 보존을 검사한다. `WeeklyRankingOrderPartitionJobTest`는 로컬 worker의 청크와 영속 재시작을 검사한다.
