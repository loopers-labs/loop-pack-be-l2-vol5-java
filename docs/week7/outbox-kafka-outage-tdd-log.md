# W7 Kafka 통신 장애·관찰·재개 TDD 기록

2026-11-06 [W7-TDD-24](../week2/commerce-tdd-plan.md#w7-outbox-원자성과-복구-tdd-계획)와 C09의 실제 전송 실패·backlog 관찰·같은 행 재개를 보강한다. [단일 relay 기록](outbox-relay-tdd-log.md)의 정상 DB/broker 연결과 통제 future 실패 검증, [중단·재발행 E2E](outbox-republication-e2e-tdd-log.md)의 broker ACK 뒤 상태 저장 공백과 구분한다.

**신규 장애·복구 테스트 1개와 이를 포함한 관련 22개 스위트·143개 테스트가 통과했다.** 임시 변이의 반례를 검출하고 원본 복원 뒤 재검증했다. API ArchUnit과 test Checkstyle도 통과했으며, W7 전체 완료로 판정하지 않는다.

## 이번 변경 범위

| 대상 | 내용 |
| --- | --- |
| 새 테스트 | [OutboxRelayKafkaOutageIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/outbox/OutboxRelayKafkaOutageIntegrationTest.java) |
| 격리 자원 | Testcontainers MySQL·테스트 전용 KafkaContainer, UUID topic·inspector group |
| 장애 주입 | 테스트가 소유한 Kafka 컨테이너만 Docker pause하고 finally에서 unpause한다. 같은 컨테이너·DB·Outbox 행으로 복구한다. |
| 실제 전송 | 기존 OutboxRelay·OutboxDeliveryTransactions·KafkaOutboxSender·KafkaTemplate을 사용한다. spy는 실제 호출과 future를 관찰할 뿐 실패 future를 만들어 반환하지 않는다. |
| 보존할 계약 | 기존 행 PK·eventId·topic·key·payload·createdAt, 짧은 상태 TX와 DB TX 밖 전송, broker 확인 뒤 PUBLISHED |
| 별도인 검증 | 이번은 relay→broker 전송·DB 상태 검사다. PG와 production streamer를 다시 연결하지 않으며, 구매 활동 중복 방지는 기존 전체 E2E 증거를 따른다. |

Kafka 프로세스가 정지해 응답할 수 없는 상황이다. 네트워크 패킷 차단·OS 강제 종료·다중 broker 장애 실험으로 표현하지 않는다. 사용자 DB·기존 topic/group을 대상으로 삼지 않는다.

## 시간 설정과 재현 방법

| 값 | 이번 실험 |
| --- | --- |
| Kafka request timeout | 테스트 속성으로 1초 |
| Kafka delivery timeout | 테스트 속성으로 3초 |
| relay 전송 예산 | 기존 12초 유지 |
| 재시도 | 기존 5→10→20→40→60초 정책 유지. 이번은 첫 두 실패의 5초·10초를 확인 |
| 저장 시각 | 테스트 Clock으로 고정·이동. 네트워크 timeout·future 대기는 실제 경과 시간을 사용 |
| 실행 | 자동 scheduler OFF, 같은 상태 TX와 sender를 사용하는 새 relay 객체로 수동 실행 |

운영 YAML·producer 기본 timeout·TX/락·전달 정책을 변경하지 않는다. 고정 Clock을 재시도 시각 직전과 정확한 시각으로 이동해 wall-clock sleep 없이 기한 경계를 검사한다. 별도 runner 생성은 애플리케이션 프로세스 재시작이나 scheduler 자동 복구의 새 증거가 아니다.

## 한 실행의 기대값

| 단계 | DB 상태·관찰 | 실제 전송 확인 |
| --- | --- | --- |
| 준비 | PENDING 1·PUBLISHED 0·시도 0. 최초 대기 시각은 저장한 createdAt | 업무 이벤트와 구분한 readiness probe의 실제 ACK를 받은 뒤 broker offset 기준값 확보·pause |
| 첫 실패 | 같은 행 PENDING·publishedAt 없음·시도 1·오류·다음 시각 +5초 | 실제 sender/future 실패 |
| 기한 직전 | 행 전체 불변·새 시도 0 | 추가 send 없음 |
| 반복 실패 | 같은 행 PENDING·시도 2·다음 시각 +10초, 최초 대기 시각 불변 | broker는 계속 pause, 실제 전송 실패 반복 |
| 복구 | unpause 뒤 같은 행을 새 relay로 처리. PUBLISHED 1·PENDING 0·시도 3·오류 해소 | 실제 성공 metadata와 inspector가 읽은 topic/partition/offset·key/payload 대조 |
| 완료 후 재실행 | Outbox 전체 행 불변·시도 0 | relay의 추가 send 없음. 과거 미확인 전송의 늦은 도착까지 없다고 단정하지 않음 |

pause는 TCP 버퍼를 지우지 않는다. 실패했던 요청이 복구 후 broker에 기록될 가능성이 있으므로 **timeout/future 실패를 broker 수신 0회로 해석하지 않는다.** 실제 record 수를 관찰하고 모든 수신 내용이 동일한 저장 사실인지, 성공 metadata가 실제 record에 대응하는지 검사한다. inspector는 복구 후 확인한 끝 offset까지 읽으며 그 사이 도착한 동일 사건도 허용한다. broker record가 반드시 1개여야 한다는 기대값이나 end-to-end exactly-once 보장을 추가하지 않는다.

준비 probe는 producer의 metadata·초기화가 실제로 완료됐음을 확인하기 위한 테스트 데이터다. 그 ACK 이후 offset을 기준으로 업무 이벤트를 검사하므로 probe를 업무 eventId의 발행 수에 합산하지 않는다.

## 관찰 SQL과 상태 의미

테스트는 격리 DB에서 실제 JDBC 조회로 미발행·발행 건수, 가장 오래된 미발행 생성 시각, 누적 시도 수를 대조한다.

```sql
SELECT COUNT(*) AS total,
       SUM(status = 'PENDING') AS pending,
       SUM(status = 'PUBLISHED') AS published,
       MIN(CASE WHEN status = 'PENDING' THEN created_at END) AS oldest,
       SUM(attempt_count) AS attempts
FROM outbox_message;

SELECT event_id, attempt_count, next_attempt_at, last_error
FROM outbox_message
WHERE status = 'PENDING'
ORDER BY next_attempt_at, id
LIMIT 10;
```

PENDING 수가 줄고 PUBLISHED가 늘어나는 것은 broker 전달 상태의 변화다. 구매 활동 저장 완료나 consumer lag 해소를 의미하지 않는다. consumer의 오류·offset·구매 활동은 별도 소비 관찰 대상이며 기존 전체 E2E와 구분한다. 누적 시도 수도 실제 broker record 수가 아니다.

반복 장애에서는 같은 미발행 행을 보존하고 원인 해소 뒤 재시도 기한에 맞춰 재개한다. 이번에 확인하는 반복 응답 불능을 모든 영구 오류 유형의 재현으로 확대하지 않는다.

## TDD와 실행 결과

| 실행 | 관찰과 처리 |
| --- | --- |
| 첫 컴파일 | `/private/tmp/w7-outage-first.log`. 테스트 소스에서 Lombok 로그 필드를 생성하는 annotation processor가 없어 컴파일 실패했다. 테스트에 명시적인 Logger를 사용하도록 고쳤다. |
| 첫 실행 | `/private/tmp/w7-outage-baseline.log`. 기대한 SEND_FAILED 대신 relay TIMEOUT이 관찰됐다. metadata 조회만으로 producer 준비를 완료했다고 가정한 fixture를 수정해, 업무 이벤트와 구분한 probe의 실제 ACK 후 장애를 시작한다. |
| 기준 실행 | `/private/tmp/w7-outage-ready-baseline.log`, XML `/private/tmp/w7-outage-ready-baseline.xml`. 신규 1개 통과, BUILD SUCCESSFUL·종료 0, 23초. 위 fixture 보완을 생산 코드 결함 수정이나 업무 기대값 변경으로 기록하지 않는다. |
| 반례 Red | `/private/tmp/w7-outage-red.log`와 `.xml`. 재시도에서 실제 broker ACK를 받은 뒤 PUBLISHED 저장만 건너뛰는 임시 변이를 넣었다. 복구 assertion의 기대 `Result(1,1,false)`와 실제 `Result(1,0,false)` 차이로 실패했다. BUILD FAILED·종료 1, 22초. finally에서 생산 파일의 원본 bytes를 그대로 복원했다. |
| 최종 Green | `/private/tmp/w7-outage-green-regression.log`, 신규 테스트 XML `/private/tmp/w7-outage-green.xml`. 원본 복원 후 관련 22개 스위트·143개 통과, 실패·오류·skip 0. BUILD SUCCESSFUL·종료 0, 1분 16초. 신규 1개는 143개에 포함한다. |

기준 실행에서는 생산 코드가 이미 기대값을 만족했으므로 최종 변경은 테스트·문서 증분이다. 반례 Red는 회귀 검출력을 확인하는 임시 변이이며 실제 구현 결함을 발견·수정한 것으로 기록하지 않는다.

### 기준 실행의 실제 관찰

| 단계 | PENDING | PUBLISHED | 누적 시도 | 오류·다음 시각 | 가장 오래된 미발행 생성 시각 |
| --- | ---: | ---: | ---: | --- | --- |
| 준비 | 1 | 0 | 0 | 없음 | `2026-11-06T03:02:05.123456Z` |
| 첫 실패 | 1 | 0 | 1 | `SEND_FAILED`, `03:04:10.123456Z` | 준비와 동일 |
| 반복 실패 | 1 | 0 | 2 | `SEND_FAILED`, `03:04:20.123456Z` | 준비와 동일 |
| 복구 후 | 0 | 1 | 3 | 오류 해소 | 없음 |

- eventId: `order-confirmed:v1:42`, Outbox ID: `1`
- topic: `w7-outage-c591f883-4a29-4536-bba1-f12925c4c3c5`
- inspector group: `w7-outage-inspector-22c26f14-c8b6-410e-b829-d4d2efe875e5`
- **실제 업무 record 2개**, 최종 성공 metadata: partition `0`, offset `1`. 준비 probe는 제외한 수다.
- 같은 Outbox PK·eventId·topic·key·payload·createdAt을 보존했으며 완료 후 재실행의 추가 send는 0이다.

이 실행에서는 실제로 같은 사건이 2개 record로 관찰됐다. 따라서 failed future를 수신 0회로 해석할 수 없다는 경계를 함께 드러낸다. 이것은 새 구매 활동 중복 검증이 아니라 동일 저장 사실의 실제 broker 전달 검증이며, 소비 DB의 중복 방지는 기존 전체 E2E를 참조한다.

### 최종 관련 회귀·정적 검사

```bash
./gradlew :apps:commerce-api:test \
  --tests '*Outbox*' \
  --tests '*ArchitectureTest' \
  :apps:commerce-api:checkstyleMain \
  :apps:commerce-api:checkstyleTest --console=plain
```

| 검사 | 이번 결과 |
| --- | --- |
| API 관련 테스트 | **22개 스위트·143개**, 실패·오류·skip 0. 신규 장애 테스트 1개와 Outbox 저장·relay·복구 및 API ArchUnit을 포함 |
| 전체 E2E 회귀 | `PgOrderOutboxRecoveryEndToEndTest`, `W6OutboxBackfillEndToEndTest`를 실제 재실행해 통과. 이들 기존 E2E는 production streamer JVM·DB를 사용 |
| API ArchUnit | 1개 실제 실행·통과 |
| API Checkstyle | test 실제 실행·위반 0. main은 UP-TO-DATE로 기존 위반 0 결과 재사용이며 새 실행과 구분 |
| streamer 전체 검사 | 이번 명령에서는 실행하지 않음. 위 E2E의 실제 streamer 실행과 별도 |
| 종료·정리 | BUILD SUCCESSFUL·종료 0. 종료 후 `docker ps`가 비어 있고 CommerceStreamerApplication·Gradle Test Executor 프로세스도 없어 소유 자원 종료를 확인. 사용자 DB·기존 topic/group·볼륨 미변경 |
| diff | `git diff --check` 통과. 생산 코드·정책·시간 설정·검사 기대값 완화 없음. 커밋·푸시하지 않음 |

최종 회귀의 장애 테스트는 기준 실행과 **별도의 격리 실행**이다. topic은 `w7-outage-c2b9b299-737c-44f3-af29-1bd1afa5c146`, inspector group은 `w7-outage-inspector-5ae82ff7-6a4c-44ec-a0ca-6498baf4ef42`다. 이 실행도 시도 3회·업무 record 2개·최종 ACK partition 0/offset 1을 확인했다. 두 실행의 사건·record 수를 합산하거나 한 실행으로 표현하지 않는다.

## 남은 단계

후속 문서 작업에서 [relay 실행·재개·보존 안내](outbox-operations-retention.md)를 현재 코드와 대조해 정리했다. 아래의 운영·보존 문서 항목은 완료됐고, **양 앱 전체 검사·공식 11항목 최종 대조**가 다음 단계다. 이 문서의 실험·검사 건수는 당시 실제 실행 결과로 유지한다.

- C10의 Outbox·Kafka·consumer dedup 보존 관계와 단일 relay 운영·재개 설명은 위 후속 안내에 정리했다.
- C11의 양 앱 전체 검사와 공식 11항목의 코드·실행 근거를 확인한다. 이번 관련 검사만으로 전체 W7 완료를 선언하지 않는다.
- PR·기술 글·커밋·제출은 구현·검증과 별도로 다룬다.
