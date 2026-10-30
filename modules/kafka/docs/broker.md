# Kafka Broker와 topic 구성

`modules:kafka`는 앱에서 재사용하는 Kafka client 연결과 테스트 broker fixture를 제공한다. broker 실행 설정은 [로컬 Compose](../../../docker/infra-compose.yml), client 공통 설정은 [kafka.yml](../src/main/resources/kafka.yml)에 있다. 업무 payload는 [공통 이벤트 계약](../../../docs/system-design/use-cases/order-confirmed-event.md)에서 관리한다.

## 책임과 구성 결정

broker는 partition별 로그와 consumer group의 offset을 보관한다. 주문이 확정됐는지, 구매 활동이 DB에 저장됐는지는 판단하지 않는다. broker의 전송 성공과 consumer의 offset 진행만으로 전체 업무의 완료를 판정하지 않는다.

업무 topic의 신규 생성값은 partition 3개·복제 1개다. partition 3개는 현재 구매 활동 consumer 3개가 분담할 단위이며, 부하 측정으로 산정한 처리 용량은 아니다. 단일 broker·복제 1은 로컬 구성의 선택으로, broker 장애 중 가용성이나 복제 내구성의 근거로 사용하지 않는다.

## 로컬·테스트 환경

| 환경 | 구성 | 연결 주소 |
| --- | --- | --- |
| 로컬 Compose | 단일 KRaft broker, `bitnamilegacy/kafka:3.5.1` | 호스트 앱은 `localhost:19092`, Compose 내부는 `kafka:9092` |
| Kafka Testcontainers fixture | 단일 `apache/kafka:3.8.1` | fixture가 주입한 실제 `spring.kafka.bootstrap-servers` |
| 독립 로컬 관찰 도구 | 실행 전용 `apache/kafka:3.8.1` | 실행마다 확보한 loopback 포트 |

producer·consumer·admin은 같은 환경의 broker 주소를 사용한다. 컨테이너 내부 주소와 호스트 주소를 섞지 않는다. 공통 YAML의 local·test admin 속성에 `kafka:9092`가 남아 있지만 Spring Boot가 공통 bootstrap 주소로 덮어쓴다. 실제 주소 일치는 `KafkaClientConfigTest`에서 확인한다.

애플리케이션의 Kafka client는 3.8.1이며, Compose broker 3.5.1과 테스트 broker 3.8.1은 구분한다. 단일 fixture 테스트 결과가 두 broker 배포판의 모든 동작을 검증한 결과는 아니다. Compose의 `/bitnami/kafka`는 `kafka-data` volume을 사용한다. 컨테이너 재시작과 volume 삭제는 구분하며, volume을 지우면 이 broker의 로그·offset을 보존할 수 없다.

## topic과 partition

| 항목 | 현재 구성 |
| --- | --- |
| 업무 topic | `order-events`, 신규 생성 시 partition 3개·복제 1개. 기존 topic은 별도 확인 |
| 업무 key | 공통 계약의 orderId 문자열. 같은 partitioner·직렬화·partition 수에서 같은 key는 같은 partition |
| 분담 | 같은 group의 member가 partition을 나누어 담당. 동시 소비 가능한 member 수는 partition 수 이내 |
| 실험 topic | 고유 suffix의 별도 topic, partition 2개·복제 1개. 구매 활동은 생성하지 않음 |

partition 안의 로그 순서를 보장한다. 해시 분산이 정확한 균등 분포를 보장하지는 않으며 partition 수 변경은 key 매핑을 바꿀 수 있다. consumer의 후속 처리 순서와 병렬성·실패 시 중단 범위는 [Consumer 설계](../../../apps/commerce-streamer/docs/purchase-activity-consumer.md#listener-설정)를 따른다.

concurrency를 바꾸는 것으로 partition 수가 바뀌지 않는다. 기존 topic이 partition 1개라면 consumer 3개를 띄워도 같은 group에서 두 member는 배정받지 못한다. partition 증설은 기존 key의 매핑과 순서 연결에 영향을 줄 수 있어 앱 기동의 부수 효과로 수행하지 않는다.

## topic 준비

로컬·테스트에서는 commerce-api의 `OrderConfirmedKafkaConfig`가 AdminClient로 없는 업무 topic을 partition 3개·복제 1개로 만든다. 이미 있으면 partition 수를 유지한다. 구체적인 앱 발행 경로는 [Producer 설계](../../../apps/commerce-api/docs/order-confirmed-producer.md)에서 관리한다. 테스트는 직접 만든 고유 topic·group을 준비하고 정리한다.

초기화는 API의 `ApplicationRunner`에서 실행한다. 생성 응답을 최대 5초 기다리고 AdminClient는 유한하게 닫는다. 이미 존재한다는 응답은 허용하며, 기존 partition 수·복제 수·보존 설정을 검증하거나 변경하지 않는다. 그 밖의 실패는 error 로그에 남기고 앱 시작을 계속하므로, API가 실행 중이라는 사실만으로 topic 준비 성공을 판단할 수 없다.

기동 순서는 broker와 DB 스키마 준비 → 업무 topic 준비 → 구매 활동 소비 확인 순으로 잡는다. streamer가 먼저 시작한 경우에도 consumer 수나 자동 생성에 topic 구성을 맡기지 않는다. Kafka의 topic 생성 성공 뒤에도 metadata 반영에는 시간이 걸릴 수 있으므로 실험은 기한 내 실제 topic 조회와 assignment로 준비 상태를 확인한다. [Admin API 계약](https://kafka.apache.org/38/javadoc/org/apache/kafka/clients/admin/Admin.html#createTopics(java.util.Collection,org.apache.kafka.clients.admin.CreateTopicsOptions))

`auto.create.topics.enable`은 broker 설정이다. 이 이름을 client properties에 넣어도 broker의 자동 생성은 꺼지지 않는다. 구매 활동 consumer는 `allow.auto.create.topics=false`로 존재하지 않는 업무 topic의 자동 생성을 요청하지 않는다. 운영 topic 생성과 partition 변경은 이번 학습 범위에 넣지 않는다.

## 보존과 복제의 한계

| 설정 | 현재 소유·적용 |
| --- | --- |
| partition 수·replication factor | API의 로컬·테스트 초기화에서 신규 topic에만 지정 |
| `retention.ms` / `retention.bytes` | topic override 없음. broker 설정 상속 |
| `cleanup.policy` | topic override 없음. key별 최신 상태를 위한 compaction 정책을 추가하지 않음 |
| `min.insync.replicas` | 업무 topic override 없음. broker 설정 상속 |

record는 소비 직후 삭제되지 않는다. topic override가 없다는 뜻은 무제한 보존이 아니라 broker 값을 사용한다는 뜻이다. 실제 환경에서는 상속된 값까지 확인하며, 이미지 기본값을 업무 복구 기간의 보장으로 해석하지 않는다. 보존 시간·용량을 업무 기준으로 산정한 값은 아직 없다. [Kafka topic 설정](https://kafka.apache.org/38/generated/topic_config.html)

consumer가 멈춰도 보존 정책은 계속 적용된다. 재시작 시 필요한 offset의 record가 이미 삭제됐다면 단순 재전달로 누락을 복구할 수 없다. `earliest`는 남아 있는 로그의 처음부터 읽는 정책이며 삭제된 구매 사실까지 복구하지 않는다. 구매 활동 DB를 로그에서 재구축할 때도 필요한 전체 기간의 record가 남아 있는지 먼저 확인해야 한다.

Producer의 `acks=all`은 현재 ISR의 기록을 확인한다. 단일 broker·복제 1에서는 ISR도 하나이므로 다중 replica 기록·leader 장애 인계·최소 ISR 미달 쓰기 거절을 검증한 것은 아니다. `acks`·재시도·멱등 producer 설정은 [Producer 설계](../../../apps/commerce-api/docs/order-confirmed-producer.md#producer-설정)에서 관리한다.

## client 공통 설정과 검증

공통 consumer는 `StringDeserializer`, `ByteArrayDeserializer`, `enable-auto-commit=false`를 지정한다. 구매 활동 listener의 factory·ack·fetch 설정은 앱에서 소유한다. 공통 batch factory의 fetch·poll 설정을 구매 활동 record factory에 그대로 적용하지 않는다.

## 상태 확인

로컬 Compose가 실행 중일 때 다음 명령으로 실제 topic 구성과 구매 활동 group의 committed offset·lag를 확인한다. 테스트 topic·group은 고유 이름이므로 실행에서 사용한 값으로 바꾼다. Kafka UI는 `http://localhost:9099`에서 같은 broker를 관찰한다.

```bash
docker compose -f docker/infra-compose.yml exec kafka kafka-topics.sh \
  --bootstrap-server kafka:9092 --describe --topic order-events

docker compose -f docker/infra-compose.yml exec kafka kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 --describe --group purchase-activity

docker compose -f docker/infra-compose.yml exec kafka kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 --describe --group purchase-activity --members --verbose
```

확인 대상은 topic의 partition·leader·replica·ISR, 실제 group member와 assignment, partition별 committed offset·log end offset이다. member 조회는 배정받지 못한 consumer까지 확인하기 위해 따로 수행한다. lag가 0이어도 DB에는 확정됐지만 broker로 발행하지 못한 주문은 발견할 수 없고, 저장된 구매 활동의 정확성도 별도 DB 조회가 필요하다. 상세 원인 확인과 재시작 절차는 [Consumer 설계](../../../apps/commerce-streamer/docs/purchase-activity-consumer.md#원인-확인과-재시작)를 따른다.

## 검증 범위

[KafkaTestContainersConfig](../src/testFixtures/java/com/loopers/testcontainers/KafkaTestContainersConfig.java)가 테스트 JVM의 broker를 제공하며 Testcontainers가 최종 정리를 맡는다. producer·consumer·admin의 실제 왕복은 `KafkaClientConfigTest`, partition 2개에서 consumer 1→2→3 배정과 key 매핑은 `KafkaPartitionGroupExperimentTest`로 확인한다. 테스트별 client·topic·group을 정리하며, 공유 fixture broker를 다른 테스트가 쓰는 도중 중지하지 않는다. 실제 배정·offset·관찰 방법은 [공통 검증 문서](../../../docs/system-design/use-cases/order-confirmed-event-verification.md#partitiongroup-실험-설계)를 따른다.

topic이 없을 때의 생성과 기존 partition 유지 여부는 API의 `OrderConfirmedKafkaDeliveryIntegrationTest`로 확인한다. 복제 장애, 실제 retention 만료 후 복구, 이미지별 장애 동작과 처리 용량은 현재 실험으로 검증한 항목이 아니다.
