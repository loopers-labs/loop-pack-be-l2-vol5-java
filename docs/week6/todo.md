# Week 6 TODO

- [ ] **Producer 발행 실패 복구:** 주문 DB commit 뒤 Kafka 전송을 실패시킨다. 주문은 확정됐지만 broker record와 구매 활동은 없는지 확인하고, 재발행·Outbox가 없을 때 남는 공백을 기록한다.
- [ ] **Consumer 처리 지연과 lag:** 이벤트 유입보다 DB 처리를 느리게 해 lag와 처리 지연이 증가하는지, 처리가 회복된 뒤 backlog가 줄어드는지 확인한다.
- [ ] **Consumer 처리 실패와 DLQ:** 특정 이벤트 처리를 실패시켜 재시도·offset 진행을 관찰한다. 재시도 소진 뒤 DLQ로 보냈을 때 원본 소비와 재처리 절차를 확인한다.
- [ ] **순서와 병렬성:** 같은 key의 record가 같은 partition에서 순서대로 읽히는지 확인한다. 여러 partition을 서로 다른 consumer가 처리할 때 병렬성이 늘어나는지, 비동기 처리에서는 완료 순서가 달라질 수 있는지 확인한다.

각 실험은 실제 record의 topic·partition·offset, committed offset, DB 결과와 lag를 함께 기록한다. 실험 자원은 업무 topic·consumer·DB와 분리한다.
