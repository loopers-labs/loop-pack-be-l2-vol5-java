# 주문 확정 이벤트의 격리 검증 절차

이 도구는 별도 로컬 환경에서 두 가지 흐름을 관찰한다.

1. 기존 after-commit 직접 발행이 send 전에 멈춰 생긴 누락을 재현하고, 저장된 주문 사실로 Outbox를 보충해 복구한다.
2. 실제 주문 API부터 Outbox Relay와 구매 활동 Consumer까지 연결하고, broker 성공 뒤 Outbox 상태 저장이 실패했을 때 프로세스 재시작으로 같은 이벤트를 다시 전달한다.

자동 회귀 테스트는 각 애플리케이션 모듈에 따로 유지한다. 이 도구는 두 애플리케이션을 별도 프로세스로 띄우는 격리 실행이며 Gradle `test`나 `check`에는 연결하지 않는다.

## 실행

Java 21, Python 3, Docker가 필요하다. 저장소 루트에서 실행한다.

```bash
python3 docs/verification/order-confirmed-event/run.py
```

실제 Kafka UI 화면을 보며 캡처할 때는 대화형 터미널에서 실행한다.

```bash
python3 docs/verification/order-confirmed-event/run.py --section7-captures-only
```

캡처 전용 옵션은 최종 E2E의 세 시점에서 입력을 기다린다. Kafka UI에서 격리 topic을 열고 `eventId`를 검색해 화면을 저장한 뒤 Enter를 누른다. 모든 복구 장면의 화면도 함께 캡처하려면 `--capture-ui`를 사용한다. 캡처 옵션은 각 단계에서 터미널 입력을 기다리므로 비대화형 실행을 지원하지 않는다.

## 격리 환경

실행 도구는 현재 소스의 API·streamer·테스트 PG JAR를 빌드한다. 직접 발행 누락 사례에는 `DIRECT_PUBLICATION_REVISION`에 고정된 기준 revision의 API를 임시 디렉터리에 추출·빌드하고, 그 복사본에만 send 직전 테스트 latch를 넣는다. 기준 revision·현재 앱·PG fixture의 입력과 상태는 실행별로 격리한다. 기준 revision은 `git archive`로 꺼내므로, 이 커밋이 없는 shallow clone(CI 등)에서는 실행 전에 해당 revision을 fetch해야 한다.

고유 이름과 loopback 포트를 사용해 MySQL 8.0·Redis 7.0·Kafka 3.8.1·테스트 PG·필요한 경우 Kafka UI를 새로 띄운다. 기존 로컬 컨테이너·DB·topic·consumer group은 사용하지 않는다. 품목은 상품 7×2·8×1이고, 테스트 PG는 지정된 합성 주문에만 사용한다. 실행 로그와 `observations.json`은 출력된 임시 디렉터리에 남는다.

fixture는 한 번 준비해 API·Consumer·Kafka 조회에 같은 주소를 제공한다. 테이블은 시작할 때 한 번 만들고 앱 프로세스는 `ddl-auto=validate`로 실행해 준비 데이터를 보존한다. 실제 Consumer는 `commerce-streamer` bootJar를 별도 프로세스로 띄운다. 두 앱을 한 클래스패스에 합치지 않아 component scan·설정 충돌을 피하고, commerce-api가 commerce-streamer에 제품 의존성을 갖지 않게 한다. fixture 주소·topic·group은 실행 인자로 전달하고, broker record·partition·offset과 업무 Consumer group의 committed offset은 Kafka CLI로 조회한다. 준비·복구·재실행·결과 확인은 한 실행에서 이어가며, 그 사이 DB·Kafka·PG를 초기화하지 않는다.

성공·실패 모두 실행이 만든 앱 프로세스와 컨테이너만 종료한다. `observations.json`, 로그, 일회성 화면 캡처는 로컬 근거로 보관하고 Git 제출 대상에서 제외한다.

broker 준비, 최초 Consumer 시작과 재시작 뒤 partition 0·1·2 배정, committed offset과 DB 결과에는 각각 유한한 deadline을 둔다. 배정은 실제 Consumer group의 `--describe --offsets` 결과에서 대상 topic·partition과 현재 Consumer 소유자를 함께 확인한다. 아직 commit된 offset이 없어도 소유자가 있으면 배정을 확인할 수 있다. deadline을 넘기면 단계명·마지막 조회 상태·마지막 오류를 출력하므로 배정 실패와 결과 저장 지연을 구분할 수 있다.

## 직접 발행 누락 재현과 복구

첫 사례는 기준 revision의 `AFTER_COMMIT` listener를 event 생성 뒤 `KafkaTemplate.send()` 직전에 멈춘다. 주문 `CONFIRMED`, Kafka record·Inbox·구매 활동 0건을 같은 격리 실행의 실제 DB와 broker에서 확인한 다음 그 기준 프로세스를 끝낸다. 두 번째 주문은 기준 경로로 실제 발행·소비까지 마친 대조 사례다.

latch에 걸린 요청 때문에 정상 종료가 5초 안에 끝나지 않으면 그 기준 API 프로세스만 강제 종료한다. 강제 종료 여부는 `observations.json`에 기록한다. 이 사례는 send 전에 멈추며 broker 거절이나 send timeout을 주입하지 않는다.

현재 앱을 올려 두 주문에 저장된 `eventId`, `confirmedAt`, 품목으로 없는 Outbox만 보충한다. 같은 대상을 다시 보충해도 행과 효과가 늘지 않는지, PG create·조회·callback 수, 재고, 주문 상태, Inbox, 구매 활동, Kafka record 수를 다시 비교한다. PG SUCCESS나 Outbox 행을 테스트가 직접 삽입하지 않는다.

## 실제 주문 API → Outbox Relay → Kafka → Consumer

마지막 E2E는 기존 주문 API와 테스트 PG, 격리 MySQL·Kafka, 실제 구매 활동 Consumer를 한 실행에서 연결한다.

1. API를 Relay 비활성으로 시작해 합성 주문을 확정하고 `PENDING`, 시도 0, Kafka record 0, Inbox·구매 활동 0을 확인한다.
2. 격리 MySQL에 테스트용 trigger를 추가한다. 이 trigger는 새 대상 주문의 최초 `PUBLISHED` 상태 변경만 거절한다.
3. 실제 Relay를 시작한다. Kafka broker는 record를 성공 수신하지만 MySQL trigger가 상태 저장을 실패시킨다. 행은 `PENDING`, 시도 1로 남고 Consumer는 첫 record를 실제 처리한다. 구매 활동은 Consumer의 committed offset이 첫 offset + 1에 도달한 뒤 기록한다.
4. API 프로세스를 정상 종료하고 새 API·Relay 프로세스를 같은 MySQL·Kafka·Consumer group에 연결한다. Outbox 행을 직접 수정하지 않고 재발행과 최종 `PUBLISHED`를 확인한다. 새 프로세스는 실제 Clock을 쓰므로 저장된 `nextAttemptAt`까지 유한한 deadline 안에서 기다린다. 같은 시각 경계를 `MutableClock`으로 제어하는 검증은 Relay 통합 테스트가 맡는다.
5. 두 번째 record의 committed offset이 두 번째 offset + 1에 도달한 뒤 broker의 두 record와 Consumer group offset을 조회하고, Inbox·완료 행 1개, 상품별 구매 활동 2행·수량 2와 1, 재고·포인트·PG 호출 수가 유지되는지 확인한다.

여기서 MySQL trigger는 실제 저장 경계에 실패를 주입하는 격리 테스트 장치다. 프로세스 재시작은 정상 종료 후 새 앱 프로세스를 시작하는 방식으로 재현한다. 이 결과를 OS 강제 종료나 임의 네트워크 장애 시험이라고 부르지 않는다.

## 관련 회귀 검증

`OrderConfirmedKafkaDeliveryIntegrationTest`에는 PUBLISHED를 send 전에 commit하는 잘못된 순서의 반례도 있다. 실제 broker record가 없는데 정상 Relay 재실행도 해당 행을 고르지 못하는 것을 확인한다. 이 반례는 테스트에서만 실행하며 제품 흐름의 순서를 바꾸지 않는다.

```bash
./gradlew :apps:commerce-api:test --tests '*OrderConfirmedOutboxAtomicityTest' \
  --tests '*OrderConfirmedKafkaDeliveryIntegrationTest' \
  --tests '*PaymentResultServiceIntegrationTest'
./gradlew :apps:commerce-streamer:test --tests '*PurchaseActivityKafkaIntegrationTest'
./gradlew :apps:commerce-api:check :apps:commerce-streamer:check
```
