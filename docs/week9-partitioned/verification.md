# 주간 랭킹 정합성 검증

## 같은 상품의 부분 점수

격리 MySQL 8.0.46에 C05의 실제 014·015 DDL을 적용했다. 상품 7에 두 주문 파티션의 부분합 2, 3을 기존 `ON DUPLICATE KEY UPDATE score=?`로 기록했다. 합계 5를 기대한 회귀 검사는 실제 1행·3점으로 실패했다.

새 017·018 DDL을 적용한 뒤 두 MySQL 세션에서 같은 snapshot·상품의 서로 다른 주문 파티션에 2와 3을 동시에 저장했다. B의 3을 다시 기록한 뒤 별도 조회 결과는 `rows=2 sum=5 values=2,3`이었다. 실제 `information_schema.statistics`에서 부분 점수 기본 키는 `snapshot_id,order_partition_id,product_id`, 최종 점수 기본 키는 `snapshot_id,product_id`였다.

| 검사 | 실제 DB에서 관찰한 값 |
|---|---|
| 같은 값 재시도 | 부분 점수 두 행 2·3, 합계 5. 개별 writer가 주간 최종 stage에 만든 행은 0개. |
| 다른 값 재시도 | 3 대신 4를 시도한 청크는 실패. 원래 3 유지. 청크 롤백 뒤 새 조회로 원장의 기존값 3·시도값 4 확인. |
| 오래된 소유자 | 세대가 바뀌거나 주문 파티션이 완료된 후 이전 실행의 쓰기 거절. 실패 청크의 행 수 0개. |
| 로컬 체크포인트 | 7번 상품 커밋 뒤 8번 청크의 checkpoint UPDATE 실패. 8번 부분 점수 롤백 및 체크포인트 1 보존. 새 소유자는 같은 JobInstance에서 해당 Step의 상품 8 한 건만 처리. |
| 독립 입력 고정 | 두 주문 파티션에 대해 고정 사이에 들어온 구매가 아직 열린 주문 파티션에만 포함됨. 이미 고정된 입력은 재시작 후에도 동일. |
| 동시 최종화 | 두 finalizer와 추가 재호출 후 상품 7의 5점·완성 기록 하나·공개 포인터 하나. 공개 시각 정보는 마지막 주문 파티션의 입력 고정 시각과 일치. |
| 검산 실패 | 새 revision의 잘못된 부분합은 공개 실패. 새 최종 stage 롤백, 이전 revision의 포인터 보존. |

주문 파티션별 검산을 추가하는 과정에서 부분합을 2·3에서 1·4로 바꾸는 반례도 사용했다. 전체 합은 양쪽 모두 5이므로 기존 전역 합계 비교만으로는 공개가 성공했다. 주문 파티션·상품별로 기대값과 실제값을 완전히 대조하도록 수정한 뒤 같은 테스트에서 새 stage와 포인터가 모두 0행이었다. 누락·추가된 상품도 같은 검산에 포함된다.

두 주문 파티션에 주문 42의 상품 7·8, 주문 43의 상품 7, 주문 44의 상품 9를 배치한 공개 시험에서 순위는 상품 7(5점·1위), 9(5점·2위), 8(1점·3위)였다. 완성 checksum은 `745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2`로 C05의 정답과 일치했다.

## 서로 다른 두 JVM의 2점·3점과 B의 재전달

단일 JVM의 두 트랜잭션 검사와 별도로 `WeeklyRankingEqualScoreReplayProcessTest`를 실행했다. 별도 프로세스인 A(PID 13416)와 B(PID 27576)가 한 MySQL을 공유했다. 두 JVM의 세션 ID도 달랐다. 관찰한 snapshot은 `68bba61e-6686-4661-8a98-01926d9179dd`다.

| 주문 파티션·주문 | JVM | 세대 | JobExecution | MySQL이 관측한 상품 7 쓰기 |
|---|---|---:|---:|---|
| 0 · 주문 10 | A | 1 | 1 | INSERT 2 |
| 1 · 주문 20 | B | 1 | 2 | INSERT 3 |
| 1 · 주문 20 | B | 2 | 3 | 같은 키 UPDATE, 이전 3 → 새 값 3 |

B의 첫 로컬 상품 파티션이 쓰기와 reader 체크포인트를 커밋해 완료한 직후, 테스트가 manager에서 한 번 장애를 발생시켰다. 기본 설정에서는 재시작이 완료 worker를 건너뛰었고, 중복 쓰기를 요구한 테스트는 실패했다. 이어 B의 테스트 JVM에만 Spring Batch의 `allowStartIfComplete=true`를 설정했다. 실제 스케줄러가 B의 **같은 JobInstance 2**에서 새 JobExecution을 시작했다. 두 실행 모두 실제 reader와 writer가 상품 하나를 읽고 썼고, MySQL 트리거가 동일 키의 재반영을 기록했다. 입력·부분 점수·체크포인트에 대한 수동 수정은 없었다.

두 실행의 Job 상태는 `FAILED → COMPLETED`였다. 최종 부분 점수는 정확히 두 행 `[2,3]`, 최종 점수 테이블에는 상품 7의 5점 한 행, 공개 포인터가 가리킨 랭킹에는 상품 7의 5점·1위 한 행이 있었다. 아래 명령으로 독립 재현할 수 있다.

```powershell
.\gradlew.bat :apps:commerce-streamer:test --tests '*WeeklyRankingEqualScoreReplayProcessTest'
```

## 실제 구매와 프로세스 인수

- 실제 PG 주문 확정, Outbox 재발행, Kafka 소비로 만든 구매 활동을 두 상주 Batch JVM이 계산했다. 첫 revision의 API 조회는 상품 7·8이 각각 5점이고 동점 순서는 7이 먼저였다. 아직 고정 전인 주문 파티션의 구매는 첫 revision에 포함됐고 이미 고정한 주문 파티션의 늦은 구매는 다음 revision에 포함됐다. 다음 공개본은 상품 8이 8점·1위, 상품 7이 5점·2위였다.
- 주문 파티션의 상품 33개 중 16개를 커밋한 상태에서 첫 JVM을 강제로 종료했다. 같은 snapshot의 부분 점수 16행과 저장된 reader 위치 16을 조회했다. 잘못된 실행 ID의 복구 요청은 거절됐다. 정확한 종료·실행 근거를 준 뒤 다른 JVM이 같은 JobInstance에서 나머지 17개를 처리했다. 두 JobExecution의 상태는 `FAILED → COMPLETED`였고 최종 결과와 포인터를 확인했다.

## 로컬 상품 파티션의 부분 점수 읽기

초기 병렬 실험에서 부분 점수 확인 쿼리가 이웃 worker가 소유한 행의 잠금까지 기다려 교착이 발생했다. 실제 MySQL 8.0의 `REPEATABLE READ`에서 오래된 읽기 시점을 만든 뒤 다른 트랜잭션이 상품 16을 커밋하고 무관한 상품 17을 잠그도록 재현했다. 원래 쿼리는 잠금 대기 1205, 일반 스냅샷 조회는 기대한 16건 중 15건이었다. 변경 후에는 청크의 상품 키로 전체 기본 키를 한 건씩 찾는 현재 읽기를 한 SQL로 수행한다. `EXPLAIN`은 `eq_ref`, `PRIMARY`, 예상 행 수 1을 보였고 조회 결과도 정확히 16건이었다. `OrderPartitionScoreConsistencyTest`에서 이 조건과 2점·3점 동시 저장, 동일값 재시도, 다른 값 충돌을 검사했다.

## 동시 JobInstance 생성

두 상주 JVM이 서로 다른 주문 파티션을 처음 시작한 통합 검사에서 MySQL이 `BATCH_JOB_INSTANCE` 삽입 하나를 교착 상태로 롤백했다(SQLState `40001`, 오류 `1213`). 해당 주문 파티션의 Batch 실행과 입력 고정 기록은 모두 0건이었고, 이전 처리 방식은 운영자 인수를 기다렸다. 이 사례는 부분 점수 계산 전에 발생했다.

스케줄러 전용 JobRepository의 생성 격리를 `READ_COMMITTED`로 설정했다. 회귀 시험에서 MySQL의 영속 상태에 실행·입력·부분 점수·충돌 기록이 없는 1213은 다음 스케줄에서 다시 처리할 수 있었고, 세 번째 실패에서는 인수 대기로 전환됐다. 1205 타임아웃과 이미 입력이 고정된 주문 파티션은 첫 실패부터 인수 대기를 유지했다. 별도 사례로 실제 JobExecution이 `STARTING`으로 커밋된 뒤 호출이 예외로 끝났을 때는 생존 신호가 계속 갱신돼도 `RECOVERY_REQUIRED`와 해당 execution ID가 저장됐다. 오래된 세대의 예외는 새 소유자의 상태를 바꾸지 않았다.

## 세 앱 전체 검사

2026-11-20 KST, Java 21에서 세 앱의 `check`를 함께 실행해 `BUILD SUCCESSFUL`을 확인했다. Batch와 Streamer는 마지막 배치 수정 후 다시 실행했다. API는 앞서 단독으로 609건 전체를 통과한 결과를 Gradle이 재사용했다. 각 모듈의 JUnit XML과 Checkstyle XML을 확인한 결과다.

| 모듈 | 실행된 테스트 | 실패·오류·건너뜀 | Checkstyle 위반 |
|---|---:|---:|---:|
| commerce-batch | 184 | 0 | 0 |
| commerce-api | 609 | 0 | 0 |
| commerce-streamer | 27 | 0 | 0 |
| 합계 | 820 | 0 | 0 |

두 JVM이 동시에 처음 실행되는 구매 흐름과 실행 중인 JVM을 강제 종료하는 인수 시험은 모두 Streamer의 기본 검사에 포함된다. 세 구성의 [성능 측정과 checksum 대조](benchmark.md)는 별도로 실행한다.

## 재현

점수·공개·로컬 청크에 대한 집중 검사는 다음과 같다. Docker 29와 이 프로젝트의 Testcontainers 1.20.6 조합에서는 테스트 JVM에 `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`를 적용했다.

```powershell
.\gradlew.bat :apps:commerce-batch:test --tests '*OrderPartitionScoreConsistencyTest' --tests '*OrderPartitionedPublicationTest' --tests '*WeeklyRankingOrderPartitionJobTest'
```
