# Kafka 로컬 환경 사용법

Kafka·Kafka UI의 환경 준비 기록과 재시작·조회·중지 방법을 적는다. 현재는 브로커 3개와 업무용 topic 두 개(`order-events`, `order-events-dlt`)를 compose가 만든다. 이전 단일 브로커 준비 기록은 하단에 보존한다.

## 구성

| 항목 | 구성 |
| --- | --- |
| Docker | Docker Desktop, context `desktop-linux` |
| 구성 파일·프로젝트 | `docker/kafka-cluster-compose.yml` · `week6-kafka` |
| Kafka | `bitnamilegacy/kafka:3.5.1`, `week6-kafka-1`·`week6-kafka-2`·`week6-kafka-3` |
| 역할 | 각 서버가 broker·controller를 겸하는 KRaft 구성 |
| broker 설정 | 자동 topic 생성 끔, 내부 offset topic(`__consumer_offsets`) 복제 수 3 |
| topic 생성 | `week6-kafka-init`이 브로커 3대가 healthy가 되면 `--if-not-exists`로 `order-events`(partition 3·복제 3·min ISR 2)와 `order-events-dlt`(partition 1·복제 3·min ISR 2)를 만들고 끝난다 |
| Kafka UI | `provectuslabs/kafka-ui:latest`, 컨테이너 `week6-kafka-ui`, cluster `week6` |
| Kafka 데이터 | `week6-kafka_kafka-1-data`·`week6-kafka_kafka-2-data`·`week6-kafka_kafka-3-data` |

기존 `docker/infra-compose.yml`·중지된 `kafka`/`kafka-ui`·기존 데이터 volume은 보존했다. 새 클러스터와 기존 단일 구성이 호스트의 19092·9099를 공유하므로 동시에 실행할 수 없다. 현재는 아래의 새 파일 명령으로 브로커 3개 환경을 관리한다.

이 문서의 명령은 서버 실행과 상태 조회를 위한 것이다. 업무용 topic은 compose의 init이 만들고, 앱은 topic이 있다는 전제로 동작한다.

## 접속 주소

| 접속 위치 | 주소 |
| --- | --- |
| 브라우저의 Kafka UI | [http://localhost:9099](http://localhost:9099) |
| Mac에서 접근할 broker 1·2·3 | `localhost:19092`·`localhost:29092`·`localhost:39092` |
| Compose 안의 Kafka CLI·UI | `kafka-1:9092`·`kafka-2:9092`·`kafka-3:9092` |

공통 kafka.yml의 local·test 프로필에서도 세 호스트 주소를 producer·consumer·admin의 bootstrap 후보로 사용한다. admin의 예전 `kafka:9092` 덮어쓰기는 제거했다. Docker 내부 접속에는 표의 내부 주소를 사용한다.

앱을 `local` profile로 실행하면 commerce-api는 주문 확정 이벤트를 `order-events`로 보내고, commerce-streamer는 group `purchase-activity`·concurrency 3으로 소비한다. 기본값과 `test` profile에서는 둘 다 꺼져 있다. demo consumer는 `demo-kafka.enabled=false`가 기본이다([로컬 topic과 활성화](design.md#local-topics-and-activation)).

broker의 자동 topic 생성은 꺼져 있다. 없는 topic으로 보내면 topic이 생기지 않고 send가 metadata를 얻지 못해 실패한다. client 설정의 `auto.create.topics.enable`은 broker 동작을 제어하지 못한다. 내부 offset topic의 복제 수는 처음 만들어질 때만 적용되므로, broker 설정을 바꿨다면 volume을 지우고 클러스터를 다시 만든다. 나머지 실효값은 [설계](design.md#three-brokers)를 따른다.

## 다시 시작·상태 확인·중지

저장소 루트에서 실행한다. 기본 Docker context가 달라도 기존 환경에 접속하도록 `--context desktop-linux`를 명시한다.

```bash
docker --context desktop-linux compose -f docker/kafka-cluster-compose.yml up -d
docker --context desktop-linux compose -f docker/kafka-cluster-compose.yml ps
```

broker 접속과 현재 topic 목록·구성을 조회한다. topic을 새로 만들지 않는다.

```bash
docker --context desktop-linux compose -f docker/kafka-cluster-compose.yml exec -T kafka-1 kafka-topics.sh --bootstrap-server localhost:9092 --list
docker --context desktop-linux compose -f docker/kafka-cluster-compose.yml exec -T kafka-1 kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic order-events,order-events-dlt
docker --context desktop-linux compose -f docker/kafka-cluster-compose.yml exec -T kafka-1 kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe --status
curl --fail --silent --show-error --max-time 10 http://localhost:9099/api/clusters
```

학습을 마친 뒤 새 Kafka 환경만 멈출 때 사용한다. 중지해도 데이터 volume은 유지된다.

```bash
docker --context desktop-linux compose -f docker/kafka-cluster-compose.yml stop kafka-ui kafka-3 kafka-2 kafka-1
```

## 브로커 3개 준비 결과

브로커 3개 모두 healthy, UI에서 `status=online`·`brokerCount=3`·`topicCount=0`·`lastError=null`을 확인했다. KRaft quorum의 CurrentVoters는 `[1,2,3]`이고 세 broker의 API 조회도 성공했다. 토픽 생성·메시지 전송·장애 실험은 실행하지 않았다. 상세 실행 결과는 [주문 과제 구현 기록](implementation-log.md#three-brokers)에 둔다.

<a id="local-setup"></a>

## 최초 환경 준비 기록

범위는 기존 Compose의 Kafka·Kafka UI 실행과 접속 확인으로 한정했다. 사용자가 직접 토픽 내용과 설정을 판단하기로 했으므로 토픽 생성·메시지 전송·consumer 실행은 진행하지 않았다.

Docker 엔진이 꺼져 있어 최초 접속에 실패했다. 사용자가 Docker Desktop을 실행한 뒤 context `desktop-linux`에서 진행했다. `docker/infra-compose.yml`은 변경하지 않았다.

### 실행한 명령

저장소 루트에서 Kafka·UI 이미지를 내려받고 두 서비스를 시작했다.

```bash
docker compose -f docker/infra-compose.yml up -d kafka kafka-ui
docker compose -f docker/infra-compose.yml ps kafka kafka-ui
docker compose -f docker/infra-compose.yml exec -T kafka kafka-topics.sh --bootstrap-server kafka:9092 --list
docker compose -f docker/infra-compose.yml exec -T kafka kafka-broker-api-versions.sh --bootstrap-server localhost:19092
curl --fail --silent --show-error --max-time 10 http://localhost:9099/api/clusters
```

### 실제 관찰

| 대상 | 결과 |
| --- | --- |
| Kafka | `bitnamilegacy/kafka:3.5.1`, 컨테이너 `kafka` healthy |
| Kafka UI | `provectuslabs/kafka-ui:latest`, 컨테이너 `kafka-ui` 실행 |
| UI의 클러스터 | `name=local`, `status=online`, `brokerCount=1`, `lastError=null` |
| 토픽 | CLI 목록이 비어 있고 UI API의 `topicCount=0`, `onlinePartitionCount=0` |
| 클라이언트용 listener | 컨테이너 내부에서 `localhost:19092`로 broker API 목록 조회 성공, node ID 1 |
| UI 호스트 접속 | Mac에서 `http://localhost:9099/api/clusters` 응답 확인 |

**확인 범위:** broker 준비와 관리 UI의 연결을 확인했다. Java 앱의 producer·consumer 연결, 메시지의 전달·재전달, DB 저장은 확인하지 않았다. 단일 broker 환경이다.

두 서비스는 실행한 상태로 두었다. 앱의 API·streamer를 시작하거나 다른 DB·Redis 서비스의 설정을 변경하지 않았다. 재시작·조회·중지 방법은 위 명령을 사용한다.
