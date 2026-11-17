# Job 실행·식별·재시작·복구

이 문서는 [승인된 주간 랭킹 설계](../weekly-ranking.md)의 상세 문서다. 승인 상태와 전체 읽기 순서는 진입 문서를 따른다. 기존 절 번호는 유지하며 문서 사이에 분산된 절은 [절 번호 안내](../weekly-ranking.md#절-번호-안내)로 찾는다.

## 문서 간 읽기 경로

- [입력 고정·저장 구조·checksum](input-storage.md)
- [상품별 계산·완성본 공개](calculation-publication.md)
- [랭킹 API 계약](api-contract.md)
- [원문 대응·검증 구성](verification.md)

## 이 문서의 기준 절

[§4](#4-snapshotid와-revision) · [§5.5](#55-명시-재시작과-상태-확인-후-수동-복구) · [§5.6](#56-재시작-전-확인-순서와-지원-범위) · [§5.7](#57-읽기-전용-확인-자료와-보존정리) · [§5.8](#58-unknowncommit-결과-불명에서의-중단-절차)

## 4. snapshotId와 revision

### 4.1 서로 다른 역할

- revision은 같은 주의 몇 번째 계산본인지를 나타낸다. 원문은 기간별 명시적 증가를 요구한다.
- snapshotId는 고정 입력과 그 계산 결과 묶음 하나의 식별자다.
- revision은 완성 뒤 붙이는 번호가 아니라 입력을 만들 때부터 사용한다. 공개 여부와 구분한다.
- 같은 입력의 실패·재시작은 기존 snapshotId·revision을 유지한다. 늦은 구매를 포함한 새 계산은 원래 주의 새 revision·snapshotId를 사용한다.

### 4.2 새 계산의 자동 revision과 ID 표현

최신 합의: 사용자는 새 계산과 재시작을 명시적으로 구분하고, 새 계산에서는 프로그램이 같은 기간의 기존 revision을 조회해 다음 번호를 정하여 Batch parameter로 전달하는 방식을 선택했다. 재시작은 운영자가 지정한 실패 실행 ID에서 기존 parameter를 읽어 사용한다. 앞선 “운영자가 revision 숫자를 직접 입력한다”는 해석/합의 기록은 이 후속 선택으로 대체한다. Spring Batch의 RunIdIncrementer가 업무 revision을 관리하는 것으로 설명하지 않는다.

```text
새 계산 요청(periodStart):
  해당 기간의 다음 revision을 프로그램이 결정
  그 값과 periodStart로 snapshotId 생성
  새 계산의 식별 parameter로 JobLauncher 실행
재시작 요청(jobExecutionId):
  영속 실행 기록에서 지정 execution의 기존 parameter 확인
  재시작 가능한 상태와 이전 실행 종료 확인
  기존 revision·snapshotId·checkpoint로 명시 재시작
```

새 요청인지 재시작인지 이전 실패 존재만으로 추측하지 않는다. 사용자는 이전 FAILED 실행이 남아 있어도 명시 new는 허용하는 방향을 재확인했다. 실패 작업을 이어 가려면 restart를 선택하고 새 입력으로 다시 계산하려면 new를 선택한다. running/UNKNOWN/미확인 commit에 대한 시작 제한은 유지한다. 같은 주를 늦은 구매를 포함해 다시 계산하려는 요청과 같은 입력의 재시작은 다르다. 모드/실행 ID를 별도의 새 업무 식별값으로 추가해 JobInstance가 달라지게 하지 않는다. 실행 옵션/검증·번호 조회·등록 경계는 [§4.3~4.10](#43-다음-revision-조회와-기존-batch-api-사용)을 따른다. 별도 예약을 만들지 않고 원문 직렬 실행 전제를 유지한다. 의도적인 수동 결번 입력 허용 여부를 별도 제품 기능으로 추가하지 않는다.

할당 검토 주의: snapshot 테이블의 최대 revision만으로 다음 값을 정하면 입력 고정 전에 실패해 snapshot 행은 없고 Batch 실행 기록만 남은 요청과 같은 번호가 선택될 수 있다. 새 계산이 실수로 기존 실패 JobInstance의 재시작이 되지 않게 이미 등록된 실행의 식별값도 고려해야 한다. 또한 다음 번호 조회와 새 실행 등록 사이의 중복 요청을 정상 별도 계산으로 숨기지 않는다. 이 경계를 해결하기 전 자동 발급 구현 완료로 보고하지 않는다. 별도 예약 테이블/중간 snapshot 상태·분산 락을 승인 없이 추가하지 않는다.

snapshotId는 기간·revision을 조합해 프로그램이 생성한다. 합의한 표현 예시는 `weekly-20260817-r2`다. 사람이 ID만 보고 대상 주와 계산본을 알아볼 수 있게 하는 선택이다. 날짜의 기준은 UTC 업무 주의 periodStart다.

대안은 날짜 의미가 없는 숫자 ID다. 숫자 ID는 표현과 업무 정보를 분리할 수 있다. 선택한 문자열 방식은 별도 열과 같은 정보를 반복하므로 ID와 기간·revision의 불일치 위험이 있다. canonical 생성/일치 검사는 [§8.1](input-storage.md#81-메서드의-입력과-책임)을 따른다. 문자열 ID의 사전식 순서로 revision의 신구를 판단하지 않는다.

완료된 같은 기간·revision을 다시 실행해 새 계산을 만드는 것은 허용하지 않는다. 완료 instance의 restart는 거절하고 비정상 종료한다([§4.6~4.9](#46-실행-id를-통한-명시-재시작)). 새 계산은 명시 new로 구분한다. 기존 snapshot을 재시작할 때 현재 원본을 재복사하지 않는다. 현재 원본이 늦은 구매로 늘어난 것만으로 기존 입력이 충돌한 것은 아니다.

같은 snapshotId로 다른 기간·revision·입력을 덮어쓰지 않는 것은 원문 요구다. 요청 불일치·저장 입력 손상·정상 재사용의 분기는 [§8.2](input-storage.md#82-입력-고정과-재사용의-분기)를 따른다.

### 4.3 다음 revision 조회와 기존 Batch API 사용

이 절은 사용자가 요청한 자동 번호 결정·실행 등록 상세화다. 기본 범위는 원문대로 같은 기간의 새 계산·재시작을 직렬로 실행하는 것이다. 번호 결정을 포함한 실행 준비부터 동기 job 종료까지 한 실행 주체가 소유하고, 같은 기간의 다른 batch 프로세스/도구를 병행하지 않는다. 새로운 분산 락·예약 테이블·자동 스케줄러·동시 실행 기능은 추가하지 않는다. 테스트 자원 격리·소유권은 [§11.6](verification.md#116-실행-자원-소유권최초-준비정리)을 따른다.

로컬 Batch5.2.2 sources.jar에서 확인한 사실:

- JobExplorer.getJobInstances(jobName, start, count)는 지정 job의 instance를 페이지로 조회하며, getJobExecutions(instance)로 해당 실행의 parameter를 읽을 수 있다. 첫 페이지만 보고 최대 revision을 확정하면 안 된다.
- SimpleJobOperator.start는 해당 식별 parameter의 JobInstance가 이미 있으면 거절하고, 없으면 등록된 job을 JobLauncher로 실행한다. restart(executionId)는 지정 execution의 job 이름과 기존 JobParameters를 읽어 JobLauncher로 실행한다.
- TaskExecutorJobLauncher는 parameter 검증 뒤 JobRepository.createJobExecution을 호출하고 그 실행을 job.execute에 전달한다. SimpleJobRepository.createJobExecution은 신규 instance·execution·parameter·execution context의 저장을 처리한다. 기본 create 계열 repository 프록시 TX와 MySQL용 InnoDB metadata 스키마를 유지한다.
- SimpleJobRepository는 실행이 하나도 없는 기존 instance를 정상 시작 대상으로 취급하지 않고 오류를 낸다. 따라서 createJobInstance만 먼저 호출해 번호를 예약한 뒤 일반 launcher를 부르는 별도 흐름은 만들지 않는다.

모두 로컬 소스/기본 schema 확인이며 실제 bean·DB 등록 원자성의 실행 검증은 아니다. Batch의 기존 metadata create용 트랜잭션 기본값을 새로 변경하지 않고, 업무 입력/계산/공개의 기본 REPEATABLE READ 유지 지시도 그대로 따른다.

다음 값은 저장된 parameter의 내부 표현으로 제안한다. 실행 준비가 생성하고 사용자는 revision/snapshotId를 수동 입력하지 않는다.

|식별 parameter|타입|값|
|---|---|---|
|periodStart|String|검증한 UTC 월요일의 ISO 날짜|
|revision|Long|해당 기간에 등록/생성된 최대 revision+1|
|snapshotId|String|periodStart·revision으로 생성한 canonical ID|

세 parameter를 identifying=true로 고정한다. 같은 값의 재시작은 같은 instance이고 새 execution이다. 모드·재시작 대상 executionId·현재 시각·run.id는 식별 parameter에 추가하지 않는다. 파라미터 이름/타입은 새 계산·재시작·job validator·Reader에서 동일하게 사용한다. revision/canonical 계약은 [§8.1](input-storage.md#81-메서드의-입력과-책임), 날짜 형식/월요일은 [§7.7](api-contract.md#77-요청자-식별과-입력-값-객체)을 따른다. 별도 DB 날짜 범위 guard는 두지 않는다([§7.7](api-contract.md#77-요청자-식별과-입력-값-객체)).

### 4.4 실행 준비 메서드와 새 계산 순서

기존 batch 앱의 실행 진입점으로 WeeklyRankingLaunchRunner를 두는 초안이다. 별도 관리 HTTP/서버·독립 복구 앱을 추가하지 않는다. run에서 명시 모드와 입력을 검증하고, 아래 helper 책임을 한 곳에서 조율한다. 클래스/bean·실행 옵션은 [§4.7~4.9](#47-실행-옵션과-최초-준비-전제)를 따른다.

|메서드|입력·출력|책임|
|---|---|---|
|run|실행 옵션 → 완료 또는 오류|new/restart 분기·동기 실행 결과 확인·종료|
|findMaxKnownRevision|periodStart → max revision|snapshot과 같은 job 실행 기록의 해당 기간 revision을 함께 조사|
|startNew|periodStart → 새 executionId|next revision 생성·parameter 구성·JobOperator.start|
|restartExisting|executionId → 새 executionId|대상/최신 실행·상태/parameter 확인·JobOperator.restart|

```text
startNew(periodStart): // job 전체를 감싸는 상위 TX 없음
  날짜·월요일과 명시 모드 입력 검증
  historyMax = 없음
  JobExplorer에서 jobName=weeklyRanking의 instance를 끝까지 페이지 조회
    각 instance의 실행과 parameter를 읽음
    identifying periodStart/revision/snapshotId가 정상이고 서로 맞는지 확인
    같은 기간이면 등록된 revision을 historyMax에 반영
      FAILED·STOPPED·COMPLETED도 번호를 이미 사용한 실행임
    실행 없이 instance만 있거나 식별 정보 손상: 번호를 추정하지 않고 실패/수동 확인
  snapshotMax = 해당 기간 ranking_snapshot의 최대 revision
  maxKnown = max(historyMax, snapshotMax), 둘 다 없으면0
  같은 기간에 실행 중/종료 불명/UNKNOWN이 남았으면 새 작업을 자동 시작하지 않음
    기존 상태 확인·수동 복구 원칙으로 처리
  nextRevision = maxKnown + 1 // Long 범위 초과면 실패,0/음수 순환 금지
  snapshotId = canonicalId(periodStart, nextRevision)
  params = 세 identifying parameter
  job validator로 params 확인
  converter로 typed JobParameters를 operator용 Properties로 변환
  JobOperator.start(weeklyRanking, properties)
    기존 instance 존재/등록 실패를 restart로 우회하지 않음
  반환된 executionId의 영속 최종 실행 결과를 확인
```

history는 JobExplorer 공개 API로 읽는 안을 사용한다. Batch 내부 테이블에 직접 수작업 MAX CAST 쿼리를 추가하면 schema/parameter 표현에 결합하고 손상값을 자동 숫자 변환으로 숨길 수 있다. 공개 API 방식은 여러 페이지와 실행 조회 비용을 수용한다. 최근1건/완료 execution만 확인하거나 snapshot의 max만 보는 것으로 줄이지 않는다. 새 table·cache·일반 실행 이력 프레임워크를 추가하지 않는다.

Properties는 Batch의 JobParametersConverter와 같은 변환 규칙으로 만들며 revision을 문자열 타입 parameter로 오등록하지 않는다. converter 조립은 Boot의 기존 기본 구성과 대조하고 임의 전역 parser를 바꾸지 않는다. JobOperator가 같은 weeklyRanking job을 JobRegistry에서 찾을 수 있어야 한다. 기동 조립에서 실제 등록 여부를 확인하고 준비 실패를 job 성공으로 처리하지 않는다.

### 4.5 등록 실패·중단 경계

|실패 위치|남을 수 있는 상태와 처리|
|---|---|
|입력 검증/번호 조회·parameter 검증 실패|새 job 등록·snapshot 생성 전 중단|
|JobRepository의 등록 TX 확정 rollback|신규 instance/execution/parameter를 부분 예약으로 남기는 별도 흐름 없음. 실제 DB 상태를 확인하고 오류 전파|
|등록 commit 결과 불명|저장 여부를 조회하기 전 다른 번호로 재호출하지 않음. 수동 상태 확인|
|실행 등록 commit 후 입력 고정 전 실패|snapshot이 없어도 Batch parameter에 revision이 남음. 다음 new 요청은 그 번호보다 다음 값을 사용|
|등록 commit 뒤 프로세스가 시작 전에 종료|STARTING 등 metadata가 남을 수 있음. 즉시 new/restart로 숨기지 않고 이전 실행 종료/저장 상태 확인|
|입력 고정·계산·공개 중 실패|기존 snapshot/metadata/공개 상태를 보존. 같은 executionId를 명시한 재시작 경계로 연결|

번호 조회와 start 호출 전체가 다중 프로세스 간 원자적 번호 발급이라고 주장하지 않는다. SimpleJobOperator.start의 존재 확인과 launcher 호출 사이에는 간격이 있고, SimpleJobRepository는 실패한 기존 instance의 재실행도 지원한다. 따라서 프레임워크의 같은 instance 등록 보호를 같은 기간/서로 다른 요청의 분산 직렬화 보장으로 확대하지 않는다. 이 구현의 전제는 원문의 같은 기간 직렬 실행이며, 이를 벗어난 다중 launcher 지원이 필요하면 별도 승인으로 설계해야 한다. 충돌 때 무조건 번호를 올려 다시 실행하는 자동 loop도 추가하지 않는다.

### 4.6 실행 ID를 통한 명시 재시작

```text
restartExisting(executionId): // 상위 TX 없음
  executionId 양수 검증
  target = JobExplorer.getJobExecution(executionId)
  없거나 job 이름이 weeklyRanking이 아니면 거절
  같은 instance의 최신 execution과 target을 대조
    target이 최신이 아니면 거절하고 최신 ID를 안내 // 사용자 합의
  target의 세 identifying parameter·canonical ID 검증
  기존 프로세스 종료와 영속 Job/Step 상태 확인
    FAILED/STOPPED의 명확한 재시작 경로만 일반 재시작
    running/UNKNOWN/commit 불명: 수동 확인 경계, 자동 상태 변경 금지
    COMPLETED/ABANDONED: 같은 작업을 새 계산으로 둔갑시키지 않고 거절
  JobOperator.restart(executionId)
  같은 JobInstance·같은 identifying parameter·새 JobExecution 반환 확인
  기존 input/Reader checkpoint로 이어감
```

프레임워크도 running/UNKNOWN/완료 상태에 재시작 제한을 둔다. 지정한 과거 execution의 임의 checkpoint로 되돌리는 기능은 아니다. 사용자는 같은 instance의 가장 최근 실행 ID만 재시작 입력으로 허용하기로 확정했다. 예를 들어10 실패 →11 재시작/실패 후에는11을 지정한다. 과거 ID를 프로그램이 조용히 최신 ID로 바꾸지 않는다. 입력 고정 자체가 확정 rollback돼 아직 완료 입력이 없다면 기존 parameter로 freeze 단계를 다시 수행할 수 있지만, 완료된 입력은 재복사하지 않는다.

기존 Boot JobLauncherApplicationRunner와 새 launch 진입점의 중복 실행을 막아야 한다. weeklyRanking 실행 조립에서는 Boot 자동 job 실행을 끄고 명시 실행 준비 경로 하나만 사용하며 Demo 선택/기존 실행은 별도로 유지한다. 기본 동기 launcher를 유지하고 job 실행 후 영속 BatchStatus가 COMPLETED인지 확인한다. 공개 생략의 정상 완료는 허용하되 실행 실패/UNKNOWN/기동 실패를 프로세스 성공으로 보고하지 않는다. 옵션·main 연결·종료 코드는 [§4.7~4.9](#47-실행-옵션과-최초-준비-전제)를 따른다. 이 설계는 별도 자동 재시작·metadata 강제 수정·예약 상태 도입이 아니다.

이번 상세화는 로컬 소스 읽기와 계약 대조만 수행했다. 의존성 해석·실행물 생성·앱/인프라 기동·테스트/실험은 수행하지 않았다. 지원 범위는 원문 같은 기간 직렬 실행과 영속 metadata 보존이다.

### 4.7 실행 옵션과 최초 준비 전제

아래는 구현할 실행 계약이며 지금 실행한 명령이 아니다. 업무/Batch schema와 기존 구매 활동을 먼저 준비한 DB를 사용한다. 같은 기간의 번호 조회부터 동기 실행 종료까지 한 실행 주체가 소유한다. 자동 스케줄·분산 락·새 실행 서버를 추가하지 않는다.

|옵션|계약|
|---|---|
|spring.batch.job.name|weeklyRanking. 기존 Demo의 조건부 등록과 구분|
|spring.batch.job.enabled|false. Boot JobLauncherApplicationRunner의 중복 launch 방지|
|weekly-ranking.launch.enabled|true일 때만 명시 launch Runner 등록. 기본 false, 테스트/최초 schema 준비는 false|
|weekly-ranking.mode|new 또는 restart 필수. 누락/다른 값 거절|
|weekly-ranking.period-start|new에서만 필수. 엄격한 ISO 월요일 날짜|
|weekly-ranking.execution-id|restart에서만 필수. 양수 Long이며 같은 instance의 가장 최근 실행 ID|

new에 execution-id, restart에 period-start를 함께 주면 모드 혼동으로 거절한다. 사용자가 revision/snapshotId/run.id를 입력해 분기를 우회하지 않는다. 런처 옵션은 JobParameters에 복사하지 않고 [§4.3](#43-다음-revision-조회와-기존-batch-api-사용)의 세 식별값만 등록한다. `weekly-ranking` 전용 설정은 unknown field를 허용하지 않고, 명시 launch에서 non-option 인자로 별도 JobParameters를 넘기는 것도 거절한다. OS 인자뿐 아니라 적용된 설정값을 검증해 Boot 자동 실행이 켜진 상태로 명시 launch를 시작하지 않는다.

실행물 경로를 shell 변수 rankingBatchJar에 지정했다고 할 때의 예시다. 실행물 생성 자체는 후속 구현/실행 범위다.

```bash
java -jar "$rankingBatchJar" --spring.profiles.active=local \
  --spring.batch.job.name=weeklyRanking --spring.batch.job.enabled=false \
  --spring.jpa.hibernate.ddl-auto=none --spring.batch.jdbc.initialize-schema=never \
  --weekly-ranking.launch.enabled=true --weekly-ranking.mode=new \
  --weekly-ranking.period-start=2026-08-17

java -jar "$rankingBatchJar" --spring.profiles.active=local \
  --spring.batch.job.name=weeklyRanking --spring.batch.job.enabled=false \
  --spring.jpa.hibernate.ddl-auto=none --spring.batch.jdbc.initialize-schema=never \
  --weekly-ranking.launch.enabled=true --weekly-ranking.mode=restart \
  --weekly-ranking.execution-id=11
```

11은 예시 ID이며 실제 최신 실패 executionId로 바꾼다. DB 접속/비밀값은 기존 설정으로 주입하고 로그/인자 예시에 노출하지 않는다. 위 명령은 ddl-auto=none·initialize-schema=never로 이미 준비한 데이터/metadata를 보존한다. 입력·상품/브랜드·metadata 없이 실행하면 준비 실패이지 정상 빈 주를 보장하는 명령이 아니다. 다른 참여 앱의 재기동도 [§9.10](input-storage.md#910-entity-매핑쓰기-소유권과-최초-스키마-준비) 보존 설정을 사용한다.

### 4.8 기존 bean 조립과 런처 클래스

|타입·위치|입력/출력과 책임|
|---|---|
|batch/job/weeklyranking/WeeklyRankingLaunchProperties|weekly-ranking prefix의 불변 바인딩 값. 중첩 launch.enabled와 mode/periodStart/executionId 조합 검사|
|batch/job/weeklyranking/WeeklyRankingLaunchContextInitializer|ApplicationContextInitializer. main에 등록해 명시 launch의 보존/중복 실행 설정을 bean 초기화 전에 검사|
|batch/job/weeklyranking/WeeklyRankingLaunchRunner|ApplicationRunner·ExitCodeGenerator. 상위 TX 없이 모드 분기·operator 호출·영속 결과 확인·프로세스 종료 코드 제공|
|batch/job/weeklyranking/WeeklyRankingParameters|[§8.6](calculation-publication.md#86-클래스-배치포트와-호출-순서) 어댑터. 세 typed identifying 값 검증/생성·기존 converter용 Properties 변환|
|domain/ranking/RankingLaunchRepository|findMaxSnapshotRevision(periodStart) → OptionalLong. snapshot 최대 번호의 읽기 포트|
|infrastructure/ranking/JdbcRankingLaunchRepository|같은 DB의 SELECT MAX(revision) 구현. 삭제 snapshot도 이미 사용한 번호로 취급|
|batch/job/weeklyranking/WeeklyRankingLaunchException|입력/대상 없음/과거 ID/실행 금지 상태/실행 결과 오류의 정적 reason, 원인 보존. RuntimeException이며 HTTP 의존 없음|

다음 번호 조회·검증·startNew/restartExisting는 이 Runner의 내부 메서드로 유지한다. 단순 전달용 별도 관리 Facade/새 공용 실행 프레임워크를 만들지 않는다. snapshot 조회는 domain 포트를 사용하고 Batch metadata 조회는 진입 어댑터가 JobExplorer 공개 API를 사용한다. max+1은 Math.addExact로 Long overflow를 실패시키고 음수/기존 번호로 순환시키지 않는다. snapshot이 없는 실패 실행의 revision도 [§4.4](#44-실행-준비-메서드와-새-계산-순서)대로 이력에서 포함한다.

snapshot 최대 번호 SQL은 `SELECT MAX(revision) FROM ranking_snapshot WHERE period_start = ?`다. SQL NULL만 이력 없음으로 반환하며0/음수·범위 손상을 없음으로 보정하지 않는다. 삭제 이력도 번호를 사용한 사실이므로 deleted_at 필터를 넣지 않는다. history의 typed parameter와 snapshot의 최대 번호를 합치는 로직은 Runner가 소유하고 이 SELECT에 Batch 내부 schema join/CAST를 추가하지 않는다.

JobRegistry·JobOperator·JobLauncher·JobExplorer·JobRepository는 기존 Boot/Batch 자동 구성 bean을 주입한다. 별도 @EnableBatchProcessing·SimpleJobOperator 직접 생성·새 DataSource/TX manager를 추가하지 않는다. 로컬 Batch5.2.2 DefaultBatchConfiguration은 JobRegistrySmartInitializingSingleton으로 Job 등록을 처리하고 JobOperatorFactoryBean에 기존 launcher/repository/explorer/registry/converter를 연결한다. Runner 시작 시 registry의 weeklyRanking과 조립한 Job이 같은 이름/대상인지 확인한다. 없거나 잘못 등록되면 launch 전 실패한다.

새 parameter의 Properties는 기본 DefaultJobParametersConverter의 getProperties(JobParameters)로 만든다. 기본 operator의 같은 변환 규칙으로 String/Long/identifying=true를 보존하며 converter를 전역 교체하지 않는다. 실제 runtime 버전/bean이 이 전제와 다르면 추측해 다른 parser를 만들지 않고 구현 검토에서 중단한다.

기존 동기 launcher를 사용한다. 로컬 JobOperatorFactoryBean의 기본 TX advice는 stop*에만 REQUIRED/DEFAULT를 적용하며 start/restart 전체를 업무 상위 TX로 묶지 않는다. Boot의 Batch 전용 executor 설정/커스텀 launcher를 추가하지 않는다. operator가 반환했는데 영속 상태가 STARTING/STARTED/STOPPING이면 동기 완료로 간주하지 않고 실패/상태 확인 경계로 보낸다. 무기한 polling이나 자동 재호출은 넣지 않는다.

local/test에는 JPA create·Batch schema always가 있으므로 Runner에서만 보존 옵션을 검사하면 이미 초기화된 뒤일 수 있다. 제품/테스트 main은 [§4.12](#412-제품테스트-main의-공통-시작-경로)의 공통 createApplication으로 SpringApplication과 WeeklyRankingLaunchContextInitializer를 조립한 뒤 run한다. initializer는 준비된 Environment에서 weekly-ranking.launch.enabled=true일 때만 job.name=weeklyRanking·job.enabled=false·ddl-auto=none·initialize-schema=never를 요구한다. 하나라도 다르면 context refresh/DB bean 초기화 전에 기동을 거절한다. 설정을 자동 덮어쓰거나 schema를 수선하지 않는다. launch.enabled=false인 최초 준비/기존 Demo 경로에는 이 검사를 적용하지 않는다.

Boot3.4.4 SpringApplication.prepareContext에서 Environment 설정 뒤 applyInitializers가 호출되고 그 뒤 bean load/refresh가 수행되는 순서를 로컬 소스로 확인했다. 단순 @Component initializer 자동 등록으로 가정하지 않고 main에 명시 연결한다. 테스트가 main을 거치지 않으면 필요한 context 초기화 검사도 테스트 구성에 명시해야 한다. 제품/테스트 main과 initializer의 공통 연결은 [§4.12](#412-제품테스트-main의-공통-시작-경로)·[§11.5](verification.md#115-앱-간-실행-연결과-테스트-전용-실패-주입)를 따른다. 이는 재시작 데이터를 지키는 기동 검사이며 새로운 초기화 도구/DDL 기능이 아니다.

### 4.9 실행 결과와 프로세스 종료

현재 CommerceBatchApplication.main은 SpringApplication.run 뒤 SpringApplication.exit의 결과를 System.exit에 전달한다. 이 main의 종료 구조를 유지한다. 다만 명시 operator 실행은 Boot JobLauncherApplicationRunner가 발행하는 JobExecutionEvent 경로를 거치지 않으므로 Boot 기본 JobExecutionExitCodeGenerator가 결과를 받았다고 가정하지 않는다. Boot3.4.4의 해당 generator는 수신한 event 목록만 검사하며 ExitCodeGenerator bean이 있으면 기본 generator 등록이 생략된다. weeklyRanking의 조건부 Runner가 직접 ExitCodeGenerator를 구현해 이 경계를 맡는다. Demo 경로에서는 이 Runner가 등록되지 않아 기존 종료 처리를 바꾸지 않는다.

```text
run(options): // 상위 TX 없음; exitCode 초기값1
  옵션 조합·자동 launch OFF·registry·보존 설정을 확인
  mode=new: §4.4 startNew → executionId
  mode=restart: §4.6/§5.6 검사 후 operator.restart → 새 executionId
  fresh = JobExplorer.getJobExecution(반환 ID) // launcher의 메모리 상태만 믿지 않음
  fresh 없음/조회 실패/상태 != COMPLETED: 실패; exitCode는1
  재시작이면 instanceId·세 identifying 값 동일, executionId 다름을 확인
  executionId/instanceId/snapshotId/revision/최종 상태를 결과로 남김
  exitCode=0
getExitCode(): exitCode 반환
```

정상 종료 코드는0, 정상적으로 확인한 실패/거절은1이다. 준비/기동 자체의 예외는 전파해 비정상 종료시키고0으로 포장하지 않는다. 예상하지 못한 기술 예외를 catch해 성공 결과로 반환하지 않는다. SpringApplication.exit는 generator bean을 모아 종료 코드를 얻고 context를 닫는 기존 경로를 사용한다. JVM 강제 종료·외부 signal의 코드를 별도 앱 성공 코드로 변환하지 않는다.

PUBLISHED/ALREADY_PUBLISHED/SKIPPED_NEWER는 모두 Job의 COMPLETED이면 성공이다. 새로운 공개가 없다는 이유로 SKIPPED_NEWER를 실패시키지 않는다. 실행의 publicationOutcome은 해당 instance에서 대상 snapshotId가 일치하는 마지막 공개 Step context에 있을 때 함께 남기고, 완료 Step 생략으로 이번 execution에 context가 없으면 없는 값을 만들어 쓰지 않는다. 종료 코드0만으로 데이터 정답·§7 연결 검증 통과를 주장하지 않는다.

### 4.10 로그·오류와 실행 승인 경계

launch 전에는 mode·요청 기간 또는 지정 executionId, 새 계산의 결정된 revision/snapshotId를 남긴다. 실행 등록 뒤에는 instanceId/executionId를 남기고 완료 뒤 영속 상태/공개 outcome을 남긴다. 등록 응답 불명으로 ID를 못 받았더라도 등록 전 기록한 세 식별값으로 찾아야 하므로 새 번호를 곧바로 발급하지 않는다. parameter/식별값 로그에 DB 비밀정보를 섞지 않는다.

동기 operator는 실행이 끝나야 ID를 반환하므로 시작 ID를 Runner의 반환 이후 로그에만 맡기지 않는다. WeeklyRankingJobConfig에 weeklyRanking 전용 JobExecutionListener의 beforeJob callback을 연결해 실제 instanceId/executionId/세 식별값을 남긴다. 공통 Demo listener를 수정하거나 afterJob의 메모리 상태를 영속 완료 증거로 삼지 않는다. 등록 commit과 beforeJob 사이에서 종료해 시작 로그조차 없을 때는 등록 전 식별값으로 조회한다.

오래된 ID 거절 시 요청 ID와 현재 최신 ID를 안내하되 자동 재시작하지 않는다. 이름/parameter/instance 불일치·UNKNOWN·진행 중·완료/ABANDONED·등록/commit 결과 불명은 각각 원인을 구분해 기록한다. 실패한 같은 instance를 new 요청으로 자동 전환하지 않는다. 구체적인 사용자 수동 확인은 [§5.6~5.8](#56-재시작-전-확인-순서와-지원-범위)을 따른다. 이 절은 실행 설계이며 어떤 명령/앱도 이번에 실행하지 않았다.

### 4.11 Job 등록 조건과 실행·준비·테스트 설정

사용자는 기존 Demo와 같은 방식으로 spring.batch.job.name=weeklyRanking을 지정했을 때만 랭킹 실행 구성을 등록하는 방향을 선택했다. 이 값은 터미널 실행 인자 또는 IDE Program arguments에서 설정한다. 기존 application.yml의 `${job.name:NONE}` 연결은 유지하되 문서/신규 테스트는 spring.batch.job.name을 직접 지정한다. Job 선택과 실행 시점은 별개이며 원문은 스케줄러를 요구하지 않으므로 @Scheduled/cron/새 scheduler를 추가하지 않는다.

WeeklyRankingJobConfig에는 다음 클래스 단위 조건을 둔다. 이름이 다르거나 누락돼 NONE으로 해석되면 이 구성의 빈은 등록하지 않는다.

```java
@Configuration
@ConditionalOnProperty(
    name = "spring.batch.job.name",
    havingValue = "weeklyRanking",
    matchIfMissing = false
)
```

이 구성의 @Bean 메서드에서 weeklyRanking Job·freezeWeeklyRankingInput/calculateWeeklyScores/publishWeeklyRanking Step·weeklyRankingProductReader·weeklyRankingScoreProcessor·Writer·두 Tasklet·Job validator/전용 listener를 조립한다. 이 실행 구성 요소에 별도 무조건 @Component를 붙여 전체 component scan으로 조건을 우회하지 않는다. Step parameter는 step scope에서 바인딩하며 context 초기화 중 빈 JobParameters를 업무 요청으로 검증하지 않는다.

WeeklyRankingLaunchRunner는 위 선택 조건에 더해 weekly-ranking.launch.enabled=true일 때만 같은 구성의 조건부 @Bean으로 등록한다. launch.enabled가 false이면 Job은 준비할 수 있지만 Runner는 실행하지 않는다. Boot 자동 실행도 false로 두고 테스트가 JobLauncher/JobOperator를 명시 호출한다. 제품의 명시 launch에서는 initializer가 job.name/자동 실행/보존 설정을 먼저 확인해 잘못된 조합을 거절한다([§4.8](#48-기존-bean-조립과-런처-클래스)).

Entity 매핑과 Boot의 DataSource/JobRepository 등 기반 구성은 Job 선택 조건과 구분한다. 다섯 Entity를 위 JobConfig 안으로 옮기거나 선택하지 않은 Job을 이유로 schema 준비에서 제외하지 않는다. 따라서 최초 schema 준비는 NONE으로 Job을 선택하지 않아도 소유 Entity 매핑으로 수행할 수 있다. 기존 DemoJobConfig·공통 fixture의 등록/기동 방식을 바꾸지 않는다.

|상황|spring.batch.job.name|spring.batch.job.enabled|weekly-ranking.launch.enabled|동작/준비 경계|
|---|---|---|---|---|
|제품 new/restart·§7 batch child|weeklyRanking|false|true|랭킹 구성/Runner 등록. 준비된 DB에 none/never로 연결하고 명시 실행|
|랭킹 Batch fixture/probe 테스트 context|weeklyRanking|false|false|랭킹 구성만 준비, 테스트가 실제 Job을 명시 실행. probe는 별도 테스트 Job을 직접 구성하며 제품 validator에 probe-1을 통과시키지 않음|
|batch schema 준비 context|NONE|false|false|JPA update/Batch schema 준비로 없는 테이블을 준비하고 기존 테이블을 재생성하지 않음. 제품 Job 재시작 경로가 아님|
|기존 CommerceBatchApplicationTest|NONE(기존 기본값)|false(기존 테스트 지정)|false(신규 기본값)|랭킹 Job/Runner 미등록. batch test 작업의 JPA update를 적용하고 기존 기동 단언 유지|
|기존 Demo 실행/테스트|demoJob|기존 Job 실행 설정 유지|false(신규 기본값)|Demo 구성만 등록. 테스트에서는 아래 JPA update를 적용하고 기존 실행/단언 유지. 일반 Demo 실행 설정은 변경하지 않음|
|API 단독 랭킹 테스트|해당 없음|해당 없음|해당 없음|batch context를 띄우지 않고 [§11.3.1](verification.md#1131-단독-테스트의-준비-파일순서소유권) SQL로 부족한 테이블 준비|

WeeklyRankingLaunchProperties는 launch.enabled=false일 때 mode/기간/executionId 누락을 허용해야 한다. 활성화된 Runner/initializer에서 필요한 조합을 검사하며, 전역 properties scan의 무조건 필수값 검사로 Demo/NONE/최초 준비 context를 실패시키지 않는다. 활성화 시의 unknown field/모드 입력 거절은 [§4.7](#47-실행-옵션과-최초-준비-전제) 계약을 유지한다. 이 절은 등록/설정 계약이며 빈 조립·기존 테스트 회귀를 실제 실행한 결과가 아니다.

사용자는 후속 재검토 R1에 대해 배치 테스트 JVM의 기존 테스트도 데이터를 재생성하지 않도록 설정 범위를 넓히는 방향을 승인했다. 구현 시 apps/commerce-batch/build.gradle.kts의 Test 작업에 spring.jpa.hibernate.ddl-auto=update 시스템 속성을 적용한다. 기존 CommerceBatchApplicationTest·DemoJobE2ETest와 신규 랭킹 테스트 모두 이 기본값을 사용하고, jpa.yml test 프로필의 create가 기존 랭킹 테이블을 DROP/recreate하게 두지 않는다. 기존 테스트의 Job 선택·실행·기대값/단언은 유지한다.

이 설정은 batch 프로젝트의 Test 작업 범위에만 적용한다. 공통 modules/jpa의 전역 설정·일반 Demo 제품 실행·API/streamer 전체 테스트 설정을 일괄 바꾸지 않는다. 이미 준비된 DB의 명시 실행 child는 계속 CLI의 none/never를 사용하고, API 단독/연결 준비 설정은 [§11.3.1](verification.md#1131-단독-테스트의-준비-파일순서소유권)을 따른다. 새 batch Test 작업을 만들면 같은 비파괴 기본값을 빠뜨리지 않는다. 초기화 거절 자체를 확인하는 테스트는 잘못된 설정을 주되 DB/bean 초기화 전에 실패하는 경계를 검증한다. 이 절은 설정 변경의 설계 승인이고 실제 Gradle 파일은 아직 바꾸지 않았다.

### 4.12 제품/테스트 main의 공통 시작 경로

사용자는 제품과 테스트에서 같은 초기화 검사를 빠뜨리지 않도록 시작 코드 몇 줄을 공통 메서드로 모으는 방식에 동의했다. 별도 프로그램/서버/시작 프레임워크를 만들지 않고 기존 CommerceBatchApplication에 public static SpringApplication createApplication(Class<?>... additionalSources) 하나를 둔다.

```text
createApplication(additionalSources):
  app = new SpringApplication(CommerceBatchApplication.class)
  app.addPrimarySources(추가 source 목록) // 제품 호출은 비어 있음
  app.addInitializers(new WeeklyRankingLaunchContextInitializer())
  return app // 여기서는 run/DB 연결/System.exit을 하지 않음

CommerceBatchApplication.main(args):
  context = createApplication().run(args)
  code = SpringApplication.exit(context)
  System.exit(code)

WeeklyRankingTestProcessMain.main(args): // src/test의 자식 실행용
  context = CommerceBatchApplication.createApplication(WeeklyRankingFaultSupport.class).run(args)
  code = SpringApplication.exit(context)
  System.exit(code)
```

initializer 등록을 두 main에 복제하지 않는다. 두 경로는 공통 메서드를 거쳐서만 SpringApplication을 만들고 동일한 Environment/bean 초기화 전 보존 검사와 Runner의 종료 코드 경로를 사용한다. 제품 main은 테스트 클래스를 참조하지 않고 추가 source 인자에 대한 의존 방향도 test→main으로 유지한다. 기본 시간대 설정·Boot 자동 구성·Demo 기동 동작은 이 함수 추출로 바꾸지 않는다. 런처/실행 로그/상태 복구 API를 이 메서드에 추가하지 않는다.

launch.enabled=false인 일반 @SpringBootTest fixture는 제품 main을 호출하지 않아도 된다. 이 경로는 초기화 검사를 통과했다는 증거로 세지 않고, 같은 DB/context에서 실제 job/checkpoint를 검증하는 책임이다. §7의 명시 launch 자식과 보존 검사 검증은 위 공통 factory 경로를 사용한다. 검사가 필요한 테스트에서 별도 new SpringApplication/Builder로 initializer를 누락하지 않는다. 활성 launch에 update/create/always 등 보존 위반 설정을 주면 bean/DB 초기화 전 실패해야 한다는 기준은 [§4.8](#48-기존-bean-조립과-런처-클래스)을 유지한다.

이 절은 main 두 곳의 중복/누락을 줄이는 상세설계이며 실제 Java 코드나 기동/종료 코드 검증을 완료한 결과가 아니다.

## 계산 중단과 수동 복구

계산·chunk·실제 DB TX의 전제는 [§5.1~5.4](calculation-publication.md#51-chunk-크기와-실패-경계)를 함께 읽는다.

### 5.5 명시 재시작과 상태 확인 후 수동 복구

사용자는 자동 재시작 기능을 추가하지 않고 상태 확인 후 수동 복구하는 절차를 문서화하기로 선택했다. 실패를 감지해 자동 JobLauncher 재호출·자체 재시도·metadata 수정·새 run.id 작업 생성을 수행하지 않는다. 원인 확인 뒤 운영자가 명시적으로 실행하는 재시작과, 실행 중 Reader가 영속 checkpoint를 복원하는 동작은 자동 재시작 기능과 구분한다.

일반적인 재시작의 목표는 같은 업무 식별 parameter로 같은 JobInstance의 새 JobExecution을 만들고, 같은 snapshot·영속 checkpoint를 사용해 이어가는 것이다. 예를 들어 상품7·8 chunk commit 뒤9 직전 실패가 확정되고 metadata가 재시작 가능한 상태이면, 원인 제거와 이전 실행 종료 확인 뒤 동일 식별값으로 명시 실행해9부터 처리한다. 입력·stage·metadata를 지우지 않고 revision을 올리지 않는다. 사용자 후속 합의에 따라 운영자는 대상 jobExecutionId를 지정하고 프로그램이 그 실행의 기존 parameter를 조회해 재시작한다. 실행 옵션은 [§4.7](#47-실행-옵션과-최초-준비-전제), 기존 JobOperator 호출/상태 검사는 [§4.6](#46-실행-id를-통한-명시-재시작)·[§5.6](#56-재시작-전-확인-순서와-지원-범위)을 따른다.

상태 확인 대상은 이전 실행/프로세스의 생존·진행 여부, Job/Step 상태·영속 ExecutionContext, snapshot 상태·manifest, stage·완성 ranking, 해당 기간의 공개 pointer다. 구체적인 조회 SQL·확인 순서·중단 조건은 [§5.6~5.8](#56-재시작-전-확인-순서와-지원-범위)을 따른다.

UNKNOWN·진행 중으로 남은 metadata·commit 응답 불명에서는 같은 parameter를 즉시 재실행하면 해결된다고 보장하지 않는다. 지원하는 재시작 조건과 실제 업무 commit 상태를 먼저 확인해야 한다. 공개가 이미 성공한 경우 정상 결과를 지우거나 되돌리지 않고, 동일 snapshot 공개의 안전한 재실행 계약을 지킨다. 현재 선택은 metadata의 수동 SQL 변경·상태 강제 변경·별도 관리 API/CLI·새 복구 job 도입 승인이 아니다. 지원하는 수동 확인/재시작 범위와 지원하지 않는 상태 수선은 [§5.6~5.8](#56-재시작-전-확인-순서와-지원-범위)에 구분한다.

자동 복구 대안은 운영 개입을 줄일 수 있지만 진행 중 실행과 실패/불명을 잘못 구분해 checkpoint나 공개 결과를 훼손할 위험, 중복 실행 통제·상태 수리 계약이 추가된다. 이번에는 명시적인 상태 확인과 수동 절차를 선택하고 복구 속도·운영자 개입 비용을 수용한다. 단, 원문의 공개 commit 뒤 metadata 실패 구분·동일 공개 재실행 안전성 검증을 제외하는 선택은 아니다. 구체적인 테스트 구성은 전체 처리 설계 이후 논의한다.

### 5.6 재시작 전 확인 순서와 지원 범위

수동 복구는 실패 원인 제거 → 저장 상태 확인 → 허용된 경우에만 명시 restart 순서다. 이번 지원 경로는 종료가 확인된 FAILED/STOPPED의 정상 재시작이다. UNKNOWN/종료 불명 상태를 임의로 FAILED로 바꿔 재시작하는 기능까지 포함하지 않는다.

1. 해당 기간의 다른 launch를 시작하지 않는다. 이전 프로세스가 살아 있는지 실행 도구/OS의 프로세스 정보와 실행 로그로 확인한다. 시간 경과나 DB의 END_TIME 하나만으로 종료/rollback을 확정하지 않는다. 살아 있거나 소유자를 모르면 중단하고 실행 소유자에게 확인한다. PID/heartbeat를 저장하는 새 테이블은 만들지 않는다.
2. JobExplorer.getJobExecution(지정 ID)로 job 이름/instance/세 identifying 값을 확인한다. 같은 JobParameters로 JobRepository.getLastJobExecution을 조회해 지정 ID가 최신인지 검사한다. 최신이 아니면 최신 ID만 안내하고 중단한다. 같은 기간의 다른 instance에 running/UNKNOWN/미확인 commit이 없는지도 [§4.4](#44-실행-준비-메서드와-새-계산-순서)의 이력 조회로 확인한다.
3. 최신 JobExecution과 그 instance에서 재개에 사용될 각 Step의 최근 실행·ExecutionContext를 읽는다. Job이 FAILED/STOPPED이고 재개할 Step도 UNKNOWN/진행 중/ABANDONED가 아닌지 확인한다. Step의 framework 상태와 원인 예외를 함께 확인하고 endTime 유무만으로 상태를 변경하지 않는다.
4. snapshot manifest·stage·완성 결과·pointer를 [§5.7](#57-읽기-전용-확인-자료와-보존정리) 순서로 읽어 실패 단계와 맞는지 확인한다. 정상 종료 이전의 chunk만 남아 있는 것과 부분 입력/깨진 공개본을 구분한다. checksum 대조가 필요한 손상 의심은 복사본을 기준으로 하며 현재 source를 과거 snapshot의 기대값으로 삼지 않는다.
5. 확정 rollback/종료가 확인되고 DB/metadata의 모순이 없을 때 원인(예: 데이터 변환/연결 장애)을 제거한 뒤 [§4.7](#47-실행-옵션과-최초-준비-전제) restart 명령을 운영자가 명시 실행한다. 실행 사이 input/stage/result/metadata를 지우지 않는다. launch가 영속 metadata의 최신 상태를 다시 검사하므로 확인 후 상태가 바뀌었으면 거절된다. 이 검사는 분산 동시 실행 보장이 아니며 같은 기간 직렬 실행 전제를 유지한다.

Launcher가 프로세스 생존/rollback을 DB만으로 완전히 판정할 수는 없다. 소유자의 종료·원인 확인은 수동 절차이고, Runner는 영속 상태/식별값/최신 ID의 기계적 검사를 담당한다. 스스로 프로세스를 강제 종료하거나 새 복구 작업을 생성하지 않는다.

### 5.7 읽기 전용 확인 자료와 보존/정리

다음 SQL은 수동 확인용 prepared parameter 예시다. 운영자/테스트는 SELECT만 사용하며 이번에 실행한 결과가 아니다. Batch 컬럼은 로컬5.2.2 schema-mysql.sql과 대조했다. 원시 context bytes를 임의 JSON으로 파싱하거나 SQL로 고치지 않고, checkpoint 값은 JobExplorer가 역직렬화한 StepExecutionContext로 읽는다.

```sql
SELECT JOB_EXECUTION_ID, JOB_INSTANCE_ID, STATUS, START_TIME, END_TIME,
       LAST_UPDATED, EXIT_CODE, EXIT_MESSAGE
FROM BATCH_JOB_EXECUTION
WHERE JOB_INSTANCE_ID = ?
ORDER BY JOB_EXECUTION_ID;

SELECT STEP_EXECUTION_ID, JOB_EXECUTION_ID, STEP_NAME, STATUS,
       READ_COUNT, WRITE_COUNT, COMMIT_COUNT, ROLLBACK_COUNT,
       START_TIME, END_TIME, EXIT_CODE, EXIT_MESSAGE
FROM BATCH_STEP_EXECUTION
WHERE JOB_EXECUTION_ID = ?
ORDER BY STEP_EXECUTION_ID;

SELECT snapshot_id, period_start, period_end, revision, status,
       input_fixed_at, input_count, product_count, input_checksum,
       result_checksum, completed_at, deleted_at
FROM ranking_snapshot
WHERE snapshot_id = ?;

SELECT product_id, score FROM ranking_score_stage
WHERE snapshot_id = ? ORDER BY product_id;

SELECT product_id, score, calculated_rank FROM ranking_result
WHERE snapshot_id = ? ORDER BY calculated_rank;

SELECT publication.period_start, publication.published_snapshot_id,
       snapshot.revision, snapshot.status, publication.published_at
FROM ranking_publication AS publication
LEFT JOIN ranking_snapshot AS snapshot
  ON snapshot.snapshot_id = publication.published_snapshot_id
WHERE publication.period_start = ?;
```

|관찰한 상태|허용하는 다음 행동|
|---|---|
|실행 등록이 확정 rollback돼 instance/execution 없음|동일 기간 history/snapshot을 다시 조회한 뒤 명시 new 가능. 응답 불명은 이 경우로 추정하지 않음|
|Job FAILED/STOPPED, freeze 완료 전, snapshot 정보/복사본 모두 없음|같은 최신 executionId로 restart. 아직 입력이 고정되지 않아 다시 복사하는 것은 정상|
|Job FAILED/STOPPED, READY, 완료 freeze와 계산 checkpoint 존재|기존 입력을 유지하고 계산 재개. 예시 checkpoint2·상품7/8 저장 뒤에는9부터|
|공개 TX의 확정 rollback, READY와 계산 완료 기록 존재|기존 공개본 보존 확인 후 restart. 공개 Step을 같은 snapshot으로 재실행|
|대상 COMPLETED, pointer가 대상 또는 더 최신, Job/Step이 재시작 가능한 실패 상태|결과/시각을 지우지 않고 restart. 공개 Step 재진입이면 §6의 ALREADY_PUBLISHED/SKIPPED_NEWER, 이미 완료 Step이면 생략|
|Job COMPLETED|같은 instance restart 거절. 늦은 구매 재계산은 운영자가 별도 new를 선택|
|UNKNOWN, 살아 있는 실행, 종료 불명, commit 응답 불명, metadata/manifest 모순|보존하고 중단. [§5.8](#58-unknowncommit-결과-불명에서의-중단-절차) 자료 수집/지원 한계 적용|

정상 재시작 사이 반드시 보존하는 것은 source 구매 활동, 다섯 랭킹 테이블의 해당 데이터, Batch instance/execution/parameter/Step/ExecutionContext 전부다. 상품/브랜드 등 API 표시 데이터와 실제 연결 증거도 검증 목적에 맞게 보존한다. cursor/statement/connection/context 같은 실행 자원은 종료 때 반환하지만 DB 데이터 정리와 혼동하지 않는다. 실패 chunk의 저장 취소는 TX rollback의 책임이지 운영자의 DELETE 정리 작업이 아니다.

증거를 남기기 전 테스트 DB/schema/metadata를 비우지 않는다. 검증 완료 후 테스트 소유 데이터만 정리하는 순서와 실제 연결의 Kafka/PG 자원 소유권은 다음 검증 구성에서 정한다. 제품에 자동 purge/보존기간/과거 실행 삭제 기능을 추가하지 않는다.

### 5.8 UNKNOWN·commit 결과 불명에서의 중단 절차

공개 데이터가 보인다는 사실과 Batch의 재시작 가능 상태는 다르다. 먼저 이전 실행 주체가 종료했는지 확인하고, 지정/최신 executionId·instanceId·세 identifying 값·오류 시각/원인, Job/Step 상태·checkpoint, 입력 manifest·stage·완성 결과·현재 pointer를 읽어 보관한다. 등록 응답이 끊겨 ID를 못 받았다면 등록 전 로그의 식별값으로 JobExplorer에서 기존 instance/execution을 찾는다. DB 조회도 불가능하면 데이터가 없다고 결론 내리지 않고 연결 복구 뒤 다시 읽는다.

확인 결과 확정 rollback/종료와 FAILED/STOPPED가 입증되면 [§5.6](#56-재시작-전-확인-순서와-지원-범위)으로 돌아간다. 반대로 UNKNOWN이나 죽은 프로세스의 STARTING/STARTED/STOPPING이 그대로 남으면 이번 new/restart 경로로 복구 완료할 수 없다. Batch5.2.2 SimpleJobRepository는 UNKNOWN 이력이 있는 instance의 재시작을 거절하고, 진행 중 metadata도 거절한다. 시간이 지났다는 이유로 실행 가능 상태가 되지 않는다.

이때 완료된 snapshot/pointer가 있으면 그대로 유지하고 고객 API는 정상 공개본을 계속 읽는다. 공개가 안 됐으면 기존 공개본 또는 NOT_PUBLISHED를 유지한다. 새 run.id·revision으로 우회하거나 metadata 상태/버전/checkpoint를 직접 UPDATE/DELETE하지 않는다. 별도 JobOperator.abandon/상태 수선 CLI도 이번 범위에서 만들지 않는다. 업무 상태만 확인했다고 execution을 성공으로 기록하지 않는다.

이번 설계의 수동 절차는 여기까지가 지원 경계다. metadata 손상/불명의 실제 수선이 필요하면 수집한 증거를 사용자에게 제시하고, 어떤 상태를 어떤 근거로 고칠지·원래 checkpoint/공개본 보존·수선 후 검증을 별도 작업으로 승인받는다. 일괄 FAILED 변경 같은 검증되지 않은 복구 SQL을 제공하지 않는다. 이는 원문의 공개 commit/최종 metadata 실패 구분과 동일 공개 재진입 검증을 제외하는 뜻이 아니다. 그 검증과 현재 지원하지 않는 강제 상태 수선 도구를 구분한다.
