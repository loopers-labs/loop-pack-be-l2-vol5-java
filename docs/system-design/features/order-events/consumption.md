# 주문 확정 이벤트 소비 상세설계

**상태:** 2026-10-29 사용자 최종 승인 소비 상세설계다. GPT·Claude 전체 검토/재검토와 잔여 보완을 반영했다. 새 group의 earliest, 중단형 오류 처리, 공유 테스트 자원과 C10만 별도 JVM 사용을 유지한다. 별도 승인한 구현 단위와 관련 실행 검증을 마쳤다. 조건·한계는 [검증 기록](../../verification/order-events.md)을 따른다.

기준 wire 계약은 [이벤트 계약](README.md#58-orderconfirmed-v1-이벤트-계약), 발행 경계는 [발행 상세설계](publication.md)다. 이 문서는 소비 모델·파싱·DB TX·ack·실패·필수 전달 검증을 한 곳에서 검토할 수 있게 묶는다. 대안·선택 근거는 이 문서 마지막에 둔다. 물리 DB/스키마 분리를 새로 강제하지 않고 기존 JPA 연결을 사용하되, 주문 쓰기와 구매 활동 쓰기는 서로 다른 업무 TX다.

## 1. 소비할 사실과 보호 범위

- topic 기본 `order-events`, group 기본 `purchase-activity`. 전용 listener가 이 topic을 구독한다. 테스트는 고유 topic·group을 주입한다.
- key는 양수 orderId의 10진 문자열, value는 UTF-8 JSON `OrderConfirmed` / schemaVersion `1`이다. eventId=`order-confirmed:{orderId}`, confirmedAt은 최초 내부 확정 순간의 UTC `Z`·최대 마이크로초 표현이다.
- items의 productId·quantity는 양수 long. 원본 항목별 검사 후 상품별 수량을 overflow 없이 합산·정렬한다. JSON 속성·배열 순서나 소수초 후행 0 차이는 새 사실이 아니다.
- 완료 기록과 모든 품목 활동을 단일 DB TX에서 commit한다. 같은 eventId·orderId·고정 사실이면 no-op, 어느 식별자나 사실이 다르면 충돌이다. JSON 원문 bytes를 그대로 fingerprint로 비교하지 않는다.
- 소비자는 주문 확정·PG·포인트·재고·상품 조회/노출을 다시 수행하지 않는다. 삭제되거나 물리 행이 없는 productId도 과거 사실의 식별자로 저장한다.
- DB commit 뒤 ack한다. DB 실패·파싱 오류·충돌·ack 실패를 성공으로 삼켜 offset을 넘기지 않는다. DLQ·자체 재시도·복구 API·랭킹 API·Outbox는 추가하지 않는다.

## 2. 앱·클래스·의존 경계

아래 신규 타입은 `apps/commerce-streamer`의 `com.loopers` 아래 둔다. commerce-api 앱/Entity/이벤트 클래스를 직접 의존하지 않는다. 공유 `modules/jpa`는 BaseEntity·공통 설정만 제공한다. streamer도 기존 layer-first와 application→infrastructure 금지·infrastructure→application 금지 규칙을 적용한다. Kafka/JSON 타입은 수신 경계에서 불변 domain 값으로 바꾼다.

|위치·타입|책임·주요 메서드|
|---|---|
|`interfaces/consumer/OrderActivityKafkaConsumer`|`listen(ConsumerRecord<String, byte[]>, Acknowledgment)`; parser→handler→ack. topic/group/factory는 전용 설정·이름으로 지정. listener 자체에 TX·@Async 없음|
|`interfaces/consumer/order/OrderConfirmedParser`|`parseAndValidate(String key, byte[] value)`; class header 없는 JSON을 domain 값으로 변환. stream용 전용 parser 설정을 쓰고 공통 ObjectMapper를 전역 변경하지 않음|
|`domain/purchaseactivity/OrderConfirmation`|불변 record: eventType, schemaVersion, eventId, orderId, UTC confirmedAt, 정규화한 List<Item>. `normalize(...)`·`requireSameFact(stored, activities)`는 I/O 없는 검증·비교|
|`domain/purchaseactivity/ProcessedOrderConfirmationModel`|완료 사건 Entity. `completed(event)`는 필수 고정 필드·normalized itemCount를 생성. 별도 PROCESSING 상태나 선행 완료 TX 없음|
|`domain/purchaseactivity/ProductPurchaseActivityModel`|주문·상품별 수량·확정 시각 Entity. `purchased(event, item)`로 생성. 현재 Product Entity 연관·조회 없음|
|`domain/purchaseactivity/PurchaseActivityRepository`|`findProcessedByEventIdOrOrderId`→0~2행, `findActivitiesByOrderId`→상품 ID 오름차순, `insertProcessedAndFlush`, `saveActivity`. 반환 Entity는 호출 TX 안에서만 사용|
|`application/purchaseactivity/PurchaseActivityHandler`|TX 없는 `consume(event)`가 별도 TX bean을 호출. 이름 붙인 완료 unique 실패만 TX rollback 뒤 재확인. 결과 enum APPLIED/ALREADY_PROCESSED|
|`application/purchaseactivity/PurchaseActivityTransactionService`|외부 프록시 호출용 public `consume(event)` TX와 `verifyDuplicate(event, originalFailure)` readOnly TX. 자기 호출·상위 TX·TX 안 외부 Kafka 호출 없음|
|`application/purchaseactivity/PurchaseActivityProbe`|기본 no-op `afterActivitySaved(orderId, productId)`·`beforeCommit(orderId)`. 테스트가 중간 SQL flush·장벽·오류를 주입. 공개 제어 기능 아님|
|`infrastructure/purchaseactivity/PurchaseActivityRepositoryImpl`|domain 포트 구현, 두 Spring Data JpaRepository에 위임. 완료 insert는 saveAndFlush의 예외 변환 경계를 사용. EntityManager·SQL·Spring Data repository 타입은 domain 포트에 노출하지 않음|
|`support/persistence/PurchaseActivityConstraintClassifier`|DataIntegrityViolationException의 Hibernate 제약 metadata에서 아래 완료 unique 두 이름만 식별. API 앱의 helper를 참조하지 않고 작은 streamer 전용 helper로 둠|
|`domain/purchaseactivity` 오류|형식 오류·식별/사실 충돌·저장 정합성 오류를 구분하는 안전한 오류 코드. 기술 DB 예외를 정상 중복으로 바꾸지 않음. HTTP 응답 코드를 새로 만들지 않음|
|`config/OrderActivityKafkaConfig`·`OrderActivityKafkaProperties`|`commerce.order-activity` 바인딩, 전용 consumer factory·record listener factory. 공통 batch factory는 유지|

처리 완료의 단위는 이 구매 활동 기능이다. group을 바꾸더라도 같은 DB의 같은 사건을 다시 구매로 더하지 않는다. 기능별 group마다 별도의 구매 효과를 만드는 모델이 아니므로 테이블 unique에 groupId를 포함하지 않는다.

## 3. 저장 모델·제약·동일 사실 비교

두 Entity는 기존 BaseEntity의 id·created_at·updated_at·deleted_at을 사용한다. deleted_at은 항상 null이고 삭제/수정 API가 없다. 테이블은 streamer 소유다. DB·schema가 같더라도 commerce-api가 이 테이블을 쓰지 않고, streamer는 orders·payment·product 테이블을 읽거나 쓰지 않는다. 물리 FK를 새로 추가하지 않으며 scalar ID·원자적 삽입·명명 unique와 정합성 검증을 사용한다.

|테이블·열|SQL 목표·null|계약|
|---|---|---|
|`processed_order_confirmation.event_id`|VARCHAR(64) ASCII binary collation, NOT NULL|고정 사건 ID, 대소문자/공백을 임의 정규화하지 않음|
|`order_id`|BIGINT NOT NULL|양수 주문 ID|
|`event_type`|VARCHAR(32) ASCII binary collation, NOT NULL|OrderConfirmed 고정|
|`schema_version`|INT NOT NULL|현재 1|
|`confirmed_at`|DATETIME(6) NOT NULL|ZonedDateTime UTC·마이크로초 저장. 소비 현재 시각 아님|
|`item_count`|INT NOT NULL|정규화한 상품 종류 수. 부분 저장·수동 손상 확인에 사용|
|`product_purchase_activity.order_id` / `product_id`|BIGINT NOT NULL|양수 자연 식별자. Product FK 없음|
|`quantity`|BIGINT NOT NULL|양수 구매 수량|
|`confirmed_at`|DATETIME(6) NOT NULL|연결 완료 사건과 같은 확정 순간|

- 완료 기록: `uk_poc_event` UNIQUE(event_id), `uk_poc_order` UNIQUE(order_id).
- 활동: `uk_ppa_order_product` UNIQUE(order_id, product_id).
- CHECK는 완료의 `order_id > 0`, `item_count > 0`, 활동의 `order_id > 0`, `product_id > 0`, `quantity > 0`이다. enum 값 CHECK·새 상태 enum은 추가하지 않는다. 고정 eventType/version의 의미는 parser/domain guard가 지킨다.
- 시각은 발행 측과 같은 UTC·DATETIME(6) 목표 매핑이다. 활동 생성 후 수정 경로는 없지만 BaseEntity 감사 필드와 별도 불변 시각의 수명을 혼동하지 않는다. 실제 DDL·column/collation·unique/CHECK·UTC 왕복을 검증한다.

**비교 방식:** hash fingerprint나 원문 JSON 복사 열은 두지 않는다. 완료 고정 필드와 같은 TX로 저장한 모든 활동을 읽어 정규화한 업무 사실을 정확히 비교한다. 검사 순서는 아래와 같다.

1. eventId OR orderId 조회가 서로 다른 완료 2행을 반환하면 STORAGE_INTEGRITY_ERROR다. 유효 입력과 저장 행 모두 ID 유도 규칙을 지키면 두 행은 나올 수 없으므로 입력 충돌로 분류하지 않는다.
2. 완료 1행 자체의 orderId 양수, eventId=`order-confirmed:{저장 orderId}`, eventType=`OrderConfirmed`, schemaVersion=1, 필수 UTC·마이크로초 시각, itemCount 양수를 검사한다. 연결 활동의 orderId·양수 productId/quantity·상품 유일성·행 수=itemCount·각 confirmedAt의 Instant=완료 시각도 검사한다. 위반은 STORAGE_INTEGRITY_ERROR다.
3. 저장 상태가 정상일 때만 incoming의 식별자·고정 필드·시각·정규화한 `(productId, quantity)` 전체 집합을 비교한다. 동일이면 DB 변경 없는 ALREADY_PROCESSED, 식별자 차이는 IDENTITY_CONFLICT, 그 외 사실 차이는 FACT_CONFLICT다. incoming 자체의 유도 규칙 위반은 parser에서 IDENTITY_CONFLICT로 거절한다.

활동만 존재하고 완료가 없으면 저장 정합성 오류이며 완료 사건을 추측해 복원하지 않는다. 어떤 경우도 기존 행 덮어쓰기·누락 품목만 추가·부분 재실행을 하지 않는다. 기존 완료·활동은 immutable이라는 전제이므로 비교 중 별도 쓰기 잠금을 추가하지 않는다. 외부 SQL/하드 삭제가 생기면 이 전제를 재검토한다.

## 4. JSON 수신·검증·정규화

공통 Jackson은 단일 값을 배열로 받는 등 관대한 설정이 있으므로 wire 필드 타입은 JsonNode/토큰으로 명시적으로 확인한다. String→숫자·소수→정수·단일 item→배열의 자동 coercion을 이용하지 않는다. 먼저 UTF-8 CharsetDecoder의 malformed/unmappable 처리에 REPORT를 지정해 엄격하게 문자열로 디코딩하고, 그 문자열을 전용 JSON parser에 전달한다. 잘못된 바이트를 대체 문자로 바꾸는 String 생성이나 Jackson byte[] 입력의 UTF-16/32 자동 감지에 맡기지 않는다. UTF-8 오류·중복 object key·끝의 추가 JSON 문서는 형식 오류다. JSON 원문과 Jackson 원문 오류 메시지는 로그/새 예외에 복사하지 않는다.

```text
parseAndValidate(key, value):
  value null/빈 값: INVALID_EVENT
  UTF-8 decoder(REPORT)로 엄격하게 문자열 변환; 잘못된 encoding은 INVALID_EVENT
  전용 JSON parser로 단일 object 읽기; 중복 property·trailing token 거절
  필수 eventType/schemaVersion/eventId/orderId/confirmedAt/items의 존재·null·타입 확인
  eventType == OrderConfirmed, schemaVersion은 정수 1만 지원
  orderId/productId/quantity는 실제 정수 node·long 범위·양수 검사
  key == Long.toString(orderId) 확인 // 공백·선행 0·null 불허
  eventId는 nonblank ASCII String·길이<=64, 원문 값 유지
  eventId != "order-confirmed:" + orderId이면 IDENTITY_CONFLICT // 새 구매 효과 없음
  confirmedAt은 UTC Z 문자열·소수초 최대 6자리·정상 날짜/시각
  4자리 연도 1000~9999의 엄격한 ISO 날짜·시각(초 필수), DB DATETIME(6) 범위 검사
  items는 비어 있지 않은 array, 각 원소 object·원본 ID/quantity 먼저 검사
  상품별 Math.addExact 합산; overflow 거절 → ID 오름차순 불변 Item List
  return OrderConfirmation(고정 필드, UTC 시각, normalizedItems)
```

필수 필드 외의 미사용 JSON 속성은 업무 사실로 저장·비교하지 않는다. Java type header는 읽지 않는다. 지원하지 않는 타입/버전은 새 업무로 추측하거나 skip하지 않고 오류로 중단한다. 숫자·문자열 null·표현 범위 오류는 형식 오류, 정상 스키마의 ID 불일치는 충돌로 구분한다. eventId 파생 규칙에 어긋나는 입력은 DB 쓰기 전에 충돌이므로 같은 orderId의 다른 eventId도 추가 구매를 만들지 않는다. DB unique와 저장된 사실 비교는 이 parser 검사와 별도로 유지한다.

confirmedAt을 소비 시각으로 바꾸거나 더 높은 정밀도를 조용히 절삭하지 않는다. `.1Z`와 `.100000Z` 등 계약 안에서 같은 순간인 표현은 같은 사실이다. raw JSON의 속성·배열 순서에 의존하지 않는다. 현재 wire는 Z·최대 6자리 계약이며 그 밖의 표현을 임의 지원하는 schema 진화는 이번 범위가 아니다.

## 5. DB 처리·unique 경쟁·ack 의사코드

handler에는 상위 TX가 없다. transaction service의 각 public 메서드는 다른 Bean인 handler에서 프록시를 거쳐 호출하며 기본 REQUIRED를 사용한다. 실패한 쓰기 TX 안에서 제약 예외를 잡아 읽기를 계속하지 않는다. 기본 격리 수준을 유지하고, rollback 후의 재확인은 새 TX/영속성 컨텍스트이므로 이전 조회 snapshot을 재사용하지 않는다.

```text
PurchaseActivityHandler.consume(event): // TX 없음
  try:
    return transactions.consume(event) // commit 성공 후에만 APPLIED/ALREADY_PROCESSED 반환
  catch DataIntegrityViolationException failure:
    if classifier가 uk_poc_event 또는 uk_poc_order의 unique 위반을 식별하지 못하면:
      throw failure // 활동 unique·CHECK·연결 실패·deadlock 등을 중복으로 취급하지 않음
    return transactions.verifyDuplicate(event, failure) // 앞 TX rollback이 끝난 뒤 새 readOnly TX

transactions.consume(event): // public @Transactional
  candidates = repository.findProcessedByEventIdOrOrderId(event.eventId, event.orderId)
  if 서로 다른 완료 2행: throw STORAGE_INTEGRITY_ERROR
  if candidates 존재:
    완료 기록 + 모든 활동 자체의 정합성 먼저 검사; 손상이면 STORAGE_INTEGRITY_ERROR
    저장 상태가 정상이면 incoming의 식별자·고정 필드·전체 품목 비교
    같으면 return ALREADY_PROCESSED, 다르면 IDENTITY_CONFLICT/FACT_CONFLICT // 변경 없음
  if 완료 기록이 없는데 repository.findActivitiesByOrderId(orderId)가 비어 있지 않으면:
    throw 저장 정합성 오류 // 고아 활동을 정상 새 사건으로 편입하거나 부분 복원하지 않음
  repository.insertProcessedAndFlush(ProcessedOrderConfirmationModel.completed(event))
    // JpaRepository.saveAndFlush 경계로 명명 unique 경쟁을 DB에서 확정
    // 다른 TX의 미완료 insert와 경쟁하면 commit/rollback을 기다릴 수 있음
  activities = 정규화 item 각각 ProductPurchaseActivityModel.purchased(event, item)
  for activity in 상품 ID 오름차순 activities:
    repository.saveActivity(activity)
    probe.afterActivitySaved(orderId, productId) // 테스트에서 실제 SQL/flush 뒤 실패 가능
  probe.beforeCommit(orderId)
  return APPLIED // 실제 commit 성공 뒤 프록시 반환; 실패하면 ack 경로로 가지 않음

transactions.verifyDuplicate(event, originalFailure): // public readOnly TX
  candidates = 새 TX에서 eventId OR orderId 조회
  없으면 originalFailure를 다시 전파 // 무조건 성공으로 바꾸지 않음
  if 서로 다른 완료 2행: throw STORAGE_INTEGRITY_ERROR
  완료 기록 + 모든 활동 자체의 정합성 먼저 검사; 손상이면 STORAGE_INTEGRITY_ERROR
  저장 상태가 정상이면 incoming과 식별자·고정 필드·전체 품목 비교
  같으면 return ALREADY_PROCESSED, 다르면 IDENTITY_CONFLICT/FACT_CONFLICT
  // 기존 완료·활동 수정 없음; 저장 eventId 유도·타입·버전 검사도 3절대로 먼저 수행

OrderActivityKafkaConsumer.listen(record, ack): // listener 스레드, TX·@Async 없음
  try:
    event = parser.parseAndValidate(record.key, record.value)
    result = handler.consume(event) // 쓰기/중복 재확인 TX 종료 후 반환
    ack.acknowledge() // MANUAL_IMMEDIATE, 같은 consumer 스레드에서 한 번
    업무 TX 완료·ACK 호출 정상 반환 로그(topic, partition, offset, 안전한 사건 ID, result)
    // broker의 offset commit 완료 로그가 아님
  catch RuntimeException failure:
    parse/DB/conflict/ack 단계와 안전한 예외 타입·코드 로그
    payload·unvalidated key·원문 parser 오류·DB SQL 메시지를 출력하지 않음
    throw 안전한 listener 오류 // 실패를 숨기지 않고 container error handler에 전달
```

classifier는 Hibernate가 제공하는 constraint metadata의 테이블 접두어를 제거하고 명명한 완료 unique만 비교한다. metadata가 없거나 식별 불가하면 원래 오류다. 실제 MySQL/Hibernate 예외 체인·Spring 예외 변환·제약 이름 추출은 C4에서 확인하며, 확인 전 정상 unique 경쟁의 no-op 수렴을 검증 완료로 주장하지 않는다. deadlock·lock timeout도 중단 대상이므로 모든 동시 실행이 항상 APPLIED/ALREADY_PROCESSED로 끝나는 보장은 없다. 애플리케이션에서 예외 메시지 문자열 검색·INSERT IGNORE·전체 DB 오류의 중복 변환을 하지 않는다.

early flush는 완료 기록만 별도 commit하는 것이 아니다. 완료 insert와 모든 품목은 같은 TX다. 완료 marker를 먼저 영속화해 DB unique 경쟁을 받고, 품목 중간 실패와 확정적으로 rollback된 TX에서는 marker도 함께 사라진다. commit 응답 중 연결 단절처럼 DB 결과를 확정할 수 없는 오류는 무조건 rollback됐다고 단정하지 않는다. 이 경우 ack하지 않고 중단하며, 재전달 때 저장된 완료·활동의 존재와 동일 사실을 확인해 처리한다. APPLIED와 ALREADY_PROCESSED 모두 성공 TX 종료 뒤 ack하며, DB commit 성공 후 ack 실패/프로세스 종료에는 DB 효과를 취소하지 않고 재전달 때 no-op으로 수렴한다. ack 반환·처리 로그만으로 실제 group committed offset을 입증하지 않는다.

Spring Kafka 3.3.4의 `doCommitSync`는 `RebalanceInProgressException`을 즉시 호출자에게 전파하는 대신 commit을 보류할 수 있다. `checkRebalanceCommits`는 여전히 소유한 partition의 보류 offset을 다시 시도하며, 소유권을 잃으면 commit하지 못할 수 있다. 따라서 ACK 호출 정상 반환 뒤에도 재전달될 수 있고 DB 멱등성이 이를 흡수해야 한다. 별도 commit 관리 기능은 추가하지 않고 실제 broker group committed offset 조회로 완료를 관찰한다. 이 설명은 로컬 라이브러리 소스 확인이며 실행 검증 결과가 아니다.

## 6. 전용 listener 설정과 오류·복구

`OrderActivityKafkaConfig`는 공통 KafkaProperties의 consumer map을 복사하고 아래 값으로 실제 ConsumerConfig key를 덮어쓴다. 현재 YAML의 `value-serializer` 오기나 `enable-auto-commit` 사용자 속성 표기만 믿지 않는다. 공통 batch factory·demo의 전역 설정을 바꾸지 않는다.

|항목|소비 전용 계약|
|---|---|
|consumer factory bean|`orderActivityConsumerFactory`, StringDeserializer / ByteArrayDeserializer를 실제로 연결|
|listener factory bean|`orderActivityKafkaListenerContainerFactory`, batch=false, concurrency=1|
|listener id·topic·group|`order-activity-listener`, `commerce.order-activity.topic=order-events`, `.group-id=purchase-activity`|
|`enable.auto.commit`|false, Kafka가 읽는 점 표기 constant key를 명시|
|`allow.auto.create.topics`|false. 전용 업무 consumer와 직접 만드는 테스트 consumer 모두 명시. 구독으로 topic을 자동 생성하지 않으며 명시적 사전 준비를 요구|
|`auto.offset.reset`|earliest. 사용자 선택: 유효 committed offset이 없는 새 group은 보존된 가장 오래된 record부터. 기존 group은 유효 committed offset부터 재개|
|listener autoStartup|`commerce.order-activity.auto-startup=true` 기본. C7 준비용 테스트에서만 false로 주입하고 registry로 명시 시작|
|`max.poll.records`|1. 업무 단위·실패 record와 ack 경계를 명료하게 유지하는 초기 설정|
|`fetch.min.bytes` / `fetch.max.wait.ms`|1 / 500. 공통 batch의 1MiB·5초를 상속하지 않음. 소량 record를 오래 모으지 않는 초기값이며 성능 적정값은 아님|
|ack·commit|MANUAL_IMMEDIATE, syncCommits=true, asyncAcks=false. listener 스레드의 DB 종료 뒤 acknowledge|
|assignment commit|NEVER. 배정 시 offset commit을 업무 완료 근거와 혼동하지 않음|
|오류 handler|`CommonContainerStoppingErrorHandler` 기본 abnormal stop. 업무 listener container를 중단하고 실패 record를 recover/skip하지 않음|

`earliest`는 offset이 없거나 보존 범위를 벗어났을 때의 reset 정책이다. 유효 offset이 있는 기존 group을 처음부터 다시 읽게 하지 않으며 retention으로 삭제된 record를 복구하지도 않는다. Kafka clients 3.8의 기본값 latest라는 사실만으로 업무 정책을 바꾸지 않는다. 이 기능은 새 group 시작 전에 보존된 확정 사건도 구매 활동으로 반영해야 하므로 earliest를 유지한다. 과거 사건을 의도적으로 제외하려는 요구가 생기면 시작 경계를 별도 결정한다.

위 설정은 저처리량 과제의 정확한 관찰을 우선한 초기값이다. fetch 대기·max.poll.records를 처리량 비교 실험으로 확장하지 않는다. session/heartbeat/max.poll.interval·poll·sync commit timeout은 공통/라이브러리 유효값을 확인해 기록하며 SLA나 새 운영 수치를 임의 도입하지 않는다. 테스트의 Kafka API 호출·배정·중단·복구 대기는 유한 deadline을 사용하고 실제 timeout보다 짧은 무작정 sleep으로 덮지 않는다.

실패는 **해당 실행의 listener container**를 중단하며 다른 앱 인스턴스·group까지 전역 정지시킨다고 주장하지 않는다. 다른 member가 재배정받을 수 있고 동일한 영구 오류에 다시 중단될 수 있다. 실패 target partition의 committed offset이 그 record를 지나지 않아야 하며, 다른 partition의 이미 완료된 record까지 rollback하는 계약은 아니다. framework 내부의 container-stop helper thread는 존재할 수 있지만 DB handler·ack를 다른 executor에 넘기지는 않는다.

유효 payload에 주입한 일시적 DB 오류를 제거한 후 같은 topic/group/DB에서 컨테이너를 새로 시작한다. offset reset·group 변경·테이블 초기화 없이 재전달→저장/no-op→ack를 확인한다. 오류를 고치지 않은 충돌/손상/미지원 버전 record는 재시작해도 다시 실패할 수 있다. 이를 자동 건너뛰거나 재발행·DLQ로 옮기는 기능을 추가하지 않으며 영구 오류의 운영 복구 정책은 범위 밖 한계로 남긴다.

### DB·프로세스 수명과 기존 demo

테스트는 기존 MySQL Testcontainers fixture의 같은 DB/스키마를 공유한다. streamer 전용 MySQL 컨테이너·고유 스키마·CREATE DATABASE 권한을 추가하지 않는다. API의 업무 테이블과 streamer의 두 테이블은 각 앱의 Entity만 매핑하며, 업무 쓰기 소유권을 섞지 않는다. 종단 검증 부모는 기존 fixture에서 얻은 실제 JDBC URL·username·password를 자식 streamer JVM에 직접 전달하고 다른 DB로 연결되는 기본값을 남기지 않는다. 새 전역 시스템 속성 덮어쓰기로 다른 테스트의 datasource를 바꾸지 않는다.

DDL의 원본은 streamer 두 Entity의 column/unique/CHECK 매핑이다. 독립 streamer 테스트 컨텍스트는 각자 기동할 때 자신의 두 Entity만 `ddl-auto=create`로 준비한다. 이전 테스트 클래스가 스키마를 만들어 두었을 것이라는 실행 순서에 의존하지 않는다. 이미 캐시된 컨텍스트를 재사용하는 독립 시나리오는 재기동 대신 아래 cleanup·0행 확인으로 시작한다. C10도 API 초기 스키마 준비 뒤 자식 streamer를 create로 기동하며 자기 두 테이블만 생성하고 API 테이블은 drop/create하지 않았는지 확인한다. streamer 단독 검증에 API 컨텍스트는 필요 없다.

데이터 보존 구간은 하나의 컨텍스트/자식 JVM 수명 안에 둔다. C7은 같은 컨텍스트에서 listener만 stop/start하고, C8은 같은 DB 컨텍스트를 둔 채 low-level consumer만 교체한다. C10도 하나의 자식 JVM으로 진행한다. 이 구간에는 새 create 컨텍스트/JVM을 띄우거나 기존 context를 destroy/recreate하지 않는다. 따라서 C1~C10은 먼저 create한 다른 클래스가 있어야 validate 기동이 되는 구조가 아니다. 향후 같은 데이터를 보존하며 새 프로세스를 띄워야 하는 별도 검증이 승인되면 그 재기동에 validate를 명시한다. validate 자체로 unique·CHECK·collation을 확인했다고 하지 않고 C2에서 information_schema/SHOW CREATE TABLE로 직접 확인한다.

독립 시나리오 시작 전에는 모든 관련 consumer·writer를 멈춘 상태에서 기존 API cleanup과 별도로 streamer 두 테이블도 정리하고 완료·활동 모두 0행임을 확인한다. API Entity 기반 DatabaseCleanUp만 호출해 streamer까지 비워졌다고 가정하지 않는다. 공유 테스트 DB에 접근하는 관련 테스트 작업은 직렬 실행하고 다른 Gradle JVM/앱이 같은 DB를 초기화하지 않도록 수명을 소유한다. 같은 시나리오의 C7 재시작·C8 consumer 교체·중복 재전달 사이에는 cleanup/TRUNCATE·스키마 재생성·group 변경을 하지 않는다. 시나리오 종료 뒤 consumer를 정지하고 소유 구매 테이블/테스트 자원을 정리하며 개발용 MySQL에는 연결하지 않는다. 공유 Docker Kafka 연결·실행별 topic/group·정리 책임은 발행 문서 2.11의 broker 절을 따른다.

기존 demo listener는 구매 활동 group·topic을 구독하지 않는다. 통합 테스트 프로필에서는 demo bean을 명시적으로 비활성화하여 demo topic 부재가 기동·로그를 방해하지 않게 한다. streamer의 application.name을 commerce-streamer로 바로잡아 관측을 구분하고 외부 HTTP API를 새로 만들지 않는다. 새 테스트에 필요한 streamer Checkstyle·layer 의존 검증을 연결하되 API/PG의 기존 검사 규칙을 약화하지 않는다.

## 7. 실제 전달·복구 검증 기준

아래는 통과 기준이다. 실행별 관찰값·회귀 수량과 미재현 조건은 [검증 기록](../../verification/order-events.md)에 분리한다.

|ID|통과에 필요한 관찰|
|---|---|
|C1 형식·정규화|필수 타입·버전·key/ID 관계·잘못된 UTF-8 및 UTF-16/32 입력 거절·중복 JSON 필드·trailing token/추가 JSON 문서 거절·null·범위·양수·원본 -1+3·overflow 오류, 순서/후행 0 차이의 같은 사실. 잘못된 입력에는 INVALID_EVENT 등 계약 오류·DB 쓰기·ack 0. UTF-16/32는 유효 UTF-8 바이트로 디코딩된 뒤 NUL 등으로 JSON 파싱에서 거절될 수도 있으므로 디코더 단계 실패를 강제하지 않음|
|C2 저장·정상 효과|실제 MySQL information_schema/SHOW CREATE TABLE로 타입·unique·CHECK·collation 확인; Hibernate validate만으로 대체하지 않음. 주문 42의 상품 7×2·8×1은 2행, 같은 사실 반복에도 2행, 주문 43의 7×3 추가 뒤 총 3행·상품7 수량 합 5. DB 재조회로 확인|
|C3 동일성·충돌|같은 ID의 다른 주문·수량·확정 시각, 같은 주문의 다른 ID, OR에 서로 다른 두 완료 기록, 품목 누락/시각 손상·완료 없는 고아 활동. 동일 사실만 no-op이며 오류는 기존 행 불변·추가 효과 0. 식별 규칙 위반은 파싱/정규화 단계의 충돌로도 확인. OR 두 사건·저장 ID 유도/type/version 손상은 STORAGE_INTEGRITY_ERROR, 식별 불일치는 parser에서 IDENTITY_CONFLICT, 정상 저장과 다른 업무 사실은 FACT_CONFLICT. DB 단계의 IDENTITY_CONFLICT는 유효 domain 값으로 도달하기 어려운 방어 분기이며 Kafka 입력으로 억지 재현하지 않음. 손상은 별도 DB fixture/TX service 검증으로 확인하며 parser를 억지로 우회하는 Kafka 입력을 요구하지 않음|
|C4 동시 unique 경쟁|독립 TX·DB 연결의 장벽으로 같은 사건을 동시 처리. 완료 insert 경쟁이 실제 겹쳤는지 관측하고 실제 Spring 예외 타입·Hibernate 원인·uk_poc_event/uk_poc_order 제약 이름 추출을 확인. 승자 commit 뒤 패자 rollback→새 TX의 동일 사실 확인→no-op. 승자 rollback 뒤 다른 TX 정상 삽입도 확인. 오류 타입을 전부 duplicate로 바꾸지 않음|
|C5 원자성·기술 오류|두 번째 품목 전/뒤 실제 SQL flush 이후 실패, BEFORE_COMMIT 및 확정 rollback 오류에서 완료·모든 활동 rollback. commit 결과 불명 오류는 rollback으로 단정하지 않고 무ack·재전달/동일 사실 비교로 처리. 활동 unique·CHECK·식별 불가 constraint·DB 오류를 no-op으로 처리하지 않음|
|C6 실제 listener 설정|실제 listener에 지정 factory가 적용됐는지와 factory/config map의 allow.auto.create.topics=false·serializer·record mode·group·consumer 스레드·sync ack·전용 qualifier를 확인. YAML 문자열만 근거로 통과하지 않음|
|C7 실제 listener DB 실패·복구|다른 활성 member가 없는 격리 group에서 target record offset n과 group 기준 offset n을 준비. DB commit 전 실패에서 완료/활동 0·target partition offset은 n 유지·container 중단 확인. 오류 제거 뒤 같은 group/container 재시작→동일 record→정상 DB commit→broker group committed offset n→n+1을 유한 deadline으로 조회. ACK 반환 로그로 대체하지 않음. 기존 데이터 reset·강제 seek로 복구를 꾸미지 않음|
|C8 DB commit→offset 공백|아래 통제 consumer 교체 절차로 같은 record 2회 전달·업무 효과 1회·committed n→n→n+1 입증. 단순 handler 2회 호출로 대체하지 않음|
|C9 partition·group|별도 비업무 topic partition 2개에서 실제 poll·안정된 assignment를 관측. member 1→2→3 배정표·각 partition 단일 배정·3명 중 idle 확인. 두 partition에 명시 전송한 record의 소비 확인. key 매핑은 partition 지정 없이 producer metadata로 비교하고 다른 key가 다른 partition이라는 assert는 하지 않음|
|C10 실제 PG 종단·회귀|시작 시 두 구매 테이블 0행 확인 후 기존 API·PG 확정→실제 broker→streamer listener→새 DB 조회의 품목·시각 일치와 이번 최초 소비 APPLIED 확인. 이전 행·ALREADY_PROCESSED만으로 통과하지 않음. 발행 P7 미발행 1건과 정상 소비 1건을 함께 재현·기록. 삭제된 상품 사실도 추가 조회/노출 없이 저장. API·PG·POINT·소유권·예약 회귀·관련 lint/layer 검사 유지|

### C7 실제 Spring listener 중단·재시작 절차

C7은 streamer 자체 Spring 테스트에서 수행하며 별도 JVM이 필요하지 않다. 전용 listener의 autoStartup을 테스트에서 false로 설정하고 target group의 활성 member가 없는 상태에서 topic·record와 기준 committed offset n을 준비한다. 이 설정은 업무 처리나 ack를 대체하지 않는다. registry에서 `order-activity-listener`의 부모 ConcurrentMessageListenerContainer를 얻어 시작하고 실제 배정 뒤 DB 오류를 주입한다.

오류 handler가 child를 비정상 중지한 뒤 부모의 running 상태만으로 완전 정지를 판단하지 않는다. DB rollback·실패 partition의 committed offset n·child 중단을 각각 확인한다. 이후 부모 stop 완료를 기다려 child/member 해제를 확인하고 오류를 제거한 뒤 부모 start → 재배정 → 같은 record 수신 → 정상 DB commit → 실제 group offset n+1을 확인한다. 중간 cleanup·새 group·강제 seek는 하지 않는다. 중단형 handler의 내부 대기(참고 소스에서 최대 10초)와 배정 시간을 고려한 유한 deadline을 두며 parent가 running인 채 start만 다시 호출해 복구됐다고 쓰지 않는다. 실제 버전·재시작 효과는 구현 검증에서 확인한다.

### C8 통제된 consumer 교체 절차

```text
같은 실제 broker/topic/group/DB와 실제 production parser·handler를 유지
이 group에는 교체할 두 consumer 외 활성 member가 없도록 통제하고 배정/종료 확인
producer metadata에서 target partition p, offset n 획득
첫 low-level KafkaConsumer: auto commit=false, group subscribe·배정·poll 준비
  배정 준비 poll이 target을 이미 반환해도 그 record는 업무 처리/ack하지 않고 버림
  배정 후 준비 단계에서 target의 다음 읽을 위치 n을 group에 commit하고 seek(n)
  이후 poll로 다시 얻은 target만 업무 처리
  target (topic,p,n)을 읽음 → production handler.consume → DB commit
  새 DB 조회로 완료 기록·모든 활동 snapshot 확인
  n+1 commit 요청·pending commit·종료 callback commit이 없고 group offset n인 것 확인
  bounded close로 첫 consumer 종료
교체 직전 broker group committed offset n 재확인; 다른 member의 commit 간섭 없음 확인
두 번째 새 consumer: 같은 group subscribe·배정·poll, seek/offset reset 금지
  같은 (topic,p,n) record 재전달 확인
  production parser·handler로 ALREADY_PROCESSED, 활동 snapshot 불변
  그 뒤 commitSync(p,n+1), 실제 group committed offset n+1 확인
각 단계 deadline·consumer/HTTP/소유 topic·group/테스트 DB 정리, 공유 broker 유지
```

첫 consumer의 준비 offset 지정과 두 번째 consumer의 실제 재전달을 구분한다. consumer position·poll 반환·로그만으로 committed offset을 대신하지 않는다. 이 실험은 OS 강제 종료가 아니라 통제된 미commit/consumer 교체다. C7은 실제 Spring listener 실패·ack 정책, C8은 DB 성공 뒤 offset 공백이어서 둘 다 필요하다. C8의 low-level consumer 결과만으로 Spring listener의 ACK 동작까지 입증하지 않으며 C6·C7에서 별도로 확인한다. retention으로 이미 사라진 record나 모든 broker/OS 장애를 검증했다고 하지 않는다.

### 종단 검증의 앱 경계와 자료 보존

별도 streamer bootJar JVM은 마지막 C10 종단 검증에만 사용한다. P 계열과 C1~C9는 각 앱의 일반 테스트/Spring 테스트 컨텍스트·직접 consumer로 검증한다. C10은 기존 PG fixture를 사용하는 API 테스트 컨텍스트에서 실제 adapter를 선택해 PG→API TX→공유 로컬 Docker Kafka→streamer 실제 listener→같은 테스트 MySQL의 구매 테이블까지 연결한다. API 테스트 classpath에 streamer 앱 전체를 넣지 않아 동일 com.loopers의 application/설정/Entity 중복 스캔을 피한다.

C10 부모 검증은 `:apps:commerce-streamer:bootJar`를 테스트 작업의 준비 의존으로 연결하고 child 실행물 경로를 전달한다. 부모 JVM에서 만든 API용 MySQL·필요한 Redis fixture를 자식 종료/재전달 검증보다 오래 유지한다. 자식은 test 프로필, 실제 datasource URL·계정, 공유 Kafka의 일반/admin bootstrap, 전용 topic/group, demo 비활성화, 독립 기동의 자기 테이블 create, `server.port=0`·`management.server.port=0`을 명시적으로 받는다. Redis import에 필요한 master/replica 접속값도 테스트 자원에서 전달하고 개발 환경 기본값으로 연결하지 않는다. 비밀 접속값 전체를 로그에 남기지 않는다.

프로세스 생성만으로 준비 완료를 판단하지 않는다. streamer의 두 Entity·repository 등록·기동 성공을 확인하고, 전용 listener 실행·기대한 group member 참여·대상 partition 배정을 유한 deadline으로 관찰한 뒤 PG 주문 입력을 시작한다. 독립 시나리오는 완료·활동 0행에서 시작하고 이번 정상 사건의 APPLIED·eventId·실제 broker metadata·새 DB 행을 연결해 확인한다. 부모는 stdout/stderr 수집·종료·exit code·timeout과 finally 정리를 책임지며 준비 실패를 skip/통과로 쓰지 않는다. 공유 Docker broker는 종료하지 않는다.

PG simulator callback의 기존 1초 응답 제한은 늘리지 않는다. 아래 준비 원칙은 실제 adapter·callback을 쓰는 P6·P8에도 적용한다. 실제 종단 검증은 broker/topic/admin 준비와 producer 초기 metadata·전송 준비를 별도 준비용 고유 topic으로 확인한 후 업무 주문을 진행한다. 준비 메시지를 OrderConfirmed 업무 topic에 넣거나 구매 효과로 세지 않는다. callback HTTP 결과·저장 주문·broker record·구매 활동을 별도로 관찰하며, 정상 조건에서도 timeout이면 원인을 보고하고 기존 기대값을 완화하지 않는다. 사전 준비된 종단 결과로 cold start 지연까지 해결됐다고 주장하지 않고 추가 성능 실험으로 확장하지 않는다.

다음 주 재현 코드는 발행 문서의 W6 직접 발행 준비를 유지한다. 미발행 사건은 모든 partition의 제한 구간에 record 없음과 구매 활동 없음, 정상 사건은 실제 listener 저장·group offset까지 확인한다. 실행 revision·고정 Clock·품목·실제 합성 PG 식별자·eventId·metadata·DB 효과를 남기고 임시 자원은 정리한다. 미실행·skip·연결 실패를 통과로 쓰지 않는다.

## 8. 선택 근거·대안·한계

|문제|초안의 선택과 이유|대안·얻는 것·비용|
|---|---|---|
|동일 사실 비교|완료 필드+불변 활동 전체를 비교해 원문 JSON 순서와 hash 충돌에 의존하지 않음|canonical payload/fingerprint 저장은 조회를 줄일 수 있지만 별도 canonical 규칙·중복 저장·충돌 가정이 필요. 현재는 품목 재조회 비용 수용|
|동시 소비|완료 unique 경쟁→실패 TX rollback→새 TX 비교|먼저 조회만 하면 경쟁을 못 막고 INSERT IGNORE는 충돌을 숨김. 별도 주문 잠금은 streamer가 주문 DB와 결합하므로 미채택. DB 대기·deadlock 가능성은 남음|
|원자성|완료 marker+모든 활동 단일 TX, early flush로 경쟁 확인|완료 먼저 commit은 품목 누락을 완료로 오인. 활동 먼저 별도 commit은 중복 위험. early flush가 별도 TX는 아님|
|오류 복구|원문 기본 예시의 중단형 handler, 원인 제거 후 같은 group 재시작|재시도 소진 뒤 recover/skip·DLQ는 offset/실패 record 수명·후속 순서 정책이 필요해 이번 설계에 추가하지 않음. 영구 poison record는 계속 중단될 수 있음|
|처리 단위|전용 record factory·concurrency1·max.poll.records1|batch/높은 병렬성은 처리량 이점이 있지만 부분 성공·ack·offset 경계가 복잡해짐. 실험 group member 증가와 업무 병렬성 기본값을 분리|
|테스트 자원|기존 MySQL 테스트 DB와 로컬 Docker Kafka 공유, DB 초기화 직렬화·고유 topic/group·C10만 별도 JVM|전용 DB/broker 컨테이너는 격리·자동 준비에 유리하지만 추가 기동/자원 비용. 공유안은 Docker 사전 기동·주소·명시적 cleanup·동시 접근 통제 비용을 수용|
|시간·과거 사실|이벤트 확정 시각·품목 보존, 현재 Product/Order 조회 없음|현재 도메인 조회는 참조 존재 확인을 더할 수 있지만 과거 사실을 덮거나 삭제와 불필요하게 결합. scalar 저장에는 수동 SQL 고아/손상 한계|

근거는 실습 원문의 완료 기록·활동 단일 TX·명명 unique·rollback 후 재확인·record listener·두 공백·group 배정 요구와 현재 streamer/demo 설정 읽기다. 로컬 Spring Kafka 3.3.4의 CommonContainerStoppingErrorHandler 소스에서 비정상 stop·예외 전파 경로, KafkaMessageListenerContainer의 doCommitSync/checkRebalanceCommits에서 rebalance 중 commit 보류·소유 partition 재시도, ContainerProperties에서 MANUAL_IMMEDIATE·asyncAcks·assignment 옵션을 읽었다. 이는 설계 시점의 소스 확인이며, 이후 실행한 버전·실제 효과·미재현 조건은 [검증 기록](../../verification/order-events.md)에 구분해 남겼다. completion 테이블 설계·전체 품목 비교·early flush·중단형 연결은 이 요구에 대한 구체화이며 성능 비교 결과가 아니다.

retention 밖 유실·영구 오류의 운영 복구·실제 다중 broker 장애·자동 재발행·exactly-once 분산 TX는 보장하지 않는다. 같은 record 재전달에도 구매 DB 효과 한 번이라는 경계만 설명하며 Kafka 모든 경로의 exactly-once라고 부르지 않는다. DB 손상·처리량·품목 규모·새 버전/다른 eventType·다른 소비 기능 요구가 생기면 저장/비교/오류 정책을 다시 검토한다.

### DLQ의 후속 확장 경계

2026-10-29 사용자는 현재 중단형 error handler로 실패·rollback·무ack·재시작·재전달을 먼저 구현/검증하고, DLQ는 이후 별도 설계·범위 승인을 받는 확장 후보로 두기로 했다. DLQ는 실습 원문의 명시적 금지/제외 항목이 아니며 현재 범위에서 채택하지 않은 선택이다. 원문에서 명시적으로 구현하지 않는 것은 Outbox·랭킹 API다.

현재 구현 범위에는 DLQ 전송·자동 skip·자체 소비 재시도를 넣지 않는다. 영구 오류 record는 재시작해도 다시 중단될 수 있다는 한계를 수용한다. 후속 DLQ 확장에는 오류 분류·제한된 재시도·DLQ 전송 실패·원래 offset 진행·조사/재처리 책임의 결정이 필요하고, DLQ 이동을 구매 활동 성공으로 기록하지 않는다. Outbox는 주문 DB commit→broker 미발행, DLQ는 broker 수신 뒤 소비 처리 실패를 다루므로 같은 기능으로 취급하지 않는다. 이 기록은 DLQ 구현 착수나 이번 주 필수 범위 확대 승인이 아니다.
