# Transactional Outbox의 Relay가 제대로 구현되었는지 검증하기

---

## 테스트 개선 단계


| 단계                             | 수행할 일                                                                      | 확인할 것                      |
| ------------------------------ | -------------------------------------------------------------------------- | -------------------------- |
| 1. 불변조건 정의                     | Relay가 지켜야 할 정확성 조건과 전제조건을 정한다.                                            | 테스트가 판정할 기준이 명확한가?         |
| 2. 초기 테스트 작성                   | 특정 구현의 내부 구조에 묶이지 않도록 불변조건을 관찰 가능한 결과로 테스트한다.                              | 테스트가 구현 방식과 독립적인가?         |
| 3. 초기 구현                       | AI에게 대략적인 요구사항을 주고 MySQL polling relay를 구현하게 한다. 프롬프트와 결과를 보존한다.           | 초기 구현이 어떤 가정을 했는가?         |
| 4. Green 확인                    | 초기 테스트를 실행한다. 실패하면 테스트 기대값을 낮추지 않고 구현을 고쳐 통과시킨다.                           | 정상 구현이 기준 테스트를 만족하는가?      |
| 5. 장애·결함 주입 (Mutation Testing) | Kafka 응답 지연·연결 단절·Relay 종료 같은 장애와 ACK 전 완료 처리·재시도 조회 누락 같은 코드 결함을 각각 주입한다. | 테스트가 장애 복구와 잘못된 구현을 탐지하는가? |
| 6. 테스트 보강                      | 결함 주입 후에도 테스트가 통과한 경우, 놓친 불변조건과 시나리오를 테스트에 추가한다.                           | 잘못된 구현을 실제로 실패시키는가?        |
| 7. 구현 개선                       | 새 테스트가 실패하는 구현을 불변조건에 맞게 수정한다.                                             | 보강된 테스트가 다시 통과하는가?         |


5~7단계를 결함·장애 시나리오별로 반복한다. 결함이 주입된 구현은 테스트에서 실패해야 하고, 수정된 구현은 같은 테스트를 통과해야 한다.

# Mutation Testing

## 결함 주입 시나리오




| 우선순위 | 시나리오            | 주입할 코드 결함                                      | 검증할 불변조건         | 완료 여부 | 테스트가 잡았는지 | 테스트 추가 여부 |
| ---- | --------------- | ---------------------------------------------- | ---------------- | ----- | --------- | -------- |
| P0   | ① ACK 이전 완료 처리  | `send()` 직후 `PUBLISHED` 처리                     | ACK 전 완료 금지      | ✅     | ❌         | ✅        |
| P0   | ② 커밋 순서 역전      | ID 커서 기반 조회로 변경                                | 커밋 이벤트 누락 방지     | ✅     | ❌         | ✅        |
| P0   | ③ 무한 대기         | Kafka Future 실패 시 결과 Future 미완료 및 Relay 무기한 대기 | 미확정 이벤트 복구 가능    | ✅     | ❌         | ✅        |
| P1   | ④ 잘못된 상태 전이     | `PENDING`과 `FAILED`를 반대로 기록                    | 상태 전이 정확성        | ✅     | ⭕         | —        |
| P1   | ⑤ 재시도 누락        | `FAILED`를 조회 조건에서 제거                           | 재시도 가능 이벤트 누락 방지 | ✅     | ⭕         | —        |
| P1   | ⑥ 배치 크기 무시      | `LIMIT` 제거                                     | 설정된 최대 건수 준수     | ✅     | ⭕         | —        |
| P1   | ⑦ 시도 횟수 오류      | `attempt_count` 증가 제거                          | 시도 횟수 정확성        | ✅     | ⭕         | —        |
| P1   | ⑧ BLOCKED 전이 오류 | `>= maxAttempts`를 `>` 또는 `<=`로 변경              | 재시도 상한 정확성       | ✅     | ⭕         | —        |
| P2   | ⑩ 이벤트 ID 변경     | 재시도할 때 새로운 ID 생성                               | 이벤트 식별자 유지       | ✅     | ❌         | ✅        |
| P2   | ⑪ 경계값 오류        | `<= NOW`를 `< NOW`로 변경                          | 재시도 가능 시각 정확성    | ✅     | ⭕         | —        |

`테스트 추가 여부`는 최초 mutation에서 테스트가 놓친 결함을 보완하기 위해 테스트를 추가했는지를 나타낸다.

## 전체 테스트 시나리오

| 시나리오 | 기대 결과 | 관련 테스트 |
| --- | --- | --- |
| 발행 대상 조회 | 재시도 시각이 도래한 `PENDING`·`FAILED`만 조회하고 `PUBLISHED`·`BLOCKED` 및 미래 재시도 건은 제외한다. 경계 시각은 포함한다. | `selectsOnlyDuePendingAndFailedRows` |
| 조회 순서·배치 제한 | 생성 시각이 오래된 이벤트부터 정렬하고 설정된 최대 건수만 가져온다. | `ordersEligibleRowsByOldestCreationTime`, `limitsResultToConfiguredBatchSize` |
| 순차 발행 및 폴링 간 누락 방지 | 한 배치의 이벤트를 순서대로 발행하고, 이전 폴링 뒤 커밋된 더 작은 ID의 이벤트도 다음 폴링에서 조회한다. | `publishesOldestCurrentlyEligibleRowsAcrossPolls` |
| 발행 시도 횟수 | 발행 시도마다 `attempt_count`를 증가시키고, 재시도 후 DB에 누적된 횟수를 기록한다. | `branchesByPublishResultAndAttemptLimit`, `republishesFailedOutboxWhenRetryTimeArrives` |
| 성공 응답 처리 | Kafka Future 완료 전에는 발행 완료로 저장하지 않고, 성공 응답 후 `PUBLISHED`와 `published_at`을 기록한다. | `waitsForAckBeforeCompletingAttempt`, `completesPublishResultOnlyAfterKafkaSendCompletes`, `branchesByPublishResultAndAttemptLimit` |
| 실패 응답 처리 | 실패가 확인되면 `FAILED`와 다음 재시도 시각·오류 종류를 저장한다. 최대 시도에 도달하면 `BLOCKED`로 전환한다. | `branchesByPublishResultAndAttemptLimit`, `republishesFailedOutboxWhenRetryTimeArrives` |
| 결과 미확정·대기 제한 | 응답이 미확정이면 `PENDING`으로 재시도 시각을 저장하고, Publisher Future가 미완료여도 Relay는 제한 시간 안에 종료한다. 최대 시도 도달 시 `BLOCKED`로 전환한다. | `branchesByPublishResultAndAttemptLimit`, `shouldNotWaitForever` |
| Kafka 전송 실패 전파 | Kafka Future가 예외로 끝나면 Publisher Future가 실패 결과로 완료된다. | `completesPublisherFutureAsFailed_whenKafkaFutureFails` |
| 재시도 시 메시지 identity 유지 | 같은 이벤트를 다시 발행할 때 `messageKey`와 payload의 `eventId`를 유지한다. | `preservesMessageKeyAndPayloadAcrossRetries`, `republishesWithSameMessageKeyAndEventId` |
