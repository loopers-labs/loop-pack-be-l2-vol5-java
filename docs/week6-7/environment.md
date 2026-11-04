# 6~7주차 초기 실행 환경

## 준비 범위

- Java 21·기존 Gradle wrapper·Spring Boot 3.4.4/Testcontainers BOM을 유지한다.
- `modules:kafka`의 testFixtures가 `KafkaTestFixture.create()`를 제공한다.
- 실제 이미지 `apache/kafka:3.8.0`을 고정하고 시작 timeout은 2분이다.
- 생성만으로 시작하지 않는다. 테스트가 start/stop 또는 JUnit `@Container`로 수명주기를 소유한다.
- `.withReuse(true)`·고정 포트·전역 System property는 추가하지 않는다.
- producer·consumer·admin 모두 해당 container의 `getBootstrapServers()`를 사용한다.
- 테스트마다 고유 topic/group을 사용한다. 공유 compose broker/DB를 초기화하지 않는다.
- 이 fixture는 연결 기반이다. 주문·Inbox·Outbox·멱등성·재전달 요구사항 검증을 대신하지 않는다.

`testFixturesApi("org.testcontainers:kafka")`로 반환 타입을 공개하므로 consumer 모듈도 타입을 사용할 수 있다.
streamer는 이미 Kafka testFixtures 의존성을 가진다.
API 쪽 의존성/설정 연결은 R01에서 한다. 무작정 Kafka 모듈을 추가하면 기존 API context의 component scan과
KafkaConfig bootstrap 설정에 영향을 줄 수 있으므로 이번 준비에서 production classpath는 바꾸지 않는다.

## 초기 연결 검사

저장소 루트 PowerShell에서 실행한다. Docker Linux engine이 동작해야 한다.

```powershell
.\gradlew.bat :modules:kafka:test --tests '*KafkaTestFixtureTest' :apps:commerce-streamer:compileTestJava
```

연결 검사는 실제 2-partition topic 생성 → broker 발행 metadata 확보 → 같은 주소의 consumer 수신을 확인한다.
Docker가 없으면 skip으로 바꾸지 않고 실패한다. 업무 listener factory를 로드하지 않는 low-level 기반 검사다.
topic 생성/send에는 각 10초, 소비에는 20초, 테스트 본문에는 60초 제한을 둔다.

Docker 29와 기존 docker-java의 API 협상 문제를 피해야 하는 로컬 환경에서는 다음 파일을 사용한다.
이는 로컬 전용이며 `.gitignore`로 제외한다. 플랫폼 공통 요구사항으로 강제하지 않는다.

```properties
# modules/kafka/src/test/resources/docker-java.properties
api.version=1.44
```

필요하면 기존 API의 같은 로컬 파일에서 복사한다. Docker Desktop 자체가 꺼진 상태는 API 설정으로 해결되지 않는다.

## 기존 실행 설정의 후속 작업

다음은 현재 확인한 R01 연결 과제이며 이번 준비에서 수정/검증 완료로 기록하지 않는다.

- `kafka.yml` consumer의 `value-serializer`를 실제 value-deserializer 계약으로 보완.
- 사용자 정의 표기와 달리 Kafka가 읽는 `enable.auto.commit` 실효 값 확인.
- 호스트 producer/consumer와 달리 `kafka:9092`로 고정된 local/test admin 주소 일치.
- 기존 batch/MANUAL/concurrency 3 factory와 구매 활동용 record factory 분리.
- 테스트의 1MiB fetch·5초 wait 보완과 bounded assignment/result 대기.
- streamer의 `spring.application.name=commerce-api` 및 API와 동시 실행 시 포트 분리.
- 새 Inbox·활동·Outbox Entity와 명시 SQL/인덱스 및 데이터 보존 프로필 연결.

기존 `local` JPA 프로필은 `ddl-auto=create`다. 보존 중인 결제 실험 DB에 무심코 실행하지 않는다.
5주차 독립 PG·payment-lab의 보존 프로필과 명시 SQL은 기존 안내를 따른다.
새 통합 검증은 격리 MySQL·Kafka·로컬 PG를 소유하고 준비부터 복구까지 같은 상태를 유지한다.

## 검증 단계

초기 준비는 위 Kafka 연결과 관련 컴파일을 확인한다. R01부터 실제 DB·listener·업무 규칙을 추가 검증한다.
기능 PR의 마무리에는 아래 검사를 실행하고 새 테스트의 실제 실행 건수·실패/skip 여부를 확인한다.

```powershell
.\gradlew.bat :apps:commerce-api:check :apps:commerce-streamer:check :modules:kafka:check
```

현재 Checkstyle·ArchUnit gate는 API에 적용돼 있다. streamer의 신규 업무 코드에 대한 같은 기준 적용은
R01에서 실제 대상/설정과 함께 확정하고, 아직 없는 검사를 통과했다고 기록하지 않는다.
