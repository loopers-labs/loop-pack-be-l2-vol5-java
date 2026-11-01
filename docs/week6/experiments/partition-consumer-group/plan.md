# Kafka partition·consumer group 실험 계획서

## 실험 단계


| 개념·조건                    | 실험                                                 | 관찰                                                               |
| ------------------------ | -------------------------------------------------- | ---------------------------------------------------------------- |
| Consumer group·rebalance | 같은 group에 consumer를 1→2→3개로 순서대로 추가                | 각 변경 후 member별 partition 배정                                      |
| Partition·병렬 처리 한계       | partition 0·1에 record 전송                           | 두 partition은 각각 한 member가 맡고, 세 번째 consumer는 미배정. 두 record 모두 소비 |
| Key·partition 배정         | partition을 지정하지 않고 같은 key로 두 record 전송             | 두 record의 실제 partition 비교                                        |
| consumer 정상 종료           | consumer를 추가한 뒤 partition을 맡은 consumer 하나를 정상 종료   | 종료 후 partition 재배정과 안정화까지 걸린 시간                                  |
| Kafka의 consumer 장애 감지 | 실험용 timeout을 짧게 설정하고 consumer 하나의 poll을 멈춤 | Kafka가 장애를 감지해 consumer를 제외하고 partition을 재배정하는지 확인 |
| partition 증설             | 격리 topic을 2개에서 3개 partition으로 늘림                   | 새 partition을 포함한 assignment와 재배정 결과                              |


## 실험 조건

- **실험 분리:** 매 실행마다 고유 topic·group ID를 사용하고 consumer의 DB 작업은 배제한다.
- **시간 제한:** 기본 실험의 준비·배정·소비 대기는 최대 10초로 제한한다. 추가 장애 실험은 test-only timeout과 별도 deadline을 사용한다.
- **정리:** 실험 consumer를 종료하고 실험에서 만든 topic만 삭제한다. 기존 broker는 유지한다.
- **acks·복제 한계:** producer에 `acks=all`을 지정하고 broker·ISR 수를 기록한다. broker 한 대에서는 복제 장애 내구성을 검증할 수 없다고 결과에 남긴다.
