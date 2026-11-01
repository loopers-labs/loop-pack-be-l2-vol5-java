# Kafka partition·consumer group 실험 결과

- 상태: 완료
- 테스트: `KafkaPartitionAssignmentExperimentTest`

## 실험 설정


| 항목               | 값                                                                                    |
| ---------------- | ------------------------------------------------------------------------------------ |
| Kafka broker     | 1대                                                                                   |
| Bootstrap endpoint | `localhost:19092` (`KAFKA_BOOTSTRAP_SERVERS` 미설정 시 기본값)                              |
| Topic            | partition 2개로 생성, 증설 실험에서 3개로 변경                                                     |
| Producer `acks`  | `all`                                                                                |
| Consumer timeout | `max.poll.interval.ms=3000`, `session.timeout.ms=6000`, `heartbeat.interval.ms=2000` |


## Rebalance 관찰 결과


| 조건               | 변경 전 배정                                | 변경 후 배정                                   | 안정 assignment까지 |
| ---------------- | -------------------------------------- | ----------------------------------------- | ---------------: |
| consumer 추가      | consumer 1명: `[0, 1]`                  | consumer 2명: `[0]`, `[1]`                 | 미측정             |
| consumer 추가      | consumer 2명: `[0]`, `[1]`              | consumer 3명: `[0]`, `[1]`, `[]`           | 미측정             |
| consumer 정상 종료   | consumer 3명: `[0]`, `[1]`, `[]`        | 남은 2명: `[0]`, `[1]`                       | 2,120ms         |
| partition 증설     | partition 2개·consumer 2명: `[0]`, `[1]` | partition 3개·consumer 2명: `[0, 1]`, `[2]` | 미측정             |
| consumer poll 정체 | consumer 2명: `[0, 1]`, `[2]`           | 남은 1명: `[0, 1, 2]`                        | 4,113ms         |




## 메시지·Producer 관찰 결과


| 확인 항목           | 결과                                                               |
| --------------- | ---------------------------------------------------------------- |
| partition 지정 전송 | partition 0·1에 보낸 record를 각각 해당 partition에서 소비                   |
| 같은 key 전송       | partition 미지정으로 같은 key를 보낸 두 record가 partition 0의 offset 1·2에 기록 |
| `acks=all`      | send metadata 확인. partition 0·1 모두 `replicas=1`, `ISR=1`         |

## 실행·정리

- 실행: `./gradlew :apps:commerce-streamer:test --tests '*KafkaPartitionAssignmentExperimentTest' :apps:commerce-streamer:checkstyleTest`
- 테스트가 만든 consumer를 종료하고 고유 topic을 삭제했다. 기존 broker는 유지했다.
- broker가 한 대이고 partition별 replica·ISR이 하나라 복제 장애 내구성은 검증하지 못했다.
