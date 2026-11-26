# W9 자율 진행 검토 노트

태스크 1~6을 중간 검토 없이 진행하면서 설계에 없던 선택과 관찰값을 남긴다. 검토 뒤 설계 문서에 흡수하고 지운다.

## 검토 가이드 (먼저 읽기)

태스크 1~7과 태스크 8의 로컬 인수를 마쳤다(`acceptance-run.md`). PR 설명은 아직이다. push 하지 않았다.

### 커밋과 볼 곳

| 태스크 | 커밋 | 먼저 볼 파일 |
|---|---|---|
| 1 기반 | `caa308b` | `apps/commerce-batch/build.gradle.kts`, `src/main/resources/application.yml`(local ddl update, chunk-size) |
| 2 domain 규칙 | `261426b` | `domain/ranking/WeeklyRankingPublication.java`(revision·공개 분기), `WeeklyRankingSnapshot.java`, `SnapshotChecksum.java`, `domain/ranking/publish/RankingOrder.java` |
| 3 엔티티·저장소·probe | `34dfc78` | `infrastructure/ranking/freeze/WeeklyRankingSnapshotInputRepositoryImpl.java`(HQL insert-select), `domain/ranking/freeze/PurchaseActivity.java`(@Immutable 원본), `test/.../job/probe/BatchRestartProbeTest.java` |
| 4 입력 고정 Step | `1e0f422` | `domain/ranking/freeze/WeeklyRankingInputFreezer.java`, `batch/job/weeklyranking/WeeklyRankingJobConfig.java`(READ_COMMITTED) |
| 5 계산 Step | `e45f721` | `WeeklyRankingJobConfig.java`(reader·chunk), `domain/ranking/score/WeeklyScoreCalculator.java`, `test/.../RankingJobRestartTest.java` |
| 6 공개 Step | `14c3561` | `domain/ranking/publish/WeeklyRankingPublisher.java`, `batch/job/weeklyranking/publish/PublishTasklet.java`, `test/.../WeeklyRankingFlowTest.java` |

경로 앞부분 `apps/commerce-batch/src/main/java/com/loopers/`(테스트는 `src/test/java/com/loopers/`)는 생략했다. 경로는 단계별 패키지 정리(freeze·score·publish) 뒤 위치다.

### 관찰한 실제 값

| 항목 | 값 |
|---|---|
| probe reader 저장 key·위치 (첫 실행 FAILED 뒤 `JobExplorer`로 다시 읽음) | `purchaseProductProbe.read.count = 2`, 확인 행 7·8 → 재실행 뒤 7·8·9, 같은 JobInstance·새 JobExecution |
| 랭킹 reader 저장 key·위치 (상품 9 직전 실패) | `weeklyRankingProductReader.read.count = 2`, stage {7:5, 8:1}, pointer 없음 |
| 재시작한 계산 Step | readCount 1, writeCount 1, 입력 고정 Step은 새 execution에 없음, 입력 4행 그대로 |
| 첫 chunk commit 전 실패 (상품 8) | read.count 0, stage 없음 → 재시작 readCount 3 |
| 완성 ranking | 7:5·1위, 9:5·2위, 8:1·3위 |
| 입력 checksum | `3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298` (재시작 r1 = 정상 실행 r2 = 손 계산) |
| 결과 checksum | `745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2` (같음) |
| 입력 고정 TX 격리 수준 | 임시 출력으로 `select @@transaction_isolation` = `READ-COMMITTED` 확인(출력 코드는 원복) |
| 오래된 revision 재시작 | Job COMPLETED, exit code `SUPERSEDED`, pointer는 r2 유지 |

### 테스트·검사 결과

`./gradlew :apps:commerce-batch:check` 통과(Checkstyle main·test 포함). 테스트 26건, skip 0, 실패 0(패키지 정리·DemoJob 삭제 뒤 기준).

| 테스트 클래스 | 건수 |
|---|---|
| domain 단위 (`WeeklyPeriodTest` 3, `RankingOrderTest` 1, `SnapshotChecksumTest` 2, `WeeklyRankingPublicationTest` 5, `WeeklyRankingSnapshotTest` 2, `WeeklyRankingScoreStageTest` 1) | 14 |
| `ArchitectureTest` (레이어 방향 2, 단계 패키지 순환 1) | 3 |
| `BatchRestartProbeTest` | 1 |
| `RankingJobRestartTest` | 2 |
| `WeeklyRankingFlowTest` | 5 |
| 기존 `CommerceBatchApplicationTest` | 1 |

### 설계 문서와 달라진 점

- §3 probe와 ArchitectureTest를 태스크 1에서 태스크 3으로 옮겼다(결정 로그 참조).
- Step 이름: `freezeWeeklyInput`, `calculateWeeklyScores`, `publishWeeklyRanking`.
- 늦은 구매 시나리오가 재시작을 포함하므로 `WeeklyRankingFlowTest`도 spy를 쓴다. 테스트 수가 설계 목록보다 둘 많다(commit 전 실패, overwrite 단위).
- 정상 실행 비교는 "같은 주의 r2"로 만든다.

### 남은 위험·하지 않은 것

- **공개 commit 뒤 metadata 저장 실패**는 자동으로 재현하지 않았다. 대응 코드는 `WeeklyRankingPublisher`의 "이미 PUBLISHED면 아무것도 바꾸지 않고 PUBLISHED로 끝내는" 분기뿐이고, 이 분기 전용 테스트는 없다.
- 입력 고정의 건수·checksum 계산이 복사본 전체를 앱 메모리로 읽는다(`findLines`). 큰 주간 데이터에서는 비용이 크다.
- reader는 MySQL 드라이버 기본 동작대로 상품 ID 목록 전체를 클라이언트에 버퍼링한다.
- batch의 `PurchaseActivity` 매핑이 streamer와 어긋나면 local `ddl-auto=update`가 원본 테이블에 컬럼을 추가할 수 있다.
- 템플릿의 `DemoJob`(설정·tasklet·E2E 테스트)과 그것만 쓰던 리스너 3개(`JobListener`, `StepMonitorListener`, `ChunkListener`)를 지웠다. 랭킹 job은 리스너를 쓰지 않는다.
- 쓰이지 않거나 한 줄을 감싸기만 하던 것을 지웠다: `WeeklyRankingSnapshotIds`(→ `WeeklyPeriod.snapshotId` 직접 호출), `RevisionConflictException`(→ `IllegalStateException`, 따로 잡는 곳이 없었다), `PublishOutcome.ALREADY_PUBLISHED`(호출하는 쪽이 PUBLISHED와 구분하지 않았다), `WeeklyRankingSnapshot.isFrozen`(사용처 없음), reader 이름 상수(한 곳에서만 사용).
- 기간별 공개 행의 이름을 `WeeklyRankingPublication`(테이블 `weekly_ranking_publication`)으로 바꿨다. local DB를 이미 띄웠다면 예전 `weekly_ranking_period` 테이블이 남는다(`ddl-auto=update`는 지우지 않음). 아직 local 실행 전이라 영향은 없다.
- 태스크 8의 로컬 인수 시나리오는 `acceptance-run.md`에 기록했다(PR 설명은 아직). 태스크 7(api)은 `WeeklyRankingApiE2ETest` 7건(파라미터 4건 포함)과 `:apps:commerce-api:check`(1026건, skip 7은 기존 벤치마크) 통과.

## 결정 로그

| 태스크 | 결정 | 이유 |
|---|---|---|
| 1 | batch `application.yml`의 `local` 문서에서 `spring.jpa.hibernate.ddl-auto: update`로 덮는다 | 임시 테스트(`ConfigDataEnvironmentPostProcessor.applyTo`, profile local)로 확인한 결과 `DDL=update`, `show-sql=true`. jpa.yml을 읽은 상태에서 가져온 쪽(application.yml)의 값이 이긴다. 임시 테스트는 지웠다 |
| 1 | 기존 `DemoJobE2ETest`의 미사용 import(`lombok.RequiredArgsConstructor`)를 지웠다 | Checkstyle을 batch에 적용하자 `checkstyleTest`가 실패했다. 동작 변경 없음 |
| 1 | ArchitectureTest와 §3 probe 테스트는 태스크 3(엔티티 생성 뒤)으로 옮긴다 | ArchUnit은 대상 클래스가 없으면 실패(failOnEmptyShould). probe는 snapshot 입력 테이블이 있어야 한다 |
| 2 | 기간·revision·공개 분기·manifest 검증 규칙을 엔티티(`WeeklyRankingPublication`, `WeeklyRankingSnapshot`) 메서드로 두고 태스크 2에서 함께 만들었다. repository·나머지 엔티티는 태스크 3 | 규칙이 엔티티 상태에 붙어 있어 단위 테스트가 엔티티를 직접 만든다 |
| 2 | batch에는 `CoreException`이 없어 `IllegalArgumentException`(입력 형식)·`IllegalStateException`(불변식 위반·revision 충돌)을 쓴다 | 배치 실패 원인은 예외 메시지와 Step 실패로 드러나면 충분하다. HTTP 매핑이 없다 |
| 2 | `ProductScore`가 합계 1 미만을 거절한다(전용 테스트 없음) | 설계 `.ai.md` 4-②의 "0 이하는 계약 위반" 규칙. 생성자 한 줄 검증이라 테스트를 따로 두지 않았다 |
| 2 | 구간 경계(시작 포함·끝 제외)는 단위 테스트에서 `startInclusive`/`endExclusive` 값만 확인한다. 실제 포함 여부는 태스크 6 흐름 테스트에서 고정 결과로 확인 | 경계를 적용하는 곳이 고정 쿼리라, 단위 테스트용 `contains` 메서드를 따로 만들지 않았다 |
| 3 | HQL `insert … select`가 `BaseEntity`의 `created_at`·`updated_at`(NOT NULL)을 select의 파라미터(`:copiedAt`)로 채운다 | 원본 생성 시각이 아니라 "복사한 시각"이 이 행의 생성 시각이다 |
| 3 | snapshot 입력의 상품 수와 checksum은 복사본 전체를 정렬 조회해 앱에서 계산한다(`findLines`) | 작은 실습 데이터 기준. 큰 데이터에서는 메모리에 모든 행이 올라오는 비용이 있다(남은 위험 참조) |
| 3 | §3 probe는 과제 예시 그대로 `JdbcCursorItemReader`를 쓰고, 표 이름만 `weekly_ranking_snapshot_input`으로 바꿨다. snapshot 입력 행은 테스트에서 SQL로 직접 넣는다 | probe는 실제 랭킹 job과 분리된 연결 확인이다 |
| 4 | 입력 고정 로직은 domain 서비스 `WeeklyRankingInputFreezer`에 두고 tasklet은 parameter를 넘기기만 한다. TX는 Step의 `transactionAttribute`(READ_COMMITTED)가 연다 | tasklet 실행·StepExecution 갱신이 한 TX라 입력·manifest·Step 완료가 함께 commit된다 |
| 4 | 기간별 공개 행이 없으면 `saveAndFlush`로 만들고 그 insert의 잠금으로 직렬화한다. 동시에 처음 만들면 유일 키 위반으로 한쪽이 실패하고, 재실행하면 해소된다 | 별도 잠금 테이블 없이 기간별 공개 행 하나로 직렬화한다 |
| 4 | Job에 `DefaultJobParametersValidator`(필수 periodStart·revision)를 붙였다 | parameter 누락을 Step 실행 전에 거절한다 |
| 4 | 테스트 시나리오마다 서로 다른 주(periodStart)를 쓴다 | `DatabaseCleanUp`은 Batch metadata를 지우지 않으므로 같은 주를 쓰면 이미 끝난 JobInstance와 겹친다. metadata를 지워 격리하지 않는다 |
| 5 | 점수 계산·기록을 domain 서비스 `WeeklyScoreCalculator`(calculate·record)에 두고 processor·writer는 위임만 한다 | 업무 규칙(snapshot 입력만으로 합산, 절대값 기록)을 batch 배선과 분리한다 |
| 5 | reader는 `JpaCursorItemReader`이고 MySQL 드라이버의 결과 버퍼링을 그대로 둔다(streaming·fetch size 설정 없음) | 읽는 것은 상품 ID뿐이라 상품 10만 개여도 수 MB다. streaming 모드(`fetchSize=Integer.MIN_VALUE`)는 연결 점유 등 다른 비용이 있어 측정 없이 넣지 않았다 |
| 5 | 재시작 테스트에 "첫 chunk commit 전 실패"(상품 8에서 실패 → 위치 0·stage 없음 → 재시작 시 3개 모두 계산)를 추가했다 | 과제 §8의 "chunk commit 전 실패" 검증 범위 |
| 5 | `WeeklyRankingScoreStage.overwrite` 단위 테스트를 추가했다 | writer가 같은 상품을 다시 써도 두 배가 되지 않는 규칙. 통합 테스트로는 덮어쓰기 경로가 생기지 않는다(commit 전 실패는 rollback으로 insert부터 다시 함) |
| 6 | 공개 로직은 domain 서비스 `WeeklyRankingPublisher`에 두고, `PublishTasklet`은 `SUPERSEDED`일 때 Step ExitStatus만 바꾼다. Job exit code도 `SUPERSEDED`가 된다(테스트로 확인) | 공개 분기는 domain, 실행 기록은 batch 진입점 |
| 6 | snapshot이 이미 PUBLISHED면 아무것도 바꾸지 않고 PUBLISHED로 끝낸다. 이미 SUPERSEDED면 그대로 SUPERSEDED | 공개 commit 뒤 metadata 저장만 실패했던 재실행이 같은 결과로 수렴한다 |
| 6 | "정상 실행과 비교"는 같은 주의 r2(같은 원본, 새 snapshot)를 실패 없이 돌려 만든다. 재시작한 r1과 r2의 ranking·입력 checksum·결과 checksum이 같고, 둘 다 손으로 계산한 상수와 같다 | 과제의 "별도 snapshot으로 격리한 정상 실행". 재시작 대상의 stage·metadata는 지우지 않는다 |
| 6 | 늦은 구매 시나리오는 계산 실패를 spy로 만든다. 그래서 `WeeklyRankingFlowTest`도 spy 두 개(합계 조회, 완성 ranking 저장)를 쓴다 | 재시작이어야 "고정 뒤 늦은 구매가 재시작 결과를 바꾸지 않음"을 확인할 수 있다 |
| 7 | api 조회는 쿼리 두 개다: ① 공개 pointer ⋈ snapshot, ② 완성 ranking ⋈ 삭제되지 않은 상품(+ limit). 둘을 한 readOnly TX에서 읽는다 | MySQL REPEATABLE READ에서는 한 TX의 일관된 읽기가 같은 시점을 보므로 pointer와 ranking이 서로 다른 snapshot으로 섞이지 않는다. 설계 문서의 "한 JPQL"을 두 쿼리로 나눈 이유는 미공개(pointer 없음)와 빈 완성본(ranking 0행)을 구분하기 위해서다 |
| 7 | api의 ranking·snapshot·publication은 `@Immutable` 읽기 엔티티이고 컬럼·제약 이름을 batch와 같게 맞췄다. snapshot 상태는 enum 대신 문자열로 매핑한다 | api는 상태를 쓰지 않는다. batch와 DDL이 같아야 local에서 어느 앱이 먼저 떠도 테이블이 같다 |
| 7 | limit 검증(1~100, 기본 10)은 Facade에, periodStart 검증(형식·월요일)은 domain 값 `RankingWeek`에 둔다. 둘 다 `INVALID_INPUT`(400) | `PageRequests`와 같은 방식. 기간 규칙은 업무 규칙이라 domain에 둔다 |
| 7 | 항목 표시 정보는 랭킹 쿼리가 함께 읽은 `Product`(이름·가격)와 `BrandService.getAllByIds`(브랜드)로 만든다. 좋아요 수는 넣지 않는다 | 사용자 결정(이름·가격·브랜드). 브랜드가 없으면 상품 목록과 같이 `INTERNAL_ERROR`로 드러낸다 |
