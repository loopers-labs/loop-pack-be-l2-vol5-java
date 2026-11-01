# Kafka DB commit 후 offset 미커밋 재전달 실험 결과



- 테스트: `PurchaseActivityRedeliveryExperimentTest`



## 관찰 결과


| 단계            | 관찰                                                                   |
| ------------- | -------------------------------------------------------------------- |
| 첫 consumer 처리 | partition 0, offset 0 수신. DB에 처리 기록 1건과 구매 활동 2행 저장. group offset은 0 |
| 같은 group 재시작  | 새 consumer가 위치를 조작하지 않고 같은 partition 0, offset 0을 다시 수신              |
| 재처리 후         | handler 재호출에도 DB는 처리 기록 1건·구매 활동 2행 유지. 이후 group offset 1 commit     |
