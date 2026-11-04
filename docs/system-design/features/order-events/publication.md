# 주문 확정 이벤트 발행 상세설계

기존 절 번호 참조: [2.3](../../common/architecture.md#23-허용하는-의존-방향) · [3.7](README.md#37-최초-pg-확정-시각과-사건-값) · [5.8](README.md#58-orderconfirmed-v1-이벤트-계약).

기존 문서의 계약·절 번호·승인/검증 상태를 보존해 이동했다. 재편 자체는 제품 계약 변경이나 새 실행 검증을 뜻하지 않는다.

**W7 읽기 구분:** 아래 2.11·4.6과 기존 상태 설명은 W6 승인·구현 기준을 보존한 것이다. 문서 마지막의 W7·W7.2~W7.4는 변경 경계·저장/TX·relay/복구·검증의 기준이며, 2026-11-04 사용자가 전체 최종 승인했다. 단계 제목의 초안 표시는 작성 이력을 보존한 것이고 현재 계약은 보완본 전체다. 구현·실행 검증은 미실행이며 구현 단위별 착수 허락은 별도다.  직접 발행·Outbox 제외라는 W6 설명을 W7의 요구로 적용하지 않는다. 두 기준을 제품 runtime에서 병행한다는 뜻도 아니다.

**상태:** 2026-10-29 사용자 최종 승인 설계다. 발행·소비 전체 검토와 잔여 보완을 반영했고 승인된 단위별 구현·관련 실행 검증을 마쳤다. 관찰 결과와 한계는 [검증 기록](../../verification/order-events.md)에 둔다. 소비 측 상세설계는 [소비 상세설계](consumption.md)를 따른다. 각 구현 단위는 별도 착수 허락을 받는다.

2026-10-28 GPT 중간 검토 후 사용자가 설정 거절 정책·최소 문서 보완을 승인했다. 아래에 해당 내용을 반영했으며 전체 최종 승인·구현 검증과는 구분한다.

## 2.11 주문 확정 이벤트 발행의 책임과 설정

**상태:** 2026-10-29 사용자 승인 상세설계에 따라 구현·관련 검증을 수행했다. 이 절은 발행 측만 다루며 소비 측 저장·ack 상세설계를 대신하지 않는다. 근거·대안은 [A.34](../../decisions/order-events.md#a34-주문-확정-사실의-직접-발행), 처리 순서는 [4.6](#46-주문-확정-이벤트-발행)이다.

### 클래스와 의존 경계

아래 신규 타입은 `apps/commerce-api` 안에 둔다. 다른 앱·공통 JPA 모듈에 주문 Entity를 옮기거나 공유 이벤트 라이브러리를 새로 만들지 않는다. 기존 2.3 의존 규칙을 유지한다.

|위치·타입|계약과 책임|
|---|---|
|`domain/order/OrderModel`|`confirmPg(Money amount, ZonedDateTime confirmedAt)`으로 최초 PG 확정·시각 기록을 원자적으로 수행. 상세 불변조건은 3.7|
|`domain/order/OrderConfirmed`|불변 사건 record. `from(OrderModel)`은 TX 안에서만 호출하여 `orderId`, UTC `confirmedAt`, 정규화한 불변 `List<Item>` 복사. `Item(long productId, long quantity)`도 record. `eventId()`는 주문 ID에서 결정적으로 유도. Spring·Kafka·JPA Entity를 필드로 보관하지 않음|
|`domain/order/OrderEventPublisher`|`void publish(OrderConfirmed event)`: 최초 확정 사실을 외부에 전달하는 포트. Kafka 타입·Future·broker metadata를 호출자에게 노출하지 않음. 반환은 전송 완료 보장이 아님|
|`application/payment/PaymentTransactionService`|최초 CONFIRM 분기에 확정 시각 생성·`confirmPg`·사건 복사·Spring `ApplicationEventPublisher.publishEvent` 등록을 추가. 기존 잠금·PG 판정·저장·반환 순서 유지|
|`application/order/OrderConfirmedEventListener`|`void on(OrderConfirmed event)`. `@TransactionalEventListener(phase=AFTER_COMMIT, fallbackExecution=false)`. probe 뒤 발행 포트를 한 번 호출. `@Async`·`@Transactional`·Entity 조회 없음. commit 뒤 동기 호출 오류는 안전한 로그로 표시하고 정상 반환|
|`application/order/OrderPublicationProbe`|`void beforeSend(OrderConfirmed event)`: commit 뒤·send 전 누락 검증용 seam. 기본 bean은 no-op, 테스트만 대상 주문에서 gate·실패를 주입. 공개 HTTP 제어 기능이나 재발행 기능이 아님|
|`infrastructure/order/KafkaOrderEventPublisher`|bean 이름 `kafkaOrderEventPublisher`인 발행 포트 구현. `publish`에서 wire DTO 변환·String key·send·Future 완료 로그. nested 불변 `OrderConfirmedV1`·`Item` DTO는 5.8 wire 필드만 가짐. domain·application에 DTO/serializer를 노출하지 않음|
|`config/OrderEventKafkaProperties`|`commerce.order-events` 설정 바인딩. `topic` 기본 `order-events`, `producerProperties`는 아래 표의 설정 map, `physicalCloseTimeoutSeconds` 기본 15. topic 공백·close 음수는 기동 오류. 테스트에서 topic만 고유 suffix로 대체 가능|
|`config/OrderEventKafkaConfig`|이름 `orderEventProducerFactory`, `orderEventKafkaTemplate`인 전용 bean. publisher는 qualifier로 전용 template을 주입. `Clock` bean `orderConfirmationClock`은 `Clock.systemUTC()`, TX 담당 객체도 qualifier로 사용|

`OrderConfirmed`는 외부 소비자의 작업 지시가 아니라 도메인의 확정 사실 값이다. 내부 사건에는 schemaVersion·topic·Kafka key 설정을 넣지 않고 adapter가 5.8 wire 계약을 만든다. 같은 사실을 담더라도 내부 record와 외부 DTO를 분리하여 infrastructure → application 의존을 만들지 않는다. 새 Domain Service나 다른 Facade 호출을 추가하지 않는다. API의 `OrderInfo`와 HTTP DTO에 confirmedAt·발행 상태 필드를 추가하지 않는다.

### 전용 producer의 조립과 적용 범위

commerce-api build에 `modules:kafka` 의존성과 `kafka.yml` import를 연결한다. 공통 `KafkaConfig`의 batch listener·기존 `producerFactory`·`kafkaTemplate`은 전역 변경하지 않는다. 전용 template·factory를 별도로 이름 붙여 주문 발행에만 사용하며, 공통 producer는 사용 전 실제 KafkaProducer를 만들지 않는 기존 lazy 생성 규칙을 확인한다. bean을 만든 사실만으로 연결·발행 성공을 기록하지 않는다.

복수 factory/template이 등록되므로 전용 publisher뿐 아니라 기존 공통 bean의 주입 지점도 확인한다. generic 타입·bean 이름·qualifier로 각 template이 의도한 factory에 연결되는지 전체 컨텍스트 기동 검증에서 확인한다. 모호성을 해결하기 위해 전용 bean을 무조건 `@Primary`로 지정해 기존 경로를 바꾸지 않는다. 실제 충돌이 있으면 기존 공통 연결을 명시적으로 지정하는 최소 수정으로 해결하며 업무 계약을 바꾸지 않는다.

`OrderEventKafkaConfig`의 조립 순서:

```text
props = KafkaProperties.buildProducerProperties()의 새 map
props.remove(ProducerConfig.RETRIES_CONFIG)
  // 공통 producer.retries=3을 실제 유효 map에서 제거. 앱 YAML의 생략만으로 해결하지 않음
props.putAll(commerce.order-events.producer-properties)
  // 아래 표는 이 namespace의 기본값. 공통 consumer 설정에는 적용하지 않음
if 최종 props에 ProducerConfig.TRANSACTIONAL_ID_CONFIG key가 존재:
  기동 설정 오류로 거절 // 값 제거·비트랜잭션으로 조용히 전환하지 않음
props의 serializer/type header/acks/idempotence/timeout 유효 조합 확인
factory = 전용 DefaultKafkaProducerFactory<String, Object>
  // StringSerializer, 기존 ObjectMapper를 사용하는 JsonSerializer를 명시적으로 연결
  // JsonSerializer는 properties 경로로 add.type.headers=false 적용, setter/configure 혼용 금지
factory.setPhysicalCloseTimeout(physicalCloseTimeoutSeconds)
if factory.transactionCapable():
  기동 설정 오류로 거절 // 아직 실제 producer 생성·send로 시험하지 않음
template = 이 factory만 사용하는 KafkaTemplate<String, Object>
transactionIdPrefix·producerPerThread·autoFlush는 사용하지 않음
```

명시적인 prefix setter 호출 여부만으로 비트랜잭션이라고 단정하지 않는다. 최종 config map과 factory의 실제 capability를 모두 확인한다. 설정 오류 메시지는 충돌 key와 고정된 설명으로 남기고 설정값 전체를 출력하지 않는다. serializer는 ObjectMapper 생성자와 properties 구성만 사용하며 `configureSerializers` 기본 동작을 끄지 않는다. type header 설정 setter를 추가해 properties 경로와 혼용하지 않는다.

|설정 key|초기값|이유·한계|
|---|---|---|
|`acks`|`all`|ISR에 따른 broker 확인. 단일 broker의 복제 장애 내구성 입증은 아님|
|`enable.idempotence`|`true`|producer 프로토콜 내부 재시도 보호. 별도 send 중복·소비 DB 중복은 별개|
|`max.in.flight.requests.per.connection`|`5`|idempotence와 호환. 이유 없는 1개 직렬화는 하지 않음|
|`retries`|명시 제거, client 기본값|횟수 3으로 먼저 포기하지 않고 전달 제한 시간 안에서 재시도. Kafka clients 3.8.1 기본값은 2147483647이며 무한 전달 보장이 아님|
|`retry.backoff.ms` / `retry.backoff.max.ms`|`500` / `1000`|반복 실패의 backoff·jitter. 항상 500ms 고정 간격이 아님|
|`max.block.ms`|`5000`|metadata·buffer 확보 대기 기회. 요청 스레드 점유 비용이 남고 직렬화·HTTP 전체 시간 상한은 아님|
|`request.timeout.ms`|`20000`|기존 개별 요청 대기값 유지. 정상 응답 지연을 과하게 실패시키지 않으려는 초기 가설|
|`delivery.timeout.ms`|`30000`|전달·재시도의 시간 예산. 회복 불가 오류·배치 만료 등으로 더 일찍 완료될 수 있음|
|`linger.ms`|`0`|소량 이벤트에 의도적인 batch 대기를 추가하지 않음|
|`key.serializer` / `value.serializer`|String / JSON|5.8 계약. Java 클래스 header에 소비 계약을 의존시키지 않음|
|`spring.json.add.type.headers`|`false`|공통 YAML에도 있는 값을 전용 producer에 실제 적용|

수치는 운영 적정값이나 실측 결과가 아니다. 현재 초기값은 정상 최초 전송·일시적 지연에 여유를 주는 쪽을 택했고, cold start·broker 장애에서 요청/결과 관측이 길어지는 비용을 수용한다. `delivery.timeout.ms >= request.timeout.ms + linger.ms`, idempotence의 acks·retries·in-flight 호환을 확인한다. producer 최초 생성·객체 직렬화 등 모든 지연을 5초로 보장하지 않는다. 바인딩된 YAML과 실제 factory map·serializer·전송 metadata를 구분해 검증한다.

### 기존 회귀와 실제 발행 검증의 분리

실제 최초 PG 확정 경로를 실행하는 회귀에만 테스트 소스의 기록용 `RecordingOrderEventPublisher`를 명시적으로 적용한다. 해당 테스트 클래스 또는 기존 PG 전용 테스트 지원 클래스의 `OrderEventPublisher` 필드에 `@TestBean(name="kafkaOrderEventPublisher", methodName="…", enforceOverride=true)`를 두며, 인자 없는 static factory가 기록용 구현을 만든다. Spring의 test bean override 기능을 사용하고, 동명 일반 @Bean·전역 bean 덮어쓰기 허용·@Primary만으로 대체하지 않는다. fake 적용만을 위한 새 공통 부모 상속·전체 앱 공통 annotation·전역 customizer/guard는 사용하지 않는다. 실제 앱의 기본 adapter는 계속 Kafka이며 fake를 런타임 대안으로 노출하지 않는다.

적용 여부는 테스트 이름이나 전체 컨텍스트 기동 여부가 아니라 실제 PG CONFIRM·commit 후 발행 도달 여부로 분류한다. PG 성공을 만드는 상품 캐시 무효화 테스트는 대상이고, POINT 과거 주문 fixture·PG 시작만 수행하는 스키마 검증·확정 전 거절 경로만 실행하는 테스트는 대상이 아니다. PG fixture를 공유하는 기존 `PgTestSupport`에 명시적인 override를 둘 수 있으나 브랜드·포인트·상품 전체의 공통 부모까지 전파하지 않는다. 직접 `SpringApplicationBuilder`로 기동하면서 PG 확정까지 수행하는 재시작 검증은 테스트 전용 initializer로 포트를 교체하고, 스키마 준비·validate만 수행하는 컨텍스트에는 이를 요구하지 않는다.

실제 adapter 검증은 fake override를 제외한 별도 컨텍스트에서 실제 포트·전용 template·factory 연결을 직접 확인한다. 전역 mode 선택을 위한 `@RealOrderEventKafkaTest` marker와 `spring.factories`의 guard 등록은 두지 않는다. 필요한 회귀와 발행 검증에서 포트가 의도한 구현 한 개인지 명시적으로 확인한다. 새 PG 확정 회귀에 fake를 빠뜨리는 경우를 모든 컨텍스트에서 자동 차단하는 보장은 포기하며, 새 테스트의 호출 경로 검토와 해당 테스트의 포트 선택 검증으로 관리한다. 서로 다른 bean override 구성은 Spring TestContext의 기본 캐시 구분을 사용하고, reset·실제/fake 컨텍스트 분리를 회귀로 확인한다. 이 검증 기반은 모두 API 테스트 소스·리소스에만 두며 실제 앱·streamer 실행물에 포함하지 않는다.

P2·P3와 기존 회귀는 기록용 포트로 사건 등록·commit 순서·미발행을 확인한다. P4의 실제 wire 관찰, P5의 adapter/설정 검증, P6~P8·C10은 별도 테스트 컨텍스트에서 fake override를 제외하고 실제 adapter·전용 template을 명시적으로 사용한다. P5의 send/Future 오류 단위 검증에 template 대역을 쓰면 broker 실행 근거와 구분한다. 각 컨텍스트는 발행 포트가 의도한 구현 하나인지 확인하고, 실제 broker 검증은 record·metadata도 확인해 fake의 호출 기록으로 대체하지 않는다. P9의 기존 기대값·시간 제한을 Kafka 편의를 위해 완화하지 않는다.

기록용 fake는 `publish(event)` 안에서 동기적으로 호출하는 선택적 관찰 hook을 제공한다. 기본은 기록만 하고, P2는 이 hook에서 관찰 작업을 요청·유한 대기해 포트 호출 시점의 commit을 확인한다. 관찰 결과·오류는 테스트가 직접 확인하며 listener의 오류 로그만으로 성공 처리하지 않는다. 캐시된 컨텍스트를 재사용할 때 기록·hook은 독립 테스트 전 reset하고 진행 중 관찰 작업을 먼저 끝낸다.

P2의 발행 포트 호출 시 commit 관찰은 별도 스레드에서 새 readOnly TX/영속성 컨텍스트를 열어 같은 MySQL을 조회한다. AFTER_COMMIT 호출 스레드의 JdbcTemplate/같은 DataSource만으로 연결 독립성을 가정하지 않는다. 별도 스레드 작업은 제한 시간 안에 끝내고 정리하며, 실제 원 TX와 다른 연결을 사용했음을 확인한다. AFTER_COMMIT 호출자가 연결을 보유한 채 기다릴 수 있으므로 동시 관찰 수를 제한해 기존 test pool(최대 10)에 관찰용 연결 여유를 남긴다. 풀 획득 timeout·관찰 실패를 commit 성공으로 바꾸거나 대기값을 임의로 늘리지 않는다.

실제 adapter로 PG callback 경로를 타는 P6·P8에도 C10의 준비 원칙을 적용한다. 별도 고유 준비용 topic으로 실제 사용할 전용 producer의 초기 metadata·전송 준비를 확인한 뒤 업무 입력을 진행하고, callback HTTP 결과·DB 확정·broker 결과를 나눠 관찰한다. 기존 1초 callback 제한과 회귀 기대값은 유지하며, 준비된 환경의 관찰을 cold start 지연 해결 근거로 삼지 않는다.

### broker와 정상 종료

테스트 broker는 사용자가 로컬 Docker로 실행한 기존 Kafka를 공유한다. Kafka Testcontainers·추가 broker를 생성하지 않고 broker image/version도 변경하지 않는다. 현재 compose의 `bitnamilegacy/kafka:3.5.1`은 선언된 초기 환경 정보이며 실제 실행 server/client 버전은 구현 검증에서 기록한다. broker 연결 실패·topic 준비 실패는 검증 실패로 보고하고 skip·통과·다른 broker로의 자동 우회로 바꾸지 않는다.

공통 연결/자원 helper는 `modules/kafka/src/testFixtures/java/com/loopers/testsupport/LocalKafkaTestSupport`에 둔다. broker를 띄우는 fixture가 아니라 JVM별 AdminClient 수명과 실행별 자원 소유 목록을 관리한다. bootstrap은 명시적인 테스트 입력을 우선하고 기본 호스트 주소는 `localhost:19092`다. `spring.kafka.bootstrap-servers`와 `spring.kafka.admin.properties[bootstrap.servers]`, 직접 생성하는 producer/consumer/AdminClient에 모두 같은 접근 주소를 준다. local/test YAML의 admin `kafka:9092`가 별도로 남지 않도록 실제 유효 map도 검증한다. C10의 자식 streamer JVM에도 동일한 호스트 주소를 실행 인자로 전달한다.

각 시나리오는 실행 식별자가 들어간 `order-events-test-{runId}` topic·`purchase-activity-test-{runId}` group을 생성한다. 배정 실험은 별도 topic을 사용한다. 기본 `order-events`·`purchase-activity` 및 다른 실행 자원은 소비·초기화·삭제하지 않는다. 컨텍스트 refresh/consumer 구독보다 앞선 static 준비·DynamicPropertySource 단계에서 AdminClient로 topic을 명시적으로 만들고 빈 시작 offset·partition 수를 확인한다. 별도 C10 자식도 topic 준비 뒤 기동한다. 전용 consumer와 직접 만드는 모든 테스트 consumer에는 `allow.auto.create.topics=false`를 실제 ConsumerConfig map에 적용한다. client map의 `auto.create.topics.enable=false`는 broker 자동 생성 설정을 바꾸지 못하므로 이를 근거로 삼지 않는다. 이 consumer 설정만으로 producer나 다른 client의 자동 생성까지 막았다고 주장하지 않고 broker 전역 설정도 변경하지 않는다. 각 topic은 partition 2개·복제 계수 1로 준비하고 단일 broker의 min ISR가 `acks=all` 쓰기를 허용하는지 확인한다. 이는 테스트 구성이며 앱의 운영 partition 수·증설 정책은 새로 정하지 않는다.

부모 테스트 실행은 소유 목록에 등록된 topic/group만 정리한다. 먼저 listener·별도 JVM·직접 consumer를 종료하고 group 비활성을 확인한 뒤 group/topic을 삭제하고 유한 대기 동안 삭제 완료·topic 재생성 없음도 확인한 뒤 AdminClient를 닫는다. Spring 컨텍스트가 캐시돼 있어도 구독 listener를 실제 정지시키고, 재사용 전 다음 시나리오 topic을 먼저 준비한다. 부분 준비 실패에도 동일하게 정리하고 오류를 보고한다. 공유 Docker Kafka를 중지하거나 볼륨·기존 topic을 지우지 않는다. 환경은 공유하지만 이름·offset·소비 member는 실행별로 격리하며, DB를 공유하는 시나리오는 소비 문서의 순차 실행/초기화 조건도 따른다.

P4·P6~P8 broker 검증은 커머스 앱을 직접 create/validate로 기동·재기동하므로 기존 테스트 데이터와 섞이지 않도록 기존 MySQL 테스트 컨테이너 안의 임시 스키마 `order_event_kafka`를 사용하고 검증 종료 시 삭제한다(2026-10-29 사용자 승인). 정리는 PG fixture·스키마·Kafka 소유 자원을 각각 시도하고 첫 오류와 추가 오류를 함께 보존한다. 이 허용은 발행 검증에만 해당하며 streamer·C10의 별도 스키마 허용으로 확대하지 않는다. 전용 producer의 IO 스레드 확인은 해당 테스트 컨텍스트에만 부여한 고유 `client.id`로 식별하며 production 기본 client ID는 바꾸지 않는다.

HTTP는 기존 `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=30s`를 명시한다. 전용 singleton·비트랜잭션 producer의 physical close 대기는 15초다. HTTP 진행 요청의 사건 등록·send가 끝난 뒤 factory가 종료되는 실제 lifecycle 순서를 확인한다. Spring Kafka 3.3.4 소스의 factory phase는 `Integer.MIN_VALUE`, stop은 destroy로 연결되지만 실제 bean 의존성과 종료 로그 검증을 대신하지 않는다.

정상 실행의 전달 예산 30초를 종료 때 끝까지 주겠다는 계약이 아니다. close가 시작되면 15초 대기 후 미완료 record가 실패할 수 있으며, 그 결과를 관측 가능한 범위에서 로그로 남긴다. phase 대기는 동기 stop을 강제로 중단하는 전역 deadline이 아니고, phase 30초와 close 15초를 더해 앱 전체 45초 종료 보장으로 쓰지 않는다. 별도 flush·종료 executor·진행 이벤트 영속화는 추가하지 않는다. OS 강제 종료·전원 장애에는 callback 로그도 남지 않을 수 있다.

## 4.6 주문 확정 이벤트 발행

**상태:** 2026-10-29 사용자 승인 발행 상세설계. 아래 코드는 처리 계약을 설명하는 의사코드이며 구현·실행 결과가 아니다. 4.3의 PG 검증·잠금·예약·충돌 보존을 유지하고 최초 확정 분기에만 사건을 등록한다. 클래스는 2.11, 저장·시각은 3.7, wire 계약·로그는 5.8, 대안·한계는 A.34를 따른다.

### 공통 결과 적용과 snapshot 생성

```text
PaymentTransactionService.applyObservation(token, observation): // 별도 Bean의 public TX B
  첫 업무 SQL = Order PK 잠금, 그 뒤 Payment 처음 일반 조회
  기존 연결·금액·고정 품목·상태 검사 + 3.7의 PG 상태별 confirmedAt 검사
  decision = 기존 payment.recordObservation(token, observation, 기존 확인 시각)
  event = null
  switch decision.effect:
    CONFIRM:
      now = ZonedDateTime.now(orderConfirmationClock)
            .withZoneSameInstant(UTC).truncatedTo(MICROS)
      order.confirmPg(payment.amount, now) // 상태·금액·시각을 함께 기록
      event = OrderConfirmed.from(order)   // TX 안 scalar 복사; Entity 보관 없음
      outcome = FINAL
    RELEASE:
      기존 재고 반환·order.failPg(), outcome = FINAL // 사건 없음
    FINAL_UNCHANGED:
      outcome = FINAL // 시각 갱신·사건 등록·미발행 복구 시도 없음
    PENDING / UNAVAILABLE / CONFLICT:
      기존 분기·충돌 요약대로 처리 // 사건 없음
  기존 Order·Payment 저장
  if event != null:
    ApplicationEventPublisher.publishEvent(event) // 아직 Kafka send가 아님
  기존 BEFORE_COMMIT probe // 주입 실패·flush/commit 오류면 전체 rollback
  return 기존 PaymentApplyResult(OrderInfo, outcome)
  // 실제 commit은 프록시가 수행. 반환 직전이나 publishEvent 위치를 commit 뒤로 해석하지 않음

OrderModel.confirmPg(amount, confirmedAt):
  PG·PAYMENT_PENDING·저장 confirmedAt 없음·amount=저장 totalAmount 확인
  입력 시각의 null·UTC·마이크로초 정밀도 확인
  검사 실패: INTERNAL_ERROR, 일부 값 먼저 변경하지 않음
  paidAmount=amount, status=CONFIRMED, this.confirmedAt=confirmedAt

OrderConfirmed.from(order):
  3.7의 주문·시각·저장 품목 전제 확인
  각 원본 항목의 productId·quantity를 합산 전에 양수 검사
  수량 map = productId별 Math.addExact 합산; 합산 결과 양수·overflow 검사
  entries를 productId 오름차순 Item record로 변환·불변 List 복사
  return OrderConfirmed(양수 orderId, 저장 confirmedAt, copiedItems)
```

즉시 생성 SUCCESS·권위 조회 callback·reconcile은 기존 공통 applier 호출을 유지한다. OrderConfirmFacade의 저장 결과 반환·대기·차단 경로, 읽기 API, POINT fixture에서는 내부 사건을 등록하지 않는다. 같은 Order 잠금으로 최초 CONFIRM을 직렬화하되 실제 MySQL 경쟁 검증을 수행해야 한다. 사건 생성·등록 오류는 TX 안의 오류이므로 기존 500·rollback이고, commit 실패 뒤 별도 거래 unique 충돌 기록 TX에서도 사건을 만들지 않는다. snapshot이 이미 등록되었어도 원 TX rollback이면 AFTER_COMMIT에서 실행하지 않는다.

### commit 이후 전송과 실패 관측

```text
OrderConfirmedEventListener.on(event): // AFTER_COMMIT, fallbackExecution=false, @Async 없음
  try:
    publicationProbe.beforeSend(event) // no-op 기본 구현. 실패 주입은 여기서만 send 전 차단
    orderEventPublisher.publish(event)  // 한 번 호출; DB 조회·상태 갱신 없음
  catch RuntimeException failure:
    이 절의 안전 로그(eventId, orderId, 단계 LISTENER_CALL, 원인 타입)
    return // 이미 끝난 commit을 취소하거나 예외를 PG 실패로 바꾸지 않음

KafkaOrderEventPublisher.publish(event): // TX 없음, 전용 KafkaTemplate qualifier
  try:
    payload = OrderConfirmedV1(
      "OrderConfirmed", 1, event.eventId(), event.orderId,
      ISO_INSTANT.format(event.confirmedAt.toInstant()), copiedItems)
    key = Long.toString(event.orderId)
    future = template.send(configuredTopic, key, payload) // 명시 partition 없음
  catch RuntimeException failure:
    이 절의 안전 로그(단계 SEND_CALL) // 변환·직렬화·send 호출 오류 포함
    return
  future.whenComplete((result, failure) ->
    if failure != null:
      이 절의 안전 로그(단계 SEND_COMPLETION, 원인 타입)
    else:
      이 절의 성공 로그(result.recordMetadata의 topic, partition, offset)
  )
  return // get/join/flush·whenCompleteAsync·자체 retry 없음
```

기본 Spring 이벤트 multicaster의 동기 등록 흐름을 유지하고 이 사건을 처리하는 일반 `@EventListener`·`BEFORE_COMMIT` Kafka 발행자를 추가하지 않는다. TX 밖의 publishEvent는 `fallbackExecution=false`로 대상 listener를 실행하지 않으며, 실제 생산 경로는 최초 CONFIRM TX 한 곳으로 제한한다. send 자체의 metadata·buffer 대기는 2.11대로 요청 스레드에 남고 Future의 최종 완료는 기다리지 않는다. 이미 완료된 Future의 callback은 등록 시 바로 실행될 수도 있으므로 무조건 HTTP 반환 뒤 로그가 나온다고 약속하지 않는다.

발행 adapter의 동기 오류는 adapter가 기록하고 정상 반환하며 listener는 probe/포트 외곽의 예기치 않은 RuntimeException만 기록해 같은 오류의 중복 로그를 피한다. callback은 불변 scalar·DTO만 캡처하고 짧은 로그 처리만 수행한다. DB·PG·재발행·대기·새 executor는 호출하지 않는다. JVM의 치명적 Error까지 catch하여 정상 성공처럼 숨기지 않는다. 로그는 실패 시점을 관측하는 수단이며 영속 복구 보장이 아니다.

|실패 시점|주문·예약|발행·응답의 의미|
|---|---|---|
|상태 전이·snapshot·사건 등록·DB flush/commit 전 실패|TX B 전체 rollback, 이전 예약/주문 상태 유지|원 TX의 Kafka send 없음. 기존 기술 오류·거래 unique 처리 유지|
|commit 뒤 probe 또는 send 호출 실패|CONFIRMED·confirmedAt·예약 소비 유지|로그로 별도 발행 실패. 기존 확정 응답, 새 PG create·주문 후퇴 없음|
|Future 실패·timeout|저장 확정 유지|broker 미기록이라고 단정하지 않음. 같은 시도의 callback/reconcile도 자동 재발행하지 않음|
|commit 뒤·send 전 프로세스 종료|저장 확정 유지|미발행 가능. 이번 직접 발행의 남은 공백이며 재시작으로 자동 복구되지 않음|
|정상 종료 close 예산 소진|저장 확정 유지|미완료 전송 실패 가능. 30초 전달 예산을 모두 주는 계약 아님|

### 발행 측 검증 기준

다음은 발행 측 통과 기준이며 실행 결과 자체가 아니다. 설계 작성 단계에서는 미실행이었고 이후 단위별로 검증했다. 실제 결과는 [검증 기록](../../verification/order-events.md)과 구분해 읽는다. 기존 PG 전체 테스트 수치·mock 호출 성공을 실제 Kafka 발행 검증으로 대신하지 않는다.

|ID·계층|사례와 필수 관찰|
|---|---|
|P1 domain·snapshot|최초 확정 시각 설정, 재확정 거절, 동일 ID 유도, 원본 각각의 양수 수량 검사 뒤 중복 합산·overflow 검사, 불변 List·품목. 같은 상품의 -1·3이 합계 2로 은폐되지 않고 실패. 외부 Entity 변경 영향 없음. 경계 동작 중심으로 검증|
|P2 실제 Spring TX·MySQL|즉시 SUCCESS·callback·reconcile 각각 최초 확정 commit 뒤 포트 호출. 포트 호출 시점에 기존 스레드의 TX에 참여하지 않는 별도 스레드의 새 TX·실제 별도 DB 연결로 CONFIRMED·confirmedAt이 보이는지 확인. listener 호출 여부나 isActualTransactionActive 플래그만으로 commit을 입증하지 않음. callback/reconcile 동시 경쟁은 실제 Order 잠금으로 등록 1회·시각 불변. 관찰 hook의 결과/실패도 테스트가 직접 확인. 일반 포트 대역은 broker 저장 근거로 쓰지 않음|
|P3 미발행·rollback|DRAFT·PENDING·UNKNOWN·RELEASE·CONFLICT·FINAL_UNCHANGED·POINT 경로 등록 0. 내부 사건 등록 뒤 BEFORE_COMMIT 실패와 실제 commit 제약 실패에서 send 0·업무 전체 rollback. 테스트 상위 TX의 자동 rollback을 commit 성공으로 해석하지 않음|
|P4 저장·직렬화|실제 DDL의 DATETIME(6)·nullable, 고정 Clock의 나노초 입력을 마이크로초 절삭. commit 뒤 1차 캐시가 아닌 새 컨텍스트/직접 SQL로 DB의 Instant·마이크로초 정밀도 비교. 실제 record의 key, 필수 JSON 필드 전체의 이름·타입·값, 시각 Z 표현·수량·type header 미발행 확인. Java 객체의 값만으로 wire 일치를 판단하지 않음|
|P5 전용 설정·오류|factory 실제 map에서 공통 retries=3 미상속·나머지 2.11 값 확인. transactional.id 혼입 기동 거절과 정상 factory.transactionCapable=false, 기존 공통/전용 bean 주입 관계·전용 qualifier·serializer properties 실제 적용을 확인. send 호출 오류와 Future 실패 각각 확정 유지·안전 로그·PG 추가 생성 0. timeout을 미기록 증거로 삼지 않음|
|P6 실제 정상 발행|실제 주문 API·PG fixture·결과 TX·broker를 거쳐 key·eventId·DB 품목·시각·성공 metadata를 새 조회로 비교. producer가 partition을 고르고 실제 위치를 관측. 소비 DB 완료 증거는 `consumption.md` C10과 결합|
|P7 commit→send 공백|대상 주문만 beforeSend probe에서 실패 주입. 새 TX/SQL로 CONFIRMED·시각·예약 소비 유지, adapter send 0 확인. 격리 topic의 모든 partition별 시작/끝 offset을 고정하고 각 유한 구간을 끝까지 읽어 eventId record 없음 확인. 주문 commit 뒤 장벽으로 주입 위치를 입증. listener 로그만으로 부재를 주장하지 않으며 모든 Kafka 경로·시점의 미발행으로 일반화하지 않음|
|P8 정상 종료|진행 HTTP 요청의 사건 등록·send와 producer factory close의 순서, 대기 예산·관측 가능한 완료/실패 callback·IO thread 정리를 실제 컨텍스트 종료에서 확인. 정상 완료와 close 예산 소진·관측 누락을 구분한다. callback 로그 없음만으로 전송 성공을 판정하거나 모든 record의 로그 확보를 보장하지 않음. 각 phase timeout을 전체 종료 보장으로 오해하지 않음. 지연 주입 시 통제 실패라고 명시|
|P9 회귀·구조|기존 PG·예약·금액·소유권·POINT 사실·삭제 비노출·캐시와 기존 의미 있는 테스트 유지. 기존 회귀의 기본 포트는 기록용 fake이며 실제 adapter 검증은 별도 컨텍스트로 구분. 필요한 PG fixture 시각 입력만 새 계약으로 보강하고 이유 기록. 실제 PG 확정을 실행하는 회귀의 명시적 fake 적용·reset과 실제 adapter 컨텍스트의 포트 선택을 확인. 무관한 테스트에 fake 상속·설정을 전파하지 않으며 전역 guard는 사용하지 않음. 관련 Checkstyle·ArchUnit 포함|

### 다음 주 재현 준비와 검증 범위

실제 PG 결과 적용 경로의 **미발행 1건과 정상 발행·소비 1건**을 재현 코드로 보존한다. 고정 Clock·품목·수량·W6 revision·실행 방법과 각 실행의 실제 `orderId, providerOrderId, transactionKey, eventId, confirmedAt, items`를 기록한다. 주문·예약·broker·활동 결과는 각 경계에서 새 조회로 확인하고 민감정보는 제외한다. W7 재실행에서는 새 사건 ID를 얻어 준비→복구 사이에 고정하며 원래 숫자를 맞춰 원본 행이 보존됐다고 쓰지 않는다. W6 직접 발행 준비 경로를 다시 실행할 수 있게 유지하고 W7 Outbox 결과를 지워 누락을 꾸미지 않는다.

이 과제의 거래 식별자는 기존 로컬 합성 PG/fixture에서 실제 생성한 값이다. 이 재현에 필요한 값을 임의 마스킹하여 원문 필수 근거를 잃지 않으며, 실제 외부 PG 계정·거래나 비밀정보를 사용·공개하는 기능을 추가하지 않는다.

정상 소비 결과·실제 listener 실패/ack·소비 DB commit 뒤 offset 공백·partition 2개와 consumer 1→2→3 배정은 필수로 남는다. 소비 클래스·오류 복구·재전달·종단 검증은 `consumption.md`의 2~7절·C6~C10에 통합했다. P7은 DB→broker 공백, 소비 재전달은 DB→offset 공백이라 서로 대체하지 않는다. 준비·대기에는 deadline을 두고 시작한 consumer·HTTP fixture·별도 JVM·테스트 DB와 소유 topic/group을 정리하며, 공유 Docker broker는 유지하고 임시 DB를 다음 주까지 유지하지 않는다. 선택적 동기/비동기·timeout 비교 실험은 글 주제 선정과 별도 승인 전까지 추가하지 않는다.

### 성공·실패의 공개 의미와 로그

최초 확정 TX commit 뒤 발행 실패는 기존 `PaymentApplyResult`와 HTTP 확정 응답을 바꾸지 않는다. 저장 결과 조회·callback·reconcile은 기존 PG 계약을 따르고 Kafka 복구 명령으로 사용하지 않는다. 신규 HTTP 오류 코드·발행 성공 플래그·확정 시각 응답 필드는 추가하지 않는다. `send`의 반환은 broker 기록도 구매 활동 완료도 아니고, 성공 Future의 metadata는 설정된 acks 조건에서 broker가 전송을 확인한 위치다. 구매 활동 DB commit은 별도 관찰한다.

|로그 사건|필수 필드·의미|
|---|---|
|`ORDER_CONFIRMED_SEND_SUCCESS` (INFO)|eventId, orderId, 실제 topic·partition·offset. broker 전송 확인이며 소비 완료 아님|
|`ORDER_CONFIRMED_SEND_FAILURE` (WARN)|eventId, orderId, configured topic, stage=`LISTENER_CALL` / `SEND_CALL` / `SEND_COMPLETION`, exceptionType와 식별 가능한 causeType. 발행 후속 처리 오류이며 주문 취소 아님|

stage는 발생 구간이며 broker 수신 여부 판정이 아니다. `LISTENER_CALL`은 probe/발행 포트 외곽 오류, `SEND_CALL`은 DTO 생성부터 template.send 반환까지의 오류(네트워크 전송 시작 전 오류 포함), `SEND_COMPLETION`은 Future 완료 결과의 실패를 뜻한다.

실패 로그는 원문 exception message·Throwable stacktrace·DTO.toString·payload·카드 값을 무조건 출력하지 않는다. stage·예외 클래스와 고정된 안전한 설명을 사용한다. 관련 주문을 내부 ID로 찾을 수 있게 하고 오류 타입을 모두 “중복”이나 “확실한 미기록”으로 바꾸지 않는다. 알려진 timeout은 결과 불확실 가능성을 설명하되 전체 예외 분류 체계를 새로 만들지 않는다. Kafka 라이브러리 자체 로그의 안전성도 직렬화 실패 검증에서 확인하며, payload 자체에도 불필요한 개인정보를 넣지 않는다.

로그 관측만 제공하며 별도 발행 상태 테이블·지표·알림·대시보드·재발행 보장은 없다. callback은 로그 외 DB/외부 업무를 수행하지 않는다. 프로세스가 종료되거나 로그 저장소가 실패하면 실패 기록이 없을 수도 있으며, 이를 전송 성공으로 해석하지 않는다. 설정·실제 metadata·새 DB 조회를 함께 사용하는 검증 기준은 4.6이다.

## W7. 변경 경계와 W6 준비→복구 흐름 — 1단계 초안

**상태·근거:** 2026-11-03 사용자가 이 변경 경계와 흐름의 초안 작성 착수를 승인했다. 원문이 정한 필수 계약, 기존 코드에서 확인한 사실, 사용자가 선택한 기본 방향과 후속 상세설계를 구분한다. 아래 흐름은 구현 지시서·실행 결과가 아니다. 선택 근거·대안·비용은 [W7 판단 기록](../../decisions/order-events.md)의 A.36에 둔다.

### W7-1. 유지할 계약과 교체할 책임

이번 변경은 최초 PG 성공의 확정 TX에 전달할 사실을 함께 남기고, 확정 TX 밖의 단일 relay가 이를 전달하는 경계다. 주문 생성·PG 결제 전체를 새로 설계하거나 소비 업무를 주문 TX에 합치지 않는다.

|경계|유지할 계약|W7에서 바꿀 책임|
|---|---|---|
|PG 호출·결과 판정|TX A의 예약·시도, TX 밖 PG HTTP, Order 우선 잠금과 공통 TX B, 동일 거래·금액·종결 보호|최초 CONFIRM의 TX B에 Outbox 직렬화·insert를 포함. callback·reconcile도 같은 결과 적용 경계를 유지|
|확정 사실|`order-confirmed:{orderId}`, 저장된 최초 confirmedAt의 UTC·마이크로초, 불변 품목·안전한 수량 정규화|그 사실의 v1 messageKey·payload를 영속 보존. relay에서 현재 Product를 조회해 재생성하지 않음|
|확정 후 발행|주문과 후속 구매 활동의 업무 분리|W6의 AFTER_COMMIT 직접 send를 해당 생산 경로에서 호출되지 않게 교체. 직접 send와 relay 병행 금지|
|발행 포트·adapter|topic/key/v1 필드·type header 미사용, 단일 broker 한계·안전 로그|현재 void publish 대신 W7.3의 CompletionStage 반환 sender와 String 전용 template으로 저장 key·payload를 그대로 전달. Future 성공/실패를 relay가 판단|
|실패의 원자성|외부 PG SUCCESS는 로컬 rollback과 별개. TX A의 기존 주문·예약 보존|직렬화·Outbox insert를 포함한 TX B 실패 시 최초 성공 반영 전체 rollback. 같은 거래 재조회·적용으로 회복하고 추가 create 없음|
|중복·비성공 결과|같은 거래의 동일 종결은 추가 확정·예약 소비 없음. PENDING·UNKNOWN·FAILED에는 거짓 확정 사건 없음|최초 확정 Outbox도 하나. 과거 누락 복구는 아래 명시 대상 흐름으로 다루며 callback·reconcile을 자동 소급 복구 명령으로 바꾸지 않음|
|소비·HTTP·앱 경계|v1 parser·완료/전체 활동 단일 TX·동일 사실 비교·DB commit 뒤 ack·중단형 오류 처리, 기존 HTTP/PG 계약·소유권·POINT 사실|소비의 중복 허용 계약을 그대로 이용. Outbox 상태를 기존 주문 응답의 새 성공 플래그로 노출하지 않고 API→streamer runtime 의존성도 추가하지 않음|

W7의 새 주문 흐름은 다음과 같다. `PUBLISHED`는 broker 성공 확인을 저장했다는 뜻이고 소비 완료가 아니다.

```text
기존 주문 API·로컬 PG → 검증한 결과
  TX B: 최초 확정·예약 소비·PG 성공 사실 + immutable Outbox PENDING
        함께 commit / 함께 rollback
  TX 밖 단일 relay: 저장한 key·payload 전송 → 실제 broker Future 성공 확인
  별도 DB TX: 해당 Outbox PUBLISHED 저장
  기존 consumer: 완료 기록 + 모든 품목 활동 commit → ack
```

### W7-2. W6 두 사례의 준비와 자원 보존

**선택한 기본 방향:** 기존 W6 테스트 구성을 보존·활용한다. W6 revision 별도 실행물은 기본안이 아니다. 보존 구성이 어려운 구체적인 근거가 생기면 사용자가 판단하며 자동 전환하지 않는다. 과거 동작을 새 가짜 handler로 흉내 내거나 W7 Outbox를 삭제해 준비하지 않는다.

보존 출처는 현재 W6 merge revision `53f912b`의 직접 발행 경로와 [C10 재현 코드](../../../../apps/commerce-api/src/test/java/com/loopers/infrastructure/order/OrderEventEndToEndIntegrationTest.java)·[과거 관찰 기록](../../verification/order-events.md)이다. C10의 고정 Clock·품목 입력·실제 API/PG·broker 관찰·streamer 연결·최종 정리 책임을 활용한다. 당시 주문 번호·거래 키·offset을 이번 기대값으로 복사하지 않는다. W7 변경 후에도 실제 W6 경로를 실행할 테스트 전용 보존 범위·타입 연결·스캔 구성은 W7.4-1에 구체화했다. 현재 코드에 이미 연결됐다는 뜻은 아니다.

검증 실행이 기존 테스트 MySQL·Redis와 로컬 PG fixture를 준비→복구 완료보다 오래 유지한다. Kafka는 기존 공유 로컬 Docker broker와 실행별 고유 topic/group을 사용하며, 소유 client·topic/group의 준비·관찰·정리는 검증 실행이 맡는다. O11은 W7.4의 전용 테스트 worker가 기존 fixture로 자기 빈 MySQL/Redis를 새로 소유한다. 그 실행 안에서 W6/W7용 MySQL을 따로 띄우거나 C10 전용 스키마·새 Kafka 컨테이너를 추가하지 않는다. DB를 함께 쓰는 검증은 기존 직렬 실행 원칙을 유지한다.

|단계|수행 흐름|다음 단계 전 새 조회·실제 관찰|
|---|---|---|
|0. 격리 자원 준비|실행 소유 자원·고유 topic/group, API·PG·실제 consumer 준비. broker 준비와 listener 배정을 유한 deadline으로 확인|준비용 전송과 업무 전송 구분, 빈 업무 입력 상태. 준비 실패·연결 실패는 실패로 보고|
|1. W6 미발행 A|기존 주문 API·PG SUCCESS를 실제 W6 공통 TX에 적용. commit 완료 뒤 send 직전 probe로 대상만 통제 중단|CONFIRMED·고정 confirmedAt·품목·PG SUCCESS·예약 소비 유지. adapter send 없음, 모든 partition의 고정 관찰 구간에 A record 없음, A 완료/활동 0|
|2. W6 소비 완료 B|별도 합성 주문을 실제 확정·직접 발행·기존 consumer로 처리. 기존 C10의 삭제 상품 과거 사실 검증도 보존|B 실제 broker 위치·consumer APPLIED·group offset, 완료 1개·모든 품목 활동과 수량·시각. A의 부재도 B 처리 뒤 고정 구간에서 확인|
|3. 전환 전 기준 고정|준비 요청·callback job이 끝났음을 확인한 뒤 A·B의 이번 실행 사실과 PG·업무·소비 snapshot 기록. W6 직접 발행을 더 실행하지 않게 종료하고 W7 연결 준비|각 `orderId, eventId, confirmedAt, items, providerOrderId, transactionKey`, PG create C·거래 수 T·두 lookup 수·callback 수, 주문/결제·예약/반환·재고·포인트, B 완료/활동 전체 저장값|
|4. 같은 상태에 W7 연결|같은 DB에 Outbox 구조와 W7 복구·단일 relay를 연결. 중간 데이터 cleanup·PG reset·group 변경 없음|A·B의 기존 사실·소비 결과와 PG 거래/집계가 기준과 동일. 준비한 두 주문에 Outbox가 없었음을 확인. 원본 부족·충돌이면 복구를 진행하지 않고 실패|

단계 1·2는 Outbox 도입 전의 실제 W6 준비이며, W7 확정 후 Outbox 삭제·SUCCESS 직접 INSERT·제품용 우회 스위치로 대체하지 않는다. 두 주문은 기존 예약을 소비한 상태이며 복구에서 예약을 다시 차감하지 않는다.

단계 3→4 사이에 `@AfterEach`, `DatabaseCleanUp`, 구매 테이블 DROP/TRUNCATE, PG reset/close, topic/group 삭제·offset reset을 실행하지 않는다. PG 거래·집계가 메모리에 있으므로 API 구성 전환과 PG fixture 종료를 묶지 않는다. 현재 C10은 `@AfterAll`에서 정리하므로, 기존 테스트를 독립 재실행하고 종료한 뒤 W7 검증을 붙이는 것은 이 흐름을 충족하지 않는다.

보존 대상 DB의 W6→W7 전환에 create/create-drop을 실행하지 않는다. 사용자가 허용한 update로 Outbox 구조를 추가하고 기존 streamer JVM·PG fixture를 유지하는 구성은 W7.4-2를 따른다. 기존 행/구조 불변과 실제 Outbox 열·제약을 직접 확인하며 update·validate 설정명만으로 보존·정합성을 입증하지 않는다.

### W7-3. 저장 사실에서 복구하고 수렴 확인

아래는 원문 필수 흐름의 개요 의사코드다. 대상 지정·주문별 TX·gate·명명 unique 충돌 처리·의미 비교는 W7.3-6, 실제 W6 준비 구성과 context 전환은 W7.4가 원본이다.

```text
명시한 A·B 복구: // PG·주문 확정 경로를 호출하지 않음
  각 대상의 저장된 PG 확정 주문·품목·최초 confirmedAt에서 기존 v1 사실 복원
  사실이 부족하거나 저장 연결이 모순되면 오류; 현재 시각·현재 상품으로 보정하지 않음
  해당 eventId Outbox가 없으면:
    기존 eventId·confirmedAt·items와 같은 key·payload의 PENDING 한 행 추가
  이미 있으면:
    원본 사실과 일치하는지 확인; 충돌이면 오류, 기존 행 덮어쓰기 없음
  같은 사건을 단일 relay → 실제 broker → 기존 consumer로 전달
  A: 완료 1개·모든 품목 활동 정상 생성
  B: 재발행 record를 실제 수신해도 기존 완료·활동 전체 저장값 불변
  같은 A·B 복구를 두 번째 실행해 Outbox 행·구매 효과 불변 확인
  전체 기간 PG·업무 효과 증가 0 확인 후 소유 자원 정리
```

복구 직전 PG 기준값을 기록하고 복구 중 create·거래 수·lookupByKey·lookupByOrder·callback 증가가 모두 0인지 비교한다. 두 주문을 각 한 번 시작한 최소 준비라면 C=2·T=2이나 실제 준비 관측값이 기준이다. fixture의 상태 조회를 추가 PG HTTP 호출로 세지 않는다. 주문 재확정·예약 추가 소비/반환·포인트 추가 차감도 0이어야 한다. 과거 POINT 주문 전체를 자동 소급하지 않는다.

|확인 지점|필수 근거|
|---|---|
|W6 출발 상태|A·B 모두 저장 확정·예약 소비·Outbox 없음, A만 broker/구매 활동 없음. B는 실제 최초 소비 완료|
|W7 복구 대상 identity|이번 실행의 기존 사건·시각·품목·거래 연결 유지. 새 UUID·시각 생성·새 결제 없음|
|첫 복구|A·B 각각 같은 eventId Outbox 1행. A 활동 생성, B 재발행의 실제 broker 위치·consumer 수신과 기존 DB snapshot 불변. 품목 2종은 활동 2행·각 수량 유지|
|두 번째 복구|같은 대상 Outbox 행·불변 내용과 구매 효과 불변. PUBLISHED 행의 강제 재발행 여부를 이 단계에서 새 정책으로 정하지 않음|
|전체 부작용|PG 호출/거래 기준 불변, 기존 주문 확정 사실·예약 이력·재고·포인트 불변|

최종 확인 뒤 listener·writer·관련 컨텍스트/실행 흐름을 먼저 정지하고 소유 데이터·PG·Kafka topic/group 등을 정리한다. 준비 중 부분 실패에도 소유 자원 정리를 모두 시도하며 첫 오류와 추가 정리 오류를 보존한다. 공유 broker·개발 DB·다른 실행 자원은 정리하지 않는다. 종료 순서·deadline·실패 출력은 W7.4를 따른다.

### W7-4. 이번 초안의 경계

- 위 W6 연결 검증은 W7 신규 확정의 로컬 원자성·두 relay 중단 구간·실제 재발행 E2E를 대체하지 않는다. 각 필수 검증과 기존 회귀를 후속 검증 설계에 연결한다. W6 partition 배정·성능 실험을 신규 필수 실험으로 반복하지 않는다.
- 1단계 작성 당시 미정이었던 저장/확정 TX는 W7.2, relay 실행/수치·오류/재개·보존·복구 TX/분기는 W7.3, W6 연결·회귀·검증은 W7.4에서 구체화했다. 새 누락/모순은 사용자에게 질문한다.
- 현재 구현·설정·테스트 결과는 W6 상태다. W7 초안은 관련 이벤트/결제/공통 문서에 통합했으나 전체 Claude 검토·사용자 최종 승인·구현/실행 검증은 남아 있다.

## W7.2 Outbox 저장과 최초 확정 TX — 2단계 초안

**상태:** 2026-11-03 사용자 착수 승인 범위다. 원문 필수 계약에 더해 사용자가 JSON 문자열의 텍스트 보존과 due 대상의 nextAttemptAt→id 정렬을 선택했다. 당시 나머지 타입·책임 배치는 상세 초안이었고, 이후 보완을 포함한 전체 설계는 2026-11-04 사용자 최종 승인을 받았다. 코드 변경·실행 검증은 미실행이다. 선택 근거는 [A.37](../../decisions/order-events.md)이다.

### W7.2-1. 원문이 정한 데이터 책임

|구분|필드·역할|불변·수명 계약|
|---|---|---|
|사건 identity|eventId·orderId|기존 `order-confirmed:{orderId}`와 내부 양수 주문 ID. eventId DB 유일 제약. relay가 새 사건 ID를 만들지 않음|
|해석·전송 사실|eventType·schemaVersion·messageKey·payload|OrderConfirmed·1·양수 orderId의 정규 10진 문자열 key와 기존 v1 본문. 최초 기록 뒤 변경 없음|
|본문 내용|payload 안 confirmedAt·items|저장한 최초 UTC·마이크로초 확정 순간과 정규화한 상품별 양수 수량. 현재 상품 조회·가격·사용자/카드/PG 비밀 추가 없음|
|전달 상태|status·publishedAt|최초 PENDING·발행 확인 시각 없음. broker 확인 뒤 별도 TX에서 PUBLISHED·확인 저장 시각 기록. 소비 완료 표지가 아님|
|시도·대기·오류|attemptCount·nextAttemptAt·lastError|실패 관찰·재시도 제어. 실제 broker 수신 횟수로 해석하지 않음. 갱신 시점·오류 분류·대기 계산은 relay 계약과 함께 상세화|
|기록 시각|createdAt|Outbox 기록 시각이며 사건의 최초 confirmedAt·broker 확인 저장 시각과 구분|

품목별 업무 열을 Outbox에서 다시 조합하는 모델을 새로 도입하지 않고 발행 본문 payload를 보존한다. 위 메타데이터가 payload에도 존재하는 것은 조회·전달 관리와 wire 계약의 역할 차이이며, 서로 다른 사실을 허용하는 뜻이 아니다. 내용 생성 시 같은 사건에서 일관되게 복사해야 한다. 저장 타입·열 정의·인덱스는 W7.2-4를 따른다.

### W7.2-2. 최초 확정의 처리 순서·실패 경계

다음은 payload 저장 타입에 의존하지 않는 처리 계약이다. 현재 `PaymentTransactionService`의 공통 TX B와 기본 REQUIRED·Order 우선 잠금·기존 관찰 판정·반환 모델을 유지한다. 별도 Outbox REQUIRES_NEW TX나 AFTER_COMMIT insert로 나누지 않는다.

```text
applyObservation(token, observation): // Facade가 별도 bean 프록시로 호출하는 public TX B
  첫 업무 SQL로 기존 Order PK 잠금
  기존 Payment 첫 일반 조회·token 연결·주문/결제/고정 품목 정합성 확인
  decision = 기존 payment.recordObservation(token, observation, 기존 확인 시각)
  최초 사건 = 없음
  switch decision.effect:
    CONFIRM:
      기존 orderConfirmationClock으로 UTC·마이크로초 확정 순간을 한 번 생성
      order.confirmPg(amount, 그 시각)
      최초 사건 = OrderConfirmed.from(order) // 같은 TX 안에서 저장 품목 scalar 복사
      outcome = FINAL
    RELEASE:
      기존 예약 반환·이력·캐시 무효화 등록·order.failPg 처리; outcome = FINAL
    FINAL_UNCHANGED:
      기존 종결 결과 유지; outcome = FINAL // 새 confirmedAt·사건·과거 누락 복구 없음
    PENDING / UNAVAILABLE / CONFLICT:
      기존 분기와 충돌 보존·확인 정보 처리 유지; 새 확정 사건 없음
  기존 Order·Payment 저장
  if 최초 사건이 있으면:
    recorder.record(최초 사건) // 제품은 같은 TX에서 v1 생성·Outbox PENDING insert
    // 생성·직렬화·저장 오류는 정상 반환으로 삼키지 않고 TX 실패로 전파
    // 기존 ApplicationEventPublisher 등록→AFTER_COMMIT 직접 발행을 이 경로에서 대체
  기존 BEFORE_COMMIT probe
  기존 PaymentApplyResult(OrderInfo, outcome) 반환
  프록시 commit 성공 뒤 Facade가 기존 HTTP 결과로 변환
```

즉시 생성 SUCCESS·callback·reconcile은 모두 이 공통 경계를 사용한다. 최초 CONFIRM에서만 Outbox를 생성하며 `recordConstraintConflict`의 별도 충돌 기록 TX에서는 생성하지 않는다. Outbox 유일 제약 실패를 기존 PG 거래 유일 제약 실패로 오분류하지 않으며, 실패한 TX 안에서 정상 중복으로 삼켜 진행하지 않는다.

Outbox 생성/직렬화·insert·flush·commit 전 확정 실패에는 TX B의 주문 CONFIRMED·confirmedAt·PG 성공 사실 적용과 Outbox가 함께 rollback한다. TX A에서 이미 commit한 PAYMENT_PENDING 주문·결제의 직전 상태·예약은 남는다. 외부 PG SUCCESS가 취소되는 것이 아니므로 기존 같은 거래 조회·적용으로 회복하고 추가 create는 하지 않는다. commit 응답 중 연결 오류처럼 DB 결과가 불명확한 상황은 무조건 rollback 완료라고 주장하지 않는다.

### W7.2-3. 이 처리 계약의 검증 기준 — 미실행

- 실제 MySQL·Spring 프록시 TX에서 최초 CONFIRM의 주문/결제·시각·Outbox를 새 조회로 함께 확인한다. 테스트 자동 rollback을 commit 근거로 쓰지 않는다.
- v1 생성/직렬화 실패, 실제 Outbox insert 뒤 실패와 BEFORE_COMMIT·실제 commit 제약 실패를 통제하고 기존 주문/결제·예약의 직전 상태·새 Outbox 0을 확인한다. 외부 PG SUCCESS와 로컬 실패를 별도로 관찰한다.
- 같은 거래 callback·reconcile 반복/경쟁은 기존 Order 잠금 아래 최초 확정·Outbox 한 번·시각 불변이다. 같은 거래 재적용의 포트/DB 관찰과 실제 broker 검증은 구분한다.
- DRAFT·PENDING·UNKNOWN·확정 FAILED·UNAVAILABLE·CONFLICT·FINAL_UNCHANGED·POINT에서 새 확정 Outbox 0을 확인한다. 기존 PG 처리·소유권·POINT·예약 회귀의 유효한 기대값을 유지한다.
- relay를 실행하지 않은 실제 DB 원자성 검증부터 수행하고 broker/consumer 연결은 후속 전달 검증에서 수행한다. Java Checkstyle·ArchUnit도 구현 단위에 연결하며 이번 문서 작업에서 통과했다고 쓰지 않는다.

### W7.2-4. 저장 모델·매핑·인덱스

`domain/order/OrderConfirmedOutboxModel`을 기존 Domain/JPA 통합 Entity 원칙으로 두고 `BaseEntity`의 id·created_at·updated_at·deleted_at을 사용한다. `@Table(name = "order_confirmed_outbox")`를 명시한다. DatabaseCleanUp이 각 Entity의 Table.name을 읽으므로 생략하지 않는다. 테이블은 `order_confirmed_outbox`이며 이 주문 확정 사건의 전달을 담당한다. 범용 이벤트 플랫폼·새 결제 상태·주문 행의 발행 상태 컬럼·물리 FK를 추가하지 않는다. deleted_at은 항상 null인 보존 대상이며 삭제/수정 API를 제공하지 않는다. BaseEntity·공통 JPA의 감사 시각 생성은 바꾸지 않는다.

|필드|Java·SQL 목표·null|생성 값·변경 계약|
|---|---|---|
|eventId|String / VARCHAR(64) ASCII ascii_bin, NOT NULL|`order-confirmed:{orderId}`. 생성 뒤 불변|
|orderId|Long / BIGINT NOT NULL|양수 내부 주문 ID, 불변|
|eventType|String / VARCHAR(32) ASCII ascii_bin, NOT NULL|OrderConfirmed, 불변|
|schemaVersion|Integer / INT NOT NULL|1, 불변|
|messageKey|String / VARCHAR(20) ASCII ascii_bin, NOT NULL|`Long.toString(orderId)`, 불변|
|payload|String / LONGTEXT utf8mb4 utf8mb4_bin, NOT NULL|아래 encoder가 만든 JSON 원문, 불변. JSON 열이 아니며 품목별 별도 Outbox 열 없음|
|status|OrderOutboxStatus / VARCHAR(16) NOT NULL|최초 PENDING. W7.3 사용자 선택으로 PENDING/PUBLISHED/BLOCKED 사용|
|attemptCount|long / BIGINT NOT NULL|최초 0. 이후 relay가 시도 정보를 갱신. broker 수신 수 아님|
|cycleAttemptCount|int / INT NOT NULL|최초 0. 수동 재개 이후 자동 시도 예산을 별도로 셈. 재개 시 이 값만 0으로 초기화|
|nextAttemptAt|ZonedDateTime / DATETIME(6) NOT NULL|최초 기록 시 준비한 UTC·마이크로초 시각. 이후 재시도 계산은 relay 상세설계|
|lastError|String / TEXT nullable|최초 null. 안전한 오류 정보만 저장하며 원문 예외·payload·카드·비밀은 복사하지 않음. 분류·문안은 relay 상세설계|
|publishedAt|ZonedDateTime / DATETIME(6) nullable|최초 null. broker 확인 뒤 상태 저장 때 기록할 시각이며 confirmedAt을 대신하지 않음|

불변 열은 `@Column(updatable=false)`와 변경 메서드 미제공으로 ORM 쓰기를 제한한다. 기존 orders.confirmed_at과 달리 Outbox는 최초 insert 때 사실을 모두 갖기 때문에 이 매핑을 사용할 수 있다. SQL 직접 수정까지 차단하는 DB 불변성이라고 주장하지 않는다. 문자열의 길이·ASCII binary collation·LONGTEXT·DATETIME(6)은 columnDefinition 등 실제 매핑으로 명시하고 DDL을 직접 확인한다. status는 기존 `EnumNameConverter`를 확장한 이름 문자열 변환기로 저장해 native ENUM·암묵적인 enum 값 CHECK를 만들지 않는다.

- `uk_oco_event`: UNIQUE(event_id). Order 잠금이 정상 경쟁을 직렬화하고 이 제약이 동일 사건 중복 insert의 최종 방어다. orderId에서 사건 ID를 유도하고 모델에서 검사하므로 별도 UNIQUE(order_id)는 추가하지 않는다.
- `ck_oco_order_id`: CHECK(order_id > 0), `ck_oco_attempt_count`: CHECK(attempt_count >= 0), `ck_oco_cycle_attempt_count`: CHECK(cycle_attempt_count >= 0). 고정 타입/version·key/ID 관계와 상태/시각 결합은 모델·생성 경계가 검사하며 문자열 값 CHECK는 추가하지 않는다.
- `idx_oco_due`: INDEX(status, next_attempt_at, id). 조회는 `status=PENDING AND next_attempt_at<=:now ORDER BY next_attempt_at ASC, id ASC LIMIT :limit`다. 사용자 선택인 다음 시도 시각→ID 순서를 사용하며 limit은 양수·유한 값이다. 초기 limit은 W7.3의 batchSize=10이다.

이 인덱스는 조회 조건·정렬에 맞춘 설계이며 실제 MySQL 실행계획·선택도·처리량 검증 결과가 아니다. ID는 정렬 tie-breaker일 뿐 commit 순서나 미래 여러 업무 이벤트의 발행 순서 보장이 아니다. payload에는 업무 검색 인덱스를 두지 않는다. LONGTEXT는 TEXT의 작은 고정 길이를 새 메시지 크기 제한으로 끼워 넣지 않기 위한 선택이며 Kafka의 수신 크기 제한을 늘리는 설정이나 무제한 전달 보장이 아니다. 큰 메시지·전송 영구 오류는 relay의 미해결 오류 관리 대상이다.

### W7.2-5. 클래스·메서드와 의존 경계

다음 신규 타입은 commerce-api에만 두며 기존 application→infrastructure 금지·domain 포트 원칙을 유지한다. 클래스명은 원문 요구가 아니라 이 상세 초안의 배치다.

|타입·위치|메서드 계약·책임|
|---|---|
|domain/order/OrderConfirmed|기존 `from(order)`·eventId 유도·시각·정규화한 불변 품목 계약 유지. payload·Kafka 설정을 이 사건 record에 추가하지 않음|
|domain/order/OrderOutboxMessage|불변 record `(eventId, orderId, eventType, schemaVersion, messageKey, payload)`. `from` 같은 Entity 재조회 기능 없음. 생성 시 양수 ID·유도 ID·정규 key·고정 타입/version·비어 있지 않은 payload 검사. JSON 라이브러리에 의존하지 않음|
|domain/order/OrderConfirmedMessageEncoder|`OrderOutboxMessage encode(OrderConfirmed event)`. 같은 TX 안 snapshot을 기존 v1 JSON 본문으로 변환하는 포트. 외부 I/O·DB 저장·Kafka send 없음. 변환/직렬화 실패를 안전한 INTERNAL_ERROR로 전파|
|infrastructure/order/JacksonOrderConfirmedMessageEncoder|기존 ObjectMapper와 v1 wire DTO로 직렬화. 내부 nested OrderConfirmedV1·Item을 adapter 소유로 두며 기존 발행 adapter DTO의 wire 필드·표현을 보존. 다른 계층에 Jackson DTO·Kafka 타입 노출 없음|
|domain/order/OrderConfirmedOutboxModel|`pending(OrderOutboxMessage message, ZonedDateTime firstAttemptAt)`으로 불변 사실·PENDING·두 시도 횟수 0·firstAttemptAt·null 오류/발행 시각 구성. 부분 초기화·공개 setter 없음. relay의 상태 행동은 W7.3을 따름|
|domain/order/OrderConfirmedOutboxRepository|insertAndFlush(model)은 현재 TX에 저장/flush. findByEventId(eventId)의 Entity는 호출 TX 안에서 사용. `List<Long>` findPendingDueIds(now, limit)는 due 정렬/양수 limit으로 ID만 조회. findDueIds TX 서비스가 이 포트를 호출하므로 Entity 반환 due 조회를 별도로 중복 제공하지 않음|
|infrastructure/order/OrderConfirmedOutboxRepositoryImpl·OrderConfirmedOutboxJpaRepository|domain 포트 구현·JPA 저장/조회. insert는 Spring Data saveAndFlush 경계 사용. 구현의 `insertAndFlush`는 MANDATORY로 호출자 TX를 요구해 단독 commit을 허용하지 않음. EntityManager·Pageable·SQL·JPA repository를 domain/application에 노출하지 않음|
|application/order/OrderConfirmedRecorder|`void record(OrderConfirmed event)`라는 application의 확정 사실 기록 역할. 호출자 TX가 필수. 제품 기본 구현은 아래 Outbox 기록 하나이며 전송 포트·Domain Service가 아님|
|application/order/OrderOutboxRecordingService|recorder의 유일한 제품 구현. public `record(event)` MANDATORY에서 encoder→pending 모델→repository insertAndFlush. clock은 인자로 domain에 전달. 예외를 삼키지 않고 같은 TX 실패로 전파|
|application/payment/PaymentTransactionService|recorder를 주입해 기존 최초 CONFIRM snapshot을 같은 TX에서 기록. W7 생산 경로의 ApplicationEventPublisher 등록을 대체. PG 판정·잠금·예약·반환·충돌 분기 유지|
|config의 outboxRecordClock bean|`Clock.systemUTC()`. Outbox 기록·시도·결과 처리 시각에 사용하며 기존 orderConfirmationClock·BaseEntity 감사 시각을 바꾸지 않음. 테스트는 이 Clock을 별도로 고정 가능|

`pending`은 message가 위 전제를 만족하고 firstAttemptAt이 null이 아닌 UTC·마이크로초 값인지 먼저 검사한 뒤 모든 필드를 구성한다. 생성 뒤 eventId/orderId/type/version/key/payload는 변경하지 않는다. 신규 PENDING은 publishedAt·lastError가 null이고 두 시도 횟수가 0이다. 나중에 읽은 행의 상태 정합성·실제 payload 검사·오류 재개/보존·시도 카운터 갱신 시점은 W7.3을 따른다.

### W7.2-6. JSON 생성·저장 의사코드

생성 입력은 최초 CONFIRM TX에서 `OrderConfirmed.from(order)`로 만든 snapshot이다. 복구도 별도 대상의 저장 사실을 검증해 같은 사건 값을 준비하는 흐름이며 현재 Product·PG를 조회해 본문을 보정하지 않는다. JSON 형식의 유효성·wire 내용은 아래 encoder가 책임지고, TEXT 열 자체가 JSON 유효성을 보장한다고 하지 않는다.

```text
JacksonOrderConfirmedMessageEncoder.encode(event): // 호출 중 TX를 새로 열거나 종료하지 않음
  event의 양수 orderId·유도 eventId·UTC/마이크로초 confirmedAt·필수 품목 전제 확인
  key = Long.toString(event.orderId)
  dto = 기존 v1의 정확한 필드만 구성:
        eventType=OrderConfirmed, schemaVersion=1, eventId, orderId,
        confirmedAt=ISO_INSTANT.format(저장 순간), items=[productId, quantity]
  JSON = ObjectMapper.writeValueAsString(dto) // 주문 TX 안에서 한 번
  변환/직렬화 실패면 안전한 INTERNAL_ERROR; 원문 JSON·원문 예외를 로그/오류로 노출하지 않음
  return 불변 OrderOutboxMessage(eventId, orderId, OrderConfirmed, 1, key, JSON)

PaymentTransactionService의 최초 사건 저장 부분: // W7.2-2의 같은 TX B
  recorder.record(최초 사건) // 별도 bean 프록시; 제품 기본 구현은 아래 하나

OrderOutboxRecordingService.record(최초 사건): // MANDATORY, 새 TX 아님
  message = encoder.encode(최초 사건)
  firstAttemptAt = ZonedDateTime.now(outboxRecordClock).UTC정규화.MICROS절삭
  pending = OrderConfirmedOutboxModel.pending(message, firstAttemptAt)
  repository.insertAndFlush(pending) // Order·Payment의 변경도 같은 PC/TX의 flush 대상
  정상 반환 // void record; 아직 기존 TX B는 진행 중

PaymentTransactionService 복귀:
  기존 BEFORE_COMMIT probe → 기존 결과 반환 → 프록시 commit
```

#### W7.2-6a. flush와 기존 결제 제약 오류의 경계

flush는 Outbox 하나가 아니라 같은 PC의 Order·Payment 변경도 SQL로 반영할 수 있다. 기존 PaymentTransactionService의 “서비스에서 직접 flush하지 않는다” 주석을 TX 전체의 모든 flush 금지로 확대하지 않는다. 제품 구현은 이미 명시한 Spring Data repository 프록시의 saveAndFlush를 통해서만 이 경계를 수행한다. application이나 adapter가 EntityManager.flush를 직접 호출해 예외 변환을 우회하지 않는다.

기존 uk_payment_transaction 위반은 commit뿐 아니라 이 Outbox 저장 도중에도 드러날 수 있다. 변환된 DataIntegrityViolationException은 같은 TX 전체 rollback 뒤 기존 PaymentObservationApplier가 그 제약 이름일 때만 별도 충돌 기록 TX로 처리한다. Outbox unique/다른 DB 오류를 결제 충돌로 삼키지 않는다. Spring Data JPA/Commons 3.4.4의 SimpleJpaRepository.saveAndFlush/flush와 repository 예외 변환 interceptor를 소스로 확인했지만 실제 예외 사슬은 실행 검증이 필요하다.

SUCCESS 거래 키 중복 회귀는 기존 OrderConfirmedAtIntegrationTest의 commitConstraintFailureLeavesNoConfirmedAt와 OrderConfirmedPublicationIntegrationTest의 commitConstraintFailureSkipsPort에도 존재한다. F2 전환 때 이 기대를 보존하고, 충돌한 두 번째 주문의 PAYMENT_PENDING·confirmedAt 없음·직전 결제/예약·Outbox 0과 기존 첫 주문 사실 보존을 확인한다. 실제 applier를 통한 충돌 요약/오류 경로도 연결한다. PENDING 중복 거래 키 회귀 역시 유지한다. 새 회귀가 없다는 전제로 기존 검증을 중복 작성하지 않는다.

BaseEntity의 IDENTITY 전략에서는 save 자체가 insert SQL을 앞당길 수도 있으므로 “flush 없으면 실제 insert가 없다”고 단정하지 않는다. insert 뒤 실패/flush 실패/commit 응답 불명을 실제 SQL·예외·새 조회로 구분한다. flush 성공은 commit 성공이 아니며 테스트 hook의 명칭/기대도 실제 SQL 위치와 맞춘다. 기존 코드/테스트의 commit 시점 표현과 주석을 구현 시 이 경계에 맞춰 갱신한다.

한 번 직렬화해 저장한 문자열을 relay가 그대로 UTF-8 전송해야 한다. 현재 `JsonSerializer<Object>`에 payload String을 그대로 넘기면 JSON 문자열을 다시 인코딩하는 경로가 되므로, W7 전용 발행 경계에는 저장된 문자열을 재직렬화하지 않는 serializer 구성이 필요하다. 공통 Kafka 설정을 바꾸거나 W6 보존 adapter의 wire를 함께 바꾸지 않는다. 구체 전송 포트·template/factory·대기·callback/오류 계약은 W7.3을 따른다.

동일 사실 여부는 JSON 속성 순서·소수초 후행 0 같은 표현 차이와 구분하는 기존 v1 계약을 유지한다. 이번 선택이 기존 Outbox 복구의 충돌 비교를 raw 문자열 동등성만으로 정했다는 뜻은 아니다. 복구의 검증/비교 메서드는 W7.3-6을 따른다.

### W7.2-7. 추가 검증·후속 경계 — 미실행

- 실제 DDL의 길이·nullable·collation·LONGTEXT·DATETIME(6)·VARCHAR 상태·명명 unique/CHECK/인덱스를 information_schema/SHOW CREATE TABLE로 확인한다. validate만으로 대체하지 않는다. 같은 eventId DB insert는 실패해야 하며 임의 upsert·INSERT IGNORE로 기존 내용 충돌을 숨기지 않는다.
- 저장한 key·JSON을 새 PC/SQL로 읽어 v1 필드·값·타입·confirmedAt Z 표현·품목/수량과 대조한다. 이후 전달 검증에서 broker value가 JSON object이고 인용/이스케이프된 JSON String이 아닌지도 확인한다. Java DTO만 비교해 wire 검증 완료로 삼지 않는다.
- 별도 고정 outboxRecordClock의 나노초 입력이 마이크로초로 절삭되는지 확인하고 사건 confirmedAt과 생성/시도/발행 시각을 혼동하지 않는다. 공통 BaseEntity 감사 시각 동작은 변경하지 않는다.
- 실제 due 조회는 now 경계 포함·미래 대상 제외·PUBLISHED 제외·시각 동률의 ID 정렬·limit·실행계획을 확인한다. 이 순서가 주는 업무상의 순서 보장으로 확대하지 않는다.
- W6의 기록용 publisher override·AFTER_COMMIT 기대는 W7 생산 경로에 맞춰 재구성하되 commit/rollback·미발행·PG·POINT·예약 보호라는 유효한 검증 목적을 유지한다. 실제 Outbox DB 원자성을 fake 저장 성공으로 대신하지 않는다. 구체 테스트 교체와 W6 보존 구성의 연결은 후속 검증 설계에서 전체 조립과 함께 확인한다.
- 모델·생성 포트는 확정 사실을 불변으로 보존한다. 2단계 때 미정이었던 relay 상태 전이·실행 위치·수치·복구 TX/비교·보존은 W7.3에서 구체화한다. 테스트 조립/전환과 전체 검증 연결은 W7.4에 구체화했으며 실제 실행 검증은 남아 있다.

## W7.3 Relay·기존 사건 복구 — 3단계 초안

**상태:** 사용자가 2단계 요약을 검토하고 3단계 문서 작업 착수를 허락했다. 이후 내부 전용 스케줄러, 오류 구분·시도 한도·BLOCKED, 아래 초기값, 재개 시 새 시도 예산, 주문별 복구 TX를 선택했다. 이 선택과 기존 계약에 따른 상세설계는 이후 보완을 포함해 2026-11-04 사용자 전체 최종 승인을 받았다. 코드·설정 변경·실행 검증은 미실행이다. 선택 근거·대안·비용은 [A.38](../../decisions/order-events.md)에 둔다.

### W7.3-1. 실행·초기값·단일 실행 경계

단일 polling relay를 commerce-api 내부 전용 스케줄러로 실행한다. 별도 relay 서버·앱은 추가하지 않는다. namespace는 commerce.order-events.relay이며 enabled는 자동 스케줄러 기동을 제어한다. 명시 수동 호출도 같은 프로세스 실행 경계를 사용하고, 다른 프로세스의 relay/수동 도구와 병행하지 않는다. 프로세스 내부 lock을 분산 소유권 보장으로 해석하지 않으며 claim/lease는 추가하지 않는다.

|설정|사용자 선택한 초기값·의미|
|---|---|
|enabled|false. 로컬 API 한 인스턴스에서만 명시적으로 true|
|fixedDelay|1초. 한 회차가 끝난 뒤 다음 회차까지 대기|
|batchSize|10. due 행을 최대 10개 골라 직렬 처리|
|sendWaitTimeout|35초. send가 반환한 Future의 로컬 완료 대기|
|initialRetryDelay / maxRetryDelay|5초 / 60초. 5→10→20→40→60초 이후 상한 유지|
|maxCycleAttempts|10. 최초 시도 포함, 수동 재개 이후의 자동 시도 예산|

값은 실측 적정값/SLA가 아니다. batch/timeout/재시도 값은 양수, maxRetryDelay≥initialRetryDelay, maxCycleAttempts≥1이어야 하며 잘못된 설정은 기동 오류다. 35초에는 send의 metadata/buffer 대기·직렬화·DB 시간이 포함되지 않는다. 한 회차의 장애 대기가 수분까지 길어질 수 있는 비용을 사용자가 확인했다. 테스트는 별도의 짧은 유한 값·Clock·장벽을 사용한다.

자동 회차와 로컬 수동 run/resume/recover는 같은 OrderOutboxExecutionGate의 tryLock을 사용한다. 실행 중이면 새 흐름은 DB 변경 없이 BUSY를 반환하고 scheduler는 다음 회차로 넘긴다. 한 흐름은 전체 회차 또는 명시 복구 목록 처리가 끝날 때까지 gate를 보유한다. @Async·무제한 Future·tick 병렬화는 없다. 정상 주문의 Outbox insert는 이 gate에 참여하지 않으며 기존 Order 잠금과 DB TX로 보호한다.

### W7.3-2. 클래스·메서드 계약

다음 타입은 commerce-api 안에 둔다. TX 없는 relay/운영 객체가 별도 TX bean 프록시를 호출한다. domain은 Kafka·JSON 라이브러리·application 타입을 참조하지 않는다.

|위치·타입|주요 계약|
|---|---|
|interfaces/scheduler/OrderOutboxRelayScheduler|전용 단일 스레드 scheduler·fixedDelay로 relay.runDueBatch 호출. enabled와 lifecycle 정지 제어. application relay에만 의존|
|application/order/OrderOutboxExecutionGate|단일 실행·STOPPING·현재 thread/종료 통지 관리. isStopping()은 종료 관측용이며 상태를 바꾸지 않음. tryExecute의 불변 `Result<T>`는 EXECUTED(작업값 선택) 또는 BUSY/STOPPING·값 없음. 예외는 전파하고 finally 해제. 분산 lock 아님|
|application/order/OrderOutboxRelay|TX 없는 runDueBatch(), runEvent(eventId). gate 안에서 due ID 선택→행별 시작→검증→send 대기→상태 TX. 상위 TX가 있으면 거절|
|application/order/OrderOutboxRelayTransactionService|public readOnly findDueIds(now, limit), public TX beginAttempt(id, now), markPublished(id, attemptNo, now), recordFailure(id, attemptNo, code, now), blockInvalid(id, attemptNo, code). 별도 bean 프록시 경계|
|domain/order/OrderOutboxStoredMessage|불변 raw scalar record: eventId/orderId/type/version/key/payload. Entity 없음. 저장된 본문/메타데이터의 손상을 다음 verifier가 검사할 수 있도록 해석·보정 없이 복사|
|domain/order/OrderOutboxAttempt|불변 snapshot: outboxId·누적 attemptNo·cycleAttemptCount·OrderOutboxStoredMessage. Entity·Future 없음|
|domain/order/OrderOutboxMessageVerifier|OrderConfirmed parseAndValidate(OrderOutboxStoredMessage), void requireSameFact(OrderConfirmed expected, OrderOutboxStoredMessage stored). 메타데이터/JSON 내용 일치·v1 형식/의미 검사 포트. 입력 형식·사실 오류를 기술 DB 오류와 구분|
|domain/order/OrderOutboxMessageException|안전 code만 가진 명확한 형식/버전/사실 오류. verifier가 알려진 파싱·검증 실패를 이 타입으로 변환하며 원문 payload/예외 메시지를 포함하지 않음. 예상 밖 프로그램 오류는 이 타입으로 뭉개지 않음|
|infrastructure/order/JacksonOrderOutboxMessageVerifier|전용 엄격 JSON 파싱·정규화. 공통 ObjectMapper 설정을 바꾸지 않음. API→streamer 코드 의존 없음|
|domain/order/OrderOutboxSender|CompletionStage<OrderOutboxReceipt> send(attempt). 저장 key·payload 전송. 동기 send 오류·비동기 실패를 호출자에게 전파하며 삼키지 않음|
|domain/order/OrderOutboxReceipt|불변 topic·partition·offset scalar. broker 확인 위치이며 소비 완료 아님|
|infrastructure/order/KafkaOrderOutboxSender|W7 전용 String/String template 호출·완료 결과 변환. callback에서 DB 상태/PG/재시도 호출 없음|
|application/order/OrderOutboxRetryPolicy|초기/상한 지연·회차 시도 한도의 순수 계산. Clock·DB·Kafka 없음|
|application/order/OrderOutboxOperations|TX 없는 runEvent는 relay gate에 위임. resumeBlocked·recoverOrders는 gate 한 번 획득. recoverOrders 반환/RecoveryReport·RecoveryItem·RecoveryFailedException은 W7.3-6 계약. 공개 HTTP 없음|
|application/order/OrderOutboxRecoveryTransactionService|public TX RecoveryItem recoverOne(orderId), resumeBlocked(eventId, now). unique 오류 뒤 새 TX 재확인 메서드는 제공하지 않음|
|domain/payment/PgOrderConsistency|순수 requireConsistent(order, payment, providerAccount). 현재 PaymentTransactionService의 저장 연결·품목/금액·PG 상태/시각 결합 검사만 그대로 추출해 최초 확정/복구에서 재사용. Repository·Clock·HTTP·상태 변경 없음|
|application/order/OrderOutboxRelayProbe|기본 no-op afterBrokerConfirmed(attempt, receipt). 실제 Future 성공 뒤·PUBLISHED TX 전 통제 중단용. 테스트만 주입|
|config/OrderOutboxRelayProperties|위 namespace의 enabled·fixedDelay·batchSize·sendWaitTimeout·initial/maxRetryDelay·maxCycleAttempts 바인딩/범위 검사|
|config/OrderOutboxRelayConfig|전용 단일 스레드 ThreadPoolTaskScheduler·Clock·policy·gate와 lifecycle 조립. 공통 scheduler를 일괄 변경하지 않음|
|config/OrderOutboxKafkaConfig|기존 주문 producer 설정 계약을 유지하는 W7 String/String factory/template 조립·설정 오류 거절·qualifier 연결|

repository에는 findForUpdateById(id), findForUpdateByEventId(eventId), saveAndFlush(model)를 추가한다. 반환 Entity는 호출 TX 안에서만 사용하고 Outbox 전달 상태 TX는 Outbox 행만 잠근다. 복구 TX는 아래 Order→Payment 일반 조회→Outbox 순서를 사용한다. relay가 Order를 뒤늦게 잠그는 경로는 없으므로 복구와 역순 잠금을 만들지 않는다.

### W7.3-3. 상태·카운터·오류 정책

PENDING만 자동 조회한다. PUBLISHED는 broker 성공 확인 저장이며 BLOCKED는 자동 시도 중단·미해결이다. BLOCKED는 별도 Kafka topic/DLQ 전송이 아니다. PENDING/BLOCKED의 publishedAt은 null, PUBLISHED의 publishedAt은 UTC·마이크로초 필수이고 두 시도 횟수 모두 1 이상이다. BLOCKED도 시도 시작 뒤 전환하므로 두 횟수가 1 이상이어야 한다. 두 시도 횟수는 음수 아님·cycleAttemptCount≤attemptCount이고 가산은 Math.addExact다. 해석할 수 없는 저장 상태/카운터는 정상 중복이나 성공으로 변환하지 않고 회차를 멈춰 운영 오류로 드러낸다.

|결과|동일 행 처리|
|---|---|
|명확한 본문/버전·key/ID/본문 메타데이터 오류|send 없이 BLOCKED·안전 오류 코드|
|send 호출 실패·Future 실패·로컬 timeout|회차 횟수<10이면 PENDING·다음 시각·오류 저장. 한도 소진이면 BLOCKED|
|broker 성공 + 상태 TX 성공|PUBLISHED·publishedAt, lastError=null|
|broker 성공 뒤 상태 TX 실패|행을 임의로 PENDING으로 덮어쓰지 않고 불확실성 보고. 다음 DB 조회의 실제 상태로 판단|
|재시작 때 PENDING이나 회차 예산 소진|새 send 없이 BLOCKED. 이전 전송 결과가 불확실할 수 있음을 코드로 표시|
|종료 interrupt|새 행/send 중단·interrupt 복원. 실패 관찰이나 성공을 합성하지 않음|

시도 직전 짧은 TX에서 누적 attemptCount와 cycleAttemptCount를 증가해 commit한다. 이는 전송/수신 횟수가 아니라 처리 시도 시작 수다. 본문 검증 전이므로 본문 오류도 시도 수를 소비한다. 기록 뒤 send 전 중단에도 예산이 남고, broker 수신 0회라고 단정하지 않는다. 이 의미가 원문의 시도 수 관찰 한계다.

Outbox 모델의 requireDeliveryState는 PK·상태·카운터·운영 시각·오류/발행 시각 결합·삭제 시각 null을 검사한다. 저장된 원문 메시지 의미 검사는 verifier가 별도로 맡는다. 신규 insert는 기존 guarded OrderOutboxMessage를 받아 사실을 검증한다. 이 분리로 본문이 손상된 행도 상태 TX에서 시도/차단 정보를 남길 수 있고, 해석 불가능한 status나 카운터 자체는 운영 오류로 드러낸다.

모델 행동은 beginAttempt(now, provisionalNextAt), markPublished(attemptNo, now), recordFailure(attemptNo, safeError, nextAt, maxAttempts), blockInvalid(attemptNo, safeError), resume(now), storedMessage()다. 각 변경은 부분 대입 전에 requireDeliveryState·허용 상태·시도 번호·시각/입력 범위를 검사한다. beginAttempt는 PENDING·due에서 두 횟수 가산/다음 시각만 변경하고, 결과 행동은 위 표대로 운영 필드만 변경한다. resume은 누적 횟수/불변 사실을 유지한다. 아래 TX 의사코드의 필드 변경은 이 모델 행동을 통해 수행하며 application이 setter로 상태 규칙을 복제하지 않는다.

실패 번호 n의 지연은 min(initialRetryDelay × 2^(n-1), maxRetryDelay)다. overflow 전에 상한에서 멈추는 계산과 시각 가산 범위 검사를 사용한다. 시도 시작 때 provisional nextAttemptAt을 기록하고, 실패 관측 후에는 실패 처리 시각 기준으로 다시 계산한다. DB 장애로 다음 시각 저장에 실패하면 backoff를 영속 저장했다고 주장하지 않는다. 간격 상한과 자동 시도 한도는 다른 축이며 후자는 영구 오류 판정이 아니라 자동 처리 예산이다.

안전 오류 코드는 INVALID_OUTBOX_MESSAGE, UNSUPPORTED_SCHEMA, OUTBOX_FACT_MISMATCH, SEND_CALL_FAILED, SEND_COMPLETION_FAILED, SEND_WAIT_TIMEOUT, ATTEMPT_LIMIT_EXHAUSTED, UNCONFIRMED_ATTEMPT_LIMIT이다. 필요할 때 예외 클래스명만 붙이고 원문 message·stacktrace·payload·카드·비밀은 저장/서비스 로그에 복사하지 않는다. Kafka 인증/설정 등 추가 전송 예외를 임의로 영구 오류로 분류하지 않으며 현재는 전송 실패 예산 안에서 다룬다. DB 상태 오류와 전송 오류는 분리한다.

### W7.3-4. 주요 처리 의사코드

모든 now는 outboxRecordClock에서 UTC·마이크로초로 얻는다. 사건 confirmedAt·기존 감사 시각은 바꾸지 않는다. 개별 상태 변경 TX는 행 잠금 후 현재 status·누적 attemptNo를 확인한다. 이미 PUBLISHED는 변경 없는 결과, 시도 번호 불일치는 STALE_ATTEMPT로 변경 없음, 예상 밖 전이는 정합성 오류다. 이 방어가 다중 프로세스 소유권 보장은 아니다.

~~~text
runDueBatch(): // TX 없음
  gate.tryExecute:
    ids = transactions.findDueIds(now, batchSize) // readOnly TX 끝난 뒤 ID만 반환
    for id in 조회 순서:
      STOPPING/interrupt면 회차 종료
      task = transactions.beginAttempt(id, now) // public TX
      없거나 NOT_DUE/PUBLISHED/BLOCKED면 다음 행
      try:
        verifier.parseAndValidate(task.message) // Entity/DB/PG 조회 없이 저장 snapshot 검사
      catch 명확한 형식/버전/사실 오류:
        transactions.blockInvalid(id, task.attemptNo, 안전 코드)
        continue
      STOPPING/interrupt면 새 send 없이 종료
      try:
        receipt = sender.send(task).toCompletableFuture().get(sendWaitTimeout)
        실제 topic·partition·offset의 전송 확인 계약 검증
      catch InterruptedException:
        interrupt 복원; 회차 종료 // 늦은 완료는 DB 상태를 바꾸지 않음
      catch send 호출/완료 실패 또는 TimeoutException:
        transactions.recordFailure(id, task.attemptNo, 안전 코드, now)
        continue
      probe.afterBrokerConfirmed(task, receipt) // send 예외 catch 바깥; 중단은 상태 TX를 실행하지 않음
      STOPPING/interrupt면 상태 성공을 합성하지 않고 종료
      transactions.markPublished(id, task.attemptNo, now)
  DB 읽기/쓰기 오류·저장 정합성 오류는 회차 중단·안전 로그·실패 결과
  gate는 모든 종료 경로의 finally에서 해제

beginAttempt(id, now): // public TX, Outbox 행 잠금
  행/저장 상태 검사; PENDING·due가 아니면 해당 skip 결과
  cycleAttemptCount >= maxCycleAttempts면:
    BLOCKED·UNCONFIRMED_ATTEMPT_LIMIT 저장; send 없이 skip
  두 횟수 증가; nextAttemptAt=now+현재 회차 횟수의 지연; 불변 사실 유지
  saveAndFlush; raw scalar task 복사; commit 후 반환

recordFailure(id, attemptNo, code, now): // public TX
  행 잠금·현재 상태/누적 번호 검사
  cycleAttemptCount >= maxCycleAttempts면 BLOCKED·한도 소진 코드와 안전 원인 표시
  아니면 PENDING·nextAttemptAt=now+지연
  publishedAt=null·안전 lastError 저장; commit

markPublished(id, attemptNo, now): // public TX
  행 잠금·현재 상태/누적 번호 검사
  status=PUBLISHED·publishedAt=now·lastError=null; 불변 사실/횟수 유지; commit
~~~

blockInvalid도 같은 행/시도 번호 검사 뒤 BLOCKED·안전 오류·publishedAt=null을 저장한다. 한 행의 오류/재시도 저장이 정상 완료되면 다음 due 행을 처리한다. DB 상태 저장 실패에는 무리하게 다음 메시지를 보내지 않고 회차를 끝낸다. send Future는 receipt 변환만 수행하며 늦은 callback에서 markPublished/recordFailure를 호출하지 않는다.

테스트의 afterBrokerConfirmed probe 예외는 호출자에게 그대로 전파한다. 이를 send/DB 실패로 분류하거나 정상 결과로 바꾸지 않고, PUBLISHED 상태 TX 없이 gate만 finally에서 해제한다. O7은 그 통제 예외와 실제 저장 상태/metadata를 각각 확인한다.

### W7.3-5. 전용 producer와 정상 종료

W7의 orderOutboxProducerFactory·orderOutboxKafkaTemplate은 StringSerializer/StringSerializer를 사용한다. KafkaProperties map 복사→공통 retries=3 제거→기존 주문 발행 producer override 적용→key/value serializer 유효 map을 String으로 명시한다. 기존 acks=all·idempotence·in-flight≤5·delivery/request/linger 관계·transactional.id 혼입 거절·factory 비트랜잭션 검사를 유지한다. JSON type header는 발행하지 않는다. 공통 Kafka 설정이나 W6 보존 adapter의 wire를 함께 바꾸지 않는다. 실제 template/factory 연결은 qualifier로 명시하고 @Primary로 기존 경로를 바꾸지 않는다.

저장한 문자열을 재직렬화하지 않고 UTF-8 전송한다. sender는 성공 Future의 실제 metadata로 receipt를 만들고 오류를 정상 성공으로 바꾸지 않는다. 실제 metadata/consumer 수신/DB 효과는 각각 별도 근거다. broker가 한 대이므로 acks=all이 복제 장애 내구성을 입증하지 않는다.

전용 scheduler는 단일 스레드를 소유한다. Spring Context 6.2.5의 ContextClosedEvent가 lifecycle phase stop보다 먼저 전달됨을 포함해 다음 순서로 설계한다.

1. 전용 ThreadPoolTaskScheduler는 acceptTasksAfterContextClose=false·waitForTasksToCompleteOnShutdown=false로 early shutdown을 시작한다. continueExistingPeriodicTasksAfterShutdownPolicy=false·executeExistingDelayedTasksAfterShutdownPolicy=false를 명시해 주기/지연 작업을 종료 뒤 새로 실행하지 않는다. phase 값만으로 이 선행 이벤트를 생략해 설명하지 않는다.
2. relay 진입 제어 lifecycle phase=0의 stop은 새 수동 진입을 STOPPING으로 막고 진행 thread에 interrupt를 전달한다. 실행 finally의 gate 해제/종료 통지를 기다리고, 주기 task 취소는 중복 호출에도 안전하게 처리한다.
3. 전용 pool의 phase=-100 lifecycle 정지 절차를 거쳐 진행 작업 종료를 확인하고, producer factory는 기존 MIN_VALUE에서 종료한다. 실제 bean 의존과 stop/destroy 순서는 O10에서 관찰한다.
4. DB/라이브러리 정체로 기존 phase 대기 30초 안에 종료하지 못하면 timeout/미완료를 보고한다. producer physical close 15초를 더한 앱 전체 종료 보장을 주장하지 않는다. OS 강제 종료에서 callback/로그까지 보장하지 않는다.

종료 설명의 순서를 맞추려고 waitForTasksToCompleteOnShutdown=true로 바꾸지 않는다. 이 값은 early/lifecycle 정지 대신 destroy 단계의 실행/대기 방식까지 바꾼다. 실제 broker 확인 뒤 STOPPING이면 기존 설계대로 새 상태 TX를 시작하지 않고 PENDING을 남긴다. 다음 실행의 중복 재발행은 소비 멱등성으로 수렴시키며, ACK를 받았다는 이유로 종료 중 DB 쓰기를 추가하는 선택적 변경은 하지 않는다.

### W7.3-6. 본문 검증과 기존 사건 복구

verifier는 공통 Jackson의 관대한 coercion을 사용하지 않는다. 단일 JSON object·필수 타입·중복 key/trailing token 거절·고정 type/version·양수 long·정규 key/유도 ID·UTC Z 최대 6자리 및 DB 날짜 범위를 검사한다. 품목 원본 각각의 양수 확인 후 Math.addExact 합산·ID 정렬을 수행한다. 필수 외 미사용 속성·속성/품목 순서·소수초 후행 0은 기존 소비 계약대로 새 사실로 취급하지 않는다. 메타데이터와 본문의 ID/type/version/key가 일치해야 하며 raw 문자열 동등성만으로 충돌을 정하지 않는다. API는 streamer 앱/파서를 의존하지 않는다.

recoverOrders는 비어 있지 않은 양수 ID 목록을 검증한 뒤 중복 제거·오름차순으로 처리한다. 같은 gate 안에서 주문별 TX를 호출하며 오류면 완료·실패·미시도를 구분해 중단한다. 이미 끝난 commit을 취소했다고 하지 않는다. 전체 자동 스캔·과거 POINT 소급은 없다.

반환 계약은 application의 내부 값으로 다음과 같이 고정한다. 별도 관리자 HTTP DTO가 아니다.

- `OrderOutboxExecutionGate.Result<T>`는 Outcome(EXECUTED/BUSY/STOPPING)과 `Optional<T>` value의 불변 값이다. 값이 있는 action의 EXECUTED만 value를 담고 void action은 EXECUTED/empty를 허용한다. BUSY/STOPPING은 DB 변경/value 없이 전체 대상을 미시도한 결과다. recoverOrders의 정상 EXECUTED는 RecoveryReport가 필수다. tryExecute는 action의 예외를 정상 결과로 바꾸지 않고 finally에서 해제한다.
- recoverOrders(`List<Long>`)는 `Result<OrderOutboxOperations.RecoveryReport>`를 반환한다. RecoveryReport는 처리 순서의 불변 `List<RecoveryItem>`이다. RecoveryItem은 양수 orderId·유도 eventId·RecoveryOutcome(RESTORED/EXISTS)·OrderOutboxStatus를 담는다. RESTORED는 PENDING, EXISTS는 실제 기존 상태다. 정상 반환은 모든 명시 대상의 TX 종료 뒤다.
- 한 대상의 RuntimeException은 프록시 호출 종료 뒤 RecoveryFailedException으로 전파한다. 예외는 불변 completed 목록·`Optional<Long>` failedOrderId·안전 failureCode·오름차순 unattempted 목록과 내부 원인(없을 수 있음)을 가진다. 대상 TX를 호출하다 실패했으면 failedOrderId에 그 ID를 넣고 unattempted는 뒤 대상만 담는다. TX 시작 전 종료를 관측했으면 failedOrderId는 비우고 현재부터 끝까지 모두 unattempted에 넣는다. payload/원문 SQL/예외 message는 결과/서비스 로그에 복사하지 않는다. 입력 오류는 gate 전 거절하고 치명적 Error를 정상 실패로 삼키지 않는다.

failureCode는 알려진 메시지 검증의 안전 code·CoreException의 ErrorType 이름을 재사용하고, DB 오류는 RECOVERY_DB_FAILURE, STOPPING/interrupt는 RECOVERY_INTERRUPTED, 나머지는 RECOVERY_FAILED로 남긴다. Failed 대상은 해당 호출의 실패를 뜻한다. commit 응답 유실처럼 DB 결과가 불명인 경우 rollback 완료로 단정하지 않고 다음 명시 실행의 새 조회로 실제 행을 판단한다. completed에는 commit 반환이 확인된 대상만 넣는다.

각 대상의 TX를 시작하기 전에 gate.isStopping()·현재 thread interrupt를 확인한다. 관측되면 새 TX를 호출하지 않고 RECOVERY_INTERRUPTED로 부분 결과를 전파한다. 이미 진행한 TX는 해당 프록시의 commit/rollback 결과를 기다리며 종료 관측 시점과 TX 진입 사이 경쟁까지 원자적으로 차단했다고 주장하지 않는다. interrupt 원인을 처리할 때 플래그를 지우지 않고, InterruptedException을 원인으로 받은 경우 복원한다. 완료 target의 commit은 보존하고 중단을 정상 완료로 표시하지 않는다.

O10은 gate 진입 전 STOPPING/BUSY와 진입 뒤 대상 경계 중단을 구분한다. 첫 대상의 프록시 commit 반환 뒤 장벽에서 종료를 관측시키면 completed는 첫 대상만, failedOrderId는 없음, unattempted는 현재부터 나머지 전체이며 다음 TX 호출은 0이어야 한다. 첫 TX 전 interrupt와 진행 TX 실패의 보고도 구분한다. 대기/종료 시험에 사용한 thread·context는 이후 검증에 재사용하지 않고 소유 수명에서 정리한다.

이 장벽은 테스트가 직접 조립한 동일 OrderOutboxOperations에 위임 대역을 연결해 둔다. 대역은 context의 실제 OrderOutboxRecoveryTransactionService 프록시를 호출하고 **commit 반환 뒤** 결과를 잠시 보유한 다음 반환한다. 실제 공유 gate/협력 bean을 사용하고 테스트 부모 TX는 없다. 제품 메서드 내부나 TX 프록시 안쪽에 장벽을 넣지 않으며 새 제품 probe도 추가하지 않는다. 대역을 별도 @Transactional 대상 bean으로 다시 등록해 바깥 TX를 만들지 않는다. 제어 thread가 종료를 요청하고 유한 장벽을 finally에서 풀며, 장벽 자체의 interrupt를 복구 실패로 던지는 대신 플래그를 보존/복원해 다음 대상 전 검사에 전달한다.

~~~text
recoverOrders(orderIds): // TX 없음, 입력 검증/정렬 뒤 gate 안
  completed = []
  for orderId in 명시 대상:
    if gate.isStopping() 또는 thread interrupt:
      throw RecoveryFailedException(completed 복사, Optional.empty,
                                    RECOVERY_INTERRUPTED, 현재부터 미시도 복사, 원인 없음)
    try:
      item = recoveryTransactions.recoverOne(orderId) // proxy 호출 종료/commit 뒤 반환
    catch RuntimeException failure:
      interrupt 원인이면 플래그 복원·RECOVERY_INTERRUPTED로 분류
      throw RecoveryFailedException(completed 복사, Optional.of(orderId), 안전 코드,
                                    뒤 미시도 대상 복사, 내부 원인)
    completed.add(item)
  return RecoveryReport(completed 불변 복사)

recoverOne(orderId): // public TX
  첫 업무 SQL = 기존 Order PK 잠금; Payment 첫 일반 조회
  PgOrderConsistency.requireConsistent // 기존 저장 정합성 조건 그대로
  복구 추가 전제:
    PG·CONFIRMED·SUCCESS, 저장 transactionKey가 null/blank가 아님
    provider/account/외부 주문 연결·금액/paidAmount·pointAmount=0·고정 품목/시각
  최근 verificationStatus의 UNAVAILABLE/CONFLICT만으로 저장 SUCCESS를 취소하지 않음
  expected = OrderConfirmed.from(저장 order)
  existing = 해당 eventId Outbox 잠금 조회
  if existing 존재:
    전달 상태 정합성 확인
    verifier.requireSameFact(expected, existing.storedMessage())
    return RecoveryItem(orderId, expected.eventId, EXISTS, existing.status)
      // 행·횟수·nextAttemptAt·감사 시각·BLOCKED/PUBLISHED 변경 없음
  message = encoder.encode(expected)
  repository.insertAndFlush(pending(message, now))
  return RecoveryItem(orderId, expected.eventId, RESTORED, PENDING)
~~~

정상 확정과 복구는 같은 Order PK를 먼저 잠그므로 같은 사건 insert가 직렬화된다. 사용자 선택으로 unique 오류 뒤 별도 TX 재확인·OrderOutboxConstraintClassifier·전용 우회 경쟁 테스트는 제거한다. uk_oco_event는 최종 방어로 유지하며 위반은 해당 TX rollback/실패로 드러낸다. 기존 행 의미 비교와 명시 재실행은 유지한다. 정상 잠금을 우회한 쓰기와의 경쟁을 즉시 정상 EXISTS로 수습하지 않는다.

거래 키 존재는 recoverOne의 추가 전제다. PgOrderConsistency에 기존 requireConsistent에 없던 조건을 몰래 넣어 모든 PG 흐름의 정책을 바꾸지 않는다. 원본 부족·품목/시각/금액/거래 연결 충돌은 오류다. 새 시각·UUID·현재 상품으로 보정하거나 기존 행을 덮어쓰지 않는다. PG create/lookup/callback·재확정·예약 추가 소비/반환·재고/포인트 변경은 없다.

Order→Outbox의 짧은 TX 안에 외부 전송은 없다. MySQL RR에서 없는 eventId의 FOR UPDATE는 gap lock으로 다른 insert를 기다리게 할 수 있다. 현재 잠금 조회는 유지하고 SQL/대기·실패를 관찰한다. 교착/DB 오류는 실패 보고로 드러내며 자동 TX 재시도 정책을 추가하지 않는다. 같은 명시 대상을 다시 실행하면 이미 끝난 대상은 의미 비교로 이어간다.

복구는 기존 BLOCKED/PUBLISHED를 자동 재개·강제 재발행하지 않는다. 첫 복구는 없던 A·B에 PENDING을 추가한 뒤 별도 relay가 전달한다. 두 번째 복구는 내용 일치 확인만 한다. BLOCKED는 명시 재개를 사용한다.

### W7.3-7. 로컬 재개·관찰·보존

resumeBlocked(eventId)는 gate 안에서 별도 TX로 Outbox 행을 잠근다. BLOCKED이면 status=PENDING·cycleAttemptCount=0·nextAttemptAt=now로 바꾸고, 누적 attemptCount·사건/key/payload·createdAt은 보존한다. lastError는 다음 성공/실패까지 최근 오류로 남긴다. PENDING은 ALREADY_PENDING, PUBLISHED는 ALREADY_PUBLISHED로 변경 없음, 없는 eventId는 오류다. 별도 runEvent(eventId)는 일반 시도와 같은 due/상태/한도 검사를 수행하며 BLOCKED를 몰래 해제하거나 PUBLISHED를 다시 보내지 않는다.

runEvent는 readOnly TX에서 명시 eventId의 row ID만 복사하고 없으면 오류를 반환한다. PENDING이지만 아직 시각이 안 됐으면 NOT_DUE, BLOCKED/PUBLISHED이면 해당 skip 결과다. 이 로컬 명령의 결과도 실제 전송 확인·구매 활동 완료와 구분한다.

로컬 진입점은 application의 위 메서드와 해당 실행의 유지된 context에서 호출하는 테스트 도구로 제공한다. 공개 관리자 HTTP API·별도 운영 서버·새 CLI 배포물은 추가하지 않는다. 테스트 자동 scheduler를 끈 구성에서도 명시 도구가 같은 gate를 사용하는지 확인한다. 개발 DB를 create로 재생성하는 별도 테스트 실행을 운영 재개 도구로 쓰지 않는다.

이 범위에는 실행 중인 일반 로컬 API 프로세스에 외부에서 BLOCKED 재개를 요청하는 통로가 없다. 유지된 context를 소유한 테스트 도구에서만 재개를 호출할 수 있다. 이 한계는 후속 PR/운영 설명에 명시하며 이번 보완에서 공개/로컬 HTTP·CLI·새 서버를 추가하지 않는다.

수동 eventId 입력은 기존 유도 규칙·양수 long 범위의 정규 형식부터 검사한다. 재개는 코드/환경 등의 원인을 고친 뒤 시도할 수 있게 하는 것이며 저장 사실의 자동 수정이 아니다. 본문 자체 손상·사실 충돌은 현재 immutable payload를 임의 덮어써 고치지 않는다. 이런 행은 이번 기능이 자동 해결하지 않는 한계로 남기고 별도 조사·복구 판단이 필요하다. 유효 v1 본문을 처리하는 코드/설정 문제가 수정된 경우에는 같은 원문을 재개할 수 있다.

전송 시도·성공/실패·BLOCKED·재개·회차 DB 오류는 검증된 eventId/orderId·outboxId·누적/회차 횟수·단계·안전 코드로 추적한다. broker 성공은 실제 topic/partition/offset을 남기며 소비 완료로 표시하지 않는다. 손상된 ID/key 원문은 검증 없이 로그에 넣지 않고 row PK·고정 INVALID 표기를 사용한다.

SQL/구조화 로그로 상태별 건수·가장 오래된 PENDING 기록 시각·BLOCKED 대상·누적 시도를 확인한다. 별도 모니터링 서버는 없다. consumer lag·오류·실제 완료/품목 활동은 별도 조회한다. 이번 과제에서는 PENDING/BLOCKED/PUBLISHED와 소비 dedup 기록의 자동 삭제를 추가하지 않는다. Kafka retention은 기존 설정을 바꾸지 않으며 실제 값·관찰 기간은 검증 때 확인한다. retention 밖 record 유실·DB 손상·무한 보존 용량을 자동 해결하지 않는다. 향후 정리를 도입하려면 replay 가능 기간·Kafka retention·dedup 보존을 함께 정해야 한다.

### W7.3-8. 검증 기준·통합 경계 — 미실행

- 상태 전이·카운터 가산/overflow·backoff 상한·10회 예산·즉시 BLOCKED·재개 후 누적 보존/예산 초기화·기존 상태별 no-op을 확인한다.
- 실제 DB에서 짧은 TX/Outbox 잠금·시도 정보 commit·본문/identity 불변·phase별 오류를 확인한다. 원문 두 중단 구간은 같은 DB/identity에서 relay 객체·실행 흐름을 새로 만들어 관찰하며 행을 직접 PENDING으로 덮어쓰지 않는다.
- broker 성공 뒤 probe 중단에는 실제 metadata를 먼저 확보하고 상태 TX를 수행하지 않는다. 재시작 재발행의 별도 record·consumer 2회 이상 수신과 완료 1개·품목별 활동/수량 불변을 실제 관찰한다.
- Future 로컬 timeout 뒤 늦은 완료가 Outbox 상태를 바꾸지 않음을 통제하고, 결과 불확실/DB 상태 저장 실패와 실제 rollback 완료를 구분한다.
- scheduler/수동 run/resume/recover 중첩의 BUSY·종료 중 진입 거절·실제 producer 종료 순서·부분 실패 정리를 확인한다. 단일 프로세스 결과를 여러 API 인스턴스 보장으로 확대하지 않는다.
- 복구의 의미 비교·원본 부족/충돌·주문별 부분 commit·unique 최종 방어/실패·기존 BLOCKED/PUBLISHED 불변·두 번째 복구 불변을 확인한다. unique 오류 뒤 새 TX 재확인은 없다. 준비/복구의 PG 호출·거래·재고·포인트를 분리 관찰한다.
- W6 테스트 소스/타입/scan 보존과 context 전환·스키마 연결의 조립 설계, 테스트 교체/회귀·관련 문서 통합은 W7.4를 따른다. 현재 코드가 이미 연결됐거나 Checkstyle/ArchUnit을 통과했다는 뜻은 아니다.

## W7.4 검증 구성·회귀·기준 문서 통합 — 4단계 초안

**상태:** 2026-11-04 문서 작업 착수 및 확정 사실 기록 역할 분리를 사용자에게 확인받았다. W7.2~W7.4는 1차 Claude 검토를 대조하고 사용자 선택을 반영한 보완 초안이다. 아래 신규 테스트/지원 타입은 구현 목표이며 현재 존재·컴파일·실행 성공을 뜻하지 않는다. 원문 실습 1~8절의 필수 검증과 사용자 선택을 연결하며, W6의 과거 실행 결과는 [과거 기록](../../verification/order-events.md)에 그대로 둔다. 선택 근거는 [A.39·A.40](../../decisions/order-events.md)다.

### W7.4-1. W6 경로를 보존하는 테스트 구성

제품에서는 PaymentTransactionService가 OrderConfirmedRecorder를 호출하고 유일한 제품 구현 OrderOutboxRecordingService가 같은 TX에 Outbox를 기록한다. W6 준비 컨텍스트만 테스트 소스의 W6OrderConfirmedRecorder를 연결한다. 이 구현의 public record는 MANDATORY이며 기존 위치에서 ApplicationEventPublisher.publishEvent(event)를 호출한다. 등록은 TX 안이고 실제 전송은 보존한 AFTER_COMMIT listener다. PG 판정·Order 우선 잠금·예약·확정 시각·반환·충돌 처리는 공통 서비스를 그대로 실행한다. recorder는 사용자가 승인한 application 책임 분리이며 Domain Service나 제품의 실행 모드 선택 기능이 아니다.

|위치·목표 타입|보존/변경 범위·계약|
|---|---|
|API test의 infrastructure/order/support/W6OrderEventTestConfiguration|명시 등록한 테스트 조립만 제공. 전역 component scan 대상인 @Configuration/@Component를 붙이지 않고 필요한 @Bean 메서드를 명시 등록해 처리. 다른 API 테스트에 자동 적용하지 않음|
|같은 test 지원 영역의 W6OrderConfirmedRecorder|OrderConfirmedRecorder 구현. 호출자 TX 필수, 실제 Spring 이벤트 등록만 수행. 성공 사실·Outbox를 직접 삽입하지 않음|
|W6OrderConfirmedEventListener·W6OrderEventPublisher·W6KafkaOrderEventPublisher·W6OrderPublicationProbe|53f912b의 기존 listener·domain port·Kafka adapter·probe를 테스트 소스에 보존. package/class/bean 이름·타입 연결·scan 애노테이션만 조정. AFTER_COMMIT/fallbackExecution=false·beforeSend 위치·비동기 send/오류 처리·v1 DTO/wire는 유지|
|W6OrderEventKafkaConfig|같은 revision의 JsonSerializer 전용 producer/template·properties 검사·close 계약을 테스트 전용으로 보존. Clock 조립만 아래 공통 설정으로 이동. W7 String template으로 바꿔 W6 전송을 흉내 내지 않음|
|제품 config/OrderConfirmationClockConfig|기존 orderConfirmationClock 이름·Clock.systemUTC()·한 번 생성/UTC 마이크로초 절삭 계약 유지. 기존 OrderEventKafkaConfig에서 Clock 조립만 이동해 W6/W7 양쪽에 사용. outboxRecordClock과 별개|
|API test의 W6PreparationContextInitializer|refresh 전 W6 정의 등록과 CCPP 뒤 W7 정의 제거/교체를 구분. 아래 managed type·bean 경계를 이 준비 컨텍스트에만 적용|
|API test의 W6OutboxRecoveryEndToEndIntegrationTest|C10의 API/PG/consumer·고정 입력·probe·broker/DB 관찰을 준비 함수로 보존하고 복구까지 한 자원 수명으로 소유. 기존 C10의 완료 기대값을 낮추지 않음|

보존 출처·revision과 허용한 기계적 변경을 각 테스트 지원 소스에 명시하고 원본과 대조한다. 현재 생산 소스의 직접 발행 listener/adapter/port/probe·JSON producer 조립은 제품 경로에서 제거하고 위 테스트 소스로 보존한다. 공통 OrderEventKafkaProperties의 topic·기존 producer 계약은 유지한다. Clock 상수/qualifier 참조와 기존 테스트도 새 중립 Clock 설정에 맞춘다. 제품에는 두 recorder를 동시에 등록하지 않고 profile·환경변수·운영용 우회 스위치로 W6를 선택하지 않는다.

W6 준비 컨텍스트는 기존 CommerceApiApplication을 사용하되 **refresh 전 등록**과 **설정 해석 뒤 제거/교체**를 분리한다. C10의 무순위 BDRPP는 이미 생성된 정의 교체에 활용한다.

1. initializer의 initialize에서 refresh 전에 ManagedClassNameFilter·W6OrderEventTestConfiguration/W6OrderEventKafkaConfig와 W6 recorder/listener/adapter/probe 정의를 명시 등록한다. @Bean 메서드가 있는 테스트 설정 클래스는 이때 등록해 CCPP가 해석하게 한다. 전역 scan 대상 @Configuration/@Component로 만들지 않는다.
2. ManagedClassNameFilter는 53f912b의 관리 타입만 full class name으로 허용한다. Entity는 BrandModel, ExampleModel, LikeModel, OrderModel, OrderItemModel, PaymentModel, PaymentConflictModel, PointModel, PointHistoryModel, ProductModel, StockHistoryModel, UserModel의 12개다. Money·Stock Embeddable과 BaseEntity MappedSuperclass도 포함한다. Outbox는 관리/DDL 대상이 아니다.
3. 같은 initializer가 Replacer를 **bean 정의**로 등록한다. Replacer는 PriorityOrdered/Ordered를 구현하지 않는 BDRPP로 두어 CCPP의 설정 해석·repository/@Bean 정의 생성 뒤 실행시킨다. context.addBeanFactoryPostProcessor에 인스턴스를 직접 추가해 CCPP보다 먼저 실행시키지 않는다.
4. Replacer는 singleton 생성 전에 W7 repository 정의/구현·제품 recorder·relay/복구/운영/scheduler/sender·전용 relay/Kafka config를 제외한다. config가 만든 @Bean 정의도 factoryBeanName 연결로 제외한다. 이 단계에서는 @Bean 설정 클래스를 새로 등록해 다시 해석되길 기대하지 않는다. 고정 orderConfirmationClock·beforeSend probe는 기존 정의가 있음을 확인하고 제거/교체한다. 전역 override 허용·@Primary·이름 접두사만으로 무관한 bean 제거는 사용하지 않는다.
5. 시작 후 sole recorder가 W6 구현이고 실제 listener/JSON adapter/template/factory가 연결됐는지, W7 relay·Outbox repository/Entity가 없는지 단언한다. O11은 빈 DB 준비 검사와 W6 준비 뒤 Outbox 테이블 부재도 확인한다. 누락/충돌은 준비 실패이며 SQL 성공 상태 삽입으로 우회하지 않는다.

~~~text
initialize(context): // refresh 전
  register managed-type filter, W6 설정·일반 bean 정의
  register Replacer의 bean 정의
Spring CCPP: // PriorityOrdered
  위 W6 설정과 일반 앱 설정의 @Bean/repository 정의 해석
Replacer.postProcessBeanDefinitionRegistry: // CCPP 뒤
  remove W7 정의와 그 factoryBeanName의 @Bean 정의
  replace 기존 Clock/probe 정의
일반 singleton 생성·기동 → 실제 조립/부재 단언 → W6 실제 API 준비
~~~

Boot 3.4.4 JpaBaseConfiguration의 managedTypes/filter 연결, Spring Context 6.2.5 PostProcessorRegistrationDelegate의 PriorityOrdered→Ordered→나머지 순서, ConfigurationClassPostProcessor의 registry 재처리 방지와 ConfigurationClassUtils의 @Bean 설정 후보 인식을 로컬 소스로 대조했다. 설정 후보라는 사실만으로 늦게 등록한 클래스가 다시 해석되는 것은 아니다. 실제 기동 검증은 아직 없으며 streamer 전체 scan/runtime 의존성은 추가하지 않는다.

### W7.4-2. 한 실행의 자원 수명과 스키마 전환

기존 C10의 MySqlTestContainersConfig·RedisTestContainersConfig, LocalKafkaTestSupport, PgFixtureServer, streamer bootJar 자식 JVM 실행을 재사용한다. 테스트 JVM 소유 격리 MySQL/Redis와 공유 로컬 Kafka의 고유 2-partition topic/group을 사용하고 DB 사용 테스트는 직렬 실행한다. 공유 broker 자체는 종료하지 않는다. 부모가 PG fixture를 시작하고 URL을 두 API 컨텍스트에 주입하며 어느 API 컨텍스트의 destroy bean으로도 PG를 닫지 않는다. 실제 streamer는 동일 DB·broker/topic/group으로 한 번 시작해 복구 완료까지 유지한다. API test Gradle의 기존 streamer bootJar 선행 작업/입력 선언을 활용한다.

#### O11 전용 테스트 작업과 빈 DB 보장

사용자는 기존 fixture의 JVM 내 공유로 앞선 W7 테이블이 남는 문제를 확인한 뒤, O11만 별도 Gradle Test 작업의 새 JVM에서 실행하는 안을 선택했다. 대상 작업 이름은 `w6OutboxRecoveryTest`, 대상 클래스는 `com.loopers.infrastructure.order.W6OutboxRecoveryEndToEndIntegrationTest`로 둔다. 새 source set·제품 서버·별도 W6 revision 실행물은 만들지 않고 기존 API test의 컴파일 산출물/runtime classpath·fixture를 사용한다. 전용 Test의 testClassesDirs는 sourceSets.test.output.classesDirs, classpath는 sourceSets.test.runtimeClasspath로 명시한다.

- 일반 `test`에서는 위 클래스 하나만 정확히 제외하고 전용 작업에는 해당 클래스만 포함한다. 그 밖의 유효한 테스트는 제외하지 않는다. 전용 작업의 대상 filter는 매칭 없음 실패를 켜며 대상 클래스 존재·실제 실행 건수/skip/failure도 확인한다. 직접 실행은 `./gradlew :apps:commerce-api:w6OutboxRecoveryTest`를 사용한다. 일반 test의 대상 filter로 옮긴 클래스를 찾으려 하지 않는다.
- 전용 작업은 testClasses와 streamer bootJar를 선행 작업으로 두고, 기존 bootJar input 파일 추적·경로 JVM 인자를 동일하게 연결한다. JUnit Platform, test profile, Asia/Seoul 시간대, 기존 JVM 옵션을 명시해 일반 test에만 적용된 root 설정을 빠뜨리지 않는다. 일반 test의 datasource 런타임 주소를 복사하지 않는다.
- maxParallelForks=1·forkEvery=1로 대상 클래스의 새 테스트 worker JVM을 사용하고, `check`는 전용 작업도 dependsOn한다. 전용 작업은 일반 test 뒤에 실행되도록 mustRunAfter를 설정하되 단독 실행 때 일반 test를 추가 실행하지 않는다. prepare→복구 사이에는 worker를 바꾸거나 fixture를 재생성하지 않는다.
- 로컬 외부 자원의 실제 관찰이 목적이므로 이 작업은 outputs.upToDateWhen { false }와 outputs.doNotCacheIf 조건으로 up-to-date/build cache 결과가 실제 실행을 대체하지 않게 한다. XML/HTML 결과는 작업별 디렉터리, JaCoCo는 작업별 exec 파일로 분리한다. 기존 report의 exec 수집 범위를 유지하고 집계 report의 실행 순서에도 전용 작업을 반영한다. 기존 결과/임계값을 지우거나 낮추지 않는다.
- worker에서 기존 MySqlTestContainersConfig를 먼저 초기화하고 그 설정이 제공한 테스트 datasource로 API/streamer 기동 전 새 SQL 조회를 수행한다. 업무 테이블이 없는 빈 스키마·이 실행의 fixture 주소임을 확인한 뒤 W6 API의 create를 허용한다. W7 Outbox 테이블/행이 먼저 존재하거나 다른 실행/개발 DB를 가리키면 준비 실패다. 삭제/drop으로 빈 상태를 꾸며 진행하지 않는다.
- 실제 실행 때 worker/fixture 구분·빈 스키마 관찰·W6 준비 뒤 Outbox 부재·W7 update 뒤 구조 추가를 기록한다. 코드/문서에 설정이 있다는 사실만으로 새 JVM·격리/실행 성공을 판정하지 않는다. 결과 0건·skip·NO-SOURCE·연결 실패·제외한 전용 작업은 필수 검증 통과가 아니다.

전용 worker는 기존 fixture 방식으로 자기 MySQL/Redis를 새로 소유한다. 일반 테스트가 쓰던 컨테이너에 붙지 않으며 O11 안에서는 W6/W7 API와 실제 streamer가 이 동일 DB를 사용한다. 공유 Kafka는 실행별 topic/group으로 격리한다. 테스트 작업·별도 worker 기동/자원/보고 비용이 늘지만 기존 전체 테스트의 모든 클래스를 새 JVM으로 쪼개지 않고, W7 상태를 지워 W6를 준비하는 방법도 쓰지 않는다. Gradle 설정 변경은 후속 승인된 구현 단위에서 수행하며 지금은 설계만 기록한다.

~~~text
한 전용 테스트 worker의 실행: // 부모 테스트 TX 없음, 단계 사이 @AfterEach/정리 없음
  소유 MySQL/Redis·Kafka client/topic/group·PG fixture 준비
  W6 준비 API context: 기존 12 Entity에 ddl-auto=create
  실제 streamer 자식 JVM: 자기 두 Entity에 ddl-auto=create, 이후 계속 유지
  consumer 기동/그룹 배정 완료 확인
  C10의 실제 API/PG로 A 준비: 확정 commit 뒤 beforeSend 중단
  B 준비: 실제 직접 발행·소비 완료, 상품 삭제 뒤 고정 품목 사실도 보존
  새 DB 조회/전 partition의 유한 관찰 구간으로 W7-2의 출발 상태 단언
  PG activeRequests/activeCallbackJobs=0까지 대기
  이번 사건·PG 카운터/거래·업무·소비 전체 snapshot·기존 DDL 기록

  W6 API context만 정상 close // 데이터 정리·PG reset/close·streamer stop 없음
  W7 정상 API context:
    동일 datasource/Redis/PG/topic 사용, 새 자기 포트/callback URL은 기동 전 주입
    ddl-auto=update, 자동 relay enabled=false
    제품 sole recorder=OrderOutboxRecordingService, W6 listener/adapter 없음
  Outbox 테이블 추가와 기존 테이블/행·PG 기준 불변을 새 SQL로 단언
  operations.recoverOrders([A, B]) // 주문별 TX; 없던 Outbox 각각 1행
  같은 DB에서 새 relay 실행 흐름으로 runDueBatch
  A: 실제 broker 기록→실제 consumer→완료/전체 품목 활동 생성
  B: 최초와 다른 broker record/offset→실제 중복 수신→기존 완료/활동 전체 값 불변
  두 번째 recoverOrders([A, B]):
    Outbox 행 전체 snapshot·구매 효과·PG/업무 기준 불변
  모든 확인 또는 실패 뒤 finally에서 소유 자원 정리
~~~

최초 create는 비어 있는 격리 DB에서 W6 상태를 준비할 때만 사용한다. W6→W7 전환에 create/create-drop을 사용하지 않고 W7은 update로 Outbox를 추가한다. 기존 streamer는 재시작하지 않으므로 구매 테이블을 다시 create하지 않는다. 원본 DB 보존 방식은 이번 기본 검증에 추가하지 않는다.

전환 직전/직후 information_schema·SHOW CREATE TABLE과 PK별 새 SQL 조회를 비교한다. 기존 API 12개·streamer 2개 업무 테이블의 정의·행과 PG 메모리 상태가 그대로이며 Outbox만 추가돼야 한다. 새 Outbox의 모든 열/유일·CHECK·인덱스/문자열 타입은 W7.2와 대조한다. update가 제약을 누락하거나 기존 정의를 바꾸면 실패다. 상태를 재생성하거나 기대값을 낮춰 통과시키지 않고 실제 원인·대체 DDL 필요성을 사용자에게 보고한다. 이 update는 테스트 구성 선택이며 제품의 운영 DB 마이그레이션 보장을 뜻하지 않는다.

실제 BOM 버전 Hibernate ORM 6.6.11.Final의 AbstractSchemaMigrator.applyUniqueKeys와 UniqueConstraintSchemaUpdateStrategy.interpret를 로컬 소스로 확인했다. 기본 DROP_RECREATE_QUIETLY라도 기존 이름의 index가 발견되면 건너뛰고, 발견되지 않은 경우 drop/create를 시도한다. 따라서 update가 무조건 기존 unique를 다시 만들거나 항상 보존한다고 주장하지 않는다. 이름/대소문자·실제 index 정보와 DDL 결과를 비교하며 실패를 숨기는 전략/기준 완화는 추가하지 않는다.

PG 거래에는 W6 API의 기존 callback URL이 남을 수 있다. 복구 중 새 callback을 송신하거나 거래 URL을 바꿔 맞추지 않는다. W7 context의 URL 자체는 자기 실제 포트와 맞춰 기동하되 복구는 PG HTTP를 전혀 호출하지 않는다. 준비 직후의 실제 C·T·lookupByKey/lookupByOrder·callback 집계를 기준으로 API close/전환/복구/relay 전 과정 증가 0을 단언한다. active job이 남으면 기준을 먼저 확정하지 않는다.

B의 불변 검사는 행 수/수량만 보지 않고 완료 행과 품목 활동의 PK·식별자·확정 시각·수량·생성/갱신/삭제 시각 등 전체 저장 열을 비교한다. A/B 주문·결제/거래 연결·예약/반환 이력·재고·포인트도 기준과 비교한다. 두 번째 복구는 이미 PUBLISHED인 Outbox의 갱신 시각까지 바꾸지 않는다. DB 검증은 기존 PC의 캐시 대신 새 TX/SQL로 수행한다.

최종 정리는 W7 진입/relay를 먼저 멈추고 실제 streamer writer/프로세스를 종료한 뒤, API context가 가진 cleanup으로 소유 API 데이터·Redis를 정리하고 context를 닫는다. PG를 닫고 streamer 구매 테이블·소유 topic/group/client를 정리한다. W6 context가 아직 살아 있는 부분 실패에는 그 context를 정리/종료하고, 이미 닫혔다면 유지된 테스트 datasource로 소유 데이터만 정리한다. 각 자원은 생성 성공 여부를 추적하고 cleanup 실패에도 나머지를 모두 시도하며 첫 실패와 suppressed 정리 오류를 남긴다. 공유 개발 DB·다른 실행의 topic/group·공유 broker를 정리하지 않는다. JVM 소유 fixture는 마지막 소유자 종료 시 정리하며 단계 전환에서 재시작하지 않는다.

### W7.4-3. 원문 요구별 필수 검증표 — 전부 미실행

아래 O 식별자는 신규 검증 계획이다. 기존 P/C 식별자의 실행 이력을 재사용하지 않는다. 테스트명은 목표 배치이며 구현 때 같은 책임으로 병합/분리할 수 있지만 검증 기준을 축소하지 않는다.

|ID·목표 경계|실제/통제 자원·핵심 기대|원문 실습|
|---|---|---|
|O1 모델/codec 단위|불변 identity·v1 JSON 필드/타입·UTC 마이크로초·품목 합산/overflow, 엄격한 verifier와 허용 표현 차이. 현재 Product 조회 없음|1·2·5|
|O2 Outbox 저장/조회 통합|실제 MySQL DDL·명명 unique/CHECK·문자열 왕복·due 경계/제외/동률/limit·EXPLAIN. 새 JSON string 이중 인코딩은 broker에서도 확인|2·3|
|O3 OrderOutboxAtomicityIntegrationTest|실제 API/TX/DB/PG, relay OFF. 즉시 SUCCESS·callback·reconcile의 최초 확정+예약+Outbox commit. encoder 실패·insert 후 실패·flush/확정 commit 실패의 전체 TX B rollback과 TX A 직전 상태·PG SUCCESS 보존|1·2·6|
|O4 같은 거래·비성공/종결 회귀|중복 callback/reconcile·경쟁에서 동일 시각/Outbox 1·예약 1회. UNKNOWN/PENDING/FAILED/충돌/POINT 신규 Outbox 0. rollback 뒤 같은 거래 재조회·적용, create 증가 0|1·2·8|
|O5 설정/adapter 계약|실제 qualifier/producer map·String serializer·비트랜잭션·전달 예산/오류 전파. 실제 broker value가 v1 object이고 저장 원문 UTF-8과 동일|3|
|O6 commit→relay 전 중단|실제 commit 후 PENDING·구매 활동 0. 같은 DB/broker의 새 relay가 저장 행에서 전달하고 실제 streamer APPLIED·완료 1·모든 품목 활동/수량·group committed offset·재고/포인트 추가 효과 0까지 확인. 직접 상태 덮어쓰기 없음|6|
|O7 OrderOutboxDeliveryEndToEndIntegrationTest|O6/O7/O9를 묶는 기본 DB E2E. O6와 같은 주문으로 실제 API/PG→Outbox→Kafka→streamer. 첫 실제 metadata 확보 뒤 probe 중단, PENDING 유지. 새 relay가 같은 사건 재발행·서로 다른 broker offset 2개 이상, consumer 수신 2회 이상·완료 1·품목 2행/수량 2·1 유지·재고/포인트 추가 효과 0|6·7|
|O8 timeout/늦은 완료·DB 오류|통제 CompletionStage와 Clock으로 timeout 뒤 늦은 성공/실패가 DB를 바꾸지 않음. broker 성공 뒤 상태 저장 실패/응답 불명은 별도 분류. 통제 Future 성공을 실제 broker 성공으로 표시하지 않음|3·6|
|O9 실패·BLOCKED·재개|통제 sender/Clock으로 5→10→20→40→60 상한·10회 예산·누적/회차 횟수·전송 없는 즉시 차단·중단 후 예산 판정·재개 새 예산/누적 보존·PENDING/PUBLISHED no-op. 실제 Kafka 실패→회복→동일 행 전달도 별도 확인|3·4|
|O10 단일 실행/종료|scheduler/run/resume/recover 경합의 BUSY·STOPPING·중복 send 없음, 예외/interrupt의 gate 해제, 복구 대상별 TX 전 종료 확인·기존 commit/미시도 보고, bean별 실제 stop/producer close 순서와 미완료 보고. 다중 프로세스 보장으로 확대하지 않음|3·4|
|O11 W6OutboxRecoveryEndToEndIntegrationTest|W7.4-1/2 전체 실행. 실제 W6 A/B 준비·같은 상태 전환·첫/두 번째 복구·A 생성/B 전체 불변·복구 PG 호출/거래/업무 증가 0. 이번 사건과 역사적 원본을 구분|5|
|O12 복구 충돌/부분 완료 통합|원본 부족/충돌·의미 표현 차이·기존 BLOCKED/PUBLISHED 불변·A commit/B 실패/미시도·입력/gate 결과. 실제 unique 최종 방어와 제약 오류 rollback/실패를 확인하며 새 TX 재확인/우회 경쟁 주입은 없음|5|
|O13 기존 회귀·정적 검사|기존 W5 주문/PG/예약/POINT·W6 소비 멱등성/실패·HTTP·안전 로그, API/streamer check의 Checkstyle·ArchUnit 및 실제 테스트 수 확인. 기존 유효 기대 유지|8|
|O14 보존/관찰|상태별 SQL·가장 오래된 PENDING·BLOCKED/eventId·안전 코드·broker metadata와 소비 결과 분리, 미해결/완료/dedup 자동 삭제 없음·기존 Kafka retention 실제 값 기록|4·8|

O3에서 BEFORE_COMMIT 예외나 SQL 제약으로 확정 rollback을 통제한 경우와 commit 응답 연결 오류로 결과가 불명인 경우를 구분한다. 후자는 새 조회의 실제 상태를 확인하며 rollback 완료라고 미리 쓰지 않는다. 지연 제약이 없는 MySQL에서 임의로 “deferred constraint commit 실패”를 주장하지 않는다. 오류 주입은 테스트 전용이며 제품의 우회 정책을 만들지 않는다.

#### W7.4-3a. 같은 전달 E2E의 O6→O7·O9 — 자원·관찰 계약

사용자가 기본 테스트 DB를 재사용하는 구성을 선택했다. OrderOutboxDeliveryEndToEndIntegrationTest의 한 연속 E2E 실행이 O6/O7/O9를 소유한다. O6와 O7은 같은 신규 W7 주문의 연속 흐름이며, 실제 전송 실패 O9는 별도 신규 주문으로 분리한다. 큰 E2E는 이 신규 전달 흐름과 O11의 W6 준비/복구 두 흐름이다. 모델/원자성/회귀/설정·종료 검증은 별도로 유지한다.

|실행 경계|DB·consumer·정리 계약|
|---|---|
|신규 전달 O6→O7/O9|기존 API 테스트 worker의 MySQL/Redis fixture·기본 loopers DB 사용. W7 정상 API context의 자동 relay OFF, 기본 테이블 create는 빈 업무 입력 준비에만 사용. API→실제 PG→Outbox와 streamer 자식 JVM 하나를 동일 DB/broker/topic/group으로 연결|
|명시 W6 복구 O11|w6OutboxRecoveryTest의 별도 새 worker/빈 fixture DB. 기존 W6 A/B 준비→같은 상태에 W7 API update, 실제 streamer 한 프로세스는 끝까지 유지. W7.4-1/2의 계약 그대로|
|발행 전용 O5/O10|기존 broker 검증의 MySQL 컨테이너 안 order_event_kafka 임시 스키마·직접 Kafka 관찰 client 사용. 업무 streamer를 연결하지 않으며 소유 스키마만 최종 삭제. W6의 발행 전용 승인 범위를 streamer까지 확대하지 않음|

신규 전달 E2E는 C10의 자원/child 실행·관찰/정리 함수를 활용하되 W6 initializer는 O11 준비에만 쓴다. 신규 전달에는 정상 제품 recorder/relay를 연결한다. 새 범용 실행/bean 교체 프레임워크·제품의 우회 스위치는 만들지 않는다. API test의 streamer bootJar 선행 작업·입력/경로 전달을 유지하며, streamer를 API runtime classpath로 넣지 않는다.

부모는 API용 MySQL/Redis·PG fixture·고유 2-partition topic/group·child log/process를 소유한다. DB를 쓰는 관련 테스트는 직렬 실행한다. API 스키마 준비 뒤 child 기동 전에 구매 테이블 두 개가 없음을 확인하고, child는 test 프로필/동일 datasource·Redis/일반 및 admin Kafka 주소·고유 topic/group·demo OFF·자기 두 Entity의 ddl-auto=create로 한 번만 시작한다. 준비 완료·실제 member/partition 배정과 완료/활동 0행을 확인한 뒤 주문을 입력한다. 잘못 남은 구매 테이블/행을 조용히 삭제해 시작 조건을 꾸미지 않는다. 같은 child를 세 장면 사이에 재시작하거나 DB를 초기화하지 않는다.

~~~text
신규 전달 E2E: // 부모 TX 없음, 전체 300초 유한 경계
  실제 API/PG·정상 W7 context·실제 streamer 준비, 자동 relay OFF

  주문 D = 기존 API/PG SUCCESS로 확정
  PG 요청/콜백 작업 idle 확인 → D 확정 뒤의 PG/업무 기준 고정
  새 SQL/관찰: Outbox PENDING 1·attempt 0·확정/예약 보존·D 구매 활동 0
  아직 실행하지 않은 relay 대신 새 relay R1 생성 // O6
    같은 TX bean 프록시·verifier·sender·공유 gate 사용
  R1.runDueBatch:
    저장 행에서 조회/실제 send → 실제 broker Future 성공/metadata
    D만 대상인 one-shot probe가 PUBLISHED 저장 전 중단 // O7 출발
  R1 종료/finally gate 해제 확인
  실제 첫 record 위치·D APPLIED·group offset·완료 1·품목 활동 2행/수량 2·1 확인
    // O6의 실제 구매 활동 회복 확인; 여기 PENDING은 이어진 O7 중단의 결과
  저장 PENDING·attempt 1·불변 identity/payload/업무 효과 확인
  새 relay R2 생성, 저장 nextAttemptAt 전에는 재전송 없음
  가변 outbox Clock을 그 시각까지 전진 후 R2.runDueBatch
  D의 다른 실제 offset·ALREADY_PROCESSED·group offset·구매/재고/포인트 불변 확인
  최종 Outbox PUBLISHED 1, D의 첫/재발행 증거를 대응

  D의 전송/소비·미완료 작업이 없음을 확인
  주문 E = 별도 API/PG SUCCESS, 별도 eventId·Outbox PENDING
  PG 요청/콜백 작업 idle 확인 → E 확정 뒤의 PG/업무 기준 별도 고정
  이 실행 소유 topic의 기존 설정을 기록하고 min.insync.replicas로 실제 실패 1회 통제
  실제 send Future 실패·같은 Outbox의 오류/시도·PENDING 확인 // broker 수신 0 단정 없음
  topic 원래 설정 복원/확인 → E의 nextAttemptAt까지 outbox Clock 전진
  같은 행의 relay 전달·실제 consumer/group offset·완료/모든 품목 활동 확인 // O9
  각 확정 뒤 relay 전달/재발행 중 PG/업무 추가 효과 0과 실제 식별자/증거 기록
  모든 확인 또는 실패 뒤 finally에서 소유 자원 정리
~~~

outboxRecordClock은 기존 SettableClock처럼 전진 가능한 테스트 Clock을 주입한다. 사건 orderConfirmationClock/confirmedAt·감사 시각·실제 Kafka/consumer/deadline 시간을 바꾸지 않는다. O6은 첫 시도 전 due 상태, O7은 시도 TX가 남긴 미래 nextAttemptAt을 따르는 상태다. 행의 status/nextAttemptAt/카운터를 직접 수정하지 않는다. one-shot probe는 D의 eventId/outboxId에만 적용해 O9 주문이나 준비 전송을 중단하지 않는다.

R1/R2는 같은 제품 클래스의 새 객체와 기존 협력 bean/공유 gate를 사용한다. 이전 실행 thread 종료/해제를 확인한 뒤 다음을 실행하고 다른 gate를 별도로 만들지 않는다. 실제 lifecycle STOPPING은 O10에서 따로 검증한다. 이 범위는 객체/실행 흐름 재생성이며 전체 API/JVM/OS 강제 종료라고 표현하지 않는다.

첫 D record의 APPLIED로 O6 회복을 확인하고, 같은 사건의 재발행 ALREADY_PROCESSED로 O7 수렴을 확인한다. 중간 PENDING을 소비 미완료로 해석하지 않으며 최종 PUBLISHED와 완료 1/품목 효과 불변을 각각 확인한다. 같은 사건·같은 실험 단계이므로 관찰 중복은 재사용할 수 있지만 각 중단 직전/직후 기대를 생략하지 않는다.

broker record는 eventId/key/본문·서로 다른 실제 offset을 확인한다. 실제 child의 ORDER_ACTIVITY_HANDLED_AND_ACK_RETURNED에서 각 topic/partition/offset/eventId/result를 대응하고 새 SQL로 DB 효과를 확인한다. 이 로그는 ACK 호출의 정상 반환이므로 Admin의 실제 group committed offset도 마지막 처리 offset+1 이상인지 별도로 기다린다. send 호출 수·같은 offset 재전달 로그 둘로 재발행 두 번을 주장하지 않는다. O11의 B도 최초/복구 후 새 위치와 ALREADY_PROCESSED·전체 저장값 불변을 대응한다.

최종 정리는 신규 relay/진입을 멈추고 실제 child writer/process 종료를 확인한 뒤 소유 API 데이터/Redis·API context·PG fixture·구매 테이블 두 개 DROP·소유 topic/group/client 순으로 수행한다. API와 child가 살아 쓰는 동안 TRUNCATE/DROP하지 않는다. 생성 성공 자원을 추적하고 부분 실패에도 모든 정리를 시도해 첫 오류/suppressed 오류를 남긴다. 공유 broker/개발 DB/다른 실행 자원은 정리하지 않는다. API datasource가 이미 닫힌 실패 경로는 유지된 테스트 접속 정보의 새 SQL 연결로 소유 데이터/구매 테이블만 정리한다.

전체 300초 예산 안에서 단계별 대기는 남은 전체 예산과 각 기존 deadline의 작은 값으로 제한한다. 실제 broker 장애 send는 1회이고 간격/10회 예산 계산은 통제 sender/Clock으로 확인한다. 각 stage의 eventId·DB/broker/PG 기준/증거·실패 단계를 분리해 같은 자원을 쓴다는 이유로 다른 주문/옛 로그를 근거로 삼지 않는다. 테스트 성립/처리량·실제 종료/정리 성공은 아직 미실행이다.

실행 때는 이전 실행 잔여 구매 테이블/행으로 시작 조건이 실패했는지 알아볼 수 있는 안전 진단을 남긴다. O9는 min ISR 오류의 실제 Future 실패/예외 분류와 같은 producer의 회복 전송을 관찰하며 35초 로컬 timeout을 반드시 만들었다고 가정하지 않는다. 계약상 실제 전송 오류/timeout은 해당 안전 코드와 PENDING/예산으로 처리한다. 단계별 실제 경과 시간은 테스트용 outbox Clock과 분리해 기록하고, 예산 초과/추가 producer 오류를 skip·기대 완화·임의 producer 교체로 숨기지 않는다.

O9의 실제 Kafka 실패는 소유 topic의 min.insync.replicas를 단일 broker 수보다 크게 설정하는 통제 등으로 실제 send Future 실패를 관찰하고 원래 설정으로 finally 복원한다. 다른 topic/broker를 멈추지 않는다. 실패 시 broker 기록 0을 단정하지 않고 회복 뒤 같은 eventId의 실제 구매 활동 수렴을 확인한다. 열 번의 긴 broker 장애를 반복하는 대신 예산 소진/시간 계산은 통제 sender·Clock으로 검증하고 실제 연결 검증과 분리한다. 실제 장애 구간은 자동 scheduler OFF에서 의도한 실패 send 1회로 제한한다. delivery 30초·send 호출 metadata/buffer 대기와 Future 35초를 전체 300초 deadline 안에 반영하고, 설정 복원 확인 뒤 같은 행을 다시 처리한다. 열 번의 실 broker 실패로 예산 소진을 반복하지 않는다.

O12의 잘못된 저장 사실/본문 SQL fixture는 해당 거절 검증에만 사용한다. O11의 W6 성공 상태를 SQL로 만들거나 Outbox를 삭제하는 준비로 재사용하지 않는다. uk_oco_event 중복 insert는 저장 경계에서 제약/rollback을 확인하되 잠금 우회 경쟁을 정상 EXISTS로 변환하는 분기는 검증/구현하지 않는다.

### W7.4-4. 회귀 변경 경계와 실행 증거

사용자는 F2 A안으로 C10의 준비/검증을 O11에 흡수하기로 선택했다. 기존 C10 파일은 소스/과거 참조를 보존하는 지원 코드로 남기고 독립 @Test·@BeforeAll/@AfterAll 진입을 O11로 옮긴다. O11이 자원 수명을 소유하고 원래 assertUnpublished/assertConsumedOnce와 상품 삭제·새 SQL·전체 partition 구간·실제 consumer/offset·고정 사건 검증을 **복구 전** 모두 수행한다. 복구 성공으로 출발 상태 검증을 대신하지 않는다. 독립 실행 중복만 줄이며 과거 실행 기록은 수정하지 않는다.

현재 W6 타입 직접 참조 19개 파일에 지원 코드 참조/상속을 추적하면 33개 파일이 연결된다. 아래 경로의 기준은 apps/commerce-api/src/test/java/com/loopers/다. 이는 실행 건수가 아니라 정적 영향 파일 목록이며 신규 테스트 33개를 요구하지 않는다. 구현 때 추가 참조가 발견되면 같은 분류로 보강하고 유효 기대의 보존을 보고한다.

|현재 파일|처리|보존/대체 검증|연결|
|---|---|---|---|
|application/order/OrderConfirmedEventListenerTest.java|W6 보존|W6 listener 단위 기대(순서·안전 로그·RuntimeException/Error) 유지. 아래 실제 TX listener 검증도 연결|O13/W6|
|infrastructure/order/KafkaOrderEventPublisherTest.java|W6 보존|보존 W6 adapter/port로 타입 연결. v1/오류/metadata의 유효 기대 유지|O13/W6|
|config/OrderEventKafkaConfigTest.java|W6 보존 + W7 대응|W6 JSON 설정 검증 유지. W7 String 설정/호환 거절은 O5에 대응|O5/O13|
|config/OrderEventKafkaContextTest.java|W7 전환|실제 String sender/template/factory·qualifier·공통 bean 불변 검증으로 변경. graceful/phase 예산 기대 유지|O5/O10|
|infrastructure/order/OrderConfirmedSendFailureIntegrationTest.java|W7 전환|확정 불변·안전 오류 기대 유지, relay 동기/Future 실패·늦은 완료·PENDING/BLOCKED 저장으로 대체|O8/O9|
|infrastructure/order/OrderEventKafkaBrokerIntegrationTest.java|W7 발행 전용 전환|임시 스키마에서 O5 실제 wire/metadata·O10 실행/종료 검증. 업무 streamer가 필요한 O6/O9는 통합 전달 E2E로 이관하고 W6 누락은 O11 준비에 보존|O5/O10·이관 O6/O9/O11|
|application/payment/OrderConfirmedPublicationIntegrationTest.java|W7 전환 + W6 전용 분리|실제 Outbox·비성공/중복·rollback 기대. TX 밖 이벤트/rollback 때 listener 미실행 등 AFTER_COMMIT 전용 기대는 W6 listener TX 검증에 보존|O3/O4/O13|
|application/payment/OrderConfirmedPublicationHttpIntegrationTest.java|W7 전환|즉시/콜백/대사 HTTP 뒤 새 연결의 주문·Outbox commit·사건/시각·중복 불변. 포트 hook을 commit 근거로 쓰지 않음|O3/O4|
|application/payment/OrderConfirmedAtIntegrationTest.java|W7 유지/연결|Clock 설정 참조 이동, 최초 시각/경쟁/rollback/SUCCESS 거래 키 unique 기대 유지·Outbox rollback 확인|O3/O4|
|application/payment/PaymentObservationOrderIntegrationTest.java|W7 유지/연결|가짜 발행 교체 제거, 실제 Outbox·자동 relay OFF. 기존 관찰 순서/종결 기대 유지|O4/O13|
|application/payment/PaymentOverlapEvidenceIntegrationTest.java|W7 유지/연결|기존 교차 실행/증거·PG/예약 기대 유지, 발행 fake 의존 제거|O4/O13|
|application/payment/PaymentParentLockIntegrationTest.java|W7 유지/연결|첫 SQL/부모 잠금·경쟁 기대 유지, 실제 Outbox와 최초 CONFIRM 한 번 확인|O4/O13|
|application/payment/PaymentTransactionServiceIntegrationTest.java|W7 유지/연결|TX A/B/충돌·PENDING 거래 키 unique 회귀 보존, 실제 Outbox 연결|O3/O4/O13|
|domain/product/ProductCacheInvalidationIntegrationTest.java|W7 유지/연결|발행 fake 제거. 가격/캐시 commit/과거 주문/rollback 기대 그대로 유지|O13|
|fixture/PgTestSupport.java|W7 지원 전환|공통 fake 발행 @TestBean·기록 reset/getter 제거. PG idle/reset/회로 초기화는 유지, 실제 Outbox 사용·자동 relay OFF|O13|
|testsupport/OrderEventRecordingInitializer.java|W7 용도 퇴역/보존 검사 이관|W7 producer 교체 initializer는 제거. 단일 포트/명시 교체 검사는 W6 조립·listener 검증에 이관|O13/W6|
|testsupport/RecordingOrderEventPublisher.java|W6 지원 보존|W6 port 타입으로 연결해 listener TX 검증의 관찰 도구로만 사용. W7 확정 성공 대역으로 사용하지 않음|O13/W6|
|testsupport/RecordingOrderEventPublisherTest.java|W6 지원 보존|위 helper의 기록/reset/진행 hook 검증을 유지|O13/W6|
|infrastructure/order/OrderEventEndToEndIntegrationTest.java|독립 실행 퇴역·O11 흡수|파일/준비 함수·관찰·assert를 지원 코드로 보존, 독립 JUnit 진입/수명주기는 O11로 이동. A/B 모든 기존 기대를 복구 전에 단언|O11|
|application/payment/PaymentRestartIntegrationTest.java|W7 유지/연결|OrderEventRecordingInitializer 호출 제거. 동일 DB/PG 재기동·재조회·create 증가 0 기대 유지, 실제 Outbox 사용|O13|
|application/order/OrderConcurrencyIntegrationTest.java|상속 영향 확인|PgTestSupport 전환을 사용하며 기존 경쟁 기대 유지|O13|
|application/order/OrderConfirmFacadeIntegrationTest.java|상속 영향 확인|PgTestSupport 전환을 사용하며 기존 확정/소유권/PG 기대 유지|O13|
|application/order/OrderTransactionIntegrationTest.java|상속 영향 확인|PgTestSupport 전환을 사용하며 기존 TX/재고 기대 유지|O13|
|application/payment/PaymentHttpTestSupport.java|상속 지원 전환|PgTestSupport의 실제 Outbox 구성을 상속. 포트 기록 관찰을 요구하지 않으며 HTTP/PG fixture 계약 유지|O13|
|interfaces/api/CustomerPurchaseFlowE2ETest.java|상속 영향 확인|기존 고객 전체 흐름·금액/예약/POINT 기대 유지|O13|
|interfaces/api/order/OrderAdminV1ApiTest.java|상속 영향 확인|기존 관리자/과거 주문 조회·권한 기대 유지|O13|
|interfaces/api/order/OrderV1ApiE2ETest.java|상속 영향 확인|기존 주문 API/소유권/PG/POINT 기대 유지|O13|
|interfaces/api/payment/PgPaymentApiIntegrationTest.java|상속 영향 확인|기존 PG API/상태/오류 기대 유지|O13|
|application/payment/PaymentCircuitTimingIntegrationTest.java|간접 상속 확인|PaymentHttpTestSupport 전환. 기존 회로/시간 예산 기대 유지|O13|
|application/payment/PaymentRecoveryFlowIntegrationTest.java|간접 상속 확인|기존 같은 거래 재조회·결과 반영·외부 create 증가 0 기대 유지|O4/O13|
|application/payment/PaymentReleaseCacheIntegrationTest.java|간접 상속 확인|기존 예약 반환·캐시 commit/rollback 기대 유지|O13|
|application/payment/PaymentStoredConsistencyIntegrationTest.java|간접 상속 확인|기존 저장 정합성·오류 기대 유지|O13|
|infrastructure/payment/PgPaymentResilienceIntegrationTest.java|간접 상속 확인|기존 HTTP/회로/장애 기대 유지, Outbox 오류를 PG 실패 표본으로 오분류하지 않음|O13|

W6 listener의 실제 TX 검증은 보존 listener·기록용 W6 port·Spring 이벤트/트랜잭션을 명시 조립한 작은 테스트 context에서 수행한다. commit 뒤 한 번·rollback 때 0·TX 밖 등록 때 0의 유효 기대를 유지한다. 제품 전체를 W6로 바꾸거나 fake를 W7 원자성 근거로 쓰지 않는다. 기존 파일별 @TestBean(enforceOverride=true)·Clock 상수·listener 주입·initializer·관찰 hook을 이 표대로 제거/이관하되 대응 검증을 먼저 명시한다. C10 외 테스트를 넓은 패턴으로 제외하거나 유효 기대/규칙을 삭제하지 않는다.

W7 회귀는 자동 relay OFF·실제 Outbox 저장을 기본으로 한다. PG/소유권/예약/POINT·HTTP 제한·소비의 유효 기대는 유지하고, AFTER_COMMIT 직접 발행의 위치 의존 기대만 보존 W6 또는 O-ID에 대응한다. 모든 계층에서 같은 Outbox 조합을 반복하지 않고 O3/O4에서 원자성·최초 한 번을 집중 확인한다. callback 수신 1초 등 유효 제한을 편의상 늘리지 않는다.

실제 E2E는 streamer bootJar의 별도 JVM·실제 listener factory/parser/handler를 사용한다. API runtime→streamer 의존이나 가짜 구매 handler를 추가하지 않는다. 신규 전달 O6→O7/O9 클래스와 별도 O11은 각각 고유 topic/group·합성 주문·실제 commit·새 DB 조회를 소유하고, O6/O7은 같은 사건과 자원을 공유한다. 배정은 준비 조건이며 W6 배정/성능 실험을 새 필수 실험으로 반복하지 않는다.

broker readiness 30초·consumer/group 준비와 결과 관찰 60초·전체 E2E 300초의 유한 경계를 사용한다. 단계별 남은 전체 예산을 넘기지 않고 deadline 때 DB/PG/Outbox·topic/group/offset·자식 process·안전 로그를 남긴다. 임의 sleep만으로 준비/수렴을 판정하지 않는다. 재시도 계산은 통제 Clock/짧은 테스트 값, 실제 producer 대기/실패는 실제 시간 예산으로 구분한다.

실행 기록은 revision·실제 설정/fixture/자원·O-ID·실제 건수/skip/failure·단계별 DB/broker/PG·정리 결과·한계를 포함한다. 0건/skip/연결 실패/과거 통과를 이번 성공으로 쓰지 않고, 통제 중단을 OS 강제 종료라 하지 않는다. PUBLISHED·broker 기록·consumer 수신·구매 효과는 별도 근거다. 제출 필수 계약/근거는 Git에 포함할 기준 문서/테스트에 남긴다.

### W7.4-5. 보완본의 승인·실행 상태

계약·모델·클래스/메서드·내부 의사코드·오류/재개·W6 보존·검증 기준에 세 차례 Claude 검토 대조와 사용자 선택을 반영했다. 3차 정적 검토는 G1~G5 해결·구현 전 계약 결함 0·새 사용자 결정 없음으로 정리했고, 통제 예외 전파/복구 장벽의 위치를 마지막으로 명확히 했다. 현재 새 미정 정책은 발견하지 못했으며, 2026-11-04 사용자가 최종 요약 및 원문 부합 검토 뒤 전체 설계를 최종 승인했다. 구현/실행 검증과 구현 단위별 착수 허락은 남아 있다. 실제 누락/모순은 추측하지 않고 질문한다.

현재 코드/설정은 여전히 W6다. W7 구현·컴파일·DDL/EXPLAIN·실제 broker/PG/E2E·회귀/Checkstyle/ArchUnit·종료 동작은 모두 미실행이다. 문서 정적 대조는 이 검증을 대신하지 않는다. 단일 API 프로세스의 relay·단일 broker·명시 대상 복구·자동 삭제 없음·immutable 원본 손상 자동 수리 없음이 현재 한계다. 제품의 별도 relay 실행물·분산 claim/lease·DLQ·관리 HTTP·전체 과거 주문 소급·새 성능 실험은 추가하지 않는다.
