# weeklyRanking의 STARTED 실행 복구

## 전제

프로세스가 비정상 종료돼도 Batch 메타데이터에는 `STARTED`가 남을 수 있다. 이 기록만으로는 실제 프로세스가 아직 실행 중인지 알 수 없다. 복구 전에 운영자가 ECS 작업 상태와 실제 프로세스 종료를 확인한다. 같은 기간에 살아 있는 다른 실행이 없고, 기존 공개본과 미완성 결과를 어떻게 다룰지도 확인한다.

아래 명령의 `termination-confirmed=true`는 **운영자의 확인 표시**다. 애플리케이션이 ECS를 조회하거나 프로세스 종료를 자동으로 검증하지는 않는다. 아직 실행 중인 프로세스를 포기 처리하면 같은 기간 작업이 중복 실행될 수 있다.

## 종료가 확인된 실행 포기

이 복구 방식은 이전 JobInstance를 재시작하지 않고 새로운 snapshotId/revision으로 다시 시작하는 경우에 사용한다. 기존 JobExecution과 진행 중으로 남은 StepExecution을 `ABANDONED`로 기록한다. 스냅샷, 임시 점수, 공개 결과는 삭제하지 않는다. 같은 JobInstance는 다시 실행할 수 없고, 다른 revision의 새 JobInstance를 별도로 실행해야 한다.

예시는 개발 환경의 실행 방법이다. `EXECUTION_ID`는 확인한 실제 JobExecution ID로 바꾸고, DB와 프로필이 의도한 환경을 가리키는지 별도로 확인한다.

```bash
./gradlew :apps:commerce-batch:bootRun --args='--spring.batch.job.name=weeklyRanking --spring.batch.job.enabled=false --spring.batch.jdbc.initialize-schema=never --spring.jpa.hibernate.ddl-auto=none --weekly-ranking.recovery.execution-id=EXECUTION_ID --weekly-ranking.recovery.termination-confirmed=true'
```

`execution-id`를 지정한 복구 실행에서는 위 다섯 조건이 모두 충족되지 않으면 DB 초기화 전에 애플리케이션 시작을 거부한다. 특히 이 프로젝트의 `local/test` 기본 JPA 설정은 `create`이므로 반드시 `none`으로 지정해야 한다. 활성 상태로 남은 `weeklyRanking` 실행만 변경할 수 있다. 이 명령은 새 Job을 자동으로 실행하지 않는다.

## 확인 범위

테스트는 테스트 MySQL에 남겨 둔 실행 상태를 사용해 차단과 포기 후 새 작업을 확인한다. 실제 ECS 강제 종료·프로세스 확인·운영 DB 복구를 수행한 검증은 아니다. 재시작할 원래 스냅샷을 보존해 이어가야 한다면 이 포기 방식과 별도로 복구 정책을 검토한다.
