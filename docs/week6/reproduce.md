# W6 실행과 재현

Java 21과 실행 중인 Docker Engine이 필요하다. 테스트는 Apache `apache/kafka:3.8.1`과 `mysql:8.0` 컨테이너를 직접 준비한다. Kafka·MySQL을 localhost 고정 포트에 먼저 띄울 필요는 없다. API 회귀에서는 기존 Redis fixture도 사용한다.

## 테스트 읽는 순서

1. `apps/commerce-api/src/test/java/com/loopers/application/payment/OrderConfirmedPublicationTest.java`: 실제 주문 확정과 commit 전/후 발행 경계.
2. `apps/commerce-streamer/src/test/java/com/loopers/purchaseactivity/PurchaseActivityIntegrationTest.java`: 한 이벤트의 품목 저장, 같은 사실 재전달, 충돌·동시성·rollback.
3. `apps/commerce-streamer/src/test/java/com/loopers/purchaseactivity/KafkaDeliveryTest.java`: DB commit 뒤 미커밋 offset 재전달과 실제 listener 중단/복구.
4. `apps/commerce-streamer/src/test/java/com/loopers/week6/Week6DirectPublicationFlowTest.java`: 주문 API와 로컬 PG를 시작점으로 미발행 1건·정상 소비 1건 재현.
5. `modules/kafka/src/test/java/com/loopers/kafka/KafkaPartitionAssignmentTest.java`: partition 2개, member 1→2→3과 실제 key 매핑.

```sh
./gradlew :modules:kafka:test
./gradlew :apps:commerce-api:test --tests '*OrderConfirmedPublicationTest'
./gradlew :apps:commerce-streamer:test --tests '*PurchaseActivityIntegrationTest'
./gradlew :apps:commerce-streamer:test --tests '*KafkaDeliveryTest'
./gradlew :apps:commerce-streamer:test --tests '*Week6DirectPublicationFlowTest'
./gradlew :modules:kafka:test :apps:commerce-api:check :apps:commerce-streamer:check
```

macOS에서 기본 Java가 17이라면 명령 앞에 `JAVA_HOME=$(/usr/libexec/java_home -v 21)`을 붙인다. 같은 모듈의 Gradle test를 여러 프로세스로 동시에 실행하지 않는다. 같은 결과 디렉터리를 덮어써 이전 결과가 사라질 수 있다.

## 두 주문을 다음 주에도 다시 만드는 방법

`Week6DirectPublicationFlowTest`는 고정 Clock `2026-10-29T01:00:00.123456Z`를 사용한다. 각 주문은 첫 상품 2개, 두 번째 상품 1개를 구매한다. 주문 생성 요청에 첫 상품을 두 번 넣어 정규화도 확인한다.

1. 기존 `/api/v1/orders`로 주문을 만들고 `/confirm`으로 로컬 PG의 결제를 준비한다.
2. PG fixture에서 해당 거래를 SUCCESS로 바꾼다.
3. 기존 `/payment/reconcile`이 실제 PG HTTP 조회 결과를 검증해 최초 주문 확정을 수행한다.
4. 첫 주문만 `OrderConfirmedPublisher.publish` 진입 직전 hook에서 오류를 낸다. 그 안의 독립 `REQUIRES_NEW` DB 조회로 CONFIRMED가 이미 commit된 사실을 확인한다.
5. 둘째 주문은 실제 producer·broker·Spring listener·MySQL까지 흐른다.
6. DB·broker·consumer group의 실제 결과를 확인하고 JSON을 저장한 뒤 테스트 자원을 정리한다.

이 hook은 send 호출 이전의 통제된 실패다. OS 강제 종료를 재현했다는 뜻이 아니다. W7 Outbox 경로를 먼저 실행한 뒤 데이터를 지워 미발행으로 꾸미지 않는다.

결과 파일:

```text
apps/commerce-streamer/build/reports/week6/direct-publication-cases.json
```

파일에는 실행한 Git revision과 working tree 상태, 실제 생성된 orderId·providerOrderId·transactionKey·eventId·confirmedAt·items, 주문·예약·broker·구매 활동·committed offset이 담긴다. 카드 값은 제외한다. IDE 실행으로 Git 정보가 주입되지 않으면 `unrecorded`/`unknown`으로 표시한다.

결과 파일은 테스트 실행 시 `build` 아래에 생성하며 저장소에 사본을 보관하지 않는다. W7의 새 격리 실행에서는 새로 생성된 사건 ID를 준비→복구 사이에서 유지한다. 테스트 DB를 일주일 동안 보관할 필요는 없다.

## A 정책에서 무엇을 재시작하는가

오류 때문에 멈춘 것은 `purchase-activity` listener 컨테이너다. 컴퓨터·Kafka·MySQL을 모두 재부팅할 필요는 없다.

- 먼저 DB 연결 문제나 잘못된 이벤트 등 실제 원인을 해결한다.
- 실행 중인 앱 내부에서 `registry.getListenerContainer("purchase-activity").start()`로 listener를 시작하거나 commerce-streamer 애플리케이션을 재실행한다.
- `purchase-activity.group-id`를 유지한다. 복구 전에 offset을 앞으로 옮기지 않는다.
- 같은 메시지가 다시 와서 DB commit과 ack까지 성공했는지 확인한다.

`KafkaDeliveryTest`가 첫 방법을 실제로 검증한다. 이 메서드를 공개 관리 API로 제공하지 않는다. payload 자체가 잘못된 경우에는 DB를 다시 켜는 것만으로 해결되지 않는다. offset을 건너뛰거나 DLQ에 보내는 정책은 이번 A 구현에 추가하지 않았다.

## 별도 앱을 로컬에서 실행할 때

테스트와 달리 사람이 직접 실행할 때는 Compose의 Kafka·MySQL·Redis와 PG fixture 또는 정상 PG 설정을 준비한다. 사용자 요청으로 로컬 Compose는 기존 `bitnamilegacy/kafka:3.5.1` 설정을 그대로 사용한다. API와 streamer는 같은 Kafka bootstrap 주소를 사용해야 한다.

local profile의 포트는 다음과 같다. streamer의 앱·관리 포트는 자체 local 설정에서 지정하며, 다른 profile의 포트는 변경하지 않는다.

| 앱 | HTTP 포트 | 관리 포트 |
| --- | ---: | ---: |
| commerce-api | 8080 | 8081 |
| commerce-streamer | 8082 | 8083 |

```sh
docker compose -f docker/infra-compose.yml up -d mysql redis-master redis-readonly kafka
./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=local'
./gradlew :apps:commerce-streamer:bootRun --args='--spring.profiles.active=local'
```

두 bootRun은 별도 터미널에서 실행한다. 실제 PG 연결은 `payment-provider.base-url`에 맞게 준비해야 한다. 위 명령만으로 성공 PG 거래가 자동 생성되는 것은 아니다. 전체 동작을 즉시 재현하려면 앞의 격리 테스트를 사용한다.

두 앱을 띄운 뒤 각 관리 포트에서 상태를 확인한다. `server.port`만 바꾸면 공통 `monitoring.yml`의 관리 포트는 그대로이므로, 실행 포트를 추가로 바꿀 때는 `management.server.port`도 함께 확인한다.

```sh
curl --fail http://127.0.0.1:8081/actuator/health
curl --fail http://127.0.0.1:8083/actuator/health
```

API·streamer의 local profile은 `ddl-auto:update`로 DB 사실을 보존한다. test profile은 격리 컨테이너의 테이블을 생성한다. `ddl-auto:none` 환경은 다음 SQL을 해당 DB에 한 번 적용하고, 운영 배포의 마이그레이션 절차를 따른다.

- `sql/orders-confirmed-at.sql`: 기존 주문을 보존하며 nullable 최초 확정 시각 추가.
- `sql/purchase-activity-tables.sql`: 완료 기록과 구매 활동 테이블·유일 제약 추가.

공유 DB에 `ddl-auto:create`를 적용하거나 기존 Docker volume을 지워 재시작하지 않는다. 로컬 Compose는 `KAFKA_CFG_*` 환경 변수, `kafka-data:/bitnami/kafka` 볼륨 및 기존 healthcheck를 사용한다. Compose 파일 복원은 실행 중인 컨테이너나 기존 볼륨을 자동 교체·삭제하지 않는다.

격리된 Testcontainers fixture는 `apache/kafka:3.8.1`을 사용한다. 로컬 Compose의 Bitnami 3.5.1과 구분해서 실행 환경을 확인한다.

## 검증 한계

하나의 broker에서 `acks=all`을 사용한 결과다. replica 장애 보장이나 다중 broker 네트워크 분할을 증명하지 않는다. 실제 PG를 대신해 테스트용 HTTP PG fixture를 사용한다. 통합 테스트의 생산자·소비자는 하나의 테스트 JVM에 로드되지만 Kafka 전송과 소비는 실제 broker를 거치고 각 DB 트랜잭션은 실제 MySQL에서 commit된다. 별도 운영 프로세스의 배포·재부팅 검증으로 확대 해석하지 않는다.
