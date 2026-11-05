# 주문 확정 이벤트 구현·실행 기록

**W7 읽기 구분:** 아래 본문과 명령/관찰값은 W6 당시 revision·실행 조건의 이력이다. W7 전환 뒤에는 C10 파일을 O11의 준비/검증 지원 코드로 보존하고 독립 @Test를 옮기므로, 아래 C10 이름의 필터를 현재 W7 재현 명령으로 사용하지 않는다. 기존 broker 테스트도 W7 발행 전용 O5/O10으로 역할이 바뀐다. 과거 결과·수치·본문은 그대로 보존한다.

W7 재현 진입점은 다음과 같다. 2026-11-05 최종 실행 결과·O-ID별 근거·한계는 문서 끝 [W7 Outbox 실행 근거](#w7-outbox-실행-근거)에 있다.

~~~bash
# 기본 테스트 DB·실제 streamer: 같은 주문 O6→O7, 별도 주문 O9
./gradlew :apps:commerce-api:test --tests '*OrderOutboxDeliveryEndToEndIntegrationTest'
# 발행 전용 임시 스키마: O5/O10
./gradlew :apps:commerce-api:test --tests '*OrderEventKafkaBrokerIntegrationTest'
# 새 worker·빈 DB의 W6 두 상태 준비 → 같은 상태의 W7 복구 O11
./gradlew :apps:commerce-api:w6OutboxRecoveryTest
~~~

현재 계약·자원·증거 기준은 [발행 W7.4](../features/order-events/publication.md), 선택/보완 이유는 [A.40·A.41](../decisions/order-events.md)을 따른다. 위 명령 자체나 이 안내는 통과 근거가 아니며, 실행 근거는 W7 절의 기록이다.

2026-10-29 승인 설계의 구현과 단위별 실행 결과를 정리한다. 계약의 원본은 [이벤트 계약](../features/order-events/README.md)·[발행 설계](../features/order-events/publication.md)·[소비 설계](../features/order-events/consumption.md)이며, 이 문서는 실행 조건·관찰값·한계를 보존한다.

## 범위와 근거의 시점

발행 P1~P9·소비 C1~C10의 구현과 관련 검증을 수행했다. 모든 가능한 장애를 재현했다는 뜻은 아니다. 아래 결과는 당시 실행 기록과 Codex가 확인한 산출물을 정리한 것이며, 이번 문서 작성 중 테스트·DB·broker를 다시 실행하지 않았다.

|구현을 보존한 revision|범위|
|---|---|
|`e271b27`|최초 confirmedAt·불변 확정 사건과 주석 정리|
|`779512f`|AFTER_COMMIT·전용 발행 adapter·회귀 fake 적용 범위 축소·전송 오류 검증|
|`46f24b6`|실제 broker 발행 P4·P6~P8·종료·정리 보완|
|`71d9a79`|소비 C1~C5 모델·파서·단일 DB TX·중복/충돌|
|`bfa9830`|C6~C9 listener·실제 전달·실패 복구·재전달·partition 배정|
|`631b3c5`|C10 실제 PG 종단·demo 비활성화·bootJar 입력 추적|
|`7daea9c`|C10 테스트 단계 분리·broker/C10 공통 정리 helper 분리. 제품 코드·검증 의미는 바꾸지 않음|

각 구현의 검증은 커밋 전 작업 트리에서 실행했다. 예를 들어 C10 전체 회귀는 `bfa9830`에 C10 변경을 더한 상태, Gradle 보완 검증은 여기에 입력 선언을 더한 상태이며 최종 구현이 `631b3c5`에 보존됐다. 이미 커밋된 revision을 새로 checkout해 실행한 결과로 표현하지 않는다.

실습의 제출 요구는 PR에 대표 주문 활동 결과·배정표·실제 offset 전후·DB→broker 누락 구간과 한계를 남기고, 다음 주를 위한 두 사건의 재현 코드·실제 식별자를 보존하는 것이다. XML 원본의 Git 보관은 명시된 요구가 아니다. 이 문서가 관찰 근거를 보존하며 PR 본문에도 핵심 결과를 요약해야 한다. PR·기술 글·멘토 질문·WIL의 작성·제출 완료를 뜻하지 않는다.

## 재현 방법과 자원

전제는 Docker와 공유 로컬 Kafka다. broker 기본 호스트 주소는 `localhost:19092`이며 다른 주소는 `TEST_KAFKA_BOOTSTRAP_SERVERS`로 전달한다. 개발용 MySQL에는 연결하지 않는다.

```bash
# 실제 발행·종료 경계
./gradlew :apps:commerce-api:test --tests '*OrderEventKafkaBrokerIntegrationTest'

# 소비 모델·파서·DB 및 실제 listener·재전달·배정
./gradlew :apps:commerce-streamer:check

# 실제 PG부터 별도 streamer JVM까지, 미발행/정상 사건 함께 재현
./gradlew :apps:commerce-api:test --tests '*OrderEventEndToEndIntegrationTest'

# 관련 전체 검사
./gradlew :modules:kafka:check :apps:commerce-api:check :apps:commerce-streamer:check
```

Gradle이 UP-TO-DATE·FROM-CACHE로 건너뛴 작업을 새로운 실행 통과로 세지 않는다. 새 실행이 필요하면 해당 작업의 재실행 여부를 확인한다. 필터 실행은 같은 앱의 `build/test-results/test`를 대체할 수 있으므로 전체 회귀의 XML·HTML을 별도로 보관하려면 다음 필터 실행 전에 복사한다. 테스트 0건·skip·접속 실패는 통과가 아니다.

- Kafka: 로컬 Docker `bitnamilegacy/kafka:3.5.1`, broker 1대, Kafka clients 3.8.1·Spring Kafka 3.3.4. 실행 당시 broker는 `auto.create.topics.enable=true`, `min.insync.replicas=1`, 복제 계수 기본값 1이었다. 전역 설정은 변경하지 않았다.
- `LocalKafkaTestSupport`가 명시적으로 만든 실행별 topic/group만 사용한다. topic은 partition 2·복제 계수 1이며 업무·준비·배정 실험 topic을 분리한다. 전용·직접 테스트 consumer의 `allow.auto.create.topics=false`를 실제 map에 둔다.
- 기존 MySQL Testcontainers fixture(`mysql:8.0`, 관찰 버전 8.0.46)와 Redis fixture를 재사용한다. 독립 소비 컨텍스트는 같은 `loopers` DB에 자기 Entity 두 테이블만 create하고 시작 전 0행을 확인한다. DB를 공유하는 관련 작업은 직렬 실행한다.
- P4·P6~P8 발행 테스트만 사용자 승인된 같은 MySQL 컨테이너의 임시 스키마 `order_event_kafka`를 사용한다. 소비·C10은 이 예외를 사용하지 않는다.
- C7 재시작·C8 consumer 교체 사이에는 같은 DB 컨텍스트와 데이터를 유지한다. C10은 API 스키마 준비 후 하나의 자식 streamer JVM을 띄워 두 사건을 끝까지 검증한다.
- 실행 소유 listener·client·자식 JVM·PG를 먼저 종료하고 group 비활성 확인 후 소유 group/topic을 삭제한다. 공유 broker·볼륨·다른 실행 자원은 정리 대상이 아니다. 실행 후 잔여 test topic/group 0은 당시 Claude의 정리 확인 결과이며 이번 문서 작업에서 재조회하지 않았다.

## 기준별 구현과 검증의 연결

테스트 파일은 `apps/commerce-api/src/test/java/com/loopers` 또는 `apps/commerce-streamer/src/test/java/com/loopers` 아래에 있다. 순수 단위 검증과 실제 DB·broker 검증을 구분한다.

|기준|대표 테스트|확인한 경계|
|---|---|---|
|P1|`OrderConfirmedTest`, `OrderPgPaymentTest`|최초 확정 시각·사건 값·상태 규칙, UTC·마이크로초|
|P2·P3|`OrderConfirmedPublicationIntegrationTest`, `OrderConfirmedPublicationHttpIntegrationTest`|commit 뒤 사건 전달, 별도 DB 연결의 committed 상태, 제외 경로·rollback 미발행. 기록용 fake로 실제 broker 저장을 주장하지 않음|
|P4|`OrderConfirmedAtIntegrationTest`, `OrderEventKafkaBrokerIntegrationTest`|DATETIME(6) 왕복, 실제 wire·key·저장 품목·시각 일치|
|P5|`OrderEventKafkaConfigTest`, `OrderEventKafkaContextTest`, `OrderConfirmedSendFailureIntegrationTest`|전용 비트랜잭션 설정·send 호출/Future 오류와 주문 확정 유지. 오류 단위 검증의 template 대역과 실제 broker 검증은 별개|
|P6|`OrderEventKafkaBrokerIntegrationTest`|즉시 성공·callback·reconcile 실제 발행, 반복 적용 후 새 record 없음|
|P7|같은 broker 테스트와 `OrderEventEndToEndIntegrationTest`|commit→send 공백, 두 partition의 고정 구간과 새 DB 조회|
|P8|`OrderEventKafkaBrokerIntegrationTest`, `OrderEventKafkaBrokerCleanupTest`|실제 정상 종료·close 예산 소진, 정리 runner(`OrderEventTestCleanup`)의 부분 실패 단위 검증|
|P9|API 전체 check|기존 계약 회귀·Checkstyle·ArchUnit. fake는 실제 최초 PG 확정 경로에 명시 적용, 전역 guard·공통 부모 제거|
|C1|`OrderConfirmedParserTest`, `OrderConfirmationTest`|엄격 UTF-8·JSON 타입·중복 필드·trailing token·ID·시각·양수·overflow·같은 사실 정규화|
|C2·C3|`PurchaseActivitySchemaIntegrationTest`, `PurchaseActivityHandlerIntegrationTest`|실제 DDL·유일/CHECK·정상 효과·동일 사실 no-op·충돌·저장 손상 우선 검사|
|C4|`PurchaseActivityConcurrencyIntegrationTest`|실제 unique 잠금 경쟁·패자 rollback 뒤 새 TX 재확인·승자 rollback 후 삽입|
|C5|`PurchaseActivityRollbackIntegrationTest`, `PurchaseActivityHandlerTest`|품목 중간/commit 전 실패의 전체 rollback, 활동 unique·CHECK·기술 오류를 완료 중복으로 삼키지 않음|
|C6·C7|`OrderActivityKafkaConsumerTest`, `OrderActivityKafkaIntegrationTest`|실제 전용 설정·handler 종료 후 ack·DB 실패 중단·동일 group 복구|
|C8|`OrderActivityKafkaIntegrationTest`|DB 성공 후 offset 공백, 실제 같은 record 재전달·전체 저장 컬럼 불변|
|C9|`PartitionGroupAssignmentKafkaTest`|실제 poll·broker/member 양쪽 배정 확인·명시 partition 소비·key metadata|
|C10|`OrderEventEndToEndIntegrationTest`|실제 PG·API·broker·별도 실제 streamer 앱·새 DB 조회, 미발행/정상 소비·삭제 상품|

## 발행과 종료의 실제 관찰

고정 확정 Clock 입력은 `2026-10-29T01:02:03.123456789Z`(zone Asia/Seoul)이며 저장·wire는 `2026-10-29T01:02:03.123456Z`다. 주문마다 단가 2,000·500인 상품 둘, 수량 2·1, 합계 4,500으로 실행했다.

P6의 한 실행에서 즉시 성공 주문 1은 partition 1@0(key `1`), callback 주문 2는 0@0(key `2`), reconcile 주문 3은 1@1(key `3`)에 기록됐다. 각 주문의 저장 품목·수량·시각과 실제 value의 6필드·타입을 대조했고 type header가 없었다. 세 주문에 callback·reconcile을 반복해도 업무 record는 3건 그대로였다. callback은 기존 1초 응답 제한 아래 HTTP 200을 받았다. 이 위치는 해당 실행 관찰이며 다음 실행의 partition/offset 기대값으로 고정하지 않는다.

P7은 대상 사건의 commit 후 beforeSend hook에서 통제 실패를 주입했다. 별도 연결이 CONFIRMED·확정 시각·paid 4,500·결제 SUCCESS·예약 2·반환 0을 확인했고 send 수는 증가하지 않았다. 반복 결과 적용과 validate 재기동 후에도 재발행은 없었다. 두 partition의 제한 구간을 끝까지 읽어 0건을 확인했다. 구매 활동까지 포함한 두 사건은 아래 C10에서 관찰했다.

P8 보완 후 정상 close는 183ms, 예산 소진 close는 15,031ms였다. 전용 producer의 고유 client.id로 해당 IO 스레드 종료를 확인했다. factory physicalCloseTimeout은 15초, lifecycle phase 대기는 30초였으며 합산한 전체 종료 보장은 아니다. 예산 소진은 소유 topic에만 min.insync.replicas=2를 적용해 단일 broker가 acks=all 쓰기를 거절하게 만든 통제 조건이다. 주문은 확정 상태를 유지했고 전송 실패 로그가 남았다. 실제 복제 장애나 모든 전송의 결과 확보를 입증하지 않는다.

정리 실패 검증 2건은 정리 runner의 첫 단계 실패 뒤 후속 단계를 시도하고 추가 오류를 suppressed로 보존하는 단위 테스트다. 실제 PG close 장애를 재현한 결과는 아니다.

## 소비 DB·경쟁·원자성의 실제 관찰

실제 information_schema와 SHOW CREATE TABLE로 다음 매핑·제약을 확인했다. Hibernate validate만으로 제약을 검증했다고 하지 않는다.

|테이블|확인한 저장·제약|
|---|---|
|`processed_order_confirmation`|event_id varchar(64) ascii/ascii_bin, event_type varchar(32) ascii/ascii_bin, order_id·schema_version·item_count·confirmed_at datetime(6), 기본 감사 컬럼. `uk_poc_event`, `uk_poc_order`, 양수 order_id·item_count CHECK|
|`product_purchase_activity`|order_id·product_id·quantity bigint, confirmed_at datetime(6), 기본 감사 컬럼. `uk_ppa_order_product`, 양수 order_id·product_id·quantity CHECK|

두 테이블에 현재 Product/Order FK·groupId·처리 상태 컬럼은 없다. 직접 SQL의 중복은 MySQL 1062, CHECK 위반은 3819, 비 ASCII eventId는 1366으로 거절됐다.

- 정상 효과: 주문 42의 상품 7×2·8×1은 활동 2행. 같은 사실 재처리는 ALREADY_PROCESSED이고 행 불변. 주문 43의 상품 7×3 추가 후 완료 2·활동 3·상품 7 수량 합 5였다.
- 충돌·손상: 수량/품목/시각 차이는 FACT_CONFLICT, 잘못된 저장 ID/type/version·품목 누락·고아 활동 등은 STORAGE_INTEGRITY_ERROR로 기존 snapshot 불변이었다. 다른 입력 사실이어도 저장 손상을 먼저 검사했다.
- 경쟁: beforeCommit 장벽으로 승자 TX를 잡고 실제 `performance_schema.data_locks`에서 패자의 `uk_poc_event` 대기를 관찰했다. 승자 commit 후 패자는 `DataIntegrityViolationException → Hibernate ConstraintViolationException(UNIQUE, processed_order_confirmation.uk_poc_event) → SQLIntegrityConstraintViolationException(23000, 1062)`를 받았다. 실패 TX 종료 뒤 새 readOnly TX에서 같은 사실을 확인해 no-op으로 수렴했다. 승자 rollback 때는 패자가 APPLIED였다. 실제 `uk_poc_order` 선행 경쟁은 관찰하지 못했고 분류기 단위 검증만 있다.
- 원자성: 첫 활동 뒤·두 번째 뒤·commit 직전의 미commit 행은 각각 (완료, 활동)=(1,1)/(1,2)/(1,3)이었고 확정 rollback 뒤 모두 (0,0)이었다. 재처리는 정상 반영됐다. 실제 활동 unique·CHECK 오류는 완료 중복으로 바꾸지 않았다.
- 잠금·미commit 관찰만 같은 fixture의 root 읽기 연결을 사용했다. 업무 쓰기는 test 계정이며 권한·별도 스키마를 추가하지 않았다.

## 전용 consumer 유효 설정

C6의 실제 container와 C7의 `commerce-streamer-0` ConsumerConfig 출력에서 확인했다. 공통 batch factory는 batch=true·concurrency 3·MANUAL 그대로다.

|항목|전용 listener의 확인 값|
|---|---|
|record·병렬성|batch=false, concurrency=1, max.poll.records=1|
|역직렬화|StringDeserializer / ByteArrayDeserializer|
|자동 처리|enable.auto.commit=false, allow.auto.create.topics=false|
|offset reset|earliest. 기존 유효 committed offset은 유지|
|fetch|fetch.min.bytes=1, fetch.max.wait.ms=500|
|ack|MANUAL_IMMEDIATE, syncCommits=true, asyncAcks=false, assignment commit NEVER|
|실패|CommonContainerStoppingErrorHandler, listener 자체 TX·@Async 없음|
|session / heartbeat|45,000ms / 3,000ms|
|max.poll.interval.ms|300,000ms|
|default.api.timeout.ms / request.timeout.ms|60,000ms / 20,000ms|

위 timeout은 이미 남아 있는 해당 listener의 client 출력에서 읽은 값이며 새 실행값이나 SLA가 아니다. syncCommitTimeout은 코드에서 따로 지정하지 않는다. 로컬 Spring Kafka 3.3.4의 `KafkaMessageListenerContainer.ListenerConsumer.determineSyncCommitTimeout`은 별도 지정이 없으면 default.api.timeout.ms(없으면 Kafka 기본값)를 사용하므로 60초로 해석한다. 이는 설정·소스 대조이며 container getter를 실행해 다시 측정한 값이나 실제 60초 commit 지연 관찰은 아니다.

공통 YAML에서 복사된 무효 키 `enable-auto-commit`·`auto.create.topics.enable`가 map에 남아 있어도 이를 유효 설정 근거로 쓰지 않았다. 실제 Kafka가 읽는 점 표기 constant key와 생성된 consumer 로그를 확인했다. 안전한 listener 예외는 원래 cause를 붙이지 않아 framework 로그로 원문이 전달되는 경계를 줄이는 대신 원래 cause·stack trace를 잃는다. 모든 로그 경로의 원문 노출을 막는다는 보장은 아니다.

## 실제 실패·재전달·배정

|시나리오|broker committed offset|DB·처리 결과|
|---|---|---|
|C7 준비·실패|p0:0 → p0:0|beforeCommit 실패 1회, 완료 0·활동 0, child/부모 비정상 중단|
|C7 복구|p0:0 → p0:1|child 종료 통지·group member 0 확인 후 같은 container/group/topic/DB 재시작. 동일 record APPLIED, 완료 1·활동 2|
|C8 첫 처리·종료|p0:0 → p0:0|주문 43·상품 7×3, APPLIED, 완료 1·활동 1. n+1 commit 없이 bounded close|
|C8 두 번째 consumer|p0:0 → p0:1|동일 topic/p0/offset 0 재수신, ALREADY_PROCESSED. 두 테이블 전체 저장 컬럼 불변 후 commitSync(1)|

C7 복구에는 seek/reset·테이블 초기화·group 변경이 없다. C8 첫 consumer의 기준 offset 지정과 seek는 준비 단계에만 있고 두 번째 consumer는 같은 group의 committed offset에서 재개했다. C8은 통제된 low-level consumer 교체이며 Spring listener ACK·OS 강제 종료 증거가 아니다. 실제 listener 실패·ack 연결은 C6·C7에서 따로 확인했다.

C9는 별도 비업무 topic에서 AdminClient의 stable 배정과 각 consumer의 실제 assignment가 일치할 때 기록했다.

|같은 group의 member 수|배정|
|---|---|
|1|A:[0,1]|
|2|A:[0], B:[1]|
|3|A:[0], B:[1], C:[]|

명시 전송한 partition 0·1 record는 각각 배정된 member만 받았다. partition을 지정하지 않은 `order-1001`·`order-2002`의 각 3회 전송 metadata는 모두 [0,0,0]이었다. 같은 key의 일관성을 확인했으며 다른 key가 다른 partition으로 간다고 주장하지 않는다.

## C10: 미발행·정상 사건과 다음 주 재현

[종단 테스트](../../../apps/commerce-api/src/test/java/com/loopers/infrastructure/order/OrderEventEndToEndIntegrationTest.java)가 W6 직접 발행 경로를 다시 실행한다. 부모는 API·PG fixture·실제 전용 adapter, 자식은 실제 `CommerceStreamerApplication` bootJar다. API classpath에 streamer 앱을 넣지 않는다.

부모 fixture의 MySQL·Redis 접속값과 일반/admin Kafka 주소·고유 topic/group을 자식에 명시한다. 비밀번호는 환경 변수로 넘기며 출력하지 않는다. test 프로필·자기 테이블 create·임의 HTTP/management 포트·demo-kafka.enabled=false를 사용한다. 자식이 두 JPA repository를 등록하고 기동한 뒤 group Stable·member 1·partition [0,1] 배정을 확인하고 주문을 시작했다. API 12개 테이블의 생성 시각은 기동 전후 같고 구매 두 테이블은 0행이었다.

아래는 Gradle 입력 추적 보완 전 C10 전체 회귀 실행(`bfa9830` + 당시 작업 트리)의 실제 합성 식별자다. 고정 Clock과 품목은 위 재현 조건을 사용했다.

|사실|A: commit 후 미발행|B: 정상 최초 소비|
|---|---|---|
|orderId / providerOrderId|1 / LP-ORD-0000000001|2 / LP-ORD-0000000002|
|transactionKey(합성 PG)|9864e3cd-cdd6-4cf9-b58c-2057855413df|16deaba9-f904-4e45-bc42-6db8c29aaa83|
|eventId|order-confirmed:1|order-confirmed:2|
|items|상품1×2·상품2×1|상품3×2·상품4×1|
|confirmedAt|2026-10-29T01:02:03.123456Z|2026-10-29T01:02:03.123456Z|
|응답·주문·결제|200 CONFIRMED·paid/total 4,500·SUCCESS|200 CONFIRMED·paid/total 4,500·SUCCESS|
|예약·현재 재고|ORDER_RESERVATION 2·ORDER_RELEASE 0·재고 3/4|ORDER_RESERVATION 2·ORDER_RELEASE 0·재고 3/4|
|전송·broker|beforeSend 통제 실패 1회, send metric 1→1(준비 전송만). 두 partition 고정 구간에 해당 key/eventId 없음|key `2`, p0@0, value의 품목·시각·사건 ID 일치|
|streamer·DB|해당 완료·활동 0행|APPLIED, broker committed {p0:1}, 완료 1·활동 2|

해당 업무 topic은 `order-events-c10-test-1029141725-9dbe2aac-1`, group은 `purchase-activity-c10-test-1029141725-9dbe2aac-2`였다. broker 고정 구간은 시작 {p0:0,p1:0}, 끝 {p0:1,p1:0}이며 읽은 1건은 B뿐이었다. A는 OS 종료가 아닌 대상 beforeSend 예외 주입이다.

B는 commit 뒤 send 전에 gate로 멈춘 동안 기존 ProductFacade.delete로 상품 3을 삭제한 뒤 전송했다. 새 DB 조회는 완료 `order-confirmed:2`(OrderConfirmed, version 1, item_count 2)와 활동 (3,2)/(4,1), 확정 시각 `2026-10-29 01:02:03.123456`을 보여 줬다. 현재 Product를 조회하지 않으므로 삭제된 상품의 과거 구매 사실도 저장됐다.

두 사건을 같은 자식 JVM 안에서 수행했다. 자식은 SIGTERM 후 종료 로그·consumer 종료를 남겼고 exit 143이었다. 이후 API 데이터·Redis·컨텍스트·PG를 정리하고 자식이 만든 구매 두 테이블을 DROP한 뒤 소유 Kafka 자원을 정리했다. 임시 DB를 다음 주까지 유지하지 않는다. 다음 주 새 실행에서는 재현 코드가 새 주문·식별자를 만들며 이 표의 과거 원본 행이 보존된 것으로 취급하지 않는다. W7 Outbox 경로 실행 뒤 행을 지워 누락을 만드는 재현은 아니다.

API test는 streamer bootJar 준비에 의존하고 실제 산출물 경로를 넘긴다. 추가로 archiveFile을 `c10StreamerBootJar` 입력으로 등록했다(PathSensitivity.NONE). 동일 입력은 UP-TO-DATE였고, 같은 경로에서 jar SHA-256이 `328c97…`→`977aeb…`로 바뀌자 해당 입력 변경을 감지해 C10 1건을 다시 실행했다. 경로만 바뀌고 내용이 같으면 재실행하지 않는 설정이다.

## 회귀 결과와 현재 산출물 보존 상태

모든 행의 실패·오류·skip은 각각 0이다. 각 행은 서로 다른 시점의 실행이며 합산한 하나의 실행이 아니다.

|단계|실행과 수량|lint·layer / 근거|
|---|---|---|
|최초 사건 값|API check 808건|Checkstyle 오류 0·ArchUnit 3. 당시 XML 확인 기록|
|발행 연결·fake 축소·P5 보완|API check 848건|Checkstyle 오류 0·ArchUnit 3. 당시 실행 기록|
|실제 발행·정리 보완|API check 854건|Checkstyle 오류 0·ArchUnit 3, broker 4·cleanup 2 포함. 당시 XML 확인|
|C1~C5|streamer check 78건|Checkstyle main18/test10 오류 0·ArchUnit 1. 당시 XML 확인|
|C6~C9|streamer check 86건|Checkstyle main22/test14 오류 0·ArchUnit 1. helper 영향으로 API broker 4·cleanup 2 재실행|
|C6~C9 테스트 보완|실제 broker 통합 3건|Checkstyle test 오류 0. 전체 컬럼 비교·연도 의존 제거|
|C10 전체 회귀(Gradle 입력 보완 전)|API check 855건·streamer check 88건|API Checkstyle main166/test133·ArchUnit 3, streamer main22/test15·ArchUnit 1, 모두 오류 0. 당시 Codex가 XML 직접 확인|
|Gradle 입력 보완 후|C10 1건, jar 내용 변경 확인 후 다시 1건|실제 실행 2회 각각 통과. 사이의 UP-TO-DATE 실행은 통과 수에 포함하지 않음|
|테스트 리팩터링(`7daea9c`) 후|API broker 4·cleanup 2·C10 1, 총 7건|같은 변경 뒤 실행한 Checkstyle test 134개 파일 오류 0. 전체 check 재실행은 아님|

`modules:kafka:check`는 test 소스가 없어 NO-SOURCE이며 testFixtures 컴파일 등 수행 결과다. Kafka 모듈 테스트가 별도로 통과했다고 세지 않는다. C10의 API check에는 PG fixture·POINT·소유권·예약 등 API 회귀가 포함되며 pg-simulator 앱 자체 check를 이번에 새로 실행한 결과는 아니다.

현재 API 결과 폴더에는 테스트 리팩터링 뒤 관련 필터 실행의 XML 3개(broker 4·cleanup 2·C10 1, 총 7건)만 남아 있고, 이전 전체 회귀 213개 XML(855건)과 그 뒤 C10 단독 결과는 대체됐다. 그 전체 통과는 당시 Codex 직접 확인 기록을 보존한 과거 결과이며 현재 파일로 다시 합산할 수 있다고 하지 않는다. streamer 결과는 16개 XML·88건이 남아 있고 이번 문서 작업에서 합계를 확인했다. 이번 문서 작업은 새 회귀 실행이 아니다.

## 남은 한계

- 단일 broker·복제 계수 1이므로 복제 장애 내구성을 검증하지 않았다.
- C10은 producer 준비·listener 배정 후 즉시 PG SUCCESS 경로를 사용했다. cold start 첫 전송·callback 지연 해결 근거가 아니다. callback 실제 broker 발행은 P6에서 따로 확인했다.
- rebalance 중 commit 보류, ACK 반환 뒤 재전달, commit 결과 불명, 실제 deadlock·연결 장애는 재현하지 않았다. `uk_poc_order` 경쟁도 실제 관찰 없이 분류기 단위 검증만 있다.
- 전체 streamer 앱의 test 프로필·demo off 기동은 C10에서 확인했다. 일반 프로필에서 demo on으로 전체 앱을 기동한 결과는 없다(기본 bean 등록 조건만 별도 단위 검증).
- 직접 발행의 DB→broker 공백은 남아 있고 미발행 A를 자동 복구하지 않는다. Outbox·랭킹 API·자동 재발행·DLQ는 이번 구현에 추가하지 않았다.

## W7 Outbox 실행 근거

이 절은 W7 Transactional Outbox 구현(2026-11-03~05)의 현재 실행 근거다. 위 W6 절의 과거 결과·수치는 그대로 두며, 여기의 수치는 아래 명시한 최종 실행에서만 나온 값이다. 계약·자원·증거 기준은 [발행 W7.2~W7.4](../features/order-events/publication.md)를 따른다.

### 실행 환경과 명령

- 코드: `volume-7` HEAD `cb6c39c`에 최종 검증 보강(테스트 4개 파일·API test heap 설정)을 더한 커밋 전 작업 트리에서 실행했다. 이미 커밋된 revision을 새로 checkout해 실행한 결과로 표현하지 않는다.
- JDK 21.0.12, Gradle 8.13, Spring Boot 3.4.4·Spring Context 6.2.5·Spring Kafka 3.3.4·Kafka clients 3.8.1·Hibernate ORM 6.6.11.
- 자원: 기존 MySQL 8.0·Redis Testcontainers fixture, 공유 로컬 Kafka(`localhost:19092`, broker 1대, `min.insync.replicas=1`, 기본 복제 계수 1). 실행별 topic·group·임시 스키마만 만들고 정리한다. 개발 DB와 broker 전역 설정은 사용·변경하지 않는다.

~~~bash
./gradlew :apps:commerce-streamer:check --rerun-tasks --max-workers=1
./gradlew :apps:commerce-api:check --rerun-tasks --max-workers=1
~~~

API `check`는 일반 `test`, 전용 `w6OutboxRecoveryTest`(O11, 새 worker JVM·빈 fixture DB), Checkstyle main/test를 실행한다. 일반 `test`는 O11 클래스 하나만 제외하며 O11을 일반 test filter로 찾지 않는다. `--rerun-tasks`로 모든 작업을 실제 실행했고 두 실행은 직렬이다.

|대상|결과(2026-11-05)|
|---|---|
|commerce-streamer check|test 88건(XML 16개) skip·failure·error 0, ArchUnit 1, Checkstyle main 22·test 15 파일 오류 0|
|commerce-api check|일반 test 1029건(XML 234개) + w6OutboxRecoveryTest 1건, 합계 1030건 skip·failure·error 0, ArchUnit 3, Checkstyle main 193·test 166 파일 오류 0. JaCoCo exec는 test·w6OutboxRecoveryTest 작업별로 분리|

후속 테스트 지원 코드 리팩토링(09, 2026-11-05)은 `7b6437f`에 코드 이동을 더한 작업 트리에서 검증했다. 소유권/기동/정리 판단을 `OrderOutboxE2eLifecycleSupport`로 본문 변경 없이 옮겼고 제품 코드는 바꾸지 않았다. 정리 회귀 5·05 전달 E2E 1·ArchUnit 3·전용 O11 1, 합계 10건에서 skip·failure·error 0, test Checkstyle 167개 파일 오류 0과 두 E2E의 `kept=[]`를 확인했다. 위 전체 check는 08-3 시점 결과이며, 리팩토링 뒤 전체 check를 다시 실행한 것은 아니다.

같은 날 첫 API 전체 실행은 실패했다. 실패 원인과 수정은 다음과 같다. 유효 기대·시간 한도는 바꾸지 않았다.

- 테스트 간 간섭: BrandFacadeIntegrationTest가 실행 증거용으로 켠 `org.hibernate.orm.jdbc.bind=TRACE`가 Logback 전역에 남았다. 그 결과 뒤 클래스의 Outbox insert 바인딩 값(payload)이 출력돼, OrderConfirmedSendFailureIntegrationTest의 "로그에 payload 없음" 단언이 실패했다. 제품 relay 로그의 문제는 아니다. 해당 클래스가 끝나면 logger 수준을 기본으로 되돌리게 했다. TRACE 증거 로그와 SendFailure 단언은 그대로다.
- 메모리: maxHeapSize가 없어 test worker heap이 512MB였다. 같은 실행에서 OutOfMemoryError와 lifecycle ON의 20초 대기 초과를 관찰했다. 캐시된 Spring context(최대 32)의 메모리 부담과 GC 지연을 원인으로 추론했지만 heap dump·GC 분석으로 인과관계를 확정하지는 않았다. API `test`·`w6OutboxRecoveryTest`의 heap을 1g로 지정한 뒤 같은 기대·한도로 통과했다.

### 원문 요구별 근거(O1~O14)

테스트 파일 기준 경로는 `apps/commerce-api/src/test/java/com/loopers/`다. 모든 테스트는 위 최종 실행에 포함됐다.

|ID|대표 테스트|확인한 경계|
|---|---|---|
|O1|domain/order/OrderOutboxMessageTest·OrderConfirmedOutboxModelTest, infrastructure/order/JacksonOrderConfirmedMessageEncoderTest·JacksonOrderOutboxMessageVerifierTest|불변 identity·v1 JSON·UTC 마이크로초·엄격한 verifier, 거절 문구에 payload 없음|
|O2|infrastructure/order/OrderConfirmedOutboxSchemaIntegrationTest·OrderConfirmedOutboxRepositoryIntegrationTest|실제 DDL·명명 unique/CHECK·due 경계·EXPLAIN·uk_oco_event 중복 insert 실패|
|O3·O4|application/payment/OrderOutboxAtomicityIntegrationTest·OrderConfirmedPublicationHttpIntegrationTest·OrderConfirmedPublicationIntegrationTest 등|최초 확정+예약+Outbox 같은 TX, 실패 시 TX B 전체 rollback, 중복 callback/reconcile에 Outbox 1·비성공 0|
|O5|config/OrderOutboxKafkaConfigTest·OrderEventKafkaContextTest, infrastructure/order/KafkaOrderOutboxSenderTest·OrderOutboxKafkaWireIntegrationTest|String serializer·비트랜잭션 producer, 실제 broker value=저장 원문 UTF-8, 실제 KafkaAdmin bootstrap|
|O6·O7·O9 실제|infrastructure/order/OrderOutboxDeliveryEndToEndIntegrationTest|아래 대표 사건|
|O8·O9 통제|application/order/OrderOutboxRelayIntegrationTest·OrderOutboxRetryPolicyTest|timeout 뒤 늦은 완료 불변, 상태 저장 SQL 실패, 5→10→20→40→60초·10회 BLOCKED·재개|
|O10|application/order/OrderOutboxExecutionGateTest·OrderOutboxRelayIntegrationTest·OrderOutboxRecoveryIntegrationTest, interfaces/scheduler/OrderOutboxRelaySchedulerLifecycleTest, infrastructure/order/OrderOutboxKafkaWireIntegrationTest·OrderEventKafkaBrokerIntegrationTest|BUSY·STOPPING·interrupt, 실제 context.close의 early shutdown→phase 0→pool -100→producer 순서, phase -100 대기, graceful 중 진행 HTTP 확정, producer close 15초 예산|
|O11|infrastructure/order/W6OutboxRecoveryEndToEndIntegrationTest|아래 대표 사건|
|O12|application/order/OrderOutboxRecoveryIntegrationTest|원본 부족·충돌·의미 비교·기존 상태 불변·부분 commit/미시도·입력/gate|
|O13|API·streamer 전체 check|기존 W5/W6 회귀·Checkstyle·ArchUnit|
|O14|OrderOutboxRelayIntegrationTest·OrderOutboxDeliveryEndToEndIntegrationTest|아래 O14 절|

### 대표 실제 사건

**신규 전달(O6→O7·O9)** — runId `1105130816-80d19fe0`, 소유 topic `order-outbox-e2e-test-1105130816-80d19fe0-1`(2 partition), 실제 API·로컬 PG fixture·streamer 자식 JVM, 자동 relay OFF.

- D(order 1, `order-confirmed:1`): 확정 commit 뒤 Outbox PENDING 0회. 새 relay R1이 실제 broker에 1@0을 보냈고 성공 metadata 뒤 one-shot probe로 PUBLISHED 저장 전에 멈췄다(PENDING 유지). consumer 결과는 1@0 `APPLIED`, 완료 1·품목 활동 2행이다. 다음 시각 뒤 새 relay R2가 같은 원문을 1@1로 재발행했고 결과는 `ALREADY_PROCESSED`, 구매 효과는 그대로다. 최종 PUBLISHED 시도 2, group committed partition 1=2. PG create 1·조회 0·callback 0.
- E(order 2, `order-confirmed:2`): 소유 topic의 `min.insync.replicas`를 2로 바꿔 실제 send Future 실패를 1회 만들었다(30,014ms, `SEND_COMPLETION_FAILED:org.springframework.kafka.core.KafkaProducerException`). PENDING·시도 1·다음 시각 +5초, 끝 offset은 그대로였다. 설정 복원(1/DEFAULT_CONFIG) 뒤 같은 행이 0@0으로 전달돼 `APPLIED`, committed partition 0=1이다.
- 정리 뒤 남긴 자원 없음(`kept=[]`), 전체 44.9초.

**W6 준비 → W7 복구(O11)** — 전용 작업의 새 worker(시작 테이블 0, 테스트 컨테이너 DB), runId `1105131051-97de3d2a`.

- W6 준비: A(order 1)는 확정 commit 뒤 beforeSend 통제 실패로 send 증가 0, broker에 record 없음. B(order 2)는 0@0 `APPLIED`이며 전송 전 상품 3을 삭제했다.
- 같은 DB·PG·streamer로 W7 API를 update 기동했다. Outbox 테이블만 추가됐다. 명시 복구로 두 행을 만든 뒤 relay가 A를 1@0(`APPLIED`), B를 0@1(`ALREADY_PROCESSED`, 최초 0@0과 다른 위치)로 전달했다.
- committed partition 0=2·1=1. 복구·전달 동안 PG create 2·조회 0·callback 0 불변. 두 번째 복구는 EXISTS로 아무것도 바꾸지 않았다. 자식 JVM 정상 종료(exit 143).

**종료(O10)**

- 진행 중 HTTP 확정을 결제 TX commit 직전에 세운 채 실제 context.close를 시작했다. HTTP 200 CONFIRMED, close 뒤 새 연결에서 CONFIRMED·SUCCESS·Outbox 1행(PENDING 0회)이었다. 순서는 graceful 완료 → relay 정지 → producer factory 정지다.
- 실제 broker의 진행 전송 중 close: producer close 15초 예산 소진(15,035ms), 행은 PENDING 시도 1·오류 없음.
- phase 0 한도를 넘겨 회차가 pool에 남는 통제 장면:
  - executor가 running이면 phase -100에서 pool의 stop(callback)이 호출되고, 회차가 끝날 때까지 callback이 미뤄진다.
  - 대기 안에 끝나면 다음 phase로 넘어간다(close 3,030ms, phase 한도 3초).
  - 끝나지 않으면 Spring이 `Shutdown phase -100 ends with 1 bean still running after timeout of 1000ms: [orderOutboxRelayTaskScheduler]`를 남긴다. close는 relay thread가 남은 채 반환된다.
  - 두 경우 모두 결과 TX는 없다(PENDING 시도 1).

### O14 보존·관찰

- 운영 SQL은 상태별 건수(`GROUP BY status`), `status='PENDING' ORDER BY created_at, id`의 첫 행, BLOCKED 목록으로 본다. payload는 읽지 않는다.
- 통제 sender 장면(한 DB): `{BLOCKED=1, PENDING=2, PUBLISHED=1}`.
  - 가장 오래된 PENDING은 `order-confirmed:702`다. 시도 1, `SEND_COMPLETION_FAILED:java.lang.IllegalStateException`, next_attempt_at은 Outbox Clock 기준 2026-11-04T01:00:05.000001이다. created_at은 감사 시각 2026-11-05T04:04:02.109644다.
  - BLOCKED는 `order-confirmed:703`(INVALID_OUTBOX_MESSAGE, 시도 1)이다. 로그는 `ORDER_OUTBOX_BLOCKED outboxId=… code=INVALID_OUTBOX_MESSAGE eventId=INVALID`이고, 손상 본문과 원인 message는 출력되지 않았다.
- 실제 전달 E2E:
  - E 실패 시점은 `{PENDING=1, PUBLISHED=1}`이고 가장 오래된 PENDING은 `order-confirmed:2`다.
  - 최종은 `{PUBLISHED=2}`이며 소비 완료 2행·활동 4행이 그대로 남았다.
  - 시각은 셋으로 구분한다. 사건 confirmedAt, Outbox Clock(next_attempt_at·published_at), 감사 시각(created_at)이다. 실제 경과 시간은 별도로 기록했다.
- Kafka 보존(실행 소유 topic, Admin describeConfigs 읽기, 시작과 끝 동일): `cleanup.policy=delete`, `retention.ms=604800000`(7일), `retention.bytes=-1`, `segment.ms=604800000`, 출처 모두 DEFAULT_CONFIG. retention을 바꾸거나 만료 실험을 하지 않았다.
- 제품은 PENDING·BLOCKED·PUBLISHED Outbox 행과 소비 완료(dedup)·활동 행을 자동 삭제하지 않는다. API의 Outbox·relay·복구 코드와 streamer main에 삭제·purge·정리 스케줄이 없고, 앱 설정에 retention 지정도 없다. 테스트의 DROP·TRUNCATE·topic 삭제는 소유 자원 정리다. retention 밖 record·무한 보존 용량은 W7.3-7대로 자동 해결하지 않는다.
- broker ACK·PUBLISHED(receipt 위치), consumer 처리 로그, group committed offset, DB 효과는 각각 따로 관찰한 근거다.

### 남은 한계

- 실행하지 않은 장애: 복구의 RECOVERY_DB_FAILURE 경로, commit 응답 불명(결과를 모른 채 끊긴 commit), 실제 deadlock·연결 장애. 분류 코드만 구현했고, 응답 불명을 rollback으로 단정하지 않는다.
- phase -100 장면은 통제 sender가 interrupt를 무시하게 만든 것이다. 실제 client의 정체 조건을 재현하지 않았고 앱 전체 종료 시간을 보장하지 않는다.
- 단일 broker·복제 계수 1이라 acks=all이 복제 장애 내구성을 입증하지 않는다. relay 단일 실행은 한 API 프로세스 안의 gate만 보장한다.
- BLOCKED 재개는 유지된 context를 소유한 테스트 도구로만 호출할 수 있다(공개 관리 HTTP·CLI 없음).
- heap 1g는 현재 테스트 수에서 통과한 값이다. 실행 시간은 1회 관찰이며 성능 근거가 아니다.
- PR·기술 글·WIL·제출은 이 기록의 범위가 아니다.
