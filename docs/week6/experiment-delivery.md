# 6주차 전달 실험 결과

6주차 설계 7장의 실험 기록이다. 숫자와 ID는 아래 실행일의 **한 번 실행한 관찰값**이며, 다시 실행하면 ID · offset · 시간은 달라진다.

- 실행일: 2026-10-29, 로컬 Docker. 실험 코드는 커밋 `00ac8cb` 시점(측정 표는 `max.block.ms=1000`, 이후 `89cb956` 에서 500 으로 조정). broker 는 Testcontainers `cp-kafka:7.5.3`(Apache Kafka 3.5, 복제 1) 또는 로컬 compose `bitnamilegacy/kafka:3.5.1`(복제 1)
- **단일 broker 한계:** 복제 · ISR · `acks=all` 의 장애 내구성, broker 장애 중 leader 이동, 네트워크 분할은 시험하지 않았다. 아래 "broker 정지"는 `docker pause`(응답 없음)이며 연결 거부 · 프로세스 종료와는 다른 장애다.

- **broker 주소 (endpoint):** 테스트는 `KafkaTestContainersConfig` 가 JVM 당 한 번 띄운 컨테이너의 호스트 주소(`getBootstrapServers()`, 매핑된 포트)를 `spring.kafka.bootstrap-servers` System property 로 넣는다. producer · consumer · admin 이 모두 이 주소를 쓰고, 컨테이너 내부 주소(`kafka:9092`)는 쓰지 않는다. 두 앱 E2E 만 로컬 compose 의 `localhost:19092` 를 쓴다(`TEST_LOCAL_INFRA=true`).
- **정리 책임:** 컨테이너(Kafka · MySQL · Redis)는 Testcontainers 가 JVM 종료 때 정리한다. 테스트가 만든 topic 은 `KafkaTopics.deleteCreated()` 로 자기가 만든 것만 지우고, consumer · producer 는 각 테스트가 닫는다. DB 는 `DatabaseCleanUp` 으로 테스트마다 비운다. 실험이 직접 띄운 컨테이너(E2E 임시 MySQL, MySQL 재시작 측정, broker 3대 cluster)는 실험이 끝나면 지웠다. 다른 실행의 topic · group · DB 는 지우지 않는다.

| 절 | 실험 코드 | 실행 |
|---|---|---|
| 7.1 배정 · key | `apps/commerce-streamer/.../interfaces/consumer/KafkaDeliveryTest` (`Assignment`) | 평소 `check` 에 포함 |
| 7.2 누락 구간 | `apps/commerce-api/.../application/order/listener/OrderConfirmedPublicationGapTest` | 평소 `check` 에 포함 |
| 7.2 정상 사례 (두 앱) | `apps/commerce-api/.../experiment/OrderConfirmedEndToEndExperiment` | 아래 절차, `TEST_LOCAL_INFRA=true` |
| 7.3 재전달 | `KafkaDeliveryTest` (`Redelivery`) | 평소 `check` 에 포함 |
| 7.4 listener 실패 경로 | `apps/commerce-streamer/.../interfaces/consumer/OrderConfirmedConsumerTest` | 평소 `check` 에 포함 |
| 측정 (D-75) | `apps/commerce-api/.../experiment/OrderEventPublishExperiment` | `RUN_EXPERIMENT=true` |

```bash
./gradlew :apps:commerce-streamer:test --tests '*KafkaDeliveryTest' --tests '*OrderConfirmedConsumerTest'
./gradlew :apps:commerce-api:test --tests '*OrderConfirmedPublication*'
RUN_EXPERIMENT=true ./gradlew :apps:commerce-api:test --tests '*OrderEventPublishExperiment'
```

## 7.1 partition · consumer group

partition 2개인 격리 topic, 같은 group. 배정은 broker 가 준 `assignment()` 가 0.1초 간격으로 10번 연속 같을 때 기록했다.

| 같은 group 의 consumer | c1 | c2 | c3 |
|---|---|---|---|
| 1개 | [0, 1] | — | — |
| 2개 | [0] | [1] | — |
| 3개 | [0] | [1] | **[] (배정 없음)** |

- 두 partition 에 명시적으로 보낸 record: partition 0 offset 0 → c1, partition 1 offset 0 → c2 가 읽음.
- key 매핑: partition 3개 topic 에 key `42` 로 5번 보내 producer 가 고른 partition 이 모두 `1`. 서로 다른 key 가 다른 partition 으로 간다고는 확인하지 않았다(그런 보장은 없다).
- consumer 를 partition 수보다 늘려도 처리 담당은 늘지 않는다. 구매 활동 listener 가 concurrency 1 로 시작하고 partition 을 3 으로 둔 이유(D-88, D-89)다.

## 7.2 commit 뒤 발행 누락 구간

실제 확정 · `TestPgServer` · PG 결과 적용(reconcile) 경로로 두 주문을 확정했다. 첫 주문만 발행 포트(`OrderEventPublisher`)에 들어온 순간 상태를 기록하고 실제 send 를 부르지 않았다. 통제된 hook 이며 OS 강제 종료가 아니다.

| | 누락 사례 | 정상 사례 |
|---|---|---|
| orderId | 1 | 2 |
| providerOrderId | LP-ORD-0000000001 | LP-ORD-0000000002 |
| transactionKey | TEST-TX-0001 | TEST-TX-0002 |
| eventId | order-confirmed:v1:1 | order-confirmed:v1:2 |
| confirmedAt | 2026-10-29T09:29:21.684317Z | 2026-10-29T09:29:21.732340Z |
| items | 상품 1 × 2 | 상품 1 × 1 |
| hook 시점(이미 commit 됨) 주문 / 예약 | CONFIRMED / CONSUMED | — |
| 최종 주문 / 예약 | CONFIRMED / CONSUMED | CONFIRMED / CONSUMED |
| broker record | **0건** | 1건 (`order-events` partition 2 offset 0) |

- **구매 활동은 streamer 를 돌려 확인하지 않았다.** broker record 가 0건이라 소비될 대상이 없으므로 활동이 생길 수 없다는 결론이다.
- **확정 주문은 남는데 이벤트가 없다.** commit 뒤에 보내도 DB 와 broker 는 원자적으로 묶이지 않는다. 기록도 재전송도 없어 이 주문의 구매 활동은 만들어지지 않는다. 7주차 Outbox 의 출발점이다.
- **고정 clock 을 쓰지 않았다(한계).** 확정 시각은 서버 `Clock` bean 이 정하고, 실험은 그 bean 을 바꾸지 않아 실행마다 시각이 다르다. 고정하려면 테스트에서 `Clock` bean 을 교체해야 해 기존 테스트 컨텍스트와 갈라진다. 대신 실제 생성된 값(ID · confirmedAt · items)을 위 표에 기록하는 방식으로 재현 근거를 남겼다. 품목 · 수량은 실험 코드에 고정돼 있다.
- 7주차에 같은 장면을 다시 만들 때는 위 ID 를 그대로 쓰지 않는다. 새 격리 실행에서 실제 생성된 주문의 ID 로 준비 → 복구를 확인한다.

### 정상 사례: 두 앱을 실제 broker 로 잇기

api 가 확정 · 발행하고, 따로 띄운 commerce-streamer 가 같은 broker 에서 소비해 구매 활동을 남기는지 같은 MySQL 에서 확인했다.

| 항목 | 값 |
|---|---|
| orderId / providerOrderId / transactionKey | 1 / LP-ORD-0000000001 / TEST-TX-0001 (E2E 전용 DB 라 ID 가 1 부터) |
| 주문 | CONFIRMED, confirmed_at 2026-10-29 09:34:40.563068 UTC |
| api 발행 로그 | `eventId=order-confirmed:v1:1 topic=order-events partition=0 offset=0` |
| streamer 소비 로그 | `result=RECORDED eventId=order-confirmed:v1:1 topic=order-events partition=0 offset=0` |
| 처리 기록 | event_id `order-confirmed:v1:1`, confirmed_at 주문과 같음 |
| 구매 활동 | (상품 1, 수량 2), (상품 2, 수량 1) |
| `purchase-activity` group offset | partition 0 committed 1, lag 0 |

**실행 절차.** 로컬 3306 을 이미 다른 MySQL 이 쓰고 있어서, compose 의 MySQL 대신 E2E 전용 임시 컨테이너를 3307 에 띄웠다. 두 앱 모두 `ddl-auto=update` 로 실행해 기존 데이터를 지우지 않고, 실험 쪽은 truncate 하지 않는다.

```bash
docker compose -f docker/infra-compose.yml up -d kafka kafka-init redis-master redis-readonly
docker run -d --name w6-e2e-mysql -p 3307:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_USER=application -e MYSQL_PASSWORD=application -e MYSQL_DATABASE=loopers mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci
./gradlew :apps:commerce-streamer:bootRun --args='--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:3307/loopers --spring.jpa.hibernate.ddl-auto=update'
TEST_LOCAL_INFRA=true E2E_JDBC_URL=jdbc:mysql://localhost:3307/loopers ./gradlew :apps:commerce-api:test --tests '*OrderConfirmedEndToEndExperiment'
docker rm -f w6-e2e-mysql
```

- `TEST_LOCAL_INFRA=true` 면 MySQL · Redis · Kafka test fixture 가 컨테이너를 띄우지 않고 test profile 의 localhost 주소(compose)를 쓴다.
- 실행 뒤 임시 MySQL 은 지웠다. 로컬 compose broker 의 `order-events` 에는 이 record 와 group offset 이 남아 있다.

## 7.3 DB commit 뒤 offset 미커밋 재전달

low-level `KafkaConsumer`(자동 commit 꺼짐) 로 실제 parser · `PurchaseActivityFacade` 를 호출하고, 첫 실행의 offset commit 만 보류했다. broker · topic · group · DB 는 유지하고 consumer 만 새로 만들었다.

| 순서 | 관찰 |
|---|---|
| 준비 | target 을 partition 2 offset 0 (`n = 0`)에 발행 (send metadata) |
| 시작 위치 | 첫 consumer 배정 뒤 0 을 commit → committed **0** |
| 첫 처리 | 2@0 을 받아 `RECORDED`, 구매 활동 2행, committed **0** |
| 종료 | 대기 중 commit · 종료 commit callback 없이 `close(10s)` → committed **0** |
| 재시작 | 새 consumer, 같은 group, seek · reset 없음 → 같은 2@0 을 다시 받음 |
| 두 번째 처리 | `ALREADY_RECORDED`(no-op) → 1 을 commit → committed **1** |

- 전달 2회, 같은 topic · partition · offset, 구매 활동 2 → 2 행, committed offset **0 → 0 → 0 → 1**.
- 판정은 poll position 이 아니라 broker 의 committed offset 과 DB 로 했다. 시작 위치를 정한 commit 은 준비이지 재전달이 아니다.
- 이 실험은 "DB 효과 뒤 offset 미커밋"에서의 재전달만 증명한다. 프로세스 강제 종료 · broker 장애는 시험하지 않았다.

## 7.4 실제 Spring listener 의 실패 경로

`OrderConfirmedConsumerTest` (이 클래스 전용 topic · group, 재시도 100ms → 최대 200ms, 상한 1.5s).

| 장면 | 관찰 |
|---|---|
| 정상 | 저장 뒤 n+1 commit |
| 같은 record 두 번 | 활동 1건, 두 record 를 모두 지나 commit |
| 형식 오류 | 같은 partition 의 `.DLT` 로 원래 바이트 · key · 원래 offset 헤더, 활동 없음, 원래 offset 진행 |
| 같은 eventId 다른 수량 | 두 번째만 DLT, 첫 사실(수량 2) 유지 |
| 일시적 오류 3번 뒤 회복 | 재시도 중 committed ≤ n, 시도 4번, 회복 뒤 저장 · n+1 |
| 일시적 오류가 상한을 넘김 | child 컨테이너 정지(`isInExpectedState=false`), committed ≤ n, 활동 0 → 원인 제거 · 재시작 뒤 같은 record 를 다시 받아 저장 · n+1 |

## 측정: `max.block.ms` (D-75)

`max.block.ms=1000`, 각 장면 5번. broker 정지는 Testcontainers broker 를 `docker pause`.

| 항목 | 관찰 (ms) |
|---|---|
| 새 producer 의 첫 `send()` 막힘 (metadata 없음) | 8, 7, 5, 5, 9 |
| 같은 producer 의 다음 `send()` 막힘 | 2, 0, 0, 0, 0 |

| 장면 | reconcile 응답 (ms) | 커넥션이 사용 중이던 시간 (ms) |
|---|---|---|
| broker 정상 | 193 (첫 요청), 19, 28, 17, 21 | 177, 17, 27, 10, 16 |
| broker 정지 · producer 의 metadata 캐시됨 | 22, 19, 16, 16, 32 | 21, 16, 10, 11, 28 |
| broker 정지 · producer 새로 만듦 (broker 가 죽은 채 재시작한 상황) | 1036, 1083, 1060, 1080, 1077 | 1032, 1072, 1049, 1074, 1066 |

(Hikari active 최댓값은 모든 장면에서 1. 동시 요청 없이 하나씩 측정했다.)

- **실행 중 broker 가 멈추면 응답은 늦어지지 않는다.** metadata 가 캐시돼 있어 `send()` 는 버퍼에 넣고 바로 돌아온다. 대신 결과는 나중에 콜백으로 온다(`delivery.timeout.ms` 안의 재시도 뒤 성공 또는 실패). 이 실험은 정지가 짧아 그 실패 시점까지는 관찰하지 않았다.
- **broker 가 없는 채 producer 가 새로 만들어지면 확정 응답이 `max.block.ms` 만큼 늘어난다.** 실패는 `Topic order-events not present in metadata after 1000 ms` 로 콜백 없이 send 호출에서 나며 ERROR 로그로 남았다(주문은 CONFIRMED 유지).
- **그 1초 동안 DB 커넥션을 쥐고 있다.** 커넥션 사용 시간이 응답 시간과 거의 같다. AFTER_COMMIT listener 는 트랜잭션 자원 정리(커넥션 반납) 전에 실행되기 때문이다. 설계 3.5 에서 "확인 필요"로 둔 가정이 맞았다. 동시에 확정이 몰리면 커넥션 풀(운영 40, 테스트 10)이 `max.block.ms` 동안 묶여, 확정과 무관한 DB 요청까지 커넥션을 기다리게 된다(3주차의 풀 고갈과 같은 모양).
- **하한 근거:** 정상 broker 에서 metadata 를 처음 받는 데 10ms 미만이었다. 1s 는 정상 첫 send 보다 100배 이상 크다.

**결정: `max.block.ms` 를 1000 → 500 으로 낮췄다 (D-75).** 장애 때 응답 · 커넥션이 묶이는 시간을 반으로 줄이면서, 로컬 측정이 대표하지 못하는 운영 네트워크의 첫 연결 지연을 감당할 여유(정상 첫 send 의 50배 이상)는 남겼다. 200ms 는 Outbox 전이라 넘기면 정상 이벤트를 잃을 위험이 커져 버렸다. 위 표는 1000 일 때의 측정이다.

## 참고: 재시도 상한(D-87)을 위한 MySQL 재시작 시간

일시적 오류 재시도 상한(잠정 5분)의 참고값이다. 빈 `mysql:8.0` 임시 컨테이너를 재시작하고, 0.1초 간격으로 `select 1` 이 다시 성공할 때까지 잰 뒤 컨테이너를 지웠다.

| 장면 | 다시 쿼리 가능해지기까지 |
|---|---|
| `docker restart` 1 ~ 5회 | 2.10s, 1.90s, 2.01s, 2.42s, 2.26s |
| `docker stop` 뒤 `start` | 2.69s |

- 로컬의 빈 DB 재시작은 3초 안쪽이다. 지금 상한(5분)은 이보다 100배 이상 길다.
- 다만 이 값은 데이터 · 커넥션 · 복제가 없는 로컬 재시작이다. 운영의 managed DB failover(복제본 승격, DNS · 엔드포인트 전환)는 수십 초에서 몇 분이 걸릴 수 있어 이 측정이 대표하지 않는다. 상한을 바꿀지는 결정 대상으로 남긴다.

## 재시도 상한(D-87): 실제 DB 장애 실험

`apps/commerce-streamer/.../experiment/DatabaseOutageExperiment` (`RUN_EXPERIMENT=true`). 구매 활동 listener 가 소비하는 동안 Testcontainers MySQL 에 장애를 만들었다. backoff 는 운영값(1s, ×2, 최대 30s), 상한만 60s 로 줄였다.

- **응답 없음:** `docker pause`. **연결 거부 · 끊김:** MySQL `offline_mode`(관리자 외 세션을 끊고 새 접속 거부). 컨테이너 재시작은 쓰지 않았다. Docker 가 무작위 호스트 포트를 재시작마다 새로 배정해(55102 → 55103 → 55104 로 확인) 앱이 옛 주소로 붙기 때문이다.

| 장면 | 장애 유지 | 정지 | 시도 시각 (장애 시작 기준, s) | 예외 | 복구 뒤 commit 까지 | DLT | 활동 |
|---|---|---|---|---|---|---|---|
| 응답 없음 20초 | 20s | — | 0.5, 11.5, 22.2(성공) | `CannotCreateTransactionException` | 2.3s | 0 | 1 |
| 연결 거부 20초 | 20s | — | 0.0, 1.0, 6.1, 13.1, 24.1(성공) | 〃 | 4.2s | 0 | 1 |
| 연결 거부, 멈출 때까지 | 79s | **중단** | 0.0, 1.0, 6.0, 13.1, 24.1, 43.2, 76.2 → 재시작 뒤 79.7(성공) | 〃 | 1.2s | 0 | 1 |
| 응답 없음, 최대 400초 | 400s | **안 멈춤** | 0.0 (400초 동안 돌아오지 않음) → 복구 뒤 401.3(성공) | — | 1.3s | 0 | 1 |

- **DB 오류는 일시적 오류로 분류됐다.** 네 장면 모두 DLT 로 간 record 가 없고, 회복 뒤 구매 활동이 한 번 저장됐다. DB 장애 동안의 주문이 DLT 로 빠지지 않는다.
- **상한(`maxElapsedTime`)은 벽시계 시간이 아니다.** Spring `ExponentialBackOff` 는 `currentElapsedTime += nextInterval` 로 backoff 간격의 합만 센다(spring-core 6.2.5 소스). 시도 자체에 걸린 시간(커넥션 획득 대기 3s 등)은 더해지지 않아, 간격 합 60s 일 때 연결 거부 장애에서 실제로 멈추기까지 79초가 걸렸다. 운영 설정(간격 합 5분)이면 연결 거부 장애에서 실제 중단은 5분보다 늦다.
- **응답 없는 DB 에서는 상한이 동작하지 않는다.** listener 스레드가 DB 호출 안에서 예외 없이 기다려 재시도 자체가 일어나지 않았다(JDBC socket timeout 없음). 약 300초에 `max.poll.interval.ms`(기본 5분)를 넘겨 group 에서 빠지고 rebalance 가 일어났다. 인스턴스가 여럿이면 partition 을 넘겨받은 쪽도 같은 DB 에서 같은 일을 겪는다. DB 가 돌아오자 호출이 끝나 같은 record 를 다시 처리했다.

### 운영 DB failover 문헌값 (참고)

| DB | 문서에 나온 failover 시간 |
|---|---|
| Amazon RDS Multi-AZ DB instance | 보통 60~120초. 큰 트랜잭션 · 긴 복구면 더 길어짐 |
| Amazon RDS Multi-AZ DB cluster | 보통 35초 미만 |
| Amazon Aurora | 복제본이 있으면 보통 30초 안팎(API 문서는 35초 미만) |
| Google Cloud SQL for MySQL HA | 커넥션 재수립까지 약 60초. 다른 문서는 "몇 분" |

- failover 는 DNS 를 바꾸므로 클라이언트가 다시 접속해야 한다. AWS 는 Java 클라이언트의 DNS 캐시 TTL 을 60초 이하로 두라고 권한다. 그 시간만큼 회복이 더 늦어질 수 있다.
- 출처: [RDS Multi-AZ DB instance failover](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.MultiAZ.Failover.html), [RDS Multi-AZ DB cluster failover](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/multi-az-db-clusters-concepts-failover.html), [Aurora 가용성 FAQ](https://docs.aws.amazon.com/rds/latest/auroraextendedcontent/aurora-faq-availability-and-durability.html), [Aurora FailoverDBCluster](https://docs.aws.amazon.com/AmazonRDS/latest/APIReference/API_FailoverDBCluster.html), [Cloud SQL for MySQL 고가용성](https://docs.cloud.google.com/sql/docs/mysql/high-availability), [Cloud SQL HA 설정](https://docs.cloud.google.com/sql/docs/mysql/configure-ha). 2026-10-29 조회. 문서 갱신 시기가 서로 달라 수치는 바뀔 수 있다.

### 상한에 대한 해석

- 문헌값으로 보면 감당할 장애는 대략 2분 안쪽(RDS instance 최대 120초)이고, DNS TTL 을 더해도 3분 안팎이다. 지금 상한(간격 합 5분, 연결 거부 장애에서 실제 중단은 5분 남짓 + 시도 시간)은 이를 덮는다.
- 다만 **응답 없는 장애에서는 상한이 아니라 JDBC 의 timeout 이 없다는 점이 실제 한계**다. 상한을 어떻게 정해도 이 경우에는 동작하지 않는다.

### 드라이버 timeout 적용 뒤 (D-98)

streamer 에 Connector/J `socketTimeout=10000` · `connectTimeout=3000` 을 넣고 같은 실험을 다시 돌렸다(커밋 `980c1b2` 실험 코드).

| 장면 | 적용 전 | 적용 후 |
|---|---|---|
| 응답 없음 20초 | 시도 0.5, 11.5, 22.2(성공) | 0.4, 11.5, 22.2(성공) — 같음 |
| 연결 거부 20초 · 멈출 때까지 | 79초에 중단 | 79초에 중단 — 같음 |
| **응답 없음, 멈출 때까지** | 시도 1번이 400초 동안 돌아오지 않음, 약 300초에 group 이탈 | 시도 8번(0.0, 21.0, 33.1, 47.1, 65.2, 84.2, 117.2) 뒤 **120초에 중단**, 재시작 0.24초 뒤 저장 · commit, DLT 0, 활동 1 |

- 응답 없는 장애도 예외가 되어 재시도 · 상한 · 중단이 동작한다. 시도 하나는 12~21초 걸렸다(socket timeout 10초 + 커넥션 획득 · 검증 대기).
- 운영 설정(간격 합 5분, 시도 약 15번)에 대입하면 실제 중단까지 연결 거부는 약 6분, 응답 없음은 약 8~10분으로 추정된다. 문헌값(30초~2분)의 장애는 그 전에 회복한다.
- 정상 테스트(`check`)는 모두 통과해 정상 처리를 끊지 않았다. streamer 문장의 실제 최장 시간을 따로 재지는 않았다.

## commerce-api 에 드라이버 timeout 을 둘지: 실제 DB 장애 실험

`apps/commerce-api/.../experiment/ApiDatabaseOutage{Without,With}TimeoutExperiment` (`RUN_EXPERIMENT=true`). 결제 시작(TX A) · PG 결과 적용(TX B) 도중 MySQL 을 `docker pause` 로 40초 멈췄다. timeout 은 실험에만 켰고 실제 api 설정은 바꾸지 않았다.

| 장면 | timeout 없음 | timeout 있음 (socket 10s · connect 3s) |
|---|---|---|
| 결제 시작(TX A) 중 장애 | 장애 40초 내내 응답 없음 → 복구 뒤 성공 | **20초에 실패**(`JpaSystemException ← SQLException`), 주문은 DRAFT 로 rollback, PG 호출 0 → 같은 시도 키로 다시 보내 정상 진행 |
| PG 결과 적용(TX B) 중 장애 | 장애 40초 내내 응답 없음 → 복구 뒤 확정, 이벤트 1 | **20초에 실패**(`CannotCreateTransactionException ← SocketTimeoutException`), 결제 대기 유지 → reconcile 로 확정, 이벤트 1 |

- **timeout 이 없으면** 요청은 DB 가 돌아올 때까지 Tomcat 스레드와 DB 커넥션을 쥔 채 기다린다. 장애가 길면 풀(운영 40)이 묶여 결제와 무관한 API 까지 멈춘다. 대신 DB 가 돌아오면 같은 요청이 그대로 성공해 결과가 모호해지지 않는다.
- **timeout 이 있으면** 요청은 약 20초에 실패하고 트랜잭션은 rollback 된다(socket timeout 10초 + 커넥션 획득 · 검증 대기). 이 실험의 두 장면은 모두 TX 시작 · 첫 쿼리에서 끊겨 결과가 모호하지 않았고, 기존 복구 경로(같은 시도 키 재요청, reconcile)로 정상 진행했다. 이벤트도 한 번만 나갔다.
- **COMMIT 순간에 끊기는 경우는 재현하지 못했다.** pause 를 COMMIT 패킷 사이에 정확히 맞추기 어렵다. 대신 코드로 결과를 따졌다.
  - Spring `AbstractPlatformTransactionManager.processCommit` 은 `doCommit` 이 예외를 던지면 `afterCompletion(STATUS_UNKNOWN)` 만 부르고 `triggerAfterCommit` 을 부르지 않는다(spring-tx 6.2 소스). AFTER_COMMIT 발행은 일어나지 않는다.
  - **TX A 가 실제로는 commit 됐다면** 결제 시도 REQUESTED · 재고 예약이 남고 PG 생성은 보내지 않은 상태다. 같은 시도 키로 다시 보내면 저장 상태만 돌려줘 PG 를 다시 부르지 않는다. 5주차의 "발송 기록 뒤 멈춤(REQUESTED + 조회 0건)" 한계와 같은 상태다.
  - **TX B 가 실제로는 commit 됐다면** 주문은 CONFIRMED 인데 이벤트가 나가지 않는다. 다음 reconcile 은 종결 상태라 아무것도 하지 않는다. 6주차 commit 뒤 발행 누락 구간과 같은 상태이며, 7주차 Outbox 가 같은 TX 에 이벤트를 기록하면 사라진다.
  - commit 되지 않았다면 위 실험과 같이 기존 재시도 경로로 정상 진행한다.

**결정 (D-99): commerce-api 에도 같은 timeout 을 둔다.** 장애가 서비스 전체로 번지는 것을 막는 이득이 크고, COMMIT 이 끊긴 모호한 결과는 이미 받아들인 두 한계와 같은 상태라 새 위험이 아니다. 실험 클래스의 "timeout 없음"은 이제 설정을 0(무한)으로 덮어 적용 전과 같게 만든 기준선이다.

## 복제 장애: broker 3대 cluster 실험

`apps/commerce-streamer/.../experiment/ReplicationExperiment` (`RUN_EXPERIMENT=true`). 로컬 · 테스트 broker 는 1대라 확인하지 못하는 `acks` · ISR 보장을 Testcontainers 로 broker 3대(`cp-kafka:7.5.3`, KRaft, 노드마다 broker 겸 controller) cluster 를 따로 띄워 확인했다. 앱 코드는 쓰지 않은 Kafka 단독 실험이다.

| 장면 | leader · ISR 변화 | 결과 |
|---|---|---|
| follower 1대 정지 (RF 3, `min.insync.replicas=2`) | leader 1, ISR [1,2,3] → [1,3] | `acks=all` 성공 |
| ISR 2 < `min.insync.replicas=3` (RF 3) | leader 1, ISR [1,2] | `acks=all` 은 broker 가 매번 `NOT_ENOUGH_REPLICAS` 로 거절 → 재시도 끝에 실패 · `acks=1` 은 성공 |
| leader 정지 (RF 3, min.insync 2, `acks=all` 로 100건) | leader 1 → 2, ISR [2,3] | 확인받은 100건이 모두 새 leader 에 남음 |
| `acks=1`, follower 가 뒤처진 동안 leader 만 20건 → leader 정지 (RF 2) | leader 2, ISR [2] → leader 3(unclean 선출) | 확인받은 21건 중 **leader 만 받은 20건이 사라짐**. 복제된 1건만 남음 |
| `acks=all` 이지만 `min.insync.replicas=1`, 나머지는 위와 같음 (RF 2) | leader 1, ISR [1] → leader 2 | 확인받은 21건 중 **20건이 사라짐**. `acks=1` 과 같은 결과 |
| 크기 초과 record (topic `max.message.bytes` 1KB, 2KB 전송, RF 3) | leader 2, ISR [1,2,3] | `acks=1` 은 `RecordTooLargeException` 실패 · **`acks=0` 은 성공(offset -1)**, topic 에 저장된 record 0건 |
| 선출 요청 없음, unclean **불허** (RF 2, `acks=1` 로 leader 만 20건 → leader 정지 → 뒤처진 follower 복귀) | leader 2, ISR [2] → leader 없음 | 90초 동안 leader 가 서지 않음. partition 을 읽고 쓸 수 없음 |
| 선출 요청 없음, unclean **허용** (나머지는 위와 같음) | leader 3, ISR [3] → leader 없음 | **허용해도** 90초 동안 leader 가 서지 않음. Admin `electLeaders(UNCLEAN)` 를 요청한 두 유실 장면에서만 뒤처진 follower 가 leader 가 됨 |
| unclean 선출 뒤 옛 leader 복귀 → ISR 재합류 → `electLeaders(PREFERRED)` 로 다시 leader (RF 2, `acks=1` 로 leader 만 20건) | leader 1, ISR [1] → leader 2, ISR [1,2] → leader 1, ISR [1,2] | 끝 offset 21 → 1 → **1**. 옛 leader 로 되돌려도 확인받은 21건 중 20건 없음 |

- **`acks=all` 은 "확인받은 record 는 ISR 모두에 있다"를 지킨다.** leader 가 죽어도 ISR 의 다른 replica 가 이어받아 잃지 않았다. ISR 이 `min.insync.replicas` 보다 작으면 broker 가 쓰기를 거절해, producer 는 "보냈다"고 착각하지 않고 실패를 안다. 이 앱의 producer(`retries: 3`)라면 세 번 거절된 뒤 실패 콜백 → ERROR 로그로 드러난다(D-73).
- **`acks=all` 의 보장은 `min.insync.replicas` 가 만든다.** `acks=all` 은 "그 순간 ISR 모두"의 확인이라, `min.insync.replicas=1` 이면 ISR 이 leader 하나로 줄어도 쓰기를 받아들인다. 그 상태에서 leader 가 죽자 `acks=1` 과 똑같이 20건을 잃었다. 같은 장면에서 `min.insync.replicas` 가 ISR 보다 크면 broker 가 쓰기를 거절해(위 ISR 부족 행) 유실 대신 실패로 드러난다.
- **`acks=0` 은 broker 의 거절도 보지 못한다.** 응답을 기다리지 않으므로 offset 을 모른 채(-1) 성공으로 끝났고, 같은 record 를 `acks=1` 로 보내면 broker 가 크기 초과로 거절한 것을 실패로 받았다. 크기 초과를 고른 이유: `min.insync.replicas` 는 `acks=all` 일 때만 검사해 ISR 부족으로는 acks=0 의 차이가 드러나지 않고, leader 정지는 선출 · metadata 갱신 시점이 섞여 결과가 흔들린다. producer 의 `max.request.size`(1MB) 안이라 클라이언트가 막지 않고 broker 만 거절한다.
- **`acks=1` 은 leader 하나가 썼다는 확인만 받는다.** follower 가 뒤처진 동안 leader 가 죽고 다른 replica 가 leader 가 되면, 성공으로 확인받은 record 가 사라진다. 이 장면은 unclean 선출(ISR 밖 replica 를 leader 로)이 일어나야 나타나며, KRaft 3.5 에서는 topic 설정만으로는 자동으로 일어나지 않아 Admin API(`electLeaders(UNCLEAN)`)로 요청했다(아래 "선출 요청 없음" 두 행에서 확인). unclean 선출을 허용하지 않으면 같은 장애에서 partition 은 잃는 대신 **leader 가 없어 읽기 · 쓰기가 멈춘다**.
- 두 번째 실행(2026-10-30, acks=0 · min.insync=1 장면 추가)에서 ISR 부족 행의 `acks=all` 은 `TimeoutException` 으로 끝났다. broker 의 `NOT_ENOUGH_REPLICAS` 거절을 producer 가 재시도하다 `delivery.timeout.ms`(20초)를 넘긴 것으로, 첫 실행과 같은 결과다. 두 유실 장면의 선출 요청은 `ElectionNotNeededException` 으로 끝났다(요청 시점에 이미 선출된 상태).
- **세 번째 실행(2026-10-30): unclean 선출이 자동으로 일어나는지.** 선출 요청 없이 leader 정지 · 뒤처진 follower 복귀 뒤 90초(broker fence 시간 9초의 10배)를 지켜봤다. unclean 을 허용한 topic 도 허용하지 않은 topic 과 똑같이 leader 가 없었다. 즉 **Kafka 3.5 KRaft 에서 `unclean.leader.election.enable=true` 는 Admin 요청이 있어야 효과가 난다.** 원인은 controller 소스에 있다. 3.5.1 의 `ConfigurationControlManager.uncleanLeaderElectionEnabledForTopic` 이 topic 설정과 관계없이 `return false; // TODO` 이고, 3.9.0 에서야 topic 설정을 읽는다([3.5.1](https://github.com/apache/kafka/blob/3.5.1/metadata/src/main/java/org/apache/kafka/controller/ConfigurationControlManager.java#L476-L478)). Admin `electLeaders(UNCLEAN)` 은 이 설정을 거치지 않는다. 두 번째 실행의 `ElectionNotNeededException` 은 자동 선출이 아니라, 같은 반복문의 앞선 요청이 이미 반영된 뒤 다시 요청한 결과로 본다. 이번 실행에서는 두 유실 장면 모두 요청이 바로 성공했다. 유실 · 거절 · 크기 초과 장면의 결과는 세 번 모두 같았다.
- **유실은 영구적이다(truncation).** 옛 leader 는 돌아와 follower 로 fetch 할 때, 새 leader 가 돌려준 diverging epoch(옛 epoch 는 offset 1 에서 끝)를 받고 자기 로그를 1 로 잘랐다. 그래서 ISR 에 다시 들어온 뒤 leader 로 되돌려도 20건은 없었다. 근거: Kafka 3.5.1 `Partition.fetchRecords`(leader 쪽 diverging epoch 계산), `AbstractFetcherThread`(follower 쪽 truncation, IBP 2.7 이상), KIP-101.
- 노드가 broker 겸 controller 라 한 번에 1대만 멈췄다(2대를 멈추면 controller quorum 이 깨져 선출 자체가 안 된다). ISR 부족은 `min.insync.replicas=3` 으로, `acks=1` 유실은 RF 2 topic 으로 만들었다.
- 실험 producer 는 `acks` 차이만 보려고 idempotence 를 껐다. 켜면 producer ID 요청이 멈춘 broker 로 가 시간을 넘기는 일이 섞였다.
- 확인하지 못한 것: 네트워크 분할, broker 프로세스 강제 종료(pause 만 씀), 여러 partition · 대량 트래픽에서의 동작.

## 여러 partition · 많은 이벤트

`apps/commerce-streamer/.../experiment/ThroughputExperiment` (`RUN_EXPERIMENT=true`). 운영 listener factory(ack mode · error handler · 직렬화)로 컨테이너를 직접 만들고 concurrency 만 바꿨다. 실제 consumer · facade · DB 를 거친다. 확정 이벤트 1만 건(이벤트당 품목 2), partition 3. 노트북 Docker 의 broker 1대 · MySQL 1대 수치라 **절대값보다 비교**에 의미가 있다.

### A: concurrency 별 처리 속도

| concurrency | consumer 별 배정 | 처리 시간 | 초당 처리 |
|---|---|---|---|
| 1 | [0,1,2] | 65.0s | 161 |
| 3 | [0] · [1] · [2] | 28.5s | 351 |
| 5 | [] · [2] · [] · [0] · [1] | 26.2s | 385 |

- **partition 수까지는 consumer 를 늘리면 빨라진다.** 1 → 3 에서 약 2.2배였다. record 마다 DB 트랜잭션을 쓰므로 consumer 하나가 DB 왕복을 기다리는 동안 다른 partition 이 놀지 않게 된다.
- **partition 수를 넘는 consumer 는 일을 받지 못한다.** concurrency 5 에서 둘은 배정이 비었고, 속도는 3 과 같은 수준이었다(차이는 측정 편차 범위).
- 세 측정 모두 서로 다른 주문 1만 건이 정확히 한 번 저장됐다.
- **발행 중 생긴 중복:** 만든 직후의 topic 에 보내다 실패로 보인 record 를 다시 보냈더니, 일부는 실제로는 이미 저장돼 있어 topic 에 같은 eventId 가 중복으로 쌓였다(concurrency 1 측정 497건, 5 측정 70건). 앱이 같은 업무를 새 record 로 다시 보내면 idempotent producer 로도 막지 못하는 경우이며, consumer 의 같은 사실 판정이 모두 no-op 으로 흡수했다. 7주차 Outbox relay 의 재전송이 만드는 중복도 같은 방식으로 흡수된다.

### B: 소비 도중 rebalance

인스턴스 하나(concurrency 1)로 소비를 시작하고, 30% 에서 다른 인스턴스(concurrency 2)가 합류, 60% 에서 첫 인스턴스가 이탈했다.

| 항목 | 값 |
|---|---|
| 배정 | [0,1,2] → 합류 뒤 [0] + [1] · [2] → 이탈 뒤 [0,1] · [2] |
| 발행된 record · 저장 · 재전달 | 10000 · 10000 · 0 |
| 처리 기록 · 서로 다른 주문 | 10000 · 10000 |
| 구매 활동 행 · 수량 합 | 20000 · 30000 (기대값과 같음) |
| 남은 lag | 0 |

- **rebalance 두 번에도 주문마다 정확히 한 번 저장됐다.** 유실 0, 중복 0.
- 재전달이 0건인 것은 record 마다 DB commit 직후 즉시 ack(`MANUAL_IMMEDIATE`)하고, 이탈이 정상 종료였기 때문이다. 처리 중 강제로 죽으면 DB commit 뒤 ack 전 record 가 재전달되며, 그 경우는 7.3 에서 no-op 으로 흡수됨을 확인했다.

### D-88(concurrency 1)에 대한 판단

- 로컬 기준 concurrency 1 로도 초당 160건, 시간당 약 58만 건을 처리한다. 지금 확정 주문 규모에서는 부족하지 않아 1 을 유지한다.
- 처리량이 필요해지면 partition 수(3)까지 늘리면 효과가 있다. 그 이상은 partition 을 먼저 늘려야 한다.
