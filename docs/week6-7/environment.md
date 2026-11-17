# 주문 이벤트 · 현재 실행 환경과 설정

Java 21·Gradle wrapper를 유지한다. 아래는 기존 구현을 읽어 정리한 설정이며 새 실행/실험 성공을 보고하는 문서가 아니다.
[계약](r01-order-events-inbox/event-contract.md) · [검증 매핑](r01-order-events-inbox/verification.md) · [운영](r03-delivery-recovery/operations.md)

## 앱·데이터 보존

- commerce-api는 기본 HTTP 8080, payment-lab 관리 포트는 8082다. 테스트 PG는 HTTP 8081이다.
- commerce-streamer는 앱 이름 commerce-streamer, HTTP 8083/관리8084, 기본 demo-kafka.enabled=false다.
- API의 Outbox relay는 기본 false, payment-lab에서는 true이며 ORDER_EVENTS_RELAY_ENABLED로 지정할 수 있다. 단일 활성 relay만 운영한다.
- API의 local JPA는 기존 create 설정이므로 보존할 DB를 그대로 local로 시작하지 않는다. payment-lab은 validate이며 Streamer local은 none이다.
- 초기 초안의 event-lab 프로필은 구현돼 있지 않다. 실제 application-payment-lab.yml과 Streamer application.yml/application-local.yml을 기준으로 한다.
- [DB 준비·실행](../../docker/payment-lab/README.md)의 000~011 SQL 역할과 기존 DB 적용 상태를 확인한다.
  쿠폰 012/013은 [쿠폰 운영](../week8/completed/operations.md)을 따른다. 기존 스키마/볼륨을 초기화하지 않는다.

## Kafka 실효 설정

producer는 API의 OrderEventKafkaConfiguration, 업무 consumer는 Streamer의 OrderConfirmedKafkaConfiguration에서 구성한다.
공통 demo batch factory와 업무 record factory를 구분하며 producer/consumer/admin은 같은 실제 broker 주소를 사용한다.

| 경계 | 현재 설정 |
|---|---|
| 공통 메시지 | order-events, key는 orderId 십진 문자열, value는 byte[] JSON. 앱별 DTO이며 runtime 앱 간 의존 없음 |
| producer | StringSerializer/ByteArraySerializer, acks=all, enable.idempotence=true, retries=3, max.in.flight.requests.per.connection=5 |
| producer 시간 | max.block.ms/request.timeout.ms=5000, delivery.timeout.ms=10000, linger.ms=0 |
| consumer | StringDeserializer/ByteArrayDeserializer, enable.auto.commit=false, auto.offset.reset=earliest |
| poll/fetch | max.poll.records=1, max.poll.interval.ms=300000, fetch.min.bytes=1, fetch.max.wait.ms=100 |
| listener | record, MANUAL_IMMEDIATE, concurrency=1, syncCommits=true, sync timeout=5초, asyncAcks=false |
| 실패 | listener 내부 최초+추가2회, 회차 사이 800~1200ms 지터. 최종 개별 실패는 별도 TX 보관 뒤 ack |
| 공통 DB/실패 보관 오류 | CommonContainerStoppingErrorHandler로 중단하고 offset 유지. Spring error handler가 위 재시도를 대신하지 않음 |
| relay | fixed delay 3초, batch5, ack 대기10초, 예약20초, 총5회. 실패 뒤3/6/12/24초±20%, 소진은BLOCKED |

acks=all·producer 멱등성이 DB와 Kafka의 원자적 commit이나 consumer 효과의 EOS를 뜻하지 않는다.
업무 DB commit과 ack 사이의 재전달, broker 확인과 PUBLISHED 저장 사이 재발행을 구분한다.

## Kafka 테스트 fixture와 실행

`modules:kafka`의 com.loopers.testcontainers.KafkaTestFixture.create()는 apache/kafka:3.8.0과 시작 timeout2분을 사용한다.
생성만으로 시작하지 않으며 테스트가 start/stop 또는 Container 수명을 소유한다. 고정 포트·reuse·전역 System property는 추가하지 않는다.
실제 container.getBootstrapServers()를 각 client에 주입하고 공유 compose DB/broker를 초기화하지 않는다.

```powershell
./gradlew.bat :modules:kafka:test --tests '*KafkaTestFixtureTest' :apps:commerce-streamer:compileTestJava
./gradlew.bat :apps:commerce-api:check :apps:commerce-streamer:check :apps:test-pg:check :modules:kafka:check
```

smoke는 실제 2-partition topic 생성·send metadata·payload 수신을 확인한다. topic/send10초, 소비20초, 본문60초의 유한 대기를 사용한다.
smoke는 주문·Inbox·Outbox·재전달 검증을 대신하지 않는다. API와 Streamer의 실제 Checkstyle 설정을 유지한다.
검사 종류·클래스·실행 근거는 [검증 매핑](r01-order-events-inbox/verification.md)과 [결과](r03-delivery-recovery/result.md)를 따른다.

Docker 29/API 호환 문제가 실제로 발생하는 로컬 환경에서는 해당 모듈의 Git 제외 docker-java.properties에 api.version=1.44를 둘 수 있다.
현재 엔진 접근·API 오류·Gradle/JDK 문제를 구분하고 이를 모든 환경의 요구사항이나 성공 근거로 일반화하지 않는다.
