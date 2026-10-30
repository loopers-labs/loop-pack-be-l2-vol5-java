# W6 Kafka partition·consumer group 배정 검증 기록

## 요청과 범위

2026-10-29 사용자가 partition 2개에서 같은 group의 consumer를 1→2→3개로 늘려 실제 배정과 수신을 TDD로 검증하도록 요청했다. [과제 원문 §5](assignment-original.md)의 요구와 [W6-C04](grading-criteria.md#공식-11개-항목과-확인-증거)를 대상으로 한다.

신규 [KafkaGroupAssignmentIntegrationTest](../../apps/commerce-streamer/src/test/java/com/loopers/infrastructure/purchase/KafkaGroupAssignmentIntegrationTest.java)는 **Spring context·DB 없이**, 자신이 시작한 `apache/kafka:3.8.1` Testcontainers 한 대에서 실행한다. 배정용 topic/group과 key 실험용 topic에 매 실행 UUID를 붙인다. 업무 topic `order-events`에 가짜 구매 이벤트를 넣거나 현재 사용자 DB·기존 broker·topic/group·volume을 사용하지 않는다.

기준 HEAD는 `6e686d824d979ad16a7a2baa70afbeee2c3ce460`이며 기존 미커밋 작업 트리에서 검증했다. 이번 변경은 테스트와 관련 문서뿐이다. 운영 listener의 concurrency 1·트랜잭션·잠금·ack/error handler·producer 설정은 변경하지 않았다.

## 테스트에서 먼저 고정한 기대값

| 항목 | 기대값·검증 방법 |
| --- | --- |
| 실제 topic | AdminClient로 만든 topic의 partition 수가 2인지 조회한다. 복제 계수는 1이다. |
| 실제 group 참여 | consumer마다 전용 스레드에서 subscribe·poll한다. 단순 객체 개수가 아니라 AdminClient의 group `STABLE` 상태와 실제 member 수를 확인한다. |
| 배정 안정화 | Admin의 member ID/배정과 각 worker의 member ID/배정이 일치하고 generation도 같아야 한다. 같은 generation·배정이 100ms 간격 검사에서 5회 연속 유지되며 모든 worker의 poll 횟수가 증가해야 한다. |
| partition 소유 | 두 partition의 배정 합집합이 정확히 `{0, 1}`이고, 각 partition은 한 member에게만 배정된다. 특정 이름의 consumer가 특정 partition을 맡는다고 고정하지 않는다. |
| 1→2→3 기대 | 배정 개수를 정렬하면 각각 `[2]`, `[1,1]`, `[0,1,1]`이다. 3명째에도 실제 group member는 3명이며 무배정 member도 poll을 계속한다. |
| 각 단계의 실제 수신 | 배정 안정화 뒤 partition 0·1에 단계별 고유 probe를 명시 전송한다. producer metadata와 수신 consumer·partition·offset·key·본문을 모두 대조한다. |
| 다음 단계 진입 | 두 probe의 실제 수신과 AdminClient가 읽은 committed offset `record.offset+1`을 확인한 후에만 다음 consumer를 추가한다. |
| key 매핑 | partition을 지정하지 않고 같은 String key를 반복 전송한다. 같은 key의 실제 metadata partition이 같고 각 전송의 offset은 구별돼야 한다. 다른 key가 다른 partition으로 가야 한다는 assertion은 없다. |

worker는 commit 성공 후 수신 증거를 공유 queue에 게시한다. 테스트 스레드는 불변 배정 snapshot과 수신 증거만 읽는다. `subscribe/poll/assignment/groupMetadata/commit/close`는 모두 consumer를 소유한 worker에서 호출하며, 다른 스레드에서는 종료 신호용 `wakeup()`만 호출한다. worker 예외는 테스트 스레드에 전달하여 단순 대기 timeout으로 숨기지 않는다.

## 첫 실행과 TDD 해석

운영 코드를 바꾸기 전에 위 기대값을 두 테스트로 작성하고 실제 broker에 실행했다. **첫 실행부터 2개 모두 통과**했다. 이번은 Kafka의 배정·key 동작과 실제 수신을 검증하는 증분이며, 가짜 Red를 만들려고 기대값이나 운영 설정을 변경하지 않았다. 새로운 서비스 로직을 구현하거나 이전 중복 처리 코드를 고친 작업으로 표현하지 않는다.

| 테스트 | 검증 범위 |
| --- | --- |
| `twoPartitionsAreSharedByOneThenTwoThenThreePollingMembersAndBothAreReadAtEveryStage` | 같은 group에 consumer를 추가하며 안정된 배정, 매 단계 두 partition의 담당·수신·commit, 총 6건의 probe를 확인 |
| `identicalSerializedKeysUseTheSamePartitionAtAFixedPartitionCount` | 별도 2-partition topic에서 `42`, `43` 각각 3회 전송. partition을 producer가 선택하게 하고 실제 metadata를 비교 |

## 2026-10-29 10:51 KST 실제 배정 결과

- 배정 topic: `w6-assignment-probe-0d337482-d3da-4f08-a420-99c8d8eb3683`
- group: `w6-assignment-group-817a3491-3a2f-483c-998c-97c4eadb47d5`
- topic과 group은 세 단계 동안 동일하고, 기존 consumer는 계속 poll하면서 새 consumer만 추가했다.

| consumer 수 | consumer-1 | consumer-2 | consumer-3 | 해당 단계 probe 수신 | 각 partition의 committed offset |
| ---: | --- | --- | --- | --- | --- |
| 1 | partition 0·1 | 없음 | 없음 | 각 partition의 offset 0, 총 2건 | 1 |
| 2 | partition 0 | partition 1 | 없음 | 각 partition의 offset 1, 총 2건 | 2 |
| 3 | partition 0 | partition 1 | 배정 없음·group 참여 유지 | 각 partition의 offset 2, 총 2건 | 3 |

consumer-1/2/3은 이 실행의 clientId다. 표는 실제 관찰이며, 다른 실행에서도 반드시 같은 이름이 같은 partition을 맡아야 한다는 계약은 아니다. 각 단계에서 partition당 담당은 1명이고, 최종 수신 증거는 중복·누락 없이 **6건**이었다. 운영 업무의 모든 장애에서 전달이 정확히 한 번이라는 뜻은 아니며 안정화된 각 단계의 probe 수신 결과다.

쉽게 표현하면 partition은 두 개의 작업 줄이고 consumer는 그 줄을 맡는 작업자다. 작업자 한 명은 두 줄을 맡고, 두 명이면 한 줄씩 맡으며, 세 명이면 한 명은 맡을 줄이 없어 대기했다. 이 실험은 배정과 수신 확인이며 처리량·지연 성능 측정은 아니다.

## 실제 key 매핑 결과

별도 topic `w6-key-probe-b0d4c926-a9ca-40a0-b19b-0e4c161db18d`에서 partition 인자를 지정하지 않고 `42, 43, 42, 43, 42, 43` 순서로 보냈다.

| String key | 관찰 partition | 실제 offset |
| --- | ---: | --- |
| `42` | 0 | 0·2·4 |
| `43` | 0 | 1·3·5 |

같은 key는 같은 partition으로 갔고, **서로 다른 두 key도 같은 partition에 갈 수 있었다.** 두 partition의 실제 수신은 앞의 명시 전송 실험으로 별도 확인했다. key 실험에 양쪽 partition으로 분산돼야 한다는 기대를 추가하지 않는다. 이 관찰은 같은 key 직렬화·producer partitioner·고정 partition 2개라는 조건에 한정한다.

## 설정 선택과 보장 범위

| 설정·책임 | 선택 이유·한계 |
| --- | --- |
| producer·consumer·admin bootstrap | 모두 해당 Testcontainers의 실제 endpoint를 사용한다. 사용자 로컬 주소나 공유 broker로 fallback하지 않는다. |
| producer `acks=all`, idempotence=true | broker가 요구하는 ISR 확인과 producer 재전송 중복 보호를 사용한다. 이번 broker 1대·replication factor 1은 여러 broker의 복제 장애 내구성을 검증하지 않는다. 소비 DB의 한 번 효과도 이 설정으로 증명하지 않는다. |
| probe consumer | String key/value, auto commit false, earliest, topic 자동 생성 금지. 합성 transport probe이며 주문 구매 활동 저장을 호출하지 않는다. |
| 작은 메시지의 대기 | fetch.min.bytes=1, fetch.max.wait=100ms, poll=100ms. 작은 probe를 큰 batch가 찰 때까지 기다리지 않도록 한다. |
| 테스트 group 설정 | session timeout 6초·heartbeat 1초, 전용 broker의 initial rebalance delay 0. 실험을 유한 시간에 관찰하기 위한 fixture 값이며 운영 권장값으로 적용하지 않는다. |
| 안정화·실패 노출 | 배정 최대 20초·수신 최대 10초, Admin/commit 등의 API 호출도 유한 timeout. 대기 중 worker 오류를 즉시 전달한다. |
| 정리 순서 | 모든 worker에 stop/wakeup을 먼저 전달 → 각 thread를 유한 시간 join → worker의 finally close 완료 확인 → producer/admin close → Testcontainers 종료. 기존 topic/group을 열거나 삭제하지 않는다. |

## 검사 명령과 결과

```shell
./gradlew :apps:commerce-streamer:test \
  --tests 'com.loopers.infrastructure.purchase.KafkaGroupAssignmentIntegrationTest' \
  --tests 'com.loopers.architecture.ArchitectureTest' \
  :apps:commerce-streamer:checkstyleMain \
  :apps:commerce-streamer:checkstyleTest \
  --offline --console=plain
```

2026-10-29 **10:51 KST**, **BUILD SUCCESSFUL / 14초 / 종료 코드 0**.

| 범위 | 결과 |
| --- | --- |
| 신규 배정·key 실험 | 2개 통과 |
| streamer ArchUnit | 1개 통과 |
| 이번 JUnit 실행 합계 | **2개 스위트·3개**, 실패·오류·skip **0** |
| Checkstyle test | 실제 실행·위반 0 |
| Checkstyle main | 운영 코드 변경이 없어 UP-TO-DATE, 기존 위반 0 보고서 유지 |
| 자원 종료 | worker 종료·client close 검증, 종료 후 실행 중 `org.testcontainers=true` 컨테이너 0개 |

관련 diff 검토·`git diff --check`도 통과했다. 이전 streamer 전체 97개 및 API 862개는 이번에 재실행하거나 합산하지 않았다. 운영 코드에 변경이 없고 독립 Kafka 실험만 추가했으므로 이번 변경에 해당하는 테스트·계층 검사·lint를 실행했다. XML과 [HTML 보고서](../../apps/commerce-streamer/build/reports/tests/test/index.html)는 이후 실행으로 바뀔 수 있어 위 명령·시점·관찰값을 이 문서에 보존한다.

## 남은 단계

1. **전체 PG→Kafka→streamer 연결과 W7 인계:** 실제 주문·PG 경로에서 직접 발행 누락 1건·정상 소비 1건을 만들고, DB 결과·사건 ID·시각·품목·사용 revision·재현 코드·정리 증거를 남긴다(C01·C08·C09).
2. **최종 검증·문서 대조:** 관련 회귀와 W6 공식 11개 항목의 증거를 최종 대조한다. 기술 글·커밋·PR·제출은 별도 작업이다.

C04 배정 실험과 앞선 C05 재전달/C07 listener 실패 검증은 완료된 범위다. 이를 전체 W6 완료로 표시하거나 Outbox·랭킹 구현으로 확대하지 않는다.
