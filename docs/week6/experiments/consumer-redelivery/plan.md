# Kafka DB commit 후 offset 미커밋 재전달 실험 계획서

## 실험 내용

같은 group의 새 consumer가 미커밋 offset부터 같은 record를 다시 읽고, 실제 구매 활동 handler가 DB 효과를 중복 저장하지 않는지 확인한다.

## 절차

1. 실제 이벤트를 보내고 consumer가 구매 활동 DB 저장을 완료한다.
2. 첫 consumer는 해당 record의 다음 offset(`n+1`)을 commit하지 않은 채 종료한다.
3. 같은 group의 새 consumer가 offset을 조작하지 않고 같은 record를 다시 받는지 확인한다.
4. 재전달돼도 구매 활동은 중복 저장되지 않고, 두 번째 처리 뒤 offset이 다음 위치(`n+1`)로 commit되는지 확인한다.

실험 준비 단계에서는 group offset을 record 위치 `n`으로 맞춘다. 실제 partition·offset은 producer metadata에서 얻는다.

## 실험 조건

- DB handler commit과 offset commit은 별도 경계로 관찰한다.
- 준비·배정·소비 대기는 각각 최대 10초로 제한한다.
- 실험 consumer·group offset·topic과 해당 eventId·orderId DB 행을 정리한다.
