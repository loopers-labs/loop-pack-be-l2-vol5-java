# 주간 랭킹 정합성 검증

## 주차·revision 공통 입력 고정 (계약 3)

실제 MySQL에서 계약 2를 대상으로 공통 입력과 계획 생성 회귀 5건을 먼저 실행했다. 5건이 모두 실패했다. 구간 A가 끝난 후 양쪽에 새 구매를 넣자 B가 새 상품 8의 30점까지 읽었다. 계약 3에서 같은 시험을 실행한 결과, 계획 직후와 세 개의 주문 파티션 Job 종료 후의 입력은 모두 `[42:7:2, 44:7:3]`이었다. 세 파티션 모두 같은 `frozen_at`을 가리켰고 부분 점수에는 2와 3이 한 번씩 남았다.

| MySQL 관측 시점 | 확인한 결과 |
|---|---|
| 첫 계획의 복사 SQL이 첫 행에서 대기 중 | 다른 연결에서 새 주문 11을 커밋했다. 두 번째 계획은 같은 주차의 공개 포인터 행 잠금을 기다렸다. 어느 연결에도 미완료 계획·복사 행은 보이지 않았다. |
| 두 계획 호출 종료 | 같은 snapshot 한 개에 기존 주문 10과 20만 있었다. 경계는 20, 양쪽 건수는 1건씩, 전체는 2건이었다. |
| 복사 후 두 번째 주문 파티션 저장에서 강제 실패 | 계획·입력·경계·스케줄 커서가 함께 롤백됐다. 같은 revision을 재요청해 성공했다. |
| 구매 0건 | 계획 생성 트랜잭션이 빈 입력의 SHA-256과 공통 시각까지 저장했다. |
| 계약 2의 열린 계획 | 계약 3 Job의 실행권 연결 및 재고정이 차단됐다. 구버전에서 종료한 후 다음 revision을 계약 3으로 생성했다. |

위 사례와 재시작·공개를 포함한 실제 DB 집중 검사 25건에서 실패·오류·건너뜀이 각각 0건이었다. [계획 생성·경쟁·롤백](../../apps/commerce-batch/src/test/java/com/loopers/batch/job/ranking/coordination/WeeklyPlanGlobalFreezeTest.java) · [늦게 시작한 주문 파티션](../../apps/commerce-batch/src/test/java/com/loopers/batch/job/ranking/coordination/WeeklyRankingCommonInputTest.java)

### 주문 42·43·44, 청크 2의 실제 재시작

MySQL에 주문 42의 상품 7×2·8×1, 주문 43의 상품 7×3, 주문 44의 상품 9×5를 준비했다. 상주 Batch JVM(PID 18448)과 별도 고객 API를 기동했다. 첫 revision은 장애 없이 공개해 대조군으로 사용했고, 두 번째 revision에서는 실제 processor가 상품 9를 호출하기 직전에 장애를 주입했다.

| 두 번째 revision의 시점 | JobInstance / JobExecution | MySQL·API에서 확인한 상태 |
|---|---|---|
| 첫 실행 실패 | 2 / 2, `FAILED` | 부분 점수 `7:5, 8:1`, 최종 stage 0행, `READ_COUNT=3`, `WRITE_COUNT=2`. 영속 `weeklyRankingLocalProductPartitionProducts.read.count=2`. DB 포인터와 고객 API 모두 정상 revision 1. |
| 재시작 후 공개 | 2 / 3, `COMPLETED` | 재개한 processor의 호출은 상품 9 한 건. `READ_COUNT=1`, `WRITE_COUNT=1`. 추가 입력 INSERT를 DB에서 차단한 상태로 완료. 고객 API 순위는 상품 7(5점·1위), 9(5점·2위), 8(1점·3위). |

정상 snapshot은 `266b7d11-df2a-46d0-bc95-e178b0e206e2`, 재시작 snapshot은 `a5020477-4e96-4355-9850-a310356a68b3`이었다. 두 snapshot의 입력 SHA-256은 모두 `3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298`, 결과 SHA-256은 모두 `745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2`였다. 두 값 모두 고정 fixture의 정답과 일치했다. 실패 당시 관측한 공통 시각 `2026-11-19T18:49:56.498802Z`가 재시작 후 API의 `frozenAt`과 일치했다. [실제 스케줄러·체크포인트·고객 응답 검사](../../apps/commerce-streamer/src/test/java/com/loopers/application/ranking/WeeklyRankingChunkRestartFlowTest.java)

### 두 JVM에서 고정 후 도착한 실제 구매

실제 PG 확정, Outbox relay, Kafka consumer가 적재한 원본을 사용했다. 첫 주문 이벤트를 재발행한 뒤에도 Inbox 1건과 원래 구매 품목 수를 유지했다. revision 2에서 주문번호 경계는 3이고, 계산 JVM의 PID는 35056(A)과 32520(B)이었다. A가 작업을 끝내고 B가 계산 직전 대기하는 동안 낮은 주문 2의 지연 Outbox(상품 8×3)와 높은 신규 주문 6(상품 8×4)을 모두 소비했다.

| 시점 | 새 snapshot에 실제 저장된 두 늦은 주문 | 고객이 조회한 공개본 |
|---|---|---|
| 계약 2 대조 실행, B 재시작 후 | 주문 6만 포함 | revision 2 · 상품 7·8 모두 5점 |
| 계약 3, revision 2 계산 중 | 모두 제외, 공통 입력 총 5행 | revision 1 · 상품 7은 2점, 8은 1점 |
| 계약 3, B 재시작 및 revision 2 공개 | 모두 제외, 고정 건수·시각·SHA 동일 | revision 2 · 상품 7은 5점, 8은 1점 |
| 명시적 revision 3의 고정 후 B 계산 전 | 주문 2·6 각각 1행, 총 7행 | 여전히 revision 2, 같은 snapshot ID·순위 |
| revision 3 공개 | 주문 2·6 각각 1행, 과거 revision 2에는 0행 | revision 3 · 상품 8은 8점·1위, 7은 5점·2위 |

B의 JobInstance 3에서 JobExecution 3(`FAILED`)과 5(`COMPLETED`)가 실행됐다. revision 2의 전체 입력 SHA-256은 `639f87aa7a779061dbe094fb4fdeff96cff822ab3a633099a5b77c2cea8d3efa`, revision 3은 `2d7142055cdfa34303e95ab4fcb6eac1c8f1bd2817f0e222f619702486d41a60`이었다. 두 구간과 공개 API의 공통 시각은 각각 `2026-11-19T18:52:08.310891Z`와 `2026-11-19T18:52:29.311445Z`였다. [실제 구매·재시작·포인터 검증](../../apps/commerce-streamer/src/test/java/com/loopers/application/ranking/WeeklyRankingFlowTest.java)

## 같은 상품의 부분 점수

격리 MySQL 8.0.46에 C05의 실제 014·015 DDL을 적용했다. 상품 7에 두 주문 파티션의 부분합 2, 3을 기존 `ON DUPLICATE KEY UPDATE score=?`로 기록했다. 합계 5를 기대한 회귀 검사는 실제 1행·3점으로 실패했다.

새 017·018 DDL을 적용한 뒤 두 MySQL 세션에서 같은 snapshot·상품의 서로 다른 주문 파티션에 2와 3을 동시에 저장했다. B의 3을 다시 기록한 뒤 별도 조회 결과는 `rows=2 sum=5 values=2,3`이었다. 실제 `information_schema.statistics`에서 부분 점수 기본 키는 `snapshot_id,order_partition_id,product_id`, 최종 점수 기본 키는 `snapshot_id,product_id`였다.

| 검사 | 실제 DB에서 관찰한 값 |
|---|---|
| 같은 값 재시도 | 부분 점수 두 행 2·3, 합계 5. 개별 writer가 주간 최종 stage에 만든 행은 0개. |
| 다른 값 재시도 | 3 대신 4를 시도한 청크는 실패. 원래 3 유지. 청크 롤백 뒤 새 조회로 원장의 기존값 3·시도값 4 확인. |
| 오래된 소유자 | 세대가 바뀌거나 주문 파티션이 완료된 후 이전 실행의 쓰기 거절. 실패 청크의 행 수 0개. |
| 로컬 체크포인트 | 7번 상품 커밋 뒤 8번 청크의 checkpoint UPDATE 실패. 8번 부분 점수 롤백 및 체크포인트 1 보존. 새 소유자는 같은 JobInstance에서 해당 Step의 상품 8 한 건만 처리. |
| 동시 최종화 | 두 finalizer와 추가 재호출 후 상품 7의 5점·완성 기록 하나·공개 포인터 하나. |
| 검산 실패 | 새 revision의 잘못된 부분합은 공개 실패. 새 최종 stage 롤백, 이전 revision의 포인터 보존. |

주문 파티션별 검산을 추가하는 과정에서 부분합을 2·3에서 1·4로 바꾸는 반례도 사용했다. 전체 합은 양쪽 모두 5이므로 기존 전역 합계 비교만으로는 공개가 성공했다. 주문 파티션·상품별로 기대값과 실제값을 완전히 대조하도록 수정한 뒤 같은 테스트에서 새 stage와 포인터가 모두 0행이었다. 누락·추가된 상품도 같은 검산에 포함된다.

두 주문 파티션에 주문 42의 상품 7·8, 주문 43의 상품 7, 주문 44의 상품 9를 배치한 공개 시험에서 순위는 상품 7(5점·1위), 9(5점·2위), 8(1점·3위)였다. 완성 checksum은 `745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2`로 C05의 정답과 일치했다.

## 서로 다른 두 JVM의 2점·3점과 B의 재전달

계약 3에서도 A(PID 1472)가 주문 10의 2점을 쓰고, B(PID 35124)가 주문 20의 3점을 썼다. B의 같은 JobInstance 1에서 JobExecution 1과 3이 차례로 실행됐다. 세대 2의 재전달을 MySQL이 `oldScore=3, score=3`으로 기록했다. snapshot `7b5223e5-5cbf-48ee-8905-b07eb0af0ea5`에 부분 점수 두 행, 최종 점수 5 한 행, 공개 순위 1위 한 행을 확인했다.

[두 JVM의 동일 값 재전달 시험](../../apps/commerce-streamer/src/test/java/com/loopers/application/ranking/WeeklyRankingEqualScoreReplayProcessTest.java)은 B의 완료 worker에만 `allowStartIfComplete=true`를 적용해 같은 입력을 writer까지 다시 보냈다. 운영 기본값에서는 재시작 시 완료된 worker를 건너뛴다.

## 실행 중인 JVM 종료와 인수

상품 33개 중 16개의 부분 점수와 reader 위치 16이 커밋된 뒤 JVM을 종료했다. 잘못된 실행 ID로 인수한 요청은 거절됐다. 이전 JVM의 종료와 최신 실행을 확인한 뒤 다른 JVM이 같은 JobInstance에서 나머지 17개를 처리하고 전체 결과를 공개했다. [프로세스 인수 시험](../../apps/commerce-streamer/src/test/java/com/loopers/application/ranking/WeeklyRankingProcessRecoveryTest.java)

## 로컬 상품 파티션의 부분 점수 읽기

초기 병렬 실험에서 부분 점수 확인 쿼리가 이웃 worker가 소유한 행의 잠금까지 기다려 교착이 발생했다. 실제 MySQL 8.0의 `REPEATABLE READ`에서 오래된 읽기 시점을 만든 뒤 다른 트랜잭션이 상품 16을 커밋하고 무관한 상품 17을 잠그도록 재현했다. 원래 쿼리는 잠금 대기 1205, 일반 스냅샷 조회는 기대한 16건 중 15건이었다. 변경 후에는 청크의 상품 키로 전체 기본 키를 한 건씩 찾는 현재 읽기를 한 SQL로 수행한다. `EXPLAIN`은 `eq_ref`, `PRIMARY`, 예상 행 수 1을 보였고 조회 결과도 정확히 16건이었다. `OrderPartitionScoreConsistencyTest`에서 이 조건과 2점·3점 동시 저장, 동일값 재시도, 다른 값 충돌을 검사했다.

## 재시작 시 고정 입력과 주문번호 경계

기준 커밋 `4f2efbd`에서 회귀 테스트를 먼저 실행하고 수정 후 같은 사례를 MySQL 8.0에서 재실행했다. 고정 입력 손상과 revision 3 공개 1건, 연결 장애·재판정·동시 상태 변경 7건, 주문 경계 26건이다. 수정 전 34건 중 25건이 실패했고, 수정 후 34건 모두 통과했다.

### revision 2 손상과 revision 3 공개

revision 1은 주문 42에서 상품 7을 2개 구매한 결과를 공개했다. 주문 43의 구매 3개가 원본에 추가된 뒤 revision 2의 실제 worker에 쓰기 실패를 주입했다. 계약 2에서는 입력 고정 Step, 계약 3에서는 이미 고정된 입력의 검증 Step이 완료되고 worker와 Job이 실패했다. 이때 **revision 2에 복사된 주문 43의 수량만 3에서 30으로** 변경했다. 원본 수량은 계속 3이었다.

| 관측 시점 | 계획 / 주문 파티션 | 해당 revision의 JobExecution | 고객 공개 포인터·상품 7 |
|---|---|---|---|
| revision 1 공개 | `PUBLISHED / DONE` | 1건, ID 1 `COMPLETED` | revision 1 · 2점 · 1위 |
| 수정 전 revision 2, 두 번 점검 후 | `OPEN / RUNNING`, 사유 `NULL` | 1건, ID 2 `FAILED`, 추가 0건 | revision 1 · 2점 · 1위 |
| 수정 후 revision 2, 첫 번째와 두 번째 점검 | `CONFLICT / CONFLICT`, 불일치 사유 보존 | 1건, ID 2 `FAILED`, 추가 0건 | revision 1 · 2점 · 1위 |
| 운영자 모드로 revision 3 요청 직후 | `OPEN / READY` | 0건 | revision 1 · 2점 · 1위 |
| revision 3 계산·검산 후 | `PUBLISHED / DONE` | 1건, ID 3 `COMPLETED` | revision 3 · 5점 · 1위 |

수정 후 revision 2에는 부분 점수, 최종 점수, 순위, 완료 기록이 모두 0행이었다. 두 번째 점검과 revision 3 공개 뒤에도 손상된 입력과 사유가 보존됐다. 해당 파티션의 `last_error`는 다음과 같았다.

```text
FROZEN_INPUT_INTEGRITY_MISMATCH expectedCount=2 actualCount=2 expectedSha256=20652590a6fbcd5bf46d0a9f8257fc192829e57195e4311c0fcb278412e6c9b9 actualSha256=180311a51921ef7a02afd6801e0eb87636cdda424478f943795b72fd49c4215c
```

별도의 Spring Boot 운영 요청 컨텍스트에서 revision 3을 생성했다. 새 입력은 원본의 2개·3개와 일치했고, 기대 SHA-256도 복구됐다. 부분 점수와 최종 점수에는 상품 7의 5점이 각각 한 행, 공개 순위에는 1위 한 행이었다. [revision별 실제 경로와 DB 단언](../../apps/commerce-batch/src/test/java/com/loopers/batch/job/ranking/coordination/WeeklyRankingIntegrityRecoveryTest.java)

별도 MySQL 시험에서 첫 판정과 다음 판정 사이에 소유권 세대, 연결된 실행 ID, 최신 JobExecution 또는 입력 지문을 바꾸면 계획은 `OPEN`을 유지했다. 고정 입력 손상이 확정된 경우에는 대상 파티션이 `CONFLICT`, 미완료 형제가 `BLOCKED`가 됐다. 연결 장애 시험에서는 검증에 쓰는 실제 MySQL 세션 하나를 `KILL CONNECTION`으로 끊었다. 첫 점검은 `OPEN / RUNNING`과 기존 실행 1건을 유지했고 다음 점검에서 `OPEN / READY` 및 `RETRY_FAILED_EXECUTION`을 확인했다. [재판정·연결 장애 시험](../../apps/commerce-batch/src/test/java/com/loopers/batch/job/ranking/coordination/WeeklyOrderPartitionIntegrityRecoveryTest.java)

### 경계별 재시작

첫 주문 파티션은 43 미만, 마지막 주문 파티션은 43 이상이다. 상품 7을 커밋하고 두 번째 상품 처리에 실패시킨 뒤 영속 Step 컨텍스트를 변경했다. 각 사례에서 reader 위치는 1이었다. 원래 유한한 경계는 `Long(43)`으로 저장됐고 열린 쪽 키는 없었다.

| 체크포인트 조건 | 사례 수 | 수정 전 | 수정 후 |
|---|---:|---|---|
| 첫·마지막의 경계와 타입이 모두 정상 | 4 | 정상 재시작 | 같은 JobInstance에서 실행 1→2, 첫 파티션은 상품 7·8, 마지막은 7·9로 완료 |
| 유한한 경계 키 누락 | 6 | 새 실행 허용 | 자동·수동 모두 보류, 실행 1→1 |
| 유한한 경계값 변경 | 4 | 새 실행 허용 | 자동·수동 모두 보류, 실행 1→1 |
| 숫자는 43이나 타입이 Integer 또는 String | 8 | 새 실행 허용 | 자동·수동 모두 보류, 실행 1→1 |
| 열린 쪽에 예상 밖 경계값 존재 | 4 | 새 실행 허용 | 자동·수동 모두 보류, 실행 1→1 |

변조 22건 모두 수정 후 `CHECKPOINT_EVIDENCE_MISMATCH`로 `RECOVERY_REQUIRED`에 머물렀다. 이미 커밋된 상품 7과 기존 실행 ID가 보존됐다. 누락 6건에는 `RUNNING`에서 운영자가 직접 인수한 첫·마지막 파티션 두 건도 포함된다. 수정 전에는 마지막 파티션의 하한을 44로 바꾸거나 열린 상한에 42를 넣으면 상품 9의 부분 점수가 빠져도 주문 파티션이 `DONE`이 됐다. [경계별 실제 재시작 시험](../../apps/commerce-batch/src/test/java/com/loopers/batch/job/ranking/coordination/OrderPartitionRestartBoundsTest.java)

## 세 앱 전체 검사

2026-11-20 KST에 공통 고정 버전으로 `:apps:commerce-batch:check :apps:commerce-api:check :apps:commerce-streamer:check --continue`를 실행했다. 14분 13초에 `BUILD SUCCESSFUL`이었다. Batch 224건과 Streamer 28건을 새로 실행했고, API 609건은 기존 전체 검사 결과를 Gradle이 재사용했다. 세 모듈 JUnit XML 합계 **861건**, 실패·오류·건너뜀 및 Checkstyle 위반은 각각 0건이다. 새 스냅샷의 실제 고객 API 응답은 별도로 기동한 API 프로세스에 HTTP 요청을 보내 검증했다.

최초 Job 생성 시의 MySQL 교착(1213)과 실행 여부가 불명확할 때의 인수 조건은 [스케줄러 복구 절차](README.md#중단된-주문-파티션-인수)에 정리했다.

## 재현

저장소 루트의 PowerShell에서 실행한다. Docker 29와 Testcontainers 1.20.6으로 검증한 환경에서는 테스트 JVM에 Docker API 1.44를 지정했다.

```powershell
$env:JAVA_TOOL_OPTIONS = '-Dapi.version=1.44'
.\gradlew.bat :apps:commerce-streamer:test --tests '*WeeklyRankingChunkRestartFlowTest' --tests '*WeeklyRankingFlowTest' --tests '*WeeklyRankingEqualScoreReplayProcessTest' --tests '*WeeklyRankingProcessRecoveryTest'
.\gradlew.bat :apps:commerce-batch:test --tests '*OrderPartitionScoreConsistencyTest' --tests '*OrderPartitionedPublicationTest' --tests '*WeeklyRankingOrderPartitionJobTest'
```
