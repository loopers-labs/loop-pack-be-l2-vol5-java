 

![](orca-paste-1793036057341-42a9a840-4db7-4458-b9be-3e73fefd0d88.png)

## OrderConfirmed 발행 테스트

- 최초 PG SUCCESS: DB commit 전에는 미발행, commit 후 `order-events`에 1건 발행. key·eventId·confirmedAt·상품별 수량이 저장된 주문과 일치하고 topic·partition·offset을 확인한다.
- UNKNOWN 후 대사 SUCCESS: 동일한 확정 경로로 이벤트 1건 발행.
- 미발행: DRAFT·PAYMENT_PENDING·UNKNOWN 유지·결제 실패·확정 TX rollback에서는 이벤트가 없다.
- 중복 방지: 이미 종결된 같은 PG 시도의 반복 적용은 추가 이벤트를 만들지 않는다.
- 이전 POINT 주문: 이번 변경으로 기존 확정 주문 이벤트를 소급 발행하지 않는다.
- payload 기준: 확정 주문에 저장된 품목·수량·confirmedAt을 사용하며 현재 상품 조회값으로 바꾸지 않는다.
- commit 후 발행 실패: 테스트 hook으로 Kafka 전송 직전 실패를 주입한다. 주문 확정과 재고 예약 소비는 commit된 상태이고 Kafka record는 없는지 확인한다.

## 발행 상태 추적

- 이번 주에는 producer 발행 여부를 별도 DB에 저장하지 않는다.
- 운영 추적·복구 정책은 아직 미정이다.
- 테스트에서 주문 DB의 확정 사실과 Kafka record를 대조해 발행 결과를 확인한다.

## 주문 확정부터 offset commit까지 E2E

- PG SUCCESS 응답만 테스트 대역으로 준비하고, 실제 주문 확정 경로를 실행한다.
- 실제 Kafka producer와 streamer listener를 연결해 격리 topic에 발행·소비한다.
- broker에서 target record의 `partition`, `offset`을 얻고, streamer의 처리 기록·상품 구매 활동 DB를 확인한다.
- 같은 consumer group의 committed offset이 target `offset + 1` 이상인지 확인하고, 주문 DB·payload·구매 활동의 상품·수량·확정 시각을 대조한다.
- 실행: `./gradlew :apps:commerce-api:week6E2eTest :apps:commerce-api:checkstyleWeek6E2eTest`
- 테스트 전용 topic·group을 생성하고 종료 후 제거한다. 기존 `order-events`와 `purchase-activity` group은 사용하지 않는다.

## 구매 활동 consumer 테스트

- 정상 소비: `OrderConfirmed`의 상품별 `productId`, `quantity`, `confirmedAt`과 일치하는 구매 활동을 저장하고 이벤트 처리 완료 기록을 남긴다.
- 멱등 재전달: 같은 `eventId`와 같은 주문 사실을 다시 받으면 정상 완료하되 구매 활동 행과 수량은 늘지 않는다.
- 멱등 키 충돌: 같은 `eventId`에 다른 주문 사실이 오거나, 같은 `orderId`에 다른 `eventId`·내용이 오면 충돌로 실패하고 추가 활동을 저장하지 않는다.
- 원자성: 일부 품목 저장 중 실패하면 처리 완료 기록과 이미 저장한 품목을 모두 rollback한다.
- ack 순서: DB commit 성공 뒤 ack하고, 파싱·검증 또는 DB 저장 실패 시 ack하지 않는다.

## 다음 주 전달 사례

- 재현 가능한 테스트 코드에 미발행 사례 1건과 정상 발행·소비 사례 1건을 함께 남긴다.
- 각 사례의 `orderId`, `providerOrderId`, `transactionKey`, `eventId`, `confirmedAt`, 품목과 주문·예약·Kafka·구매 활동 결과를 기록한다. 비밀정보는 제외한다.
- W6 직접 발행 경로를 재현하며, 임시 DB·컨테이너는 관찰값과 코드를 보존한 뒤 정리한다.
