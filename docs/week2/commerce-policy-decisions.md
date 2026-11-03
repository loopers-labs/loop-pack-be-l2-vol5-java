# 커머스 정책 선택과 설계 검토

[전체 설계](commerce-erd-draft.md) · [API 계약](commerce-api-contract.md) · [TDD 계획](commerce-tdd-plan.md)

## W6 이벤트 전달의 선택과 남은 결정

**2026-10-29 선행 발행 증분:** W6-Q01의 결정적 eventId·Q02의 after-commit listener에 이어 사용자가 Q05의 전송 완료를 기다리지 않는 callback 방식을 선택했다. 공통 최초 확정 hook·발행 포트·listener·Kafka adapter를 연결했다. 발행 증분 신규 23개(실제 DB 경계 12·adapter 5·실제 Kafka 6)를 포함한 관련 47개 스위트·862개와 API Checkstyle이 통과했다. 범위·명령·한계는 [발행 TDD 기록](../week6/order-confirmed-publication-tdd-log.md)을 따른다. 당시 소비 DB·offset 실험은 후속이었다. 이번 소비 증분은 아래 Q03/Q04와 [구매 활동 소비 TDD 기록](../week6/purchase-activity-consumer-tdd-log.md)으로 구분한다. 근거는 [W6 원문](../week6/assignment-original.md)·[학습 원문](../week6/learning-original.md)이고, 전체 경계는 [W6 설계](commerce-erd-draft.md#w6-주문-확정-이벤트와-구매-활동-설계)에 확장한다.

### 과제로 고정한 동작과 보존

| 경계 | 고정 계약 |
| --- | --- |
| 발행 대상 | 검증된 PG SUCCESS를 최초 CONFIRMED·예약 CONSUMED로 반영한 TX가 실제 commit된 뒤 직접 발행. rollback·대기·실패·종결 재적용·과거 POINT 자동 소급 발행은 없음 |
| 이벤트 사실 | `OrderConfirmed`/버전1, `order-events`, 내부 orderId 문자열 key, 고정 eventId·저장된 최초 confirmedAt·상품별 정규화 수량. 현재 가격·이름 조회나 카드·비밀·불필요한 개인정보 포함 없음 |
| 발행 실패 | 확정 주문·예약 소비를 보존하고 PG create를 다시 호출하지 않음. 저장 결과로 응답하고 발행 실패를 별도로 드러냄 |
| 소비 DB·offset | 완료 기록과 모든 품목 공동 commit, DB 유일 제약과 사실 비교, commit 성공 뒤 ack. 같은 사실은 no-op, 식별자/내용 충돌은 실패. offset 재전달·실제 listener 실패/재시작은 따로 검증 |
| 기존 계약·범위 | PG 포인트 변화0, 이전 POINT 사실·재확정 오류·권한·삭제 비노출 보존. Outbox·랭킹 API·공개 이벤트 주입 endpoint는 제외 |

### W6-Q01 · 결정적 사건 ID

| 선택·대안 | 판단과 영향 |
| --- | --- |
| **선택: `order-confirmed:v1:{orderId}`** | 한 주문은 한 번 확정된다는 기존 계약에 사건 종류·버전과 저장 주문 ID를 결합한다. 동일 확정 사실에서 항상 같은 ID를 얻고 이번 경로에는 별도 eventId 컬럼·저장 이행이 필요 없다. key는 같은 orderId의 10진 문자열이다. |
| 미채택: 최초 확정 때 UUID를 저장 | 원문이 허용하는 대안이다. 독립 ID를 만들 수 있지만 ID 생성·저장·중복 전이 보존과 스키마 이행 책임이 늘어난다. 이번 단일 확정 사실에는 결정적으로 유도하는 경로를 선택했다. |
| 금지: send마다 새 UUID·현재 시각·전송 횟수 | 같은 사실의 재전달 동일성이 깨지므로 구현 선택의 대안으로 채택하지 않는다. |

식별 범위는 **같은 주문 DB에서 유일하고 재사용하지 않는 orderId**다. 서로 다른 DB·새 격리 실행에서 숫자가 같은 주문을 동일한 역사적 사건이라고 보지 않는다. 테스트는 topic/group/DB를 격리하고 W7 새 실행에서는 그 실행에서 생성한 ID를 준비→복구 동안 고정한다. 여러 주문 DB의 번호가 겹치는 데이터를 한 식별 공간으로 합치는 확장은 이번 범위가 아니다.

순수 모델은 저장된 `ZonedDateTime`의 동일 시점을 `Instant`로 복사하고, 양수 productId·quantity를 상품별로 합산해 productId 오름차순의 불변 목록으로 만든다. 개별 quantity와 합계는 `int` 범위다. 이 정규화는 원문이 요구한 순서 독립 비교를 위한 생산자 표현이며 수신 JSON parser·DB 비교 저장 형태의 선택은 아니다. `PG + CONFIRMED`, 저장 식별자, 확정 금액·예약 소비·최초 시각이 일관된 주문에서만 스냅샷을 구성한다. 유효한 모델이 만들어졌다는 사실은 실제 commit이나 한 번만 발행했다는 증거가 아니다.

### W6-Q02 · after-commit listener

| 선택·대안 | 판단과 영향 |
| --- | --- |
| **선택: after-commit listener** | W5의 공통 결과 적용 경계에서 최초 PG 확정만 등록하고 실제 commit 성공에 발행을 연결한다. callback·reconcile 각각에 발행 조건을 복제하지 않는다. TX 안에서 불변 payload를 준비해 listener의 lazy entity 접근을 피한다. |
| 미채택: TX 서비스의 실제 commit 반환 후 orchestration | 원문이 허용하는 대안이다. 프록시의 commit 뒤 반환임을 보장하고 모든 결과 적용 호출자가 같은 발행 조율을 거쳐야 한다. 이번에는 공통 최초 전이와 트랜잭션 완료를 listener로 연결하는 경로를 선택했다. |
| 금지: `@Transactional` 메서드 끝에서 바로 send | 본문 끝은 실제 commit 완료가 아니다. 뒤의 flush·commit 실패에도 broker 기록이 남을 수 있으므로 선택한 계약을 만족하지 않는다. |

Q02는 실제 DB commit 뒤 실행할 위치의 선택이다. 이번 Q05에서 별도 `@Async` 없이 Kafka send future의 callback으로 결과를 관찰하도록 구체화했다. listener를 사용해도 DB commit과 broker send 사이의 누락은 남는다. 기존 REQUIRED·READ_COMMITTED·잠금 순서는 유지하고, listener는 이미 만든 불변 이벤트만 사용한다.

### 2026-10-30 공통 발행 경로의 구조 선택

사용자는 주문 전용 발행 구성 요소를 공통 `DomainEventListener`·Kafka publisher로 바꾸는 TDD 리팩터링을 요청했다. Q01의 식별, Q02의 실제 commit 경계, Q05의 완료 미대기와 실패 격리는 유지한다. 발행 추상화·listener·adapter·타입별 route와 관련 테스트를 구현·검증했으며 새로운 업무 이벤트나 소비 기능은 추가하지 않았다. 실제 Red/Green·검사 범위는 [공통 발행 TDD 기록](../week6/common-domain-event-publication-tdd-log.md)을 따른다.

| 선택·대안 | 판단과 영향 |
| --- | --- |
| 순수 `DomainEvent`·`DomainEventPublisher` | `domain/event`에 `eventType()`·`eventId()`·`key()`와 발행 포트를 둔다. `DomainEvent` 구현은 외부 발행에 명시적으로 참여하는 계약이다. Spring 로컬 이벤트 전체를 자동 발행하지 않는다. domain에 topic·Spring·Kafka를 넣지 않는다. |
| 공통 `DomainEventListener` | `application/event`에서 AFTER_COMMIT·fallback 미실행·동기 실패 격리를 한 번만 구현한다. 주문 최초 확정 판단은 기존 공통 PG 결과 적용에 남긴다. |
| 명시적 `KafkaEventRoute` 등록 | infrastructure 설정에서 정확한 이벤트 클래스에 topic·enabled를 연결한다. 주문 route는 기존 `commerce.order-events.topic`·`commerce.order-events.enabled`를 그대로 사용한다. 같은 타입의 중복 route는 시작 시 거절하고, 미등록 외부 이벤트는 send 0·실패 로그로 드러낸다. 비활성화도 성공으로 기록하지 않는다. |
| 미채택: listener의 업무별 switch·이벤트별 listener/publisher 복제 | 새 이벤트마다 commit 경계·오류 관찰을 복제하지 않도록 공통 경로와 타입별 등록을 선택했다. 모든 이벤트에 기본 topic을 주거나 클래스 이름으로 목적지를 추측하지 않는다. |
| 주문 wire 보존 | `OrderConfirmedEvent`는 공통 인터페이스를 구현하되 6개 JSON 필드·ISO 시각·type header 없음·orderId 문자열 key·stable eventId·저장 최초 confirmedAt/품목을 유지한다. 공통 메서드를 새 JSON 필드로 노출하지 않는다. |
| 확장 검증 범위 | 두 번째 합성 이벤트를 테스트에서만 등록해 같은 listener·publisher가 재사용됨을 확인한다. 회원가입 등 새로운 업무 기능이나 실제 운영 topic을 추가하지 않는다. |

공통 로그는 `domain_event_published`·`domain_event_publication_failed`·`domain_event_dispatch_failed`·`domain_event_publication_disabled`를 사용하며 해당 단계의 eventType/eventId/key/topic/partition/offset/errorType만 기록한다. 전체 payload·예외 원문·카드·비밀은 남기지 않는다. Q05 표의 eventId/orderId 관측은 선행 주문 전용 구현의 표현이고, 공통 경로에서는 key가 주문 ID 문자열이다. route 실패·즉시 send 오류·뒤늦은 future 실패 모두 이미 commit된 주문·예약을 되돌리거나 PG create를 반복하지 않는다.

### W6-Q03 · 소비 완료와 구매 활동의 저장·비교

사용자는 **처리 완료 기록과 상품별 구매 기록의 두 테이블에 필요한 필드를 저장해 비교**하는 안을 선택했다. 별도 비교용 JSON 사본은 저장하지 않는다.

| 선택·대안 | 판단과 영향 |
| --- | --- |
| 선택: `processed_order_confirmation` + `product_purchase_activity` | 완료 기록은 eventId·orderId 각각을 유일하게, 활동은 `(orderId, productId)`를 유일하게 보호한다. 종류·버전·주문·사건 ID·최초 시각·정규화한 상품별 수량을 복원해 전체 사실을 비교한다. 다른 주문의 구매는 별도로 남긴다. |
| 미채택: 같은 두 테이블에 비교용 JSON 사본 추가 | 원본 JSON의 속성·품목 순서가 달라도 같은 사실이다. 필요한 필드로 비교할 수 있어 사본 중복 저장·두 표현의 정합성 책임을 늘리지 않는다. |
| 동시 중복 | 먼저 완료 기록과 모든 품목을 같은 READ_COMMITTED TX에 저장한다. 완료 기록의 두 유일 제약 중 하나에서 난 MySQL 1062임을 제약 이름으로 확인한 경우에만 해당 TX를 rollback한 뒤 별도 조회 TX로 사실을 비교한다. 같은 사실은 no-op, 식별자나 내용이 다르면 충돌이다. 다른 DB 오류를 중복 성공으로 숨기지 않는다. |
| 정확한 사실 보존 | `confirmedAt`은 epoch second(BIGINT)와 nano(INT)로 나누어 저장해 Instant 정밀도를 유지한다. eventId는 `utf8mb4_0900_bin`으로 대소문자·끝 공백을 구별한다. 255자를 넘는 ID는 자르거나 중복 처리하지 않고 저장 오류로 중단한다. |
| 현재 상품과 분리 | 소비 시 현재 상품·주문을 조회하지 않고 API의 상품/주문 테이블에 FK를 걸지 않는다. 활동→완료 기록의 내부 FK만 둔다. 이후 삭제·가격 변경이 과거 구매 사실을 바꾸지 않는다. |

`PurchaseActivityHandler`는 외부 TX 안에서 호출되면 거절한다. 별도 프록시의 쓰기 commit/rollback과 중복 검증 TX가 끝난 다음에만 listener가 ack하도록 경계를 고정한다. 완료 기록만 남거나 일부 품목만 남는 부분 성공은 허용하지 않는다.

### W6-Q04 · DB 완료 뒤 ack와 실패 중단

사용자는 **실패하면 전용 소비 listener를 멈추고 원인을 해결한 뒤 같은 group으로 재시작**하는 안을 선택했다. 기존 공유 batch factory는 바꾸지 않는다.

| 선택·설정 | 이유·한계 |
| --- | --- |
| 전용 record factory·concurrency 1·`max.poll.records=1` | 처음에는 한 건의 저장·실패·offset 경계를 명확히 관찰한다. 처리량 최적값이라는 뜻은 아니며 group 배정 실험은 별도로 한다. |
| `enable.auto.commit=false`, `MANUAL_IMMEDIATE`, `asyncAcks=false` | 새 저장 commit 또는 같은 사실 no-op 확인 뒤에만 listener 스레드에서 ack한다. `syncCommits=true`, commit timeout 5초로 commit 실패도 드러낸다. DB와 Kafka offset은 여전히 하나의 원자적 저장이 아니다. |
| `CommonContainerStoppingErrorHandler` | 입력 오류·사실 충돌·DB 오류를 listener가 다시 던져 중단시킨다. 실패 record를 건너뛰거나 자동 복구 성공으로 처리하지 않는다. 자동 재시도 대안은 이번 선택에서 제외했다. |
| `fetch.min.bytes=1`, `fetch.max.wait.ms=100`, poll timeout 100ms | 작은 실습 메시지를 1MiB가 찰 때까지 기다리지 않고 유한한 시간에 관찰한다. 운영 처리량·지연의 최적값은 아니다. |
| `auto.offset.reset=earliest` | committed offset이 없는 group의 시작 위치다. 재시작은 기존 committed offset을 따르며 실패 위치를 강제로 seek/reset하지 않는다. |
| 재시작과 DB 보존 | streamer의 마지막 설정 import에서 `ddl-auto=validate`를 사용해 공유 local 설정의 create를 덮어쓴다. 제공 DDL은 수동 준비하며 재시작 때 완료 기록을 지우지 않는다. 테스트는 전용 DB·topic/group만 사용한다. |

로그에는 topic·partition·offset·결과/오류 종류를 남기고 payload·예외 원문은 남기지 않는다. 예외를 삼키지 않고 중단하므로 한 독성 메시지가 해당 listener의 후속 처리를 막는 비용이 있다. 수정 후 같은 group 재시작 절차를 실제 listener 실험으로 확인하며, **DB commit 후 ack 전 공백의 재전달(C05)**과 **DB 실패 후 재시작(C07)**을 구분한다.

### W6-Q05 · 응답 대기와 전송 결과 관찰

| 선택·대안 | 판단과 영향 |
| --- | --- |
| **선택: 전송 완료를 기다리지 않고 callback으로 관찰** | 주문 응답의 근거는 이미 commit된 주문 결과다. `KafkaTemplate.send()`의 future에 callback을 등록하고 `get/join/flush`나 별도 `@Async`를 추가하지 않는다. broker 확인이 늦어도 완료 확인 대기를 고객 응답에 더하지 않는다. |
| 미채택: 전송 접수 후 최대 2초 완료 대기 | 응답 전에 전송 결과를 알 가능성은 높지만 Kafka 지연이 주문 응답 지연이 된다. DB·broker의 원자성 공백은 해결하지 못하고, 기다린 시간이 끝났다고 미발행을 확정할 수도 없어 이번에는 채택하지 않는다. |
| 성공·실패 관측 | 성공은 eventId/orderId/topic/partition/offset, 실패는 eventId/orderId/topic/예외 종류를 기록한다. 원문 예외 메시지·카드·비밀·전체 payload는 기록하지 않는다. 일반 send 실패만으로 broker에 record가 없다고 단정하지 않는다. |
| 주문·결제 보존 | 동기 send 오류와 뒤늦은 future 실패 모두 저장된 CONFIRMED·예약 CONSUMED를 되돌리지 않는다. 고객 오류나 PG create 재호출로 바꾸지 않는다. broker 성공은 구매 활동 DB 완료가 아니다. |

아래 값은 이번 실습의 초기 선택이며 실제 운영 최적값·모든 장애에서의 시간 보장을 주장하지 않는다.

| producer 설정 | 선택 값 | 이유·한계 |
| --- | --- | --- |
| `max.block.ms` | `1000` | send의 metadata·buffer 대기를 제한한다. 직렬화·스레드 스케줄링·기타 API 처리까지 포함한 전체 응답 1초 상한은 아니다. |
| `request.timeout.ms` | `3000` | 개별 broker 응답 대기 예산이다. PG HTTP timeout과 별개다. |
| `delivery.timeout.ms` | `10000` | producer의 record 전달 재시도에 유한 예산을 둔다. send 전 metadata 대기까지 포함한 API 전체 10초 보장은 아니다. |
| `retries` | `3` | 재시도 가능한 Kafka 전송 오류에 한정한 최대 재시도 수다. 예산·오류 종류에 따라 더 일찍 끝날 수 있고, PG 결제나 주문 TX를 반복하지 않는다. |
| `acks` / `enable.idempotence` | `all` / `true` | broker 확인과 producer 재전송의 중복 보호를 사용한다. 단일 broker 실험으로 복제 장애 내구성이나 소비 DB의 한 번 효과를 증명하지 않는다. |
| `max.in.flight.requests.per.connection` / `linger.ms` | `5` / `0` | idempotent producer와 호환되는 in-flight 상한을 명시하고 별도 batch 대기를 추가하지 않는다. |

API는 `spring-kafka`에 직접 의존하는 전용 typed producer 설정을 사용한다. `modules:kafka`의 기존 batch consumer factory와 admin 주소 설정을 함께 끌어와 streamer 동작을 바꾸지 않는다. 기본 발행은 활성화하며, 관련 없는 테스트만 `application-test.yml`에서 명시적으로 끈다. 실제 broker 테스트는 이를 다시 켜고 자신의 주소를 주입한다. 비활성화는 성공으로 기록하지 않는다.

### W6-Q06 · 이번 실제 broker fixture와 남은 인계

생산자와 소비자 검증에는 각각 소유한 Testcontainers `apache/kafka:3.8.1` 한 대, 고유 이름의 2-partition topic과 검사 group을 사용한다. producer·admin·검사 consumer에 같은 fixture bootstrap을 주입하고 유한 poll/close 뒤 자신의 자원만 종료한다. 사용자 DB·기존 broker·다른 실행의 topic/group·볼륨은 건드리지 않는다. 이는 승인한 실제 Kafka TDD를 위한 격리 방식이며 기존 공유 Kafka 설정을 전역 변경하는 결정이 아니다.

직접 실행한 공유 로컬 broker를 재사용하는 대안은 자원 충돌과 테스트 결과 오염을 피하려고 채택하지 않는다. 선행 발행 검사 consumer의 직접 assignment/seek는 새 발행을 관찰하기 위한 것이며 공식 재전달·group 배정 실험을 대신하지 않는다. 이후 C05는 [별도 재전달 실험](../week6/purchase-activity-offset-redelivery-tdd-log.md)으로 검증했다. 첫 consumer만 target offset n을 commit/seek하고 DB commit 뒤 n+1 미커밋 상태로 종료하며, 새 consumer는 같은 group의 저장 offset에서 seek/reset 없이 읽는다. 실제 parser·handler와 PK 포함 DB snapshot·admin committed offset으로 동일 record 두 번 전달·업무 효과 불변을 확인했다. 이는 통제된 consumer 종료이며 OS 강제 종료나 모든 장애를 재현했다는 뜻은 아니다. 당시 후속이었던 누락 사례의 소비 DB 대조는 아래 C08 실험으로 검증했다. W7 두 사례의 고정 Clock·재현 준비와 실제 W6 구현 commit 해시 연결은 아래 C09 기록으로 완료했다.

C04의 partition/group 배정과 key 매핑은 [DB 없는 별도 probe 실험](../week6/kafka-group-assignment-tdd-log.md)으로 검증했다. 각 consumer는 단일 worker에서 계속 poll하며, broker의 STABLE/member 배정과 worker의 같은 generation·배정이 연속 일치하고 poll이 진행되는 것을 안정화 조건으로 둔다. 매 단계에서 두 partition에 명시적으로 보낸 probe의 실제 담당 worker·위치·내용·commit을 확인한다. key 실험에서는 partition을 지정하지 않고 같은 직렬화/key의 metadata를 비교하며 다른 key가 반드시 다른 partition이라는 조건은 두지 않는다. 전용 Kafka만 사용해 가짜 구매 활동을 만들지 않고 운영 concurrency/timeout은 변경하지 않는다. `acks=all`·복제 계수 1의 단일 broker 실험으로 다중 broker 복제 장애 내구성을 증명하지 않는다.

C01 정상 confirm 전체 흐름은 [별도 JVM 통합 실험](../week6/normal-pg-purchase-activity-e2e-tdd-log.md)으로 검증했다. 두 앱의 `com.loopers` 스캔과 JPA 모델이 섞이지 않도록 API 테스트 환경과 production main classpath만 사용하는 streamer JVM을 분리했다. Gradle은 테스트용 classpath 문자열과 원본 DDL 경로를 전달할 뿐 API의 운영 의존성에 streamer를 추가하지 않는다. API의 기존 Testcontainers DB와 별도 streamer MySQL을 같은 소유 Kafka의 고유 topic/group으로 연결하고, 실제 group 배정·Kafka 위치·구매 활동 DB·ack를 확인한다. 같은 confirm 재요청은 추가 결제/발행/재고 효과와 구매 활동 PK·필드 변경 없이 저장 결과를 유지한다. 운영 업무 정책·트랜잭션·잠금은 변경하지 않는다.

C08은 [commit 뒤 발행 누락 전체 흐름 실험](../week6/pre-send-failure-e2e-tdd-log.md)으로 보완했다. 정상 구매 활동이 저장된 같은 streamer JVM과 DB를 유지한 채 별도 주문의 publisher에서 send 직전 예외를 주입한다. 실패 주입 전에 `REQUIRES_NEW`의 독립 DB 연결로 주문 확정 commit을 확인하고, 주입 뒤 broker endOffsets 불변·대상 record 0과 완료/활동 0을 함께 대조한다. streamer가 실행되지 않아 활동이 없는 경우와 구분하려고 정상 소비의 PK/필드·offset 유지와 실제 프로세스 생존도 확인한다. confirm·callback·reconcile은 저장된 확정 사실을 보존하며 추가 PG 생성/발행으로 누락을 복구하지 않는다. 운영 TX·락·발행 정책은 바꾸지 않으며, 이 실험은 통제된 send 직전 실패이지 모든 전송 오류의 미기록을 보장하는 실험은 아니다.

주문 결제의 저장 시각은 전용 `orderPaymentClock`을 생성자로 주입한다. 운영은 `Clock.systemUTC()`를 사용하고 재현 테스트만 `2026-10-29T00:00:00.123456Z`의 UTC `Clock.fixed`로 바꾼다. 적용 범위는 결제 시도의 발송 시작과 PG 주문 최초 확정 시각 두 곳이다. 정상 소비·누락 사례 모두 같은 고정 시각을 저장하고 이벤트·구매 활동에는 저장된 최초 확정 시각을 전달한다. 전역 시계나 PG 대기/회로 시계를 고정하는 대안은 채택하지 않는다. 저장 사실의 재현과 경과 시간 제한을 분리하기 위해 단조 시간·timeout·회로·JPA 감사 시각·로그·Kafka 시계는 유지한다. TX·락·최초 확정 보존 규칙도 바꾸지 않는다.

[고정 Clock·W7 재현 가이드](../week6/fixed-clock-w7-reproduction.md)에 재현 코드·명령·실제 두 사례·정리 방법과 검사 범위를 남겼다. 선행 실행 당시에는 사용자 요청으로 커밋하지 않았고 W6 작업 전 HEAD를 구현 revision으로 사용하지 않았다. 2026-10-30에는 이미 존재하는 W6 구현 commit `dd86f49a9edf7a7ccdfc672be3f708f0f4a3ddd1`을 연결하고 동일 코드의 두 사례 E2E를 다시 실행해 C09를 완료했다. 새 코드 commit 없이 기존 commit을 재사용하며, 최신 사건 사실·실행 HEAD·명령·자원 정리는 [C09 재검증 기록](../week6/fixed-clock-w7-reproduction.md#2026-10-30-c09-revision-연결과-재검증)을 따른다.

### 검증·인계 상태

| ID | 후속 작업 | 고정된 기준 |
| --- | --- | --- |
| W6-Q06 · 인계 완료 | 실제 W6 구현 commit 해시 연결 | 고정 Clock·정상/누락 재현 코드·명령·사건 값·자원 정리와 기존 구현 `dd86f49`·동일 코드 재실행을 [C09 기록](../week6/fixed-clock-w7-reproduction.md#2026-10-30-c09-revision-연결과-재검증)에 연결. C11 최종 대조·제출과 구분 |

생산자 모델은 API 앱 안에 두며 streamer가 API 앱 클래스에 의존하지 않는다. streamer에는 독립적인 수신 모델·엄격한 JSON parser·key/orderId 검증과 저장 사실 비교를 구현했다. 생산자의 결정적 ID 형식을 소비자에 강제하지 않아 다른 eventId 충돌도 검출한다. 미정 선택은 구현 전에 질문하며 이미 선택한 Q01~05를 다시 승인받지 않는다.

[첫 이벤트 모델 TDD 기록](../week6/order-confirmed-event-tdd-log.md)은 당시 183개 검사 범위를 보존한다. 발행 연결의 당시 범위는 [발행 TDD 기록](../week6/order-confirmed-publication-tdd-log.md)으로 보존한다. 이후 소비 DB·listener 구현과 실제 검사 결과는 [구매 활동 소비 TDD 기록](../week6/purchase-activity-consumer-tdd-log.md)을 따르며 W6 전체 완료로 확대하지 않는다.

## W5 결제 교체의 선택과 질문

**2026-10-23 상태: 4단계의 내부 신규 시작·단건 조회에서 실제 PG HTTP와 주문·재고 TX A/B를 연결했다.** 해당 범위·실행 결과는 [HTTP·DB 연결 기록](../week5/order-payment-http-connection-tdd-log.md)을 따른다. [재고 기록](../week5/order-stock-reservation-tdd-log.md)·[예약 상태](../week5/order-reservation-state-tdd-log.md)·[결제 시도](../week5/payment-attempt-state-tdd-log.md)·[DB 연결](../week5/order-payment-persistence-tdd-log.md)·[독립 PG HTTP](../week5/local-test-pg-http-tdd-log.md)는 선행 증거로 보존한다. 고객 confirm·고객/관리자 주문 조회·최초 180/175초 제한 조회·PG 조회 회로를 연결했다. callback/reconcile HTTP도 연결했으며 이번 검증 결과는 [복구 API TDD 기록](../week5/payment-recovery-api-tdd-log.md)을 따른다. 선행 confirm·조회 증거는 [고객 결제 API 기록](../week5/customer-payment-api-tdd-log.md)으로 보존한다. 후속 사용자 선택으로 HMAC-SHA256 요청 비교 방식을 채택했으며, 담당자 확인은 선택적 보완 사항이며 구현의 필수 선행 조건이 아니다. [HMAC 계산·비교 단위 TDD](../week5/payment-request-fingerprint-tdd-log.md) 이후 PaymentAttempt·JPA·011 SQL의 [지문 저장](../week5/payment-request-fingerprint-persistence-tdd-log.md), 환경변수 키 공급·다중 버전 선택·Spring 포트 bean을 추가했다. 키 설정의 선행 결과는 [키 설정 기록](../week5/payment-fingerprint-key-configuration-tdd-log.md), 신규 지문의 TX A 공동 저장과 동일 요청의 저장 상태 반환은 [주문 HMAC 연결 기록](../week5/order-payment-fingerprint-connection-tdd-log.md)을 따른다. 고객 confirm은 `prepareCustomer`·`startAndAwait`로 PG 경로에 연결했다. 이전 POINT의 사실·본문 없는 재확정 오류는 보존하고 신규 PG에서는 포인트를 차감하지 않는다. 최초 요청 제한 조회·시간/회로 초기값·저장 A안·UUID·커밋 승자만 발송하는 기존 선택은 유지한다. 근거는 [W5 과제 원문](../week5/assignment-original.md)·[학습 자료](../week5/learning-original.md)와 사용자 답변이며, 기존 POINT 사실과 변경 대상이 아닌 P00~P16·W4 계약은 보존한다.

**PG 범위 명확화:** 업체가 제공하는 테스트 환경을 선택한 것이 아니라, 원문에 명시된 [로컬 HTTP 테스트 장치](commerce-erd-draft.md#이번-과제의-테스트-pg는-무엇인가)를 준비한다. 외부 업체 가입·테스트 키 발급이나 별도 PG 제품 개발은 포함하지 않는다. 실제 PG의 시간 안내는 선택 근거로만 참고하며 업체의 멱등성·재시도 계약을 실습 PG에 가져오지 않는다. Q01의 데이터 유무 미확인과 Q03의 예약 반환 여유 검사 승인은 유지한다.

### 원문으로 확정된 내용

| 경계 | 확정 내용·이유 | 적용 기준 |
| --- | --- | --- |
| 교체·보존 | 기존·신규 DRAFT는 PG, PG 포인트 변화 0. 이전 POINT 주문은 수단·금액·품목·결과와 재확정 409 유지 | P07·P15·P16의 기존 POINT 결제 설명을 새 PG에 그대로 적용하지 않는다. 충전·조회 API는 유지한다. |
| 외부 불확실성 | 생성 수락·timeout은 최종 실패/성공이 아니다. UNKNOWN은 예약 유지 | 외부 효과를 내부 DB rollback으로 취소했다고 주장하지 않는다. 조회 0건도 새 create·반환 근거가 아니다. |
| 한 번의 효과 | 주문당 한 시도·영속 발송 보호·한 번의 결과 반영. 생성 자동 재시도 금지 | 동일 종결 PG 시도는 저장 결과 반환. 최종 실패 뒤 새 키 재결제는 범위 밖이다. TX A 사전 검사 실패로 시도가 없었던 DRAFT는 별개다. |
| 트랜잭션 | 짧은 TX A에서 확보·시도 저장 → DB TX 밖 HTTP → 짧은 TX B에서 결과 반영 | 성공은 추가 차감 없이 소비, 확정 실패만 반환. 관련 상태와 재고는 같은 TX로 변경한다. |
| 기존 기능 | 예약 전 삭제는 시작 거절, 예약 후 삭제는 결과 처리 가능. 관리자 수량은 예약 제외 가용 수량 | soft delete를 복구하지 않고 예약을 반환한다. 캐시가 가용 재고를 담으므로 예약·반환에 버전 변경을 연결한다. |
| 회복·범위 | 권위 조회한 callback·수동 reconcile, 실제 timeout, PG 조회 한 경로의 circuit breaker는 필수 | 자동 조회 retry·스케줄러·별도 PG 제품·환불·정산·실제 카드·공개 배포는 필수로 추가하지 않는다. |

### 기술 제안과 대안

상태가 **제안**인 행은 사용자 승인이나 구현 완료가 아니다. 과제에서 금지한 대안을 다시 선택지로 제시하지 않고, 허용된 구현 대안과 비용을 비교한다.

| ID | 상태 | 설계안·이유 | 대안·비용 또는 채택하지 않을 방식 |
| --- | --- | --- | --- |
| W5-D01 | 내부 신규 시작·단건 조회의 TX A → HTTP → TX B 연결 | 비TX OrderPaymentCoordinator와 별도 프록시 OrderPaymentStockService로 나눈다. 조율 진입의 활성 외부 TX를 거절하고 기존 REQUIRED·READ_COMMITTED·잠금 순서를 유지한다. 기존 POINT 서비스 클래스 전체 TX 안에 HTTP를 추가하지 않는다. 실행 결과는 HTTP·DB 연결 기록 참조. | 명시적 TransactionTemplate도 가능하다. 경계가 드러나는 대신 호출부 관리가 필요하다. 같은 객체 자기 호출 annotation만으로 분리했다고 주장하지 않는다. |
| W5-D02 | 사용자 선택 A · W5-Q07, 모델·DB 연결 구현 | PaymentAttempt를 별도 저장하고 예약은 기존 OrderItem의 불변 quantity와 예약 상태로 관리한다. 009/010 SQL로 기존 POINT·DRAFT 이행을 검증했다. 실제 수량·내부 TX A/B·HTTP 조율은 후속 실행 기록으로 연결한다. 고객 confirm·조회는 연결했으며 callback/reconcile HTTP도 연결했으며 검증 결과는 최신 복구 기록을 따른다. | 별도 StockReservation은 미채택 B안이다. 아래 비교에 선택 이유와 품목 책임 증가 비용을 남긴다. 별도 테이블이 과제의 필수 조건인 것은 아니다. |
| W5-D03 | 4단계 내부 TX 연결·관련 검사 통과 | 기존 주문 행을 공통 진입 잠금으로 사용하고 필요한 사용자→브랜드 ID↑→상품 ID↑→시도 순서를 지킨다. 목록 버전은 마지막이다. | 조건부 갱신도 가능하지만 충돌·재조회 경계를 추가해야 한다. 메모리 락을 선택하지 않았으며 실제 SQL·경합 검사는 재고 기록에서 구분한다. |
| W5-D04 | 4단계 반환 행동 연결·관련 검사 통과 | 기존 Product 수량 규칙을 유지하며 검증된 HELD를 삭제 상품에도 반환한다. 삭제 시각은 바꾸지 않는다. | 활성 상품 수정 검사를 완화하지 않는다. 현재 가용량에 저장된 확보 수량을 더하며 옛 수량으로 덮어쓰지 않는다. |
| W5-D05 | 기존 선택 유지·내부 재고 연결 | W4 DB 버전 방식과 TTL 60초·local 캐시 OFF를 유지한다. TX A 예약·TX B 반환과 버전을 함께 변경한다. | 커밋 뒤 Redis DEL로 새 정책을 바꾸지 않는다. 예약 소비만 하는 성공에는 재고가 변하지 않으므로 불필요한 버전 증가를 넣지 않는다. |
| W5-D06 | 사용자 선택 · W5-Q02, 고객 최초 제한 조회 연결 | 최초 결제 요청 안에서 한정된 시간·횟수로 기존 거래를 더 확인한다. 개별 timeout만으로 즉시 끝내지 않으며, 종결 결과 확인 시 조기 반환하고 한도 소진 시 미완료 202를 반환한다. | PENDING 뒤 추가 조회 없이 즉시 202를 주는 안은 미채택이다. 선택안은 같은 응답에서 결과를 받을 가능성을 높이는 대신 연결·worker 점유와 PG 조회량이 늘어난다. |
| W5-D07 | 수량 정책·409 응답 사용자 승인, 관련 검사 통과 | 관리자 설정 시 `proposedAvailable + sum(HELD.quantity) ≤ Integer.MAX_VALUE`. 초과하면 `409 STOCK_CAPACITY_EXCEEDED`, “예약 반환 수량을 포함하면 재고 저장 한도를 초과합니다.”로 거절한다. | Long 확대는 HTTP·JPA·DDL·이행 영향 때문에 미채택이다. 유효한 int도 예약 상태와 충돌할 수 있어 기존 입력 400과 구분했다. 반환 실패를 UNKNOWN으로 숨기거나 예약을 버리지 않는다. |
| W5-D08 | 사용자 승인한 초기값 · W5-Q06, HTTP/최초 예산/조회 회로 구현 | 최초 요청 180초 목표, 외부 확인 175초 마감, 생성 1회·조회 최대 12회와 아래 timeout·간격·회로 설정을 사용한다. Apache HttpClient 5.4.2의 실제 응답/풀 대기·전체 호출 취소를 검증했다. | 175초 외부 확인 예산·제한 조회·조회 전용 회로를 연결했다. 180초는 운영 전체 하드 상한 검증이 아닌 목표다. 연결 3초는 설정했지만 연결 지연 실측은 아직 없다. 모든 PG의 표준값이나 실측 최적값으로 주장하지 않으며 값 변경 시 근거·영향을 확인한다. |
| W5-D09 | 지문 포함 TX A 동시 저장·내부 신규/동일 요청 연결 · W5-Q08 | 재고 예약·시도·주문 대기·발송 시작 기록을 TX A에서 함께 저장한다. 내부 startNew 또는 startOrReuse의 신규 분기에서 원자적 시작 전이와 지문을 함께 저장하고 TX A 커밋에 성공한 그 실행만 TX 밖에서 최초 POST한다. 상태를 읽은 것만으로는 발송할 수 없다. | 별도 짧은 claim TX는 미채택이다. 준비 직후 호출하는 이번 흐름에서 추가 커밋과 중간 재개 규칙을 줄인다. 시작 기록은 실제 PG 수신의 증거가 아니다. 커밋 직후 HTTP 전 중단의 불확실성과 자동 재발송 금지는 유지한다. 동일 요청 비교·저장 상태 반환은 고객 confirm에도 연결했으며 최초 발송 권한을 얻은 실행만 제한 조회한다. |
| W5-D10 | **계산·저장·환경변수 키 설정·TX A·고객 confirm 재요청 연결** | UUID로 시도를 식별하고 서버 HMAC-SHA256으로 요청 내용을 비교한다. 기존 무지문 행은 세 컬럼 NULL로 보존한다. 사용자 승인한 환경변수에서 현재·이전 키를 공급하고 저장된 키 버전으로 비교한다. 계산·저장·[키 설정](../week5/payment-fingerprint-key-configuration-tdd-log.md)과 [서비스 연결 증분](../week5/order-payment-fingerprint-connection-tdd-log.md)의 검증 범위를 구분한다. 내부 신규 준비는 지문을 필수 저장하고 재요청은 저장 버전으로 비교한다. | UUID만 비교하면 카드 변경을 놓치고, 메모리는 재시작에 약하며, 단순 해시는 후보 대입 비교가 쉽다. HMAC은 비밀키·형식 버전 관리가 필요하다. 환경변수는 이번 실습의 공급 선택이지 운영 보안 우위가 아니다. **과제 해석 확인은 Q04의 선택적 보완 사항**이며 담당자의 사전 승인을 구현 조건으로 요구하지 않는다. 기존 TX 경계·잠금 순서·SQL을 유지하며 고객 confirm HTTP만 승인된 PG 계약으로 전환했다. callback/reconcile HTTP도 연결했으며 검증 결과는 최신 복구 기록을 따른다. |
| W5-D11 | 주문·예약·시도·TX·고객 confirm/조회 연결 | [주문·시도 전이표](commerce-erd-draft.md#주문결제-시도의-상태-전이표)의 주문 4상태·시도 5상태와 `sendStartedAt`을 저장한다. UNKNOWN에서도 시작 기록·이미 연결한 거래를 보존한다. | 객체 변경·잠금 조회 연결만으로 실제 발송을 증명하지 않는다. 내부 신규 발송·단건 조회의 실제 HTTP·DB 실행 결과는 연결 기록, 고객 요청 비교·JSON 표현의 연결 결과는 고객 API 기록을 따른다. |
| W5-D12 | 사용자 확인 · W5-Q09, 모델·DB 검사 구현 | DRAFT의 결제수단은 null(아직 결제하지 않음). PG 준비부터 PG 필수, 기존 POINT 확정은 POINT 유지. 결제 진행·완료 상태의 null은 손상 상태로 거절한다. | 생성 시 PG를 기본값으로 넣는 안은 채택하지 않는다. 주문 생성과 결제 시작은 다른 시점이며, PG는 모든 미래 결제수단을 뜻하는 강제가 아니라 W5 신규 결제 경로다. |
| W5-D13 | 3단계 HTTP 구현 선택 | Boot 관리 Apache HttpClient 5.4.2를 직접 사용한다. pool/connect/response timeout과 요청 취소, 자동 retry/redirect 차단을 같은 경계에서 명시한다. 테스트 서버는 JDK HttpServer다. | RestClient + Apache request factory도 가능하지만 이번에는 개별 요청 취소와 연결 회수를 직접 확인하는 작은 adapter를 선택했다. WebClient·Feign·실제 PG SDK나 별도 PG 제품을 추가하지 않는다. |

Q03의 합은 **해당 상품의 모든 주문 중 아직 소비·반환되지 않은 HELD 수량**이다. 상품 잠금 뒤 비잠금 집계로 읽고 확보·소비·반환도 같은 상품 행을 보호한다. 관리자 경로에서 주문 행을 역순 잠그지 않는다. 넓은 중간 타입과 범위 검사로 합산 오버플로를 막는다. 상한 동등은 허용하고 초과 시 가용량·예약·목록 버전을 보존한다. 예약이 없으면 기존 최댓값도 허용한다. **후속 사용자 승인으로 상한 오류만 `409 STOCK_CAPACITY_EXCEEDED`와 위 문구를 확정했다.** Q05의 나머지 오류·ACK까지 정한 것은 아니다.

반환 여유의 이유와 [앞으로 고민할 점](../week5/order-stock-reservation-tdd-log.md#앞으로-고민할-점--이번-필수-구현과-구분)은 실행 기록에 정리한다. Long 확대·관리자 최대 수량 표시·HELD 합계 성능 개선은 별도 판단이며 필수 미완료 기능으로 추가하지 않는다. 장기 HELD를 시간만으로 해제하거나 예약을 삭제하는 방식은 외부 성공 가능성을 무시하므로 채택하지 않는다.

### 질문·답변과 구현 전 남은 결정

| ID | 질문·상태 | 답에 따라 달라지는 것·다음 행동 |
| --- | --- | --- |
| W5-Q01 | 로컬 DB에 보존할 실제 주문·포인트·PG 기록이 있는가? **사용자: 현재는 잘 모르겠음. 로컬 기존 기록 유무는 아직 확정하지 못함.** | 읽기 전용 점검만으로 0건이라고 단정하지 않으며 보존을 전제로 한다. 사용자 DB에 011을 적용하거나 DB·볼륨을 삭제/초기화하지 않는다. 사용자 승인에 따라 격리 Testcontainers DB에서 기존 행 보존 이행을 검증하며 관찰·실행 결과는 지문 저장 기록을 따른다. |
| W5-Q02 | 최초 요청에서 제한적으로 더 조회하는 방식 선택. **사용자: 최초 결제 요청부터 전체 2~3분을 뜻하며, 후속 180초 초기안을 문서에 반영하도록 요청.** | D06·D08과 아래 시간 예산을 구현 목표로 사용한다. 개별 timeout마다 시간을 초기화하지 않는다. callback 선종결 결과를 늦은 PENDING/timeout으로 되돌리지 않는다. callback·수동 reconcile에 최초 요청의 180초 루프를 자동 적용하는 선택은 아니다. |
| W5-Q03 | 관리자 재고 설정에 예약 반환 여유 검사를 추가해도 되는가? **사용자 승인: 반환 여유까지 검증해 설정 거절.** | D07을 구현 목표로 확정한다. 새 검사와 실패 시 저장 상태 보존·동시성·상한 경계를 테스트한다. 기존 숫자 타입을 확대하지 않는다. |
| W5-Q04 | **사용자 선택:** 호출자 UUID·전역 유일성·PG 규격 카드·HMAC-SHA256·과거 전체NULL 보존. 후속 HTTP-D01~03에서 JSON·요청 비교 오류·서버 PG ID도 선택했다. 과제 해석 질문은 선택 사항이며 사전 승인 조건이 아니다. | 다른 주문의 같은 키·같은 주문의 새 키·다른 내용은 충돌이다. 소유권 우선·과거 지문 소급 생성 금지·조회 복구 유지는 계속 적용한다. 상세 계약은 아래 HTTP 선택표를 따르며 고객 confirm·조회에 연결했다. 번호 한 종류 제한이나 과제 담당자의 공식 승인으로 확대하지 않는다. |
| W5-Q05 | **Q03 상한 오류와 후속 W5-HTTP-D01~03의 JSON·요청 충돌/비교 불가 오류·PG ID는 사용자 선택 완료.** | 구체 계약은 아래 HTTP 선택과 API 상세 표를 따른다. 후속 HTTP-D04에서 PG 비활성화·외부 결과 충돌의 고객 응답도 선택했다. 후속 위임 선택 REC-D01~06에서 callback ACK·충돌 WARN 구조 로그·미시작 reconcile 오류를 정했다. HTTP 연결·검증 완료와는 구분한다. |
| W5-Q06 | timeout·최초 조회 예산·회로 수치는 **초기 설계값 승인, 개별 HTTP/최초 제한 조회/조회 회로 연결**. | Apache HttpClient 5.4.2의 응답·풀·전체 호출 취소는 [3단계 기록](../week5/local-test-pg-http-tdd-log.md)을 따른다. 175초 조율·조회 횟수/간격·회로를 연결했다. callback·별도 reconcile은 REC-D04의 GET 최대1회·기존 호출전체10초를 선택했다. HTTP는 연결했으며 검증 결과는 최신 복구 기록을 따른다. 운영 전체 하드 상한 검증은 별도다. |
| W5-Q07 | 예약 저장 위치는 **사용자 A 선택: 기존 OrderItem에 예약 상태 추가**. | PaymentAttempt 별도 JPA 저장 + OrderItem 예약 상태와 009/010 이행을 구현했다. 별도 StockReservation은 미채택. 재고·캐시 버전·내부 TX는 재고 기록, 실제 PG 조율은 HTTP·DB 연결 기록을 따른다. 고객 confirm·조회는 연결했으며 callback/reconcile HTTP도 연결했으며 검증 결과는 최신 복구 기록을 따른다. |
| W5-Q08 | 발송 시작 기록의 TX 위치는? **2026-10-23 추가 비교 후 사용자 선택: 재고 예약·시도·주문 대기와 TX A에 한 번에 저장.** | 발송 기록을 실제 PG 도달 사실과 구분한다. 공동 저장/롤백과 내부 신규 실행의 커밋 후 POST를 연결했다. 실제 실행 결과는 HTTP·DB 연결 기록과 주문 HMAC 연결 기록을 따른다. 재요청 비교와 고객 HTTP 응답도 연결했다. |
| W5-Q09 | DRAFT의 결제수단 null을 허용할까? **사용자: null을 기본으로 두고 필요한 경우 오류 처리에 동의.** | D12에 따라 DRAFT만 null을 허용하고 PG 준비 이후 누락은 모델·수동 SQL에서 거절한다. POINT 과거 행만 POINT로 이행하며 금액·시각·품목 사실을 바꾸지 않는다. |

### 고객 결제 HTTP 계약 선택 · 2026-10-23

사용자가 아래 세 추천안을 선택했다. [API 상세 계약](commerce-api-contract.md#고객-결제-api-상세-계약)이 JSON·메시지·상태별 반환의 단일 기준이며, 실제 구현·검사는 [계약 TDD 기록](../week5/customer-payment-contract-tdd-log.md)으로 구분한다. Q04·Q05 중 이 표의 범위는 더 이상 미정이 아니다. 후속 고객 API 연결에서 비활성화·PG 결과 충돌 응답도 선택했다. callback ACK·충돌 구조 로그·미시작 reconcile 오류·복구 요청 예산은 아래 REC-D01~06의 위임 선택으로 정했다. 구현·검증은 별도 증거로 확인한다.

| ID | 사용자 선택 | 선택 이유·대안 |
| --- | --- | --- |
| W5-HTTP-D01 | 요청은 `paymentAttemptKey/cardType/cardNo`, 응답은 기존 주문 필드 + `paymentMethod/payment`와 PG 조회 경로. 정상 상태 반환은 meta.SUCCESS, 실제 결제 결과는 payment.status | 입력과 PG 필드 대응이 쉽고 기존 주문 구조를 보존한다. 결제 FAILED도 확인된 업무 결과로 200 반환한다. meta.FAIL로 처리하는 대안은 API 거절과 결제 결과를 혼동시키고 data 생략 규칙과 충돌한다. |
| W5-HTTP-D02 | 요청 내용 충돌409 `PAYMENT_REQUEST_CONFLICT`, 과거 전체NULL 지문409 `PAYMENT_REQUEST_UNVERIFIABLE`, 키/형식 사용 불가503 `PAYMENT_VERIFICATION_UNAVAILABLE` | 기다려도 비교값이 생기지 않는 기존 기록과 서버 복구가 필요한 상황을 구분한다. 모두503으로 합치는 안은 고객 대응을 구별하기 어렵다. 내부 사유를 타입으로 구분하고 손상 데이터는 기존500으로 보존한다. |
| W5-HTTP-D03 | 신규 providerOrderId는 `LP-ORD-` + 주문 ID 최소10자리, 기존 시도는 저장값 재사용 | 주문당 한 시도인 실습에서 추적이 간단하다. 별도 서버UUID보다 읽기 쉽지만 여러 DB의 같은 PG 계정 공유/DB 단독 초기화에는 부적합할 수 있다. PG의 멱등 지원을 가정하지 않는다. |
| W5-HTTP-D04 | 후속 사용자 승인: PG 비활성화 시 신규 DRAFT503 `PAYMENT_PROVIDER_UNAVAILABLE`, 외부 결과 충돌409 `PAYMENT_RESULT_CONFLICT` | PG OFF에서도 기존 PG 재요청은 `reuseCustomer`로 지문을 비교해 저장 결과 또는 정해진 비교 오류를 반환한다. 신규 시도·예약·PG 호출·POINT 대체는 하지 않는다. 식별·금액·종결 충돌은 업무 실패로 단정하지 않으며 기존 상태·예약을 보존한다. 정확한 메시지는 API 표를 따른다. callback ACK·충돌 운영 기록까지 확정한 것은 아니다. |

이번 선택으로 최초 confirm의 180초 목표/175초 외부 확인·최대12회, 같은 요청의 저장 상태 즉시 반환, 기존 POINT 호환·포인트 변화0·TX/잠금·사용자 DB 미변경 조건을 바꾸지 않는다. 선행 DTO·오류·ID 생성 기록과 실제 [고객 API 연결 기록](../week5/customer-payment-api-tdd-log.md)을 구분한다.

### 발송 시작 기록의 TX 배치 비교

**2026-10-23 W5-Q08 사용자 선택: A, TX A에 함께 저장.** 내부 신규 준비의 실제 재고 연결은 [재고 기록](../week5/order-stock-reservation-tdd-log.md), 커밋 후 PG 발송 조율의 실행 결과는 [HTTP·DB 연결 기록](../week5/order-payment-http-connection-tdd-log.md)을 따른다. 처음 추가 비교 후 선택한 결정으로, 예약 저장 위치 A안(OrderItem 확장)과 구분한다. 아래 미채택 대안도 보존하며 재고 5개 중 2개를 주문하는 장면으로 비교한다.

| 비교 | A · TX A에 함께 저장 (사용자 선택) | B · 준비 뒤 별도 짧은 발송 TX (미채택) |
| --- | --- | --- |
| 순서 | TX A: 가용 3·HELD 2·시도·주문 대기·발송 시작을 함께 커밋 → TX 밖 PG → TX B | TX A: 가용 3·HELD 2·시도·주문 대기 커밋 → 별도 TX: 발송 시작 선점·커밋 → TX 밖 PG → TX B |
| 준비 저장이 실패하면 | 준비·발송 기록 모두 rollback, PG 0 | 준비 전체 rollback, 발송 TX/PG 0 |
| 준비 커밋 후, 발송 시작 커밋 전 서버 중단 | 두 기록이 함께 커밋되므로 이 중간 구간 없음 | 준비됐지만 발송 시작 기록 없는 시도가 남을 수 있음. 재고는 이미 예약된 상태 |
| B의 중간 상태를 이어가려면 | 해당 없음 | 동일 입력을 확인하고 카드 입력을 일시적으로 다시 확보할 실행·재개 조건이 필요. 카드 비저장 조건상 DB만 읽는 자동 발송으로 해결하지 않음. 현재 재개 방식은 선택하지 않음 |
| 발송 시작 커밋 직후, 실제 HTTP 전 서버 중단 | 실제 호출 여부가 불확실. 조회 0건이어도 자동 재발송·반환 금지 | 동일한 한계. TX를 나눈다고 DB 커밋과 외부 HTTP가 원자적으로 이어지지 않음 |
| 이득·비용 | DB 왕복과 중간 복구 상태가 적고, 준비 직후 바로 보내는 이번 흐름에 단순함 | 준비와 발송 단계를 구별하기 쉬움. 대신 추가 TX·발송 전 재개 규칙·검증이 필요 |

선택 이유는 **한 주문·한 시도, 준비 직후 같은 실행에서 PG 호출**이라는 현재 범위에서 준비·발송 기록을 한 번에 보호하고 중간 커밋/복구 단계를 줄이는 것이다. 발송 기록은 “PG가 받았다”는 증거가 아니라 **우리 쪽에서 발송을 선점했고 이후 실행은 다시 보내면 안 된다는 영속 기준**이다. 최초 전이·커밋을 성공시킨 실행만 보내며, 기록을 읽은 다른 실행은 보내지 않는다. 이 선택이 PG 자체의 멱등성이나 모든 시도의 자동 완료를 보장하지는 않는다.

실제 영속화·통합 테스트는 **TX A의 예약·시도·주문 대기·발송 기록·목록 버전이 함께 커밋/rollback**되는지, 커밋 전에 HTTP가 시작되지 않는지 검증한다. 커밋 직후 실제 HTTP 전에 중단되면 기록은 있지만 외부 거래는 0건일 수 있고, 재시작 후 자동 create·임의 반환은 여전히 금지한다. 발송 TX 선택이 callback ACK·충돌 기록·새 오류(Q05), 별도 reconcile 예산(Q06)까지 승인하는 것은 아니다. HMAC 기술 방식은 아래 D10의 후속 사용자 선택으로 따로 확정했다.

### 카드 입력과 최초 발송의 선택

**2026-10-22 사용자 선택, 2026-10-23 내부 HTTP·DB 연결.** 카드 입력은 [과제의 로컬 PG HTTP 계약](commerce-api-contract.md#로컬-테스트-pg의-별도-http-계약)에 맞춰 adapter에서 전달한다. 내부 신규 startNew의 최초 발송은 PG 호출 전 DB 상태 전이·커밋으로 보호하며 [실행 결과](../week5/order-payment-http-connection-tdd-log.md)를 따른다. 후속 내부 startOrReuse는 같은 키의 카드 변경을 저장 지문으로 비교한다. 고객 confirm API도 `startAndAwait`·`prepareCustomer`에 연결했다.

| 구분 | 선택·보호할 의미 | 선택하지 않았거나 아직 남은 것 |
| --- | --- | --- |
| 카드 입력 | `cardType`·`cardNo`를 테스트 PG 규격에 맞춰 전달하고 TEST·합성 값만 사용. 합성 번호도 DB·로그에 남기지 않음 | 원문의 `0000-0000-0000-0000`은 예시이며 그 번호 하나만 허용하는 추가 제한을 확정하지 않음. 원문이 정하지 않은 허용 형식·검증 상세를 임의 추가하지 않음 |
| 동일 요청 | 승인한 UUID 키·주문·저장 금액과 요청의 일치 여부를 확인 | 카드 입력이 달라져도 키만 같으면 동일 요청으로 취급하는 것은 금지. [HMAC 계산·지문 저장](#w5-hmac-요청-지문-제안)을 내부 prepareOrReuse·startOrReuse에 연결했지만 고객 confirm까지 PG 흐름과 비교를 연결. 과제 해석 확인은 선택 사항 |
| 최초 발송 | 로컬의 `미시작 → 발송 시작` 전이를 DB에서 원자적으로 성공시키고 커밋한 실행만 POST | 상태 이름은 의미 설명용. 이미 시작된 상태를 조회한 실행·동시 재요청·재시작한 실행에는 새 발송 허용 없음 |
| 상태의 구분 | 로컬 발송 시작은 우리 쪽 실행 이력이고, PG의 PENDING은 외부가 처리 중이라고 응답한 관찰 | 우리 DB의 status를 변경했다고 외부 PG가 접수·성공한 것으로 간주하지 않음 |
| 영속성과 복구 | 이후 PENDING/UNKNOWN/종결로 바뀌어도 한 번 시작한 발송 이력을 보존. 생성 자동 재시도 없이 같은 거래 조회 | 단일 status 값만 바꾸며 발송 이력을 지우는 설계는 불가. 기존 PaymentAttempt 안에서 표현할 수 있으며 별도 발송 권한 모델/테이블이 필수라는 뜻은 아님 |

DB 상태 전이가 rollback되거나 커밋 여부를 확인하지 못하면 PG POST를 시작하지 않는다. 커밋 뒤에는 DB TX·잠금을 해제하고 HTTP를 호출한다. 반대로 커밋 직후 HTTP 전에 멈추면 거래 0건·예약 유지가 남을 수 있으며, 재시작 후 조회 0건도 재발송·자동 반환 근거가 아니다. **2026-10-23 Q08 후속 선택으로 발송 시작 기록은 준비 TX A에 함께 둔다.** 객체·필드·잠금 구현은 D01·D03·D11에서 별도로 상세화하고, 원문이나 사용자 선택을 추가 상태 이름·테이블의 강제로 확대하지 않는다.

<a id="w5-hmac-요청-지문-제안"></a>

### W5 HMAC 요청 지문 선택

**2026-10-23 사용자 선택(W5-D10·Q04): UUID 시도 식별 + 서버 HMAC-SHA256 요청 내용 비교를 채택한다.** 2026-10-22의 제안 문서화·채택 보류 이후 [첫 계산·비교 증분](../week5/payment-request-fingerprint-tdd-log.md)을 구현했다. 후속 승인에 따라 PaymentAttempt·JPA·011 SQL에 지문 저장을 추가하고 기존 무지문 행은 세 컬럼 NULL로 보존한다. 격리 DB 검증 결과는 [저장 TDD 기록](../week5/payment-request-fingerprint-persistence-tdd-log.md)을 따른다. 이후 사용자가 환경변수 공급을 승인하여 키 설정·버전 선택·Spring 포트 bean까지 추가했다. [키 설정 기록](../week5/payment-fingerprint-key-configuration-tdd-log.md)은 합성 키 설정 검증이다. 후속 [주문 HMAC 연결](../week5/order-payment-fingerprint-connection-tdd-log.md)에서 내부 TX A·재요청에 연결했으며, [고객 API 기록](../week5/customer-payment-api-tdd-log.md)의 증분에서 고객 confirm과 PG OFF의 기존 시도 재요청에도 연결했다.

| 구분 | 현재 상태 |
| --- | --- |
| 기술 방식 | **선택 완료.** 카드 원문 없이 요청 내용을 비교할 서버 HMAC-SHA256을 사용하도록 설계 |
| 과제 해석 | **선택적 확인.** 원문에 HMAC 금지나 담당자 사전 승인 의무는 없다. 필요 시 해석을 확인하되 구현의 필수 선행 조건으로 두지 않음 |
| 구현·검증 | 순수 계산·비교, **PaymentAttempt·JPA·011 저장, 환경변수 키 설정·다중 버전 선택·Spring 포트 bean을 구현**했다. 저장과 키 설정의 실행 결과는 각각의 기록을 따른다. 내부 서비스 TX A의 지문 공동 저장과 startOrReuse의 재요청 비교를 연결했다. 고객 confirm의 PG 전환·HTTP 응답도 연결했으며 최신 실행은 [고객 API 기록](../week5/customer-payment-api-tdd-log.md) 참조 |

카드 번호 원문은 confirm 실행 중 검증·지문 계산에 일시 사용하고, 발송 권한을 얻은 실행만 PG에 보낸다. DB·로그에는 남기지 않는다는 기존 조건을 유지한다. 서버 비밀키는 고객의 `paymentAttemptKey`와 다른 값이다.

**선행 조건 정정(2026-10-23):** 이전의 ‘담당자 확인 후에만 지문 저장을 구현한다’는 문구는 원문보다 강한 제약이어서 철회한다. HMAC 선택과 카드 번호 비저장·DB 중복 보호 요구는 유지한다. 당시 구현하지 않은 이유는 사용자가 문서 정정만 요청했기 때문이었다. 이후 별도 실행 승인으로 계산·비교 단위 증분을 진행했다.

#### 원문 근거와 선택적 확인

| 구분 | 확인한 내용 |
| --- | --- |
| 원문 요구 | [과제 원문 2·3절](../week5/assignment-original.md)은 같은 키의 다른 주문·금액·요청을 충돌로 처리하고, 합성 카드 번호도 DB·로그에 남기지 않으며 시도에는 금액과 필요한 식별 관계만 보존하도록 한다. |
| 해석과 선택 근거 | 원문에는 HMAC 금지나 담당자 사전 승인 요구가 없고, [채점 기준 정리의 직접 선택 범위](../week5/grading-criteria.md#직접-선택할-것과-확대하지-않을-범위)에도 요청 일치 판별 방법이 포함된다. 카드 원문 없이 필요한 비교값을 보존하는 HMAC을 요구 충족을 위한 구현 선택으로 판단한다. 이를 과제 담당자의 공식 승인이나 카드 유래 정보의 무조건적 안전성으로 주장하지 않는다. |
| 선택적 확인 질문 | 필요 시 “합성 카드 번호 원문은 DB·로그에 남기지 않고, 같은 키의 카드 변경을 구분하기 위한 서버 HMAC 요청 지문만 저장해도 ‘필요한 식별 관계만 보존’ 조건에 부합하는가?”를 확인할 수 있다. 답변 수신을 구현 착수 조건으로 두지 않는다. |
| 추후 명시적 제약이 추가되면 | 현재 없는 금지 조건을 가정해 구현을 보류하지 않는다. 이후 카드 유래 비교값까지 금지하는 명시적 안내가 생기면 요청 비교 범위나 다른 식별 계약을 재검토한다. 현재 PG 조회 응답에는 카드 정보가 없으므로 UUID만 같으면 다른 입력도 같다고 간주하는 방식으로 우회하지 않는다. |

#### 선택한 비교 방식과 저장 설계

UUID는 **어느 결제 시도인지**, HMAC은 **그 시도의 요청 내용이 같은지**를 구분한다. DB의 발송 상태 전이·커밋은 **누가 PG를 호출할 수 있는지**, TX B의 종결·예약 상태는 **결과를 이미 반영했는지**를 보호한다. HMAC은 유일 키나 PG 멱등 키가 아니며 기존 DB 보호를 대체하지 않는다.

| 항목 | 선택한 내용·이유 |
| --- | --- |
| 계산 | 서버가 `HMAC-SHA256(secret[keyVersion], canonical(fingerprintVersion, userId, orderId, storedAmount, cardType, cardNo))`를 계산한다. 사용자 ID는 검증된 식별값, 금액은 주문에 저장된 값이다. client가 보낸 지문을 신뢰하지 않는다. |
| 입력 표현 | 용도·필드 경계·타입·순서가 명확한 버전별 직렬화를 사용한다. 단순 문자열 이어 붙이기나 원본 JSON 전체 비교는 피하며, JSON 공백·필드 순서는 판별 기준이 아니다. 카드 형식은 PG 계약에 따른 검증 후 사용하고 임의로 하이픈·공백을 제거해 다른 입력을 합치지 않는다. 현재 시각·진행 상태·매번 바뀌는 난수는 넣지 않는다. 첫 구현에서 아래 canonical v1 규격을 고정했다. |
| 저장 구조 · 모델/JPA/011 구현 | PaymentAttempt의 `request_fingerprint`는 nullable `VARBINARY(32)`, `fingerprint_version`·`key_version`은 nullable 양수 `INT`다. CHECK는 **세 값 모두 NULL 또는 모두 존재하며 지문 32바이트·버전 양수**만 허용한다. 지문에는 유일 인덱스를 추가하지 않는다. 모델은 setter 없이 방어복사·수정 불가 필드로 보존한다. 카드 원문·원본 요청 본문·복원용 암호문·비밀키와 별도 지문 테이블은 저장하지 않는다. |
| 최초 저장 · 내부 TX A 연결 | `prepareNew`와 `prepareOrReuse`의 신규 분기는 6인자 PaymentAttempt 생성자로 유효한 지문을 필수 저장한다. 기존 5인자 생성자는 과거 무지문 자료·fixture 호환용이며 실제 신규 준비 경로에서는 사용하지 않는다. 시도·예약·주문 대기·발송 기록·지문·목록 버전이 TX A에서 함께 커밋/rollback된다. 기존 UUID/주문 유일 제약·TX·잠금 순서를 유지하며 이미 저장한 지문을 재요청 값으로 덮어쓰지 않는다. |
| 기존 무지문 행 · 사용자 승인 | 001~010의 기존 행에 011만 추가하고 세 컬럼을 NULL로 보존한다. Optional.empty와 비교 불가 예외로 표현하며 소급 지문·PG FAILED 추정은 없다. 후속 HTTP-D02에서 전체NULL409·서버검증503·저장손상500을 구분했다. 기존 조회·거래 복구는 유지하며 고객 confirm·조회에 연결했다. |
| 재요청 · 내부 연결 | `prepareOrReuse`는 소유권·주문/키·제공자 관계와 저장 지문을 확인한다. 같은 내용은 현재 저장 상태만 반환하고 추가 HTTP·예약 변경은 없다. 같은 주문의 새 키·다른 주문의 같은 UUID·다른 내용과 UUID 제약의 최종 충돌은 `PaymentRequestConflictException`이다. 후속 HTTP-D02가 `409 PAYMENT_REQUEST_CONFLICT`의 고정 응답을 선택했다. 종결 시도도 비교를 생략하지 않는다. |
| 복구·호환 | GET·reconcile·callback에 카드나 지문 재제출을 요구하지 않는다. 기존 식별 관계와 권위 조회로 결과를 확인하며 TX B의 한 번 반영 규칙을 유지한다. 이전 POINT 재확정의 본문 없는 409 계약에 PG 지문 검사를 추가하지 않는다. |

같은 내용의 confirm 재요청은 관찰 시점의 저장 상태·결과를 반환한다. 처음 202, 나중에 종결 결과 200이 될 수 있으며 최초 HTTP 응답을 영구 복제하는 설계가 아니다. HMAC이 같아도 이미 발송을 시작한 시도에 새 create를 보내지 않는다.

#### canonical v1 · 계산 증분에서 고정한 기술 규격

| 순서 | 바이트 표현 |
| --- | --- |
| 1 | 용도 태그 `LP-PAYMENT-REQUEST`의 고정 ASCII 18바이트 |
| 2 | `fingerprintVersion=1`, 4바이트 big-endian 정수 |
| 3 | 검증된 내부 `userId` → `orderId` → 주문에 저장된 `storedAmount`, 각각 8바이트 big-endian 정수 |
| 4 | `cardType`의 UTF-8 **바이트 길이** 4바이트 → 해당 원문 바이트 |
| 5 | `cardNo`의 UTF-8 **바이트 길이** 4바이트 → 해당 원문 바이트 |

JSON 원문·시각·UUID·진행 상태는 넣지 않는다. UUID와 주문의 관계는 별도 DB 경계가 보호하며, 이 계산은 같은 내용인지 판단하는 역할만 맡는다. UTF-8로 표현할 수 없는 깨진 문자열은 대체 문자로 바꾸지 않고 거절한다. Unicode·공백·하이픈·대소문자를 임의 정규화하지 않는다. 이 인코딩 규칙이 새 카드 허용 목록이나 고객 HTTP 오류 계약을 정하는 것은 아니다.

첫 계산기는 양의 정수 `keyVersion`과 외부에서 전달할 32바이트 이상 비밀키 한 개를 받는다. 32바이트 하한은 SHA-256 출력 길이에 맞춘 구성 방어이며 키의 무작위성·안전성을 길이만으로 증명하지 않는다. [RFC 2104의 키 길이 권고](https://www.rfc-editor.org/rfc/rfc2104.html#section-3)를 참고했다. 키는 복사해 보관하고 호출마다 별도 `Mac`을 만든다. 지문은 전체 32바이트를 `MessageDigest.isEqual`로 비교하며 임시 배열은 사용 후 정리한다. Java 문자열·암호 구현 내부 복사본까지 완전 삭제한다고 주장하지 않는다.

순수 계산기의 알고리즘과 canonical v1은 유지한다. 후속 `VersionedPaymentRequestFingerprinter`가 버전별 계산기를 선택하고 `PaymentFingerprintConfiguration`이 환경변수로 구성한 `PaymentRequestFingerprinter` bean을 등록한다. DB 자료형과 기존 행의 NULL 보존 이행은 [011 저장 증분](../week5/payment-request-fingerprint-persistence-tdd-log.md), 키 공급은 [키 설정 증분](../week5/payment-fingerprint-key-configuration-tdd-log.md)으로 구분한다. 내부 서비스 TX A·재요청은 이 포트를 호출하며 고객 confirm도 같은 경계에 연결했다. 키가 없거나 저장 지문의 형식/키 버전을 지원하지 않으면 `PaymentFingerprintUnavailableException`을 내며, 내용 불일치나 PG FAILED로 바꾸지 않는다. 고객 HTTP는 이를 승인된 `503 PAYMENT_VERIFICATION_UNAVAILABLE`로 분류하고 GET은 지문 비교 없이 저장 사실을 반환한다. [첫 계산 증분의 실제 테스트와 한계](../week5/payment-request-fingerprint-tdd-log.md)는 당시 증거로 보존한다.

#### 선택 이유와 대안

| 후보 | 검토 결과·비용 |
| --- | --- |
| UUID·주문·금액만 비교 | 구조는 단순하지만 같은 키로 카드만 변경한 요청을 구별하지 못한다. 카드 입력까지 비교할 이번 설계의 목적에는 부족하다. |
| 카드/요청의 단순 SHA-256 | 서버 비밀키 관리가 없지만 후보가 적은 합성 카드 값을 대입해 비교값을 추측하기 쉽다. 선택한 HMAC보다 우선하지 않는다. |
| 서버 HMAC-SHA256 | 카드 원문 없이 요청 내용을 비교할 수 있고, DB 비교값만으로 비밀키 없는 후보 대입 검증을 어렵게 한다. 대신 키 및 형식 버전 관리가 필요하다. **기술 선택은 유지하며 담당자 사전 승인을 구현의 필수 조건으로 두지 않는다.** |
| 카드 원문·복원용 암호문 저장 | 직접 비교할 수 있지만 카드 번호를 보관하지 않는 이번 조건·설계와 맞지 않아 선택하지 않는다. |
| 메모리에만 비교 정보 보관 / PG 카드 토큰 | 전자는 재시작·다중 서버에서 비교 근거를 잃는다. 후자는 현재 로컬 PG에 토큰 발급 계약이 없어 바로 채택할 수 없다. 과제 PG 규격을 임의 확장하지 않는다. |

선택 이유는 **카드 변경 판별·재시작 후 비교·카드 원문 비저장**을 함께 충족하려는 것이다. 비밀키·형식 버전 관리 비용을 받아들이되, 현재 계약에 없는 PG 토큰 API나 별도 지문 테이블은 추가하지 않는다.

HMAC은 복호화해 카드 번호를 꺼내 쓰는 암호문이 아니지만 **카드 유래 비교 정보**이므로 완전한 익명화나 실제 카드 보안 요건 충족으로 주장하지 않는다. 비밀키까지 유출되면 후보 대입 위험이 남는다. 알고리즘·검증 근거는 [RFC 4231의 HMAC-SHA-256과 테스트 벡터](https://datatracker.ietf.org/doc/html/rfc4231)를 참고하며 이 기술 문서가 과제의 저장 허용 근거는 아니다.

#### 키 관리와 검증 범위

**2026-10-23 사용자 승인: 환경변수로 공급한다.** 소스·YAML·DB에 비밀키 값을 넣지 않으며 기본 키나 시작마다 새로 생성하는 키를 사용하지 않는다. 아래 예시는 형식 설명용 placeholder로 실행 가능한 키가 아니다.

| 설정·동작 | 구현한 경계 |
| --- | --- |
| `PAYMENT_FINGERPRINT_CURRENT_KEY_VERSION` | 신규 지문을 계산할 양의 `int` 버전. 부호·공백·선행 0 없는 십진 문자열이며 키 목록에 반드시 존재해야 함 |
| `PAYMENT_FINGERPRINT_KEYS` | `{"1":"<Base64-encoded-key>"}` 형태의 JSON 객체. 버전 이름도 위 정수 규칙을 따르고 값은 문자열만 허용. 각 값을 표준 Base64로 디코딩한 키는 32바이트 이상이어야 함 |
| 입력 출처·검증 | Spring `systemEnvironment` property source의 두 값만 직접 읽는다. YAML·CLI·Java system properties의 대체값, placeholder 해석, Spring Binder는 사용하지 않는다. 중복 JSON 키·뒤따르는 JSON·잘못된 항목 하나라도 있으면 전체 설정을 사용하지 않음 |
| 적용 시점·버전 선택 | 앱 시작 시 한 번 읽은 불변 구성. 자동 refresh는 없다. 신규 계산은 현재 버전, 비교는 저장 지문의 `keyVersion`에 해당하는 계산기를 사용하며 형식은 canonical v1을 유지 |
| 누락·잘못된 설정 | 값·원인 예외를 싣지 않는 고정 WARN과 비교 불가 포트 bean을 등록한다. 앱 시작 자체는 막지 않으며 계산·비교 호출만 `PaymentFingerprintUnavailableException`으로 거절 |
| 저장 버전 미지원 | 키 버전이나 지문 형식을 지원하지 않으면 비교 불가다. 현재 키로 대체·다른 버전 순회·같은 요청 추정·저장 지문 덮어쓰기를 하지 않음 |

- 비밀키는 시도 DB·소스 저장소·로그와 분리하고 접근을 제한한다. 환경변수도 프로세스 진단·덤프·잘못된 로그 수집으로 노출될 수 있어 운영 보안 우위나 완전한 비밀 보호를 보장하지 않는다. 키 분리·보관 원칙은 [OWASP 지침](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html#key-storage)을 참고하며 별도 Secret Manager 도입은 이번 범위 밖이다.
- 재시작·여러 서버에서도 같은 버전에는 같은 키를 공급해야 한다. 수동 교체 시 모든 인스턴스에 이전 키와 새 키를 먼저 준비한 뒤 현재 버전을 전환한다. 실행 중 자동 반영은 없으므로 해당 구성으로 재기동해야 한다. 기존 시도의 비교에 필요한 기간에는 이전 키·형식 지원을 보존하되 기간을 코드가 자동 결정하지 않는다. 유출 키는 단순히 오래 보관하는 것으로 해결되지 않으며 별도 복구 정책이 필요하다.
- 같은 버전에 다른 키를 잘못 넣은 경우는 자동 감지하지 못하고 같은 입력도 비교 `false`가 될 수 있다. 이전 키를 잃으면 저장된 지문만으로 그 키를 복구하거나 새 키 지문을 재생성할 수 없다. 버전 이름만 맞추는 것을 키 일치 검증으로 주장하지 않는다.
- 비교 불가는 새 POST·예약 변경 없이 기술 오류로 처리하도록 설계하며 PG 확정 FAILED로 바꾸지 않는다. 카드 불필요한 기존 거래 조회·결과 복구를 중단시키지 않고 고객 오류는 HTTP-D02의 409/503으로 구분한다. 내부 서비스의 계산·비교와 고객 HTTP 오류 매핑을 연결했다.
- 지문·키를 HTTP 응답이나 로그에 노출하지 않는다. 카드 원문은 입력 오류·예외·DTO 출력·SQL bind·HTTP 디버그/trace 로그에도 남지 않도록 검증한다. 설정 검사와 지문 저장이 전체 호출 경로의 비노출 검사를 대신하지 않는다. 합성 키로만 테스트했고 실제 환경변수 키를 설정하거나 사용자 DB에 접근하지 않았다.

선택한 방식의 동일/변경 입력, 동시 요청, TX rollback, 재시작·버전, 비교 불가, 비노출 사례는 [HMAC TDD 계획](commerce-tdd-plan.md#hmac-요청-지문의-검증-계획)의 W5-HMAC-01~06에 둔다. 계산 단위의 선행 증거, [모델/JPA/011 저장](../week5/payment-request-fingerprint-persistence-tdd-log.md), [환경변수·버전 선택의 키 설정 검증](../week5/payment-fingerprint-key-configuration-tdd-log.md)을 구분한다. 서비스 TX A·내부 재요청 비교는 [주문 HMAC 연결 기록](../week5/order-payment-fingerprint-connection-tdd-log.md)의 범위다. 고객 HTTP·전체 비노출·실제 배포 키 교체까지 완료로 표시하지 않는다. 현재 결과를 과거 통과 수에 더해 전체 통과로 표시하지 않는다. 직렬화는 canonical v1, 저장은 VARBINARY(32)와 두 버전으로 고정하고 기존 무지문 행은 세 컬럼 NULL로 보존한다. 사용자 DB에는 SQL을 적용하지 않았으며 로컬 기록이 0건이라고 단정하지 않는다. DB 유일 제약·발송 시작 보호·TX B 중복 반영 금지와 create 재시도 금지는 기존 W5-TDD 계획대로 유지한다. 지문 비교는 발송 기록 직후 프로세스 중단의 불확실성을 없애거나 모든 결제의 자동 완료를 보장하지 않는다.

### W5 복구·callback HTTP의 위임 선택

**2026-10-23 사용자가 남은 계약 선택과 구현을 위임한 범위에서 채택한 안이다.** 과제 원문은 권위 조회·멱등 반영·불확실할 때 재발송/임의 반환 금지를 요구하지만, 아래 ACK·오류 이름·단건 예산을 그대로 강제하지는 않는다. 현재는 선택한 HTTP 경로를 코드에 연결했으며 검증 결과는 [복구 API TDD 기록](../week5/payment-recovery-api-tdd-log.md)을 참조한다.

| ID | 이번 선택 | 대안·선택 이유 |
| --- | --- | --- |
| W5-REC-D01 | reconcile은 본문 없음/`{}`만 받고 본인 주문의 저장 시도로 복구한다. DRAFT409 `PAYMENT_NOT_STARTED`, POINT는 기존 재확정409. 종결 PG는 조회 없이200, 미완료는 GET 최대1회 뒤200/202 | 카드·시도 키를 다시 받는 안은 과거 무지문/키 장애 때 복구까지 막는다. 새 결제 요청 비교가 아니라 기존 거래 확인이므로 주문 소유권과 저장 식별자를 사용한다. |
| W5-REC-D02 | callback은 고정 계정+providerOrderId로 시도를 찾고 알림 transactionKey로 권위 GET을 최대1회 한다. status는 힌트이며, 종결 상태도 조회해 반대 결과 충돌을 확인한다 | 본문만 믿거나 종결이면 무조건200 하는 안은 위조 알림·상충 결과를 놓친다. 미등록 시도404·저장 거래 키 불일치409는 사전에 거절해 불필요한 외부 호출을 줄인다. |
| W5-REC-D03 | 검증된 callback PENDING/중복/최종 반영은200 `data.acknowledged=true`. 조회404/UNKNOWN/회로 차단은503 `PAYMENT_RESULT_UNAVAILABLE`, 충돌409, PG OFF503 | 모든 알림에200 하는 안은 확인·반영 실패를 성공 ACK로 숨긴다. 503은 재확인할 여지를 남기지만 제공자가 자동 재전송한다는 보장은 아니다. PG FAILED는 유효 결과이므로 정상 ACK이며, ACK를 결제 SUCCESS와 혼동하지 않는다. |
| W5-REC-D04 | 두 요청 모두 GET 최대1회, 기존 HTTP 전체 timeout10초·조회 회로 공유. retry/sleep/180초 루프 없음 | 최초 confirm의 긴 루프를 복사하면 알림과 고객 재확인 요청이 worker를 오래 점유한다. 짧은 단건 확인 뒤 고객은 상태 조회/수동 재확인을 사용할 수 있다. DB 처리까지 포함한 엄격한10초 완료 보장은 선택하지 않는다. |
| W5-REC-D05 | 충돌 WARN 로그는 내부 orderId·attempt UUID·고정 source·고정 reason만. raw body·카드·HMAC·외부 문자열/예외 원문 비노출 | 새 충돌 테이블/outbox는 이번 증분에 추가하지 않는다. 구조 로그는 원인 추적의 단서이지 영속 감사·복구 스케줄러의 대체가 아니다. 외부 서명 인증·자동 조회·승인 취소/환불은 별도 운영 범위다. |
| W5-REC-D06 | 기존 조회→TX 밖 PG→공통 TX B를 재사용하고 기존 잠금 순서를 유지한다. callback 대상 찾기의 read-only TX를 끝낸 뒤 별도 TX에서 주문→시도 순으로 최신 상태를 확인한다 | 한 TX에서 시도를 먼저 읽고 이후 잠그면 영속성 컨텍스트의 오래된 객체를 사용할 수 있다. HTTP 동안 잠금을 유지하는 안도 외부 지연을 DB 경합으로 전파한다. TX B에서 식별·금액·종결을 다시 검사해 HTTP 중 다른 실행의 연결·종결을 보호한다. |

세부 JSON·오류 문구·PG OFF의 종결/미완료 차이는 [복구 API 계약](commerce-api-contract.md#복구callback-http-계약-선택)을 단일 기준으로 사용한다. 지문 없는 기존 시도도 복구할 수 있지만 저장 손상은 정상 legacy로 숨기지 않는다. 회로 차단은 외부 관찰이 없어 저장 상태를 보존하고, 실제 조회 실패의 UNKNOWN도 이미 종결한 상태를 덮어쓰지 않는다. 사용자 DB·SQL001~011·기존 데이터는 변경하지 않는다.

### W5 예약 저장 구조 비교와 선택

**2026-10-22 사용자 선택: A.** 원문은 필요한 예약 사실과 중복 방지를 요구하며, [학습 자료](../week5/learning-original.md)의 “실제 테이블·객체 분리는 기존 모델에 맞게 정합니다”를 따른다. 두 안 모두 기존 Order·OrderItem·상품 재고를 유지하고 PaymentAttempt를 별도 관리하는 후보였으며, 그 클래스 이름·테이블 분리가 과제의 지정 정답은 아니다.

| 비교 | A · 선택: OrderItem 확장 | B · 미채택: StockReservation 분리 |
| --- | --- | --- |
| 저장 위치 | 기존 품목의 불변 quantity + 새 예약 상태 | 주문 품목·시도·상품에 연결된 별도 예약 수량·상태 |
| 장점 | 주문당 한 시도·전량 예약·수량 불변인 이번 범위에서 예약 행과 수량 중복을 줄임 | 품목 스냅샷과 예약 진행 상태의 책임·조회 구조가 분명함 |
| 비용 | 구매 사실을 담던 OrderItem이 예약 상태 전이도 책임짐 | 테이블·FK·저장 경로가 늘고 예약 수량/주문 수량의 일치도 검증해야 함 |
| 관리자 HELD 합계 | 해당 상품의 실제 HELD 품목 quantity만 합산 | 해당 상품의 HELD 예약 행 수량만 합산 |
| 기존 POINT·DRAFT 이행 | W5 예약 없음으로 구분. 과거 POINT의 실제 결제/차감 사실은 그대로 유지 | 가짜 PG 시도·예약 행을 만들지 않고 과거 사실 유지 |
| 선택 이유 | 현재 OrderItem의 quantity는 변경 메서드가 없고 JPA `updatable=false`이며 현재 과제 범위를 표현할 수 있음 | 분리의 명확성은 유효한 장점이나, 이번에는 추가 예약 저장 구조를 만들지 않기로 선택 |

예약 상태는 `NOT_RESERVED → HELD → CONSUMED 또는 RELEASED`다. [enum](../week5/order-reservation-state-tdd-log.md)·[OrderItem/DB](../week5/order-payment-persistence-tdd-log.md) 선행 증분과 [내부 TX·실제 재고 연결](../week5/order-stock-reservation-tdd-log.md)을 구분한다. **quantity만으로 예약됐다고 판단하지 않는다.** TX A에서 실제 가용 차감과 HELD·시도·발송 기록을 함께 저장하고 TX B에서 소비/반환한다. NOT_RESERVED는 W5 예약 없음이지 과거 POINT 차감 사실을 지우는 의미가 아니다.

별도 테이블 유무와 관계없이 동일 주문/상품 보호, 관리자 반환 여유 검사, 삭제 후 반환 시 비노출 보존, 현재 가용 수량에 더하기, 재고·예약·결과의 같은 TX 반영은 유지한다. **테이블 분리 여부만으로 성능·동시성 우열을 주장하지 않는다.** 구체 관계는 [W5 ERD](commerce-erd-draft.md#w5-결제예약-관계-확장안), 검증은 [W5 TDD 계획](commerce-tdd-plan.md#w5-pg-결제-tdd-계획)에 연결한다.

### W5 최초 결제 요청의 대기·조회·회로 설정

**2026-10-22 사용자 요청으로 채택한 초기값이다. 2026-10-23 고객 confirm의 단일 예산·제한 조회·조회 회로까지 연결했다. 실행 범위와 한계는 [고객 API 기록](../week5/customer-payment-api-tdd-log.md)을 따른다.** 처음에는 대기 방식이 미정이었으나, 사용자가 “최초 결제 요청부터 전체 2~3분”과 아래 제안의 문서 반영을 선택했다. 시간 경과는 결과 확인을 그만둘 기준이지 결제 실패·예약 반환의 근거가 아니다.

#### 시간 예산과 선택 이유

| 항목 | 선택한 초기값 | 이유·범위 |
| --- | --- | --- |
| 최초 요청 전체 목표 | 요청 진입부터 180초 | 사용자가 허용한 2~3분의 상한. 입력/TX A·외부 호출·추가 조회·대기·최종 처리 시간을 포함 |
| 외부 확인 마감 | 같은 진입 시점부터 175초 | 마지막 상태 확인·DB 반영·응답을 위해 5초 여유. DB가 반드시 5초에 끝난다는 보장은 아님 |
| HTTP 연결 수립 | 3초 | 연결 자체가 안 될 때 긴 응답 timeout까지 기다리지 않기 |
| HTTP 연결 풀 대기 | 1초 | 사용 가능한 연결을 얻지 못한 요청의 누적 대기 제한. DB 연결 풀 제한과는 별개 |
| 생성 응답 timeout | 60초 | 토스페이먼츠의 결제 처리 API Read Timeout 안내를 참고한 보수적 시작값. 로컬 PG의 PENDING 응답을 일부러 지연시키지는 않음 |
| 조회 응답 timeout | 5초 | 로컬 PG의 단순 상태 읽기를 생성 처리보다 짧게 제한. 실제 업체 조회 API의 공통 권장값이 아님 |
| 개별 호출 전체 제한 | 생성 65초 / 조회 10초 | 연결·응답 등 여러 단계가 합쳐지는 시간도 제한. 하위 HTTP 구현에서 실제 작동을 검증해야 함 |

3단계는 위 기본값을 `PgHttpTimeouts`로 연결했다. 테스트에서는 짧은 값을 주입해 응답·풀 대기와 전체 호출 제한이 실제 소켓/연결을 종료·회수하는지 확인한다. 연결 3초는 하위 connection 설정까지 반영했지만 네트워크 연결 지연을 재현한 실측 증거는 아직 없다. 이는 180초 전체 응답 보장이 아니며 상세 실행·한계는 [HTTP 기록](../week5/local-test-pg-http-tdd-log.md)에 둔다. `commerce.test-pg.enabled=false`가 기본이고 base URL `http://127.0.0.1:9090`, callback URL `http://127.0.0.1:8080/api/v1/payments/callback`은 로컬 설정이다. 활성화로 fixture가 자동 기동되거나 callback controller가 생기지는 않는다.

마감은 TX A 종료나 timeout 발생 때가 아니라 **최초 요청 진입의 단일 기준 시점**에서 계산한다. 경과 시간은 시계 보정에 영향받지 않는 단조 시간으로 계산한다. 우선순위가 가장 높은 servlet 필터에서 최초 진입 시각을 저장하므로 MVC body 변환과 TX A를 포함하고 필터 이전 네트워크·서버 큐 대기는 제외한다. 예를 들어 최초 생성에 60초를 사용하면 이후 전체 잔여 예산은 약 120초이며 180초를 다시 시작하지 않는다. 호출 전체 제한과 최종 처리 여유를 확보하지 못하면 새 외부 호출을 시작하지 않는다. 대기 역시 남은 예산을 넘기지 않는다.

180초는 **전체 처리 목표이며 운영 하드 상한 검증 완료가 아니다**. connect/read/pool 설정을 더하거나 Future의 대기만 중단했다고 하위 HTTP가 종료되는 것은 아니다. 요청 중단·연결 회수, DB SQL/잠금 대기와 인바운드 클라이언트·프록시 제한, 긴 요청의 worker/연결 점유를 함께 검증하기 전에는 하드 상한 달성을 주장하지 않는다. 기존 DB 락 대기 3초를 변경하거나 PG HTTP/대기 동안 DB TX를 유지하는 선택은 아니다. 최종 DB 반영 실패를 단순 미확인 202로 숨기지 않고 기존 오류·재조회 복구 계약을 따른다.

설정 불일치로 남은 예산보다 긴 HTTP를 시작하지 않도록 `TestPgProperties`에서 생성 전체 timeout ≤65초·조회 전체 timeout ≤10초를 검증한다. 최초 요청 중 예산이 부족하면 새 호출을 시작하지 않고 저장 상태를 반환한다. fast-clock 시험은 예산 계산·횟수·대기 순서를 검증하며 운영 환경의 180초 실시간 지연 측정을 대신하지 않는다.

#### 조회 횟수와 간격

| 항목 | 선택한 초기값·규칙 | 이유·대안 |
| --- | --- | --- |
| 생성 POST | 발송 권한을 얻은 실행의 최대 1회, 자동/숨은 retry 없음 | 중복 제거를 보장하지 않는 과제 PG에 새 거래를 만들 위험 차단 |
| 첫 조회 | PENDING 또는 생성 timeout 뒤, 종결 저장 결과가 없고 시간·회로가 허용하면 즉시 | 이미 끝난 거래를 빨리 확인. timeout을 확정 실패로 처리하지 않음 |
| 후속 간격 | 이전 조회 완료 후 5초 → 10초 → 이후 15초 | 초반 빠른 확인과 장기 대기 조회량 절충. 고정 1초 반복은 호출량이 크고 처음부터 긴 간격은 결과 전달이 늦음 |
| jitter | 위 간격의 ±20% | 여러 요청의 조회 시점 집중을 줄임. 실제 지연은 각각 4~6초·8~12초·12~18초이며 테스트에서는 제어 |
| 최초 요청의 조회 상한 | 첫 조회 포함 최대 12회 | CB 허용 후 시작한 client 조회 시도를 센다. 연결 실패도 1회이며 HTTP client 내부 retry로 호출 수를 늘리지 않음 |
| 중단 | 종결 확인, 조회 상한 소진, 다음 호출 예산 부족/시간 마감 중 먼저 도달한 조건 | 정상 결과가 확인되면 2~3분을 채우지 않고 조기 반환. 미완료이면 저장 상태와 202·조회 경로 반환 |

조회 한 번이 끝나기 전에 다음 조회를 겹쳐 보내지 않는다. 거래 키를 모르므로 주문별 조회를 했다가 키 기반 조회로 전환해도 **같은 12회 예산**에서 센다. 원시 HTTP 시도 수·fixture 수신 수·회로 차단 수는 구분해 관찰하며, 연결 실패 때문에 fixture 수신 수가 적어도 시도 제한을 우회하지 않는다. 회로 OPEN에서 거절된 실행은 실제 외부 조회 0회이며 12회에서 차감하지 않는다. 대신 전체 마감과 유한 대기로 무한 루프를 막는다.

후속 조회는 **선택한 전체 대기 간격과 다음 HTTP의 10초 예산**을 모두 확보할 때만 진행한다. 남은 시간이 부족하면 저장 결과를 확인하고 종료하며, 마감 직전에 간격을 압축해 한 번 더 조회하지 않는다. 이 규칙은 175초 외부 확인 예산을 보호하는 것이며 최종 DB 처리까지 포함한 180초 하드 상한을 보장하지는 않는다.

이 예산은 **신규 발송 권한을 얻은 최초 confirm 실행**의 것이다. 동시 동일 confirm은 기존 저장 상태를 반환하며 같은 긴 조회 루프를 중복 시작하지 않는다. callback·별도 수동 reconcile의 조회는 이 12회와 구분하되 공통 조회 회로는 공유한다. 두 진입점은 REC-D01~04에 따라 GET 최대1회·호출전체10초·반복 대기 없음과 callback200 ACK를 선택했으며 최초 요청용180초 루프를 복사하지 않는다. DB 처리까지 포함한 엄격한10초 보장은 아니다. 외부 조회 전에 저장된 종결 결과를 확인하고 종료 직전에도 다시 확인해 callback 선종결을 보존한다. 상시 scheduler는 추가하지 않는다.

#### 조회 서킷브레이커와 fallback

공통 PG 조회 경계에 **같은 애플리케이션 인스턴스·같은 PG/제공자 계정의 회로를 공유**하도록 구현했다. 주문마다 회로를 만들거나 Redis/DB로 전 서버의 회로 상태를 동기화하는 설계가 아니다. 실제 GET 한 번을 표본 한 건으로 삼는 경계이며 생성 POST나 전체 confirm 한 번을 조회 표본으로 세지 않는다. 허용된 GET의 연결 실패도 장애 표본에 포함한다. 후속 사용자 승인으로 `Resilience4j 2.3.0` core CircuitBreaker를 선택하고 두 GET 조회를 감싼 `CircuitBreakingPaymentGateway`에 연결했다. Boot 자동설정/AOP·생성 재시도는 추가하지 않았다. 직접 상태기계를 만드는 대안보다 검증된 회로 구현을 재사용하되 표본 분류·업무 결과 보존은 프로젝트에서 책임진다.

| 항목 | 선택한 초기값 | 이유·비용 |
| --- | --- | --- |
| 집계 창 | COUNT_BASED, 최근 20건 | 최근 결과를 반영하면서 일부 오류에 과민하게 차단하지 않기. 동시 호출 수 제한이 아님 |
| 최소 표본 | 10건 | 한두 번 실패로 전체 조회를 막지 않기. 저트래픽에서는 장애 판단이 늦을 수 있음 |
| 실패율 | 50% 이상 | 최소 표본 이후 절반 이상 장애이면 계속 호출하는 비용을 줄임 |
| 느린 호출 | 3초 초과, 비율 50% 이상 | 조회 응답 timeout 5초 전에 지연 악화를 감지. 정상 응답도 느리면 지연 표본 |
| OPEN | 30초 | PG에 회복 시간을 주면서 최초 3분 예산 안에서 탐색 기회를 남김 |
| HALF_OPEN | 탐색 2건 | 단 한 건의 우연한 정상 응답만으로 회복 판단하지 않기 |
| HALF_OPEN 최대 체류 | 40초 | 조회 최대 10초 + 최대 jitter 간격 18초 + 다음 조회 10초를 허용할 여유. 탐색 표본이 채워지지 않아도 무한 대기하지 않음 |

최소 10건을 채운 뒤 최근 창의 실패율 또는 느린 호출 비율이 50% 이상이면 OPEN으로 전환한다. OPEN의 30초가 지난 뒤 허용된 다음 요청으로 제한된 탐색을 수행한다. 탐색 2건의 결과에서 두 비율 모두 기준 미만이면 CLOSED, 하나라도 기준 이상이면 OPEN이다. 40초 안에 탐색을 마무리하지 못해도 OPEN으로 돌아간다. 회로 차단 자체를 새 PG 실패 표본으로 누적하거나 retry로 즉시 우회하지 않는다.

최초 요청의 전체 예산이 남았다면 OPEN 기간을 DB TX 없이 유한하게 기다린 뒤 허용된 탐색을 시도한다. 남은 예산이 부족하면 저장 상태를 확인하고 미완료 202로 끝낸다. 기다리는 동안 callback이 반영한 종결 결과를 보존한다. 회로가 열렸다고 업무 상태를 FAILED로 바꾸거나 예약을 반환하지 않는다. **회로·timeout은 PG 거래의 취소 장치가 아니다.**

| 조회 관찰 | 회로의 분류 | 업무 결과 |
| --- | --- | --- |
| 연결/응답 timeout·네트워크 오류·HTTP 5xx | 장애 표본 | 다른 확정 결과가 없으면 미확인·예약 유지 |
| 잘못된 JSON·필수 응답 필드 누락 | 응답 계약 장애 표본 | 가짜 결과를 만들지 않고 미확인·예약 유지 |
| 유효한 PENDING·SUCCESS·FAILED | 정상 통신. 늦으면 지연 표본에는 반영 | 식별·금액 검증과 기존 상태 전이 적용. 결제 FAILED 자체는 통신 장애가 아님 |
| 계약상 transactionKey 조회 404·주문별 빈 목록 | 정상 통신. 늦으면 지연 표본에는 반영 | 확정 실패/효과 0건 증거가 아니며 재발송·임의 반환 금지 |
| 형식이 유효한 주문·금액·거래 불일치 | 통신 장애와 구분한 충돌 판정 | 기존 주문/예약/종결 결과를 바꾸지 않음. 고객 응답은 `409 PAYMENT_RESULT_CONFLICT`, 별도 운영 충돌 저장·callback ACK는 Q05 |
| 계약 밖 4xx·5xx·redirect 등 | adapter는 `UNKNOWN / HTTP_STATUS`로 분류. 조회 회로의 장애 표본에 연결 | 주문별 조회 404도 해당한다. 본문 없는 생성 422는 `INVALID_RESPONSE`이며 확정 실패로 확대하지 않는다. 숨은 자동 생성 retry·redirect 금지 |

fallback은 마지막으로 저장된 상태와 현재 확인 불가를 반환하는 책임이다. **회로 차단은 외부 호출을 하지 않은 것이므로 새 UNKNOWN 관찰을 저장하지 않고, 다시 읽은 기존 상태를 반환한다.** 도메인 실패로 변환하거나 금액 0·가짜 성공·포인트 대체·자동 예약 반환으로 바꾸지 않는다. 정상 결과를 수신한 뒤 내부 TX B가 실패한 경우도 회로의 PG 통신 장애로 섞지 않고 내부 저장 실패·재조회 적용 경계에서 다룬다.

#### 근거와 검증 연결

| 근거 | 사용하는 범위 |
| --- | --- |
| [W5 과제](../week5/assignment-original.md)·[학습 자료](../week5/learning-original.md) | 조회 회로·실제 timeout·제한 조회·UNKNOWN 예약 유지·create 재시도 금지의 필수 조건 |
| 사용자 답변, 2026-10-22 | 최초 요청부터 전체 2~3분, 후속 설정안 문서 반영, 저장 구조 A 선택 |
| [토스페이먼츠 timeout 안내](https://docs.tosspayments.com/resources/glossary/timeout), 2026-10-22 확인 | 결제 처리 Read Timeout 60초의 참고 근거. 그 업체의 멱등키 기반 재시도 계약은 이번 PG에 적용하지 않음 |
| [Resilience4j CircuitBreaker 문서](https://resilience4j.readme.io/docs/circuitbreaker), 2026-10-22 확인 | 표본·실패/지연 비율·OPEN/HALF_OPEN·최대 체류의 의미. 이 문서의 20/10/30/40 등의 수치를 업계 표준값으로 정당화하는 근거는 아님 |

검증할 항목은 [W5 TDD 계획](commerce-tdd-plan.md#w5-pg-결제-tdd-계획)에 유지한다. 실제 HTTP timeout/자원 종료, 최초 시점 기준 예산·경계값, 호출 수·jitter, 정상 업무 결과와 장애의 분류, 회로 공유·탐색·재차단, callback 경합, 긴 요청의 자원 점유를 검사한다. 빠른 시계 제어 시험과 실제 HTTP 지연 시험을 분리하며 **설정 문서 작성이나 단위 시계 시험만으로 180초 상한/운영 적합성을 검증했다고 기록하지 않는다.**

### AI 제안과 반례 검토

| 장면 | 위험한 해석 · 거절 | 유지할 결과·이유 |
| --- | --- | --- |
| 외부 거래 생성 후 응답 유실 | timeout이면 결제 실패로 바꾸고 재고 반환 | 외부 거래 1·내부 UNKNOWN·예약 유지. 같은 거래 조회로 복구하며 create 추가 0 |
| PG 성공 후 내부 저장 실패 | 우리 TX를 롤백했으니 결제도 취소됨 | 내부만 롤백된다. 기존 외부 거래를 다시 확인해 같은 TX B로 한 번 반영 |
| callback·reconcile 동시 도착 | 둘 다 성공 결과이므로 각자 재고 차감/반환 | 동일 예약·주문·시도 상태를 보호한다. 추가 차감·중복 반환 0, 종결은 늦은 응답으로 퇴행하지 않음 |
| 발송 기록 직후 HTTP 전 프로세스 중단 | 조회 0건이면 재발송해도 안전함 | 불확실한 발송을 자동 반복하지 않는다. 거래 0건·예약 보유 상태가 오래 남을 수 있는 한계는 기록하고 자동 완료로 포장하지 않음 |

위 반례의 결과는 W5 원문에 근거한다. D02의 저장 A안, D06의 제한 조회, D07의 반환 여유, D08의 초기값, D09의 호출 전 DB 상태 전이·TX A 동시 저장과 Q04의 UUID 키·PG 규격 입력은 사용자 선택으로 구분했다. 요청 비교는 D10의 후속 HMAC 기술 선택을 따르며, 선택적 과제 해석 확인을 구현 선행 조건으로 만들지 않는다. callback ACK의 후속 위임 선택은 REC-D03에 따로 구분하며 이 초기 선택으로 소급 승인한 것으로 해석하지 않는다.

## W2~W4 승인·구현 기록

2026-09-18 사용자 답변을 반영한 정책 P00~P15, 2026-10-08 W3 재확정 거절 승인 P16과 책임·의존·저장 방식의 대안 검토를 모은다. 사용자는 추천 묶음의 진행을 승인하여 금액·나머지 HTTP·초기 데이터·이름 길이·동시성 정책을 확정했다. 정책 승인과 실제 구현 완료는 구분한다. 과거 실행 기록의 당시 범위·수치는 보존한다.

W2의 25개 API 연결·테스트와 6개 테이블의 수동 DDL·JPA validate·관계 제약 검증 증거는 [전체 완료 체크리스트](commerce-completion-checklist.md)에 보존한다. **2026-10-08 W3 브랜드 일괄 삭제의 정상 처리·기본 경계·전체 롤백·연결 API·접근 제한·주문 및 상품 등록 경합(W3-BRAND-01~08)을 검증했다.** 마지막 연결·동시성 검증 범위는 [네 번째 증분 기록](../week3/brand-removal-tdd-log.md#네-번째-증분--w3-brand-0408-연결동시성-검증)을 따른다. 후속 승인한 주문 재확정 거절 P16도 `W3-RECONFIRM-01..06` 구현·검증을 완료했으며 [별도 실행 기록](../week3/order-reconfirmation-tdd-log.md)으로 추적한다. 다른 W3 주문 경쟁·주문 내부 저장 경계 실패/롤백 검증·갱신 유실 대조군 과제까지 포함한 완료 표시는 아니다.

후속으로 주문 내부 저장 경계의 실패·롤백 `W3-ORDER-TX-01..03`도 검증했다. 정책·생산 코드·락 변경 없이 테스트를 추가했으며 [실행 기록](../week3/order-transaction-tdd-log.md)에서 재확정 증분과 구분한다. 이후 갱신 유실 대조군 `W3-LOST-UPDATE-01`도 [별도 테스트로 재현·검증](../week3/stock-lost-update-control-plan.md)했다. 재고 8주문 경쟁은 [W3-STOCK-RACE-01](../week3/stock-order-concurrency-tdd-log.md), 포인트 3주문 경쟁은 [W3-POINT-RACE-01](../week3/point-order-concurrency-tdd-log.md), 충전/결제 경쟁은 [W3-CHARGE-ORDER-01](../week3/point-charge-order-concurrency-tdd-log.md)로 검증했다. 2026-10-09 [전체 최종 검사](commerce-completion-checklist.md#최신-통합-검사)를 통과했고 PR 초안을 준비했다. 기술 글·실제 제출 상태는 완료 체크리스트에서 별도로 관리한다.

## W4 캐시 설계의 선택과 미정 질문

2026-10-16 문서 작성 이후 사용자가 Redis + DB 버전 방식의 TDD 구현을 승인했다. 아래는 구현 선택·완료한 실측과 남은 운영 판단을 구분한 기록이다. 기존 P00~P16 업무 계약은 유지한다. 근거는 [W4 채점 기준](../week4/grading-criteria.md), [실측 비교](../week4/product-read-cost-comparison.md), 사용자 답변과 [캐시 구현·검증 기록](../week4/product-list-cache-tdd-log.md)이다. 후속 [TTL·메모리 비교](../week4/product-list-cache-ttl-memory.md)는 완료했으며 [현재 상태·최신 검사](commerce-completion-checklist.md#최신-통합-검사)를 함께 따른다.

**2026-10-16 후속 사용자 승인으로 이번 과제의 선택을 확정했다.** 무필터 좋아요순 첫 페이지에 기존 Redis + DB 버전 캐시와 TTL 60초를 사용한다. 캐시는 요청 시 채우며 서버 시작 워밍은 도입하지 않는다. 이는 현재 구현을 유지하는 결정이며, 운영 최적값의 검증과 과제의 설계 선택을 구분한다.

### 범위와 기본 선택

| ID | 상태 | 내용·선택 이유 | 다른 대안·비용 |
| --- | --- | --- | --- |
| W4-CACHE-D01 | 과제 대상 확정·구현 | 이번 과제의 캐시 대상은 무필터 `likes_desc`, `page=0,size=20`으로 확정한다. 홈·메인에서 반복 조회할 가능성이 있는 첫 페이지를 우선하며 이미 구현·검증한 범위를 유지한다. | 모든 페이지·크기 캐시는 키 수·낮은 재사용 가능성이 커져 확대하지 않는다. 실제 홈 호출 조건·첫 페이지 접근 비율은 별도 확인 사항이다. |
| W4-CACHE-D02 | 기존 계약 유지 | 기존 필터·정렬·동점·페이지·응답·오류를 그대로 사용한다. 좋아요가 모두 0이어도 ID 내림차순이고, 정렬 생략은 최신순이다. | 초기 좋아요 0일 때 자동 최신순 전환은 대화 후 채택하지 않았다. 캐시 개선을 이유로 정렬 결과를 바꾸지 않는다. |
| W4-CACHE-D03 | 사용자 확정·구현 | 삭제 전 DB 버전을 확인한 진행 중 hit도 이전 결과를 허용한다. 삭제 완료 후 새로 시작한 조회는 새 버전으로 삭제 대상을 제외한다. | 모든 진행 중 응답까지 삭제 완료 시점에 차단하는 더 강한 계약은 선택하지 않았다. 늦은 캐시 저장이 새 요청을 오염시키는 것은 별도 방지한다. |
| W4-CACHE-D04 | 구현 | 공통 페이지 DTO 전체를 JSON으로 저장: 상품 ID·이름·가격·재고·브랜드 ID/이름·좋아요 수, items/page/size/totalElements/totalPages. JPA 엔티티·사용자별 liked·주문·포인트·관리자 전용 필드는 제외한다. | 순위 ID만 저장하고 현재 상세를 DB에서 일괄 합성하는 대안(C)은 변경 영향과 저장량을 줄일 수 있지만 hit의 DB 비용·페이지 일관성 검증이 추가된다. |
| W4-CACHE-D05 | 구현 | 키 예: `commerce:local:products:v1:brand=all:sort=likes_desc:page=0:size=20:rev=42`. 환경·응답 스키마·조회 조건·DB 버전을 구분한다. API 입력 검증 뒤 기본값을 정규화한다. | 공개 결과이므로 사용자 ID로 키를 분산시키지 않는다. `v1`은 응답 스키마이며 DB 데이터 버전 `rev`와 다르다. |
| W4-CACHE-D06 | 사용자 확정·기존 구현 유지 | [5·30·60초 실측](../week4/product-list-cache-ttl-memory.md) 후 이번 과제에서는 고정 TTL 60초를 유지하기로 선택했다. 변경 없는 지속·드문 조회의 재계산이 가장 적었으며, 30초 대비 작은 추가 이득과 변경 구간의 추가 키 메모리 약 187 KiB를 함께 고려했다. hit마다 연장하지 않고 값과 만료를 한 SET으로 저장한다. | 초당 약 2회 변경에서는 모두 hit 80%·키 메모리 약 31/187/374 KiB였다. 30초는 메모리를 줄이는 미채택 대안이다. 60초는 운영 최적값·오래된 표시 허용 시간이 아니며 최신성은 DB 버전으로 지킨다. |
| W4-CACHE-D07 | 구현 | 캐시 전용 master-only 연결·StringRedisTemplate·엄격한 JSON 역직렬화를 사용한다. connect/command 각각 100ms를 별도 설정한다. | 기존 `REPLICA_PREFERRED` 연결은 바꾸지 않는다. 100ms는 API 전체 지연 상한이 아니며 DNS·연결·재연결·DB 비용까지 포함한 실제 지연은 별도 측정한다. |
| W4-CACHE-D08 | 사용자 선택·구현 | local 프로파일의 상품 목록 Redis 캐시 기본 OFF. JVM 메모리 로컬 캐시를 뜻하지 않는다. 현재 local은 DB를 재생성하므로 Redis의 과거 버전 키와 충돌할 수 있다. 테스트는 명시 ON·전용 namespace로 검증한다. | 로컬 실행마다 UUID namespace를 바꾸는 대안 대신 기본 OFF를 선택했다. 활성화 시 보존형 DB 설정·008·새 namespace 준비. 운영 DB 초기화/복원 시 namespace 전환도 필요하다. |
| W4-CACHE-D09 | 후속 승인·구현 | 같은 초기 조회 버전의 진행 중 miss는 한 JVM에서 결과를 공유한다. 리더는 Redis 재확인 후 snapshot/SET, 대기자는 사용자 선택인 최대 500ms 대기 후 DB 직접 조회·SET 생략. 진행 중 버전 최대 64개는 기술적 초기 방어값이다. | TTL·신선도·쓰기 잠금을 바꾸지 않는다. Redis 분산 락은 도입하지 않으며 여러 서버의 중복 계산은 남는다. 500ms는 API 전체 기한이 아니고 64개는 전체 DB 동시 요청 상한이 아니다. [TDD·전후 비교](../week4/product-list-single-flight-tdd-log.md) |
| W4-CACHE-D10 | 후속 승인·구현, 최종 검증은 실행 기록 참조 | 고객 무필터 `likes_desc`는 전체 개수 → 전체 노출 후보의 ID/count 순위 페이지 → 선정 ID 상세 IN → 순위대로 합성한다. 기본 20개와 다른 page/size 모두 적용하며 넓은 상세 정보를 전체 집계에서 제외한다. 기존 정렬·삭제·응답과 읽기 TX/RR, 쓰기 락, Redis 전체 payload·TTL은 유지한다. | 상세 왕복이 추가되어 비어 있지 않은 DB-only는 3 SELECT, 직접 계산하는 miss는 5, hit는 1이다. 상품 20개를 먼저 고르는 방식은 전체 순위를 깨뜨리므로 제외한다. 좋아요 카운터 저장은 쓰기 정합성 책임이 추가되어 이번 범위에서 제외한다. [RANK-01..05·전후 비교](../week4/product-like-ranking-tdd-log.md) |
| W4-CACHE-D11 | 사용자 확정·워밍 미도입 | 이번 과제에서는 서버 시작 워밍을 도입하지 않고 기존 요청 시 채움을 유지한다. 재시작 뒤 유효한 현재 버전 캐시는 재사용한다. 워밍은 남은 필수 구현이 아니라 이번 범위에서 선택하지 않은 기능이다. | 시작 시 선채움은 첫 요청 비용을 줄일 후보지만 실행 비용·준비 완료 지연·워밍 후 응답은 검증하지 않았다. 캐시 잔존 비교만으로 워밍 효과를 단정하지 않는다. 재검토할 때는 이득과 기동 비용을 함께 측정한다. [첫 조회 관찰](../week4/product-list-cold-start-measurement.md) |

### 무효화 방식 비교

사용자가 B를 선택하여 구현했다. 공통 목표는 비싼 집계의 재사용과 기존 계약 보존이지 적중률 숫자만 높이는 것이 아니다. B가 모든 부하에서 가장 빠르다는 결론은 아니다.

| 후보 | 원리·고려 이유 | 비용·부족한 점 | 상태 |
| --- | --- | --- | --- |
| A. 커밋 후 삭제 기반 cache-aside | 변경 커밋 후 관련 키를 지우고 다음 miss에서 다시 채운다. 매 hit의 DB 버전 확인·공통 버전 행 갱신을 피할 수 있다. | 단순 DEL만으로는 삭제 실패·늦은 채움을 막지 못한다. 재처리·세대 검증·삭제 여부의 권위 확인 등 보완을 구체화하고 그 비용까지 비교해야 한다. | 보완 설계가 필요한 대안 |
| B. DB 버전 기반 cache-aside | 업무 변경과 목록 버전 증가를 같은 DB 트랜잭션으로 묶고, 현재 버전 키만 읽는다. 옛 키가 남거나 늦게 저장돼도 새 조회가 선택하지 않도록 한다. | hit에도 DB 조회가 남는다. 여러 쓰기가 공통 버전 행에서 기다릴 수 있고, 한 변경으로 기존 세대 캐시를 재사용하지 못한다. 새 저장 구조와 모든 쓰기 연결이 필요하다. | 사용자 선택·구현 |
| C. 순위와 현재 상세 분리 | 순위 ID 등을 캐시하고 상품·브랜드의 현재 표시값은 DB에서 일괄 조회한다. 가격·재고 변경 때 순위 캐시까지 버리는 비용을 줄일 후보다. | 좋아요·생성·삭제는 순위/total에 계속 영향을 준다. 삭제 항목만 빼서 19개를 반환하는 식으로 페이지를 훼손하면 안 된다. 합성·일관성·DB 비용 설계가 필요하다. | 미채택 대안 |
| D. TTL만 사용하거나 일정 간격 순위 갱신 | 재사용 시간을 늘리고 변경마다 무효화하는 비용을 줄인다. | 이것만으로는 정상 변경 완료 뒤 새 조회의 최신 결과와 삭제 비노출을 충족하지 못한다. 기존 계약 변경 승인 없이 적용하지 않는다. | 단독 방안으로 보류 |
| E. 현재 DB 조회 유지 | Redis·무효화 비용이 없는 성능 비교 기준이다. | 캐시 과제의 최종 대체 구현은 아니며, 캐시의 순효과를 판단하는 대조군으로 사용한다. | 비교 기준 |

**D10은 미채택 대안 C와 다르다.** D10은 같은 DB 읽기 snapshot 안에서 순위 SQL과 상세 SQL을 나누는 최적화다. Redis에는 여전히 전체 페이지 DTO를 저장하며 hit에서 상세를 다시 조회하지 않는다. 캐시 대상은 무필터 첫 페이지 `page=0,size=20` 그대로이고, DB 조회 최적화만 고객 무필터 좋아요순의 다른 page/size에도 적용한다. 브랜드 필터·최신순·가격순·상세·관리자·내 좋아요는 이번 변경 대상이 아니다.

### B의 버전 의미와 구현 흐름

버전은 상품별 잠금 번호가 아니라 **이 공통 목록의 변경 번호**다. 예를 들어 버전 42의 첫 페이지를 저장한 뒤 좋아요가 바뀌어 버전 43이 되면, 새 조회는 42의 캐시가 남아 있어도 43의 키를 찾는다. TTL이 남았는지와 별개다.

| 순서·경계 | 구현한 처리 |
| --- | --- |
| 변경 | [전체 영향 경로](commerce-erd-draft.md#세-대표-흐름과-변경-영향)의 업무 변경과 버전 증가를 같은 트랜잭션으로 커밋/롤백한다. 별도 `REQUIRES_NEW`나 커밋 후 버전 증가로 분리하지 않는다. 원자적 증가로 동시 갱신 유실을 막는다. |
| 잠금 | 기존 READ_COMMITTED·비관적 업무 잠금 순서를 유지하고 업무 flush 뒤 버전 행을 갱신한다. 현재 HTTP 유스케이스의 마지막 쓰기이며 실제 SQL 순서도 검사한다. 여러 변경 서비스를 임의로 외부 트랜잭션 하나에 조합하는 것은 검증 범위가 아니다. |
| hit 후보 | DB 원본의 현재 버전을 새로 읽고 해당 키로 Redis GET을 수행한다. 버전 확인용 짧은 DB 조회는 Redis 대기 전에 종료한다. hit에서도 이 DB 비용은 남는다. |
| miss | 같은 lookup 버전의 진행 중 작업에 합류하거나 리더가 된다. 리더의 재확인도 miss이면 별도 프록시 경계에서 버전 → 전체 개수 → ID/count 순위 → 선정 ID 상세를 같은 RR 스냅샷/연결로 읽고 순위대로 합성한다. 대기 중 DB 연결·트랜잭션을 보유하지 않는다. 합치기 OFF는 개별 snapshot/SET이며 원본 조회에는 같은 D10 최적화를 사용한다. |
| 채움 | DB 읽기 종료 뒤 **그 스냅샷이 반환한 버전**의 키에 결과와 TTL을 함께 저장한다. 처음 GET에 쓴 버전이나 나중에 다시 읽은 새 버전을 옛 payload에 붙이지 않는다. |
| 늦은 채움 | `42 읽기 → 변경/43 커밋 → 42 결과 늦게 저장 → 새 요청`에서 새 요청은 43만 선택한다. 진행 중 요청의 이전 스냅샷 허용과 새 요청의 옛 키 선택 금지를 구분한다. |
| 옛 키 정리 | 물리 DEL 성공을 정확성의 필수 조건으로 두지 않고 TTL로 정리한다. 모든 쓰기 참여·권위 있는 버전 확인·일관된 채움이 전제이며, TTL 자체가 최신성을 보장하는 것은 아니다. |

공통 버전 행의 위험은 다음 세 가지다. **서로 다른 상품 변경도 버전 갱신에서 경합**할 수 있고, **좋아요·주문 재고 변경이 잦으면 첫 페이지 hit가 거의 안 날 수 있으며**, **hit에도 버전 SELECT가 추가**된다. 변경 없는 읽기 실험의 이득만으로 B가 모든 부하에서 가장 좋다고 결론 내리지 않는다. 한 유스케이스의 실제 변경당 한 번 증가하며 동일 값 수정·중복/없는 좋아요·재삭제는 불필요하게 증가시키지 않는다. 캐시 OFF도 버전 갱신과 008 스키마가 필요하다.

2026-10-16 [혼합 부하 실측](../week4/product-list-cache-mixed-load.md)에서 고정 20조회/초에 좋아요 변경 0/2/10회/초를 섞자 hit율은 100/80.25/25.75%였다. 2회/초는 중앙값 개선과 p95 악화가 함께 있었고, 10회/초는 중앙값·p95·SELECT 수가 모두 나빠졌다. TTL 만료 없는 실험이므로 TTL 연장만으로 이 문제를 해결한다고 판단하지 않는다. 이는 통제된 단일 writer 실험의 관찰이며 운영 분포·여러 쓰기 간 경합·대안 A/C의 우열까지 확정한 것은 아니다. 기존 선택과 정책은 유지한다.

**재시작 직후의 비용:** [첫 HTTP 관찰](../week4/product-list-cold-start-measurement.md)에서 유효한 현재 버전 캐시가 남은 경우와 없는 경우를 실제 새 앱 JVM으로 비교했다. 캐시 잔존의 이득과 새 앱의 초기 요청 비용은 구분한다. 후속 사용자 승인으로 D11의 워밍 미도입을 선택했다. 워밍 실행 비용·워밍 후 첫 HTTP는 측정하지 않았으므로 워밍이 불필요하거나 효과가 없다고 결론 내린 것은 아니다.

### TTL과 적중률의 선택 근거를 만드는 방법

| 비교할 것 | 이유·판단 방법 |
| --- | --- |
| 5초·30초·60초 | 짧음·중간·긴 재사용 구간으로 비교한 후보이며, 같은 seed·요청 분포·변경 빈도로 여러 만료 주기의 [실측을 완료했다](../week4/product-list-cache-ttl-memory.md). 재계산·추가 hit와 옛 키 메모리를 함께 비교했고 적용 설정은 유지했다. 실제 운영 분포의 최적값 확정은 별도다. |
| 변경과 만료의 차이 | B에서는 버전이 바뀌면 TTL이 60초 남아 있어도 재사용할 수 없다. 변경이 병목이면 TTL 연장 대신 payload·대상·무효화 단위를 재검토한다. |
| 적중률의 분모 | 캐시 대상 요청의 최초 GET 중 유효 hit 비율을 사용한다. 리더의 재확인 hit/miss·결과 공유·DB 우회는 별도 집계한다. Redis lookup 카운터는 재확인까지 포함한 연산 수이므로 요청 적중률로 혼용하지 않는다. 오류·비대상 요청과 전체 대상 비중도 분리한다. |
| miss 원인 | 최초 채움·TTL 만료·데이터 변경/버전 전환·키 분산·메모리 퇴거를 구분한다. GET이 비었다는 사실만으로 원인을 단정하지 않고 확인 불가능한 것은 미상으로 기록한다. |
| 합성 부하 | 변경 없는 반복 읽기, 다양한 페이지·크기 요청, 좋아요·재고 쓰기가 섞인 읽기를 구분한다. 요청 비율·빈도·동시성·기간·준비 실행을 고정해 공개한다. 이는 운영 접근률의 실측이 아니다. |
| 최종 선택 | DB-only/miss/hit의 결과 동일성, 비싼 SQL 수·호출률, API p50/p95, DB 버전 조회·쓰기 대기, Redis 키 수·payload 크기·메모리·오류를 함께 본다. 적중률이 높아도 전체 비용·쓰기 지연이 악화되면 채택을 재검토한다. |

### 장애 대응과 남은 질문

| 상황 | 구현한 동작·선택 이유 |
| --- | --- |
| Redis GET 오류·시간 초과·손상 payload | 연결 장애는 DB 목록으로 우회하고 SET을 생략한다. 손상 payload는 invalid miss로 집계하고 올바른 snapshot으로 재채운다. 무제한 재시도하지 않는다. |
| DB 조회 성공 뒤 Redis SET 실패 | 올바른 DB 응답은 반환하고 저장 실패 지표를 기록한다. 표시용 복사본 저장 때문에 정상 조회를 실패시키지 않는다. |
| DB 변경 커밋 뒤 Redis 장애 | B는 쓰기 경로에서 Redis를 호출하지 않는다. 버전이 업무와 함께 커밋되어 옛 키가 남아도 선택되지 않는다. 관리자 성공을 Redis 가용성과 분리한다. DEL 실패를 가짜로 주입하지 않고 실제 Redis 중단 중 변경과 복구를 검증한다. |
| B의 버전 저장·조회 실패 | 업무 변경 중 버전 증가 실패는 함께 롤백한다. 현재 버전을 읽지 못하면 옛 캐시를 임의 사용하지 않는다. DB까지 실패한 상황을 빈 목록·가격 0으로 숨기지 않는다. |
| 복구 완료 | 실제 사용 endpoint 재시작 뒤 현재 DB와 일치하는 miss→재채움→hit 및 비싼 조회 감소를 확인한다. PING 성공이나 FLUSHALL만으로 복구 완료라 하지 않는다. |

| 질문 ID | 남은 질문·상태 | 답에 따라 달라지는 것 |
| --- | --- | --- |
| W4-CACHE-Q01 | 실제 홈 호출 조건·접근 비율은 미확인이다. 이번 과제의 대상은 D01의 무필터 좋아요순 첫 페이지로 확정했으며, 실제 홈 트래픽 확인은 운영 적용 시 재검토할 근거다. | 향후 캐시 대상·키·대상 요청 비율 재검토. 현재 API의 기존 정렬 규칙은 변경하지 않는다. |
| W4-CACHE-Q02 | B 방식 선택은 사용자 승인으로 해결. 운영 읽기/쓰기 분포에서 순이득인지는 후속 측정 대상이다. | 공통 버전 행 경합·낮은 재사용률이 문제면 A/C 등으로 재검토한다. |
| W4-CACHE-Q03 | B 선택으로 쓰기 경로의 Redis 후처리를 없앴다. 기존 관리자 성공 계약을 유지한다. | 실제 Redis 중단 중 DB 변경과 새 조회·복구를 검증한다. 버전 증가 자체의 실패는 전체 rollback이다. |
| W4-CACHE-Q04 | 과제의 TTL 선택은 D06의 60초 유지로 해결했다. 운영에서는 실제 접근/변경 분포와 메모리 예산을 기준으로 재검토한다. 연결/명령 100ms를 포함한 현재 설정의 운영 최적성·SLO 충족 여부는 별도 검증 대상이다. | [TTL·메모리 실측](../week4/product-list-cache-ttl-memory.md), 시간 경계·혼합 부하 증거. 현재 선택을 미정으로 되돌리지 않으며 단일 로컬 실험을 운영 최적값이나 SLO로 표시하지 않는다. |
| W4-CACHE-Q05 | 사용자 확정: 삭제 전 버전을 확인한 진행 중 요청은 허용한다. | 해당 hit 경합은 옛 응답을 허용하되 삭제 완료 후 새 요청은 새 버전 결과만 반환한다. |

기술 참조: 기존 [Redis 연결 설정](../../modules/redis/src/main/java/com/loopers/config/redis/RedisConfig.java), [Redis SET](https://redis.io/docs/latest/commands/set/), [MySQL 일관된 읽기](https://dev.mysql.com/doc/refman/8.0/en/innodb-consistent-read.html). 기대값은 [캐시 TDD 계획](commerce-tdd-plan.md#w4-캐시-tdd-제안), 실제 실행과 한계는 [캐시 기록](../week4/product-list-cache-tdd-log.md)을 따른다.

## 구현 전에 확인할 정책 선택

아래는 W2~W4 승인 이력이다. P07·P15·P16의 포인트 결제·재확정 설명은 이전 POINT 사실에 보존하고, W5 신규 시도에는 위 교체 계약을 적용한다. P08의 수량 자료형은 유지하되 관리자 설정에는 사용자 승인 W5-Q03 검사를 추가한다.

[API 계약](commerce-api-contract.md)의 25개 경로와 핵심 업무 조건은 과제 명세를 따른다. 아래 표의 **확정 내용만 사용자 답변을 반영한 결정**이다. 같은 행의 남은 제안이나 아래 기술 추천까지 승인된 것으로 취급하지 않는다. 기존 문서 링크를 유지하기 위해 이 절의 제목은 유지한다.

| ID | 항목 | 사용자 답변으로 확정한 내용 | 남은 제안·구현 시 확인할 부분 |
| --- | --- | --- | --- |
| P00 | 상품·브랜드 삭제 | `deleted_at`을 기록하는 논리 삭제로 행을 보존한다. | 고객 조회 제외, 과거 주문 스냅샷 조회, 남은 본인 좋아요 취소 등 작업별 조회 조건을 구분한다. 좋아요 취소의 관계 삭제는 P04를 따른다. |
| P01 | 중복 주문 품목 | 같은 productId의 양수 수량을 합산하여 상품별 항목 하나로 저장한다. 각 입력 수량과 합산 범위를 검사하고 확정 시 합산된 전체 수량으로 재고를 검사한다. | `DUPLICATE_ORDER_ITEM`은 채택하지 않는다. 수량은 P08, 입력·합산 범위 초과의 400 INVALID_REQUEST는 P12를 따른다. |
| P02 | 이름·가격 | 상품 가격은 1원 이상이다. 상품명·브랜드명은 앞뒤 공백 제거 후 Unicode 코드 포인트 기준 1~100자다. | 후속 묶음 승인으로 상품명의 계산 방식도 확정했다. 이름 중복 금지는 추가하지 않는다. 금액 표현은 P08을 따른다. |
| P03 | 사용자 식별자 연결 | 문자열 `X-USER-ID`를 DB 사용자 ID·역할로 연결하는 실습 fixture 매핑을 한 곳에서 관리한다. `alice → 1/CUSTOMER`, `bob → 2/CUSTOMER`, `admin → 3/ADMIN`으로 확정했다. 대소문자와 앞뒤 공백을 구분하여 원문으로 비교하며 trim·소문자 변환을 하지 않는다. 세 사용자의 초기 잔액은 0이다. C06 경로와 관리자 주문 필터의 userId는 외부 식별 문자열이다. | 자동 회원 생성이나 별도 인증 기능을 추가하지 않는다. 매핑과 실제 DB 행·잔액 초기화의 구현 증거는 구분한다. 고객 공개 조회·오류 응답은 P11·P12, 관리자 인증은 P15를 따른다. |
| P04 | 좋아요 취소·중복 요청·반복 삭제 | 좋아요 취소는 본인 관계 행을 실제로 삭제한다. 중복 좋아요, 이미 없는 관계 취소, 이미 삭제된 상품·브랜드의 재삭제는 추가 변경 없이 200을 반환한다. | 응답 본문·대상 없음 오류는 P12, 좋아요 변경의 상품 행 잠금은 P13을 따른다. DB 유일 제약과 실제 경합 검증이 필요하다. |
| P05 | 상품 수정·관리자 조회 | 상품 수정은 name·price만 허용하며 브랜드와 재고는 유지한다. 관리자 조회에는 삭제 행을 포함한다. 고객 조회의 삭제 제외와 삭제 대상 수정 거절도 유지한다. | 허용하지 않은 JSON 필드는 400 INVALID_REQUEST다. 관리자 응답 필드는 P12의 승인된 계약을 따른다. |
| P06 | 페이지·상품 정렬·브랜드 필터 | page·size 기본값은 0·20, size 범위는 1~100이다. 상품 정렬의 동률은 ID 내림차순이며, 없는·삭제된 브랜드 필터는 빈 목록을 반환한다. | P12에서 목록별 필터·정렬·응답·오류의 나머지 계약도 승인했다. 집계와 정렬 후 페이지 처리한다. |
| P07 | 충전·주문 생성의 반복 요청 | 성공 요청마다 새 충전·새 주문으로 처리한다. 주문 확정 재요청은 후속 승인 P16의 거절 계약을 따른다. | W2의 확정 재요청 200·기존 결과 반환은 P16으로 대체한다. 당시 실행 기록은 보존하며 별도 멱등 키나 클라이언트 자동 재전송을 추가하지 않는다. |
| P08 | 숫자 자료형 | DB 자원 ID는 `Long`, 상품 수량·재고는 `Integer`다. 금액·포인트는 원 단위 정수 `Long`/`BIGINT`, 최대 `9,223,372,036,854,775,807`로 확정했다. 덧셈·곱셈의 범위 초과를 검출한다. | 부동소수점·소수 금액·별도 일일/회당 한도를 추가하지 않는다. 잔액 합산·주문 금액 초과와 잘못된 입력은 P12의 서로 다른 오류를 따른다. |
| P09 | 기본 동시성 전략 | 행을 잠근 뒤 검증·변경·저장하는 방식을 기본 전략으로 사용한다. | 구체 격리 수준·잠금 순서·공유 범위·타임아웃·충돌 응답은 후속 승인 P13을 따른다. |
| P10 | 관리자 요청의 복합 오류 우선순위 | 일반 고객이 잘못된 관리자 API 입력을 보내면 관리자 경로 진입 단계의 권한 오류를 우선한다. | P12의 요청자·권한·입력 오류를 매핑하고 바인딩 전 진입 검사를 검증한다. 고객 API는 승인된 공통 처리 순서를 따른다. |
| P11 | 고객 브랜드 상세 HTTP | C01은 헤더 없이 공개 조회하며 전달된 X-USER-ID도 무시한다. 200 BrandView(brandId/name), 없음·삭제 404 BRAND_NOT_FOUND, 잘못된 ID 400 INVALID_REQUEST, 예상 외 500 INTERNAL_ERROR. meta/data 형식과 문서의 세 오류 메시지를 승인했다. | 선행 C01 승인 기록이다. 나머지 HTTP는 후속 P12에서 별도로 승인했다. |
| P12 | 나머지 HTTP·입력·조회 계약 | API 계약의 나머지 응답 필드·상태·오류 코드/메시지·필터·정렬을 승인했다. C02·C03도 공개 조회이며 사용자 헤더를 무시한다. JSON의 필수값·타입·허용 필드를 엄격히 검증하고 숫자 문자열·소수의 정수 강제 변환을 금지한다. 405 METHOD_NOT_ALLOWED / `지원하지 않는 HTTP 메서드입니다.`, 415 UNSUPPORTED_MEDIA_TYPE / `지원하지 않는 Content-Type입니다.`, 406 NOT_ACCEPTABLE / `지원하지 않는 응답 형식입니다.`를 사용한다. | 기존 Example API의 관찰 계약을 유지한다. 정책 승인만으로 HTTP·저장 연결이나 오류 매핑이 구현되었다고 기록하지 않는다. |
| P13 | 구체 동시성 계약 | 변경 트랜잭션은 READ_COMMITTED. 필요한 행을 주문 → 사용자 → 브랜드 ID 오름차순 → 상품 ID 오름차순으로 잠근다. 상품 등록·변경과 브랜드 삭제는 브랜드 잠금을 공유하고, 좋아요 변경은 상품을 잠근다. 잠금 타임아웃 3초, 서버 자동 재시도 없음. 교착·타임아웃은 409 CONCURRENT_MODIFICATION / `다른 요청과 충돌했습니다. 다시 요청해 주세요.`다. | 경로별로 필요한 행만 잠그되 공통 순서를 역전하지 않는다. 실제 DB 경합·롤백·타임아웃과 재요청 결과로 검증한다. |
| P14 | 구현·운영 스키마 순서 | JPA를 유지하고 기능 구현을 먼저 완료한 뒤 나머지 운영 수동 DDL을 작성·검증한다. | 이미 검증한 브랜드 SQL은 보존한다. 운영 DDL·관계 제약·초기 데이터는 최종 범위에서 제외하지 않는다. |
| P15 | 과제 피드백의 관리자 접근·검증 보완 | 관리자 API는 과제 지원 설정인 Spring Security의 ADMIN 역할을 사용한다. 고객용 X-USER-ID fixture와 분리하며 일반·미식별 관리자 요청은 403이다. 쓰기 요청은 CSRF 검증을 유지하고 MockMvc로 실제 계층·DB까지 검사한다. 잔액 0→충전 10000→복수 품목 결제 7000→내 주문·잔액 3000 조회를 연결 검증하고 다른 Java 모듈·testFixtures까지 Checkstyle을 확장한다. | 사용자가 채점 피드백을 제시하며 수정을 요청한 범위다. P03·P12의 과거 관리자 헤더 해석을 이 계약으로 바로잡는다. 로그인 기능은 추가하지 않는다. |
| P16 | W3 주문 재확정 거절 | 본인 CONFIRMED 주문의 재확정은 `409 ORDER_ALREADY_CONFIRMED` / `이미 확정된 주문입니다.` / meta.result=FAIL·data 생략. 소유권을 먼저 검사해 타인은 404. 상품·브랜드가 이후 삭제돼도 상태 오류이며 기존 주문·재고·잔액·결제 결과는 불변. GET 조회와 실패한 DRAFT의 조건 개선 후 재시도는 유지한다. | 2026-10-08 사용자 승인으로 W2의 재확정 200·성공 결과 공유를 대체. W3-RECONFIRM-01..06 구현·검증 완료. 같은 주문 동시 확정도 성공 한 건과 후속 상태 오류를 구분한다. [실행 기록](../week3/order-reconfirmation-tdd-log.md) |

선행 P03·P09·P10·P11 승인 당시의 범위와 후속 묶음 승인을 구분한다. 사용자의 “어 진행해줘” 답변으로 추천한 금액·나머지 API·초기 잔액·상품명 길이·잠금 세부값을 확정했다. 아래 설계 대안의 비교 기록은 보존하며 API 계약의 승인과 개별 클래스 구조 선택·구현 완료를 혼동하지 않는다. 새 업무 정책이나 기존 계약 변경이 생기는 경우에만 별도로 확인한다.

확정한 행 잠금 전략의 적용을 주문 확정까지 미루지 않는다. 상품 등록과 브랜드 삭제도 경합하므로 **브랜드 CRUD부터 P13의 공유 잠금과 획득 순서를 적용한다.** 중복 차감·재고와 잔액의 음수·부분 반영을 허용하지 않는 기대값은 구현 편의를 위해 완화하지 않는다.

## W3 주문 재확정 거절

W5에서는 이전 POINT 확정 주문에 보존할 계약이다. 새 PG 동일 시도 종결 결과의 200 재반환을 이 과거 결정으로 거절하지 않는다.

출처는 사용자가 제공한 W3 과제의 확정 주문 재요청 거절 요구와 이번 후속 승인이다. 같은 주문을 다시 처리하지 않는다는 상태 보존 요구는 유지하고 외부 응답만 W2의 성공 재사용에서 명시적 상태 오류로 변경한다.

| 구분 | 결정·이유 |
| --- | --- |
| 선택 | 소유권 확인 후 CONFIRMED면 `409 ORDER_ALREADY_CONFIRMED`. 확정은 상태 전환 요청이며 이미 완료된 전환을 다시 성공으로 처리하지 않는다. |
| 버린 대안 | W2의 `200`·기존 성공 결과 반환. 추가 차감 방지는 가능하지만 새 과제의 재확정 거절 요구와 맞지 않아 대체한다. 기존 결과 확인은 GET 책임이다. |
| 보존 | 원주문·항목·결제액·확정 시각·재고·잔액을 재요청으로 바꾸지 않는다. 타인에게 상태를 노출하지 않고 404를 우선한다. 이후 상품·브랜드 삭제가 이 상태 오류를 404 상품 오류로 바꾸지 않는다. |
| 경계 | 최초 확정에 실패한 DRAFT는 조건 개선 후 다시 확정할 수 있다. 동시 요청의 최초 성공과 후속 상태 오류를 구분하고, 잠금 타임아웃·교착을 재확정 오류로 바꾸지 않는다. P13의 잠금·트랜잭션 경계는 유지한다. |
| 검증 상태·범위 | 승인한 W3-RECONFIRM-01..06 구현·검증 완료. [재확정 TDD 기록](../week3/order-reconfirmation-tdd-log.md)에 실제 결과를 남겼다. W3의 8개 주문 재고 경쟁·3개 주문 포인트 경쟁·주문 내부 저장 경계 실패/롤백 검증·갱신 유실 대조군은 이번 증분 범위가 아니다. |

## W3 브랜드·상품 일괄 논리 삭제

출처: 사용자가 제공한 W3 「Implementation Quest」의 브랜드 일괄 처리·중간 실패 검증 요구와 후속 대화. 과제 요구, 사용자 선택, 구현 시 선택한 기술 방식을 다음처럼 구분한다.

| 구분 | 결정·현재 상태 |
| --- | --- |
| 과제 요구 · 확정 | 미삭제 상품 존재 시 거절하던 동작을 브랜드·연결 미삭제 상품 전체의 삭제·비노출로 변경. 재고 0 포함, 상품이 없어도 성공. 다른 대상·과거 주문·결제 결과와 기존 접근 경계를 보존 |
| 사용자 선택 · 확정 | P00의 soft delete 유지. 비관적 잠금과 하나의 DB 트랜잭션으로 브랜드·상품 변경을 함께 커밋/롤백. P09·P13의 잠금 후 검증, 공통 잠금 순서, 대기 제한과 오류 계약 유지 |
| 기술 선택 · 구현 | 상품 ID를 정렬 조회하고 각 기본 키에 `SELECT ... FOR UPDATE`한 뒤 bulk UPDATE. P13을 바꾸지 않고 상품별 엔티티 로딩·저장 호출을 피함. 잠금 SELECT는 N회이며 성능 측정 결과는 아님 |
| 첫 증분 검증 | 정상 HTTP 삭제·다른 대상과 확정 주문 보존, 상품 UPDATE 1회·상품 엔티티 로딩 0회, 외부 트랜잭션의 로딩된 상품 동기화와 다른 변경 보존. [실행 기록](../week3/brand-removal-tdd-log.md) |
| 기본 경계 검증 | 빈 브랜드·없는 브랜드·기삭제 상품의 혼합/전부 기삭제·재삭제의 응답과 저장 상태 검증 완료. 기존 구현에서 바로 통과하여 생산 코드 변경 없음. [W3-BRAND-02 기록](../week3/brand-removal-tdd-log.md#두-번째-증분--w3-brand-02-경계-검증) |
| 전체 롤백 검증 | 실제 상품 UPDATE와 브랜드 saveAndFlush 뒤 테스트 전용 런타임 예외를 주입해 HTTP 500·전체 DB 원복 확인. 기존 구현에서 바로 통과하여 생산 코드 변경 없음. [W3-BRAND-03 기록](../week3/brand-removal-tdd-log.md#세-번째-증분--w3-brand-03-롤백-검증) |
| 연결·접근 검증 | 실제 삭제 후 고객 조회 제외·새 좋아요/주문/DRAFT 최초 확정 거절, 본인 좋아요 취소·기존 주문 조회 보존, 관리자 변경 거절·삭제 행 조회, 권한·CSRF 거절과 DB 불변 확인. [W3-BRAND-04~06 기록](../week3/brand-removal-tdd-log.md#네-번째-증분--w3-brand-0408-연결동시성-검증) |
| 경합 검증 | 주문 생성·최초 확정·상품 등록과 삭제의 선후 결과는 순차 검증. 별도 실제 경합은 시작만 맞추고 허용된 요청 결과·최종 DB 정합성 확인. [W3-BRAND-07~08 기록](../week3/brand-removal-tdd-log.md#네-번째-증분--w3-brand-0408-연결동시성-검증) |
| 코드 현황 | `AdminBrandService.delete()`의 기존 READ_COMMITTED 트랜잭션·브랜드 잠금을 유지. `BrandDeletionService`가 상품 저장소의 일괄 삭제 후 Brand를 삭제하고 application이 브랜드를 저장. 존재 시 거절과 `BRAND_HAS_PRODUCTS` 매핑은 제거 |

### 트랜잭션 경계와 호출 순서

업무 규칙·외부 결과는 [전체 설계](commerce-erd-draft.md#w3-브랜드상품-일괄-논리-삭제)·[A05 계약](commerce-api-contract.md#관리자-api-계약표)을 따른다. 기존 HTTP 진입과 서비스 프록시는 유지하고, 상품 변경·브랜드 저장을 다음 경계에 연결한다.

| 순서 | 호출·책임 | 트랜잭션·상태 |
| --- | --- | --- |
| 1. HTTP 진입 | 관리자 Security·CSRF 경계 → `AdminBrandController.delete()` → Spring 프록시 → `AdminBrandService.delete()` | 서비스의 `@Transactional(READ_COMMITTED)`에서 시작. 기본 REQUIRED이며 같은 트랜잭션 관리자에 속한 외부 트랜잭션이 있으면 참여. application의 역할 재검사도 유지 |
| 2. 브랜드 보호 | `BrandRepository.lockById()` → `BrandRepositoryImpl` → `BrandJpaRepository`의 `PESSIMISTIC_WRITE` | 브랜드 락을 얻은 뒤 현재 상태 판단. 없으면 기존 오류, 이미 삭제됐다면 추가 변경 없이 성공. 미삭제 상품 존재는 더 이상 거절 조건이 아님 |
| 3. 상품 변경 | `BrandDeletionService` → domain의 `ProductRepository.softDeleteNonDeletedByBrandId()` → infrastructure의 잠금·bulk UPDATE | 해당 브랜드의 미삭제 상품 ID를 오름차순으로 각각 잠근 뒤 flush·bulk UPDATE. 서버가 한 번 정한 삭제 시각과 수정 시각을 반영. 재고·가격은 보존. 갱신 0건은 브랜드 없음 오류가 아님 |
| 4. 브랜드 변경·저장 | 브랜드 도메인의 삭제 행동 → `BrandRepository.save()` → JPA 저장 | 상품과 같은 삭제 시각을 사용하고 같은 트랜잭션에 참여. `saveAndFlush()`는 SQL 반영이지 독립 커밋이 아님 |
| 5. 완료·실패 | 현재 HTTP 진입처럼 서비스에서 트랜잭션을 시작하면 프록시 종료 시 커밋 또는 롤백 → HTTP 결과 | 중간 저장 실패는 예외를 삼키거나 부분 성공으로 바꾸지 않고 전파. RuntimeException 등 롤백 대상 예외로 전체 취소. 커밋 성공 후에만 정상 응답 |

외부 트랜잭션에 REQUIRED로 참여한 호출이라면 서비스 반환이 곧 커밋은 아니며, 실제 완료는 외부 트랜잭션 경계에서 일어난다. 그 호출자도 READ_COMMITTED와 P13 순서를 지켜야 한다. 저장소의 일괄 변경은 `MANDATORY`로 기존 트랜잭션 참여를 강제한다. 기존 `new BrandDeletionService(...)`는 바깥 서비스 트랜잭션 안에서 실행되는 도메인 협력 객체이며 그 자체가 트랜잭션 누락을 뜻하지 않는다. 상품별 `REQUIRES_NEW`·별도 커밋·비동기 작업으로 분리하지 않는다. 체크 예외를 도입한다면 롤백 규칙도 명시하고 검증한다.

### 락 대기와 경쟁 상태

한 트랜잭션은 서로 다른 테이블의 필요한 행 락을 함께 보유할 수 있다. 다음 테이블을 처리한다고 앞의 락을 해제하지 않는다. **브랜드 락이 연결 상품으로 자동 전파되는 것은 아니다.** 상품 UPDATE도 DB 락을 획득하며, 관련 경로의 대상·순서가 함께 맞아야 한다.

락을 늦게 얻는 것 자체는 정상적인 대기다. 위험한 것은 잠금 전 읽은 값으로 사용 가능하다고 판단한 뒤, 기다리는 동안 바뀐 상태를 확인하지 않고 처리하는 것이다. 기존 `OrderService`는 브랜드·상품을 잠근 뒤 삭제 여부를 검사하고, 사용자·상품 락을 확보한 뒤 재고·잔액을 검증한다. 새 삭제 경로도 이 원칙을 유지한다. DB의 잠금 조회·해제 동작은 [MySQL locking reads](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)를 참고한다.

아래는 락 대기 제한 안에서 선행 요청이 성공한 경우의 기대값이다. HTTP 도착 순서가 아니라 공유 락의 획득과 트랜잭션 완료로 순서가 정해진다.

| 선행 처리 | 뒤 요청의 판단·보존할 결과 |
| --- | --- |
| 브랜드 삭제가 먼저 락을 얻고 커밋 | 기다리던 상품 등록·새 주문 생성·DRAFT 최초 확정은 락 획득 후 삭제 상태를 확인하고 거절. 새 상품·차감·확정 결과를 남기지 않음 |
| 최초 주문 확정이 먼저 락을 얻고 커밋 | 이후 브랜드·상품 삭제는 허용하되 이미 저장된 주문·결제 결과와 차감 효과는 되돌리지 않음 |
| DRAFT 생성이 먼저 커밋된 뒤 브랜드 삭제 | 생성 성공이 나중의 확정까지 보장하지 않음. 최초 확정 시 삭제 여부를 다시 확인하고 거절하며 DRAFT·기존 주문 스냅샷 보존 |

대기가 길어지면 P13의 **DB 행 락 대기 제한 3초**, 교착·타임아웃 `409 CONCURRENT_MODIFICATION`, 서버 자동 재시도 없음 정책을 적용한다. 3초는 전체 요청의 최대 실행 시간이 아니다. 브랜드 잠금 공유는 같은 브랜드의 서로 다른 상품 요청도 대기시킬 수 있으므로 보호 범위와 처리 시간을 함께 확인한다.

W3-BRAND-07~08은 두 종류의 증거를 구분한다. 주문 생성·최초 확정·상품 등록 각각과 브랜드 삭제의 양쪽 선후 결과는 순차 호출로 검증한다. 실제 경합은 worker의 시작만 맞춰 실제 Spring 서비스·MySQL을 호출하고, 모든 요청이 끝난 뒤 허용된 성공·업무 거절과 최종 DB 정합성을 확인한다. 잠금 구간에 장벽을 넣어 선후를 강제하지 않으며, 기술 오류를 업무 거절이나 삭제 성공으로 숨기지 않는다. 따라서 특정 DB 락 대기·양쪽 선후의 동시 실행 재현이나 모든 실행 스케줄을 보장하는 증거로 쓰지 않는다. [실행 기록](../week3/brand-removal-tdd-log.md#네-번째-증분--w3-brand-0408-연결동시성-검증)에 세부 범위를 남긴다.

### bulk UPDATE의 구현 선택과 검증 범위

| 확인점 | 선택 이유·현재 구현 |
| --- | --- |
| 잠금 순서 | bulk SQL 실행 계획에 맡기지 않고 대상 ID를 정렬하여 기본 키별 `FOR UPDATE`를 실행한 뒤 갱신. 브랜드 잠금으로 같은 브랜드의 등록·변경을 조율하며 기존 P13 유지. 선후 결과의 순차 검증과 시작만 맞춘 실제 경합 검증을 구분하며 테스트만으로 모든 락 획득 순서를 관찰했다고 주장하지 않음 |
| 시각·상태 보존 | 엔티티별 `@PreUpdate`에 기대지 않고 `deleted_at`·`updated_at`을 같은 값으로 갱신. `deleted_at IS NULL`로 기존 삭제 상품 제외. 정상 요청의 시각 일치·다른 데이터 보존과 W3-BRAND-02의 기삭제 상품 전체 행·재삭제 시각 보존 검증 완료 |
| 영속성 컨텍스트 | 잠금 조회의 자동 flush를 늦춰 상품 잠금을 먼저 확보하고 명시적 flush 후 bulk 실행. 이미 로딩된 대상 Product만 refresh하여 오래된 객체 재사용을 방지. 전체 clear 없이 다른 관리 객체의 변경을 보존하는 외부 READ_COMMITTED 사례 통과. [Spring Data JPA modifying queries](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html#jpa.modifying-queries) |

업무상의 일괄 처리·원자성이 반드시 SQL 한 문장을 뜻하지는 않는다. 반복 `saveAndFlush()` 대신 상품 상태 UPDATE 한 번을 선택했지만 잠금 SELECT는 상품 수만큼 실행한다. 따라서 전체 SQL 1회나 성능 개선을 측정했다고 주장하지 않는다. 큰 대상 수의 처리량·락 유지 시간은 미측정이다. application의 트랜잭션, 도메인 서비스의 협력, domain 저장소 약속과 infrastructure 구현으로 기존 계층 의존을 유지한다.

검증은 [TDD 계획](commerce-tdd-plan.md)의 W3 브랜드 사례로 연결한다. W3-BRAND-03에서는 **실제 상품 UPDATE 후 다음 브랜드 저장의 saveAndFlush까지 수행한 뒤 테스트 전용 예외를 유발**했다. 트랜잭션 안에서 양쪽 변경을 관찰하고 서비스 종료 후 새 JDBC 조회로 6개 테이블의 전체 행이 원복됨을 확인했다. DB 접속 단절·커밋 실패 등 모든 장애를 재현한 것은 아니다. 과거 W2 실행 로그는 새 일괄 삭제의 완료 증거로 재사용하지 않는다.

## AI와 설계 다듬기: 책임·의존·대안 검토

아래는 책임 중복과 의존에 대한 검토 기록이다. 확정 업무 규칙과 기존 의존 기준은 유지하며, 대안의 비용 비교와 실제 선택·구현을 구분한다. 선택하지 않은 대안은 비교 기록으로 보존한다. 구현 구조를 바꾸면 관련 계약·테스트 기대값의 영향도 함께 확인한다.

| 검토 질문 | 비교한 대안 | 초안의 선택·이유와 주의점 |
| --- | --- | --- |
| 관리자·주문 서비스가 각각 재고 부족을 검사하면 중복되지 않는가? | 각 서비스가 직접 수량 계산 / 상품 도메인이 재고 상태 규칙 관리 | Product가 ProductStock을 내부 소유하고 수량 검증·변경을 위임한다. 애플리케이션은 도메인의 사전 검증·변경 메서드를 호출하며 비음수·차감 계산을 다시 구현하지 않는다. |
| Order가 상품·사용자 조회와 저장까지 맡아야 하는가? | Order가 전체 결제 처리 / 애플리케이션이 도메인을 조율 | 애플리케이션이 트랜잭션·조회·소유권·재요청 분기를 조율하고 Product·User·Order는 각자의 상태를 변경한다. 한 주문의 원자성을 엔티티별 저장으로 쪼개지 않는다. |
| Brand가 상품 전체를 가지고 있어야 삭제를 판단할 수 있는가? | Brand에 상품 컬렉션 로딩 / 도메인 서비스와 상품 저장소의 협력 | W2는 존재 조회로 삭제 거절 여부를 판단했다. W3는 application 트랜잭션 안에서 도메인 서비스가 상품 저장소의 bulk 삭제와 브랜드 상태 변경을 조율한다. Brand의 상품 컬렉션 로딩은 필요하지 않다. |
| 상품 삭제 시 실제 행을 지우는가? | 물리 삭제와 FK 재설계 / deleted_at 논리 삭제 | P00에서 상품·브랜드 논리 삭제를 확정했다. 기존 주문·좋아요 FK를 유지한다. 작업별 미삭제 조건이 필요하며 모든 조회에 공통 필터를 강제하면 안 된다. |
| 좋아요 취소도 상품처럼 논리 삭제해야 하는가? | 관계 행 보존·재활성화 / 관계 행 실제 삭제 | P04에서 취소 시 관계 행 실제 삭제를 확정했다. UNIQUE(user_id,product_id)와 재등록·관계 집계를 함께 검증한다. 상품 삭제 시 관계를 함께 지우는 규칙은 추가하지 않는다. |
| 상품 조회의 좋아요 수를 별도 컬럼으로 관리할까? | 상품 카운터 동시 갱신 / 관계 집계 | 과제에 따라 관계에서 집계한다. 중복 등록·재취소로 개수가 변하지 않는다. 정렬은 집계 후 페이지 처리하고 성능 검토 없이 캐시 컬럼을 추가하지 않는다. |
| 과거 주문 조회에 현재 상품명·가격을 사용할까? | 현재 상품과 결합해 표시 / OrderItem 스냅샷 | 생성 스냅샷을 선택한다. 가격 변경·상품 삭제와 무관하게 합의한 주문 금액을 보존한다. 현재 재고·판매 가능 여부는 DRAFT 확정에서 따로 확인한다. |
| 애플리케이션이 JPA 저장소 구현에 직접 의존해도 되는가? | infrastructure 구현 직접 참조 / domain 저장소 인터페이스 참조 | domain 저장소 인터페이스와 infrastructure의 JPA 구현을 연결했다. 현재 ArchUnit 규칙과 계층 의존 방향을 유지한다. 실행 시 저장소 호출 방향과 코드 의존 방향을 구분한다. |
| 동시 확정은 트랜잭션 선언만으로 충분한가? | 단순 읽기·저장 / 비관적 잠금 / 버전·조건부 갱신 | P09에서 행 잠금 후 검증·변경·저장을 기본 전략으로 확정했다. 아래 대안 비용을 보존하며, 브랜드 CRUD부터 P13의 잠금 순서·3초 타임아웃·재시도 없음·409 충돌 응답과 실제 경합을 검증한다. |

**W2 당시 구현·과거 증거:** `domain/brand/BrandDeletionService`는 삭제 행 포함 브랜드 조회와 미삭제 상품 존재 조회에 의존하고, 삭제가 가능할 때만 Brand의 상태를 변경했다. 최초 [브랜드 모델 실행 기록](commerce-tdd-brand-log.md)은 조회 대역을 사용한 판단·상태 보존의 증거다. 이후 `AdminBrandService`에서 브랜드 행 잠금·실제 상품 존재 SQL·저장·트랜잭션·HTTP를 연결했다. 재고 0 상품의 삭제 차단 등은 [관리자 브랜드 실행 기록](commerce-tdd-admin-brand-http-log.md)에 당시 계약으로 보존한다. W3는 존재 시 거절을 제거하고 일괄 변경을 구현했으며, 실제 SQL 이후 저장 경계 실패의 전체 롤백을 세 번째 증분에서 검증했다.

브랜드는 **직접 JPA 매핑·기존 BaseEntity 미상속**으로 Long IDENTITY ID와 생성·수정·삭제 시각을 한 곳에서 관리한다. domain의 `BrandRepository`를 infrastructure의 JPA 구현으로 연결하고, `BrandName`은 infrastructure의 autoApply converter로 변환한다. 실제 MySQL 저장·재조회·Unicode 경계·롤백·변경 감지·감사 시각은 [영속성 실행 기록](commerce-tdd-brand-persistence-log.md)에 보존했다. 최초 검증은 `ddl-auto=create`였으며, 후속 [브랜드 스키마 TDD](commerce-tdd-brand-schema-log.md)는 수동 SQL을 별도 MySQL에 적용하여 JPA `validate`와 실제 저장으로 검증했다. 현재 다른 도메인도 JPA로 연결했지만 기존 BaseEntity를 개편하거나 자동 마이그레이션을 도입한 것은 아니다. 나머지 5개 테이블도 P14에 따라 기능 구현 후 수동 DDL을 작성했으며, [전체 스키마 TDD](commerce-tdd-schema-log.md)에서 6개 모델의 validate·저장·제약을 검증했다.

고객 브랜드 상세는 `BrandQueryService.getDetail(long)`의 읽기 전용 트랜잭션에서 없음·삭제를 동일한 HTTP 독립 사유로 처리한다. 저장소의 삭제 포함 조회 계약과 application 결과·HTTP DTO의 경계를 유지했다. 최초 [조회 실행 기록](commerce-tdd-brand-query-log.md)과 [브랜드 HTTP TDD](commerce-tdd-brand-http-log.md)의 15개 HTTP·MySQL 사례는 당시 증거로 보존한다. 이후 관리자 변경, 상품, 포인트·좋아요, 주문까지 승인된 25개 API를 연결했다. [주문 실행 기록](commerce-tdd-order-log.md)은 스냅샷·소유권·재확정·원자적 차감과 실제 SQL 실행 후 롤백을 다룬다. 전체 회귀와 운영 DDL의 최신 증거는 완료 체크리스트를 따른다.

### 1. Product와 ProductStock의 상태 소유

ProductStock은 수량 규칙을, Product는 삭제 상태에 따른 변경 가능 여부를 책임진다. 변경 가능한 재고 객체를 외부로 노출하면 Product의 삭제 상태 검사를 우회할 수 있으므로 Product 내부에서만 소유한다.

| 대안 | 초기 비용 | 운영·변경 비용 | 테스트 비용 |
| --- | --- | --- | --- |
| A. Product가 ProductStock을 내부 소유하고 행동을 위임 | 위임 메서드와 노출 제한 필요 | 수량 규칙과 상품 생명주기 규칙을 각각 변경할 수 있음. 별도 저장 상태를 중복으로 두지 않음 | 기존 수량 테스트 유지, 삭제·수정·차감 연결 테스트 추가 |
| B. 수량과 규칙을 Product에 통합 | 기존 구현·테스트 이동 필요 | 타입은 줄지만 재고 규칙 변경이 상품 모델과 함께 진행됨 | 브랜드·가격 등 상품 생성 조건까지 재고 테스트 준비에 영향을 줄 수 있음 |

**실제 구현: A.** Product가 `@Embedded ProductStock`을 내부 소유하며, 삭제 상태를 확인한 뒤 재고 설정·차감·차감 사전 검증을 위임한다. 변경 가능한 ProductStock 참조는 외부에 노출하지 않는다. 상품 엔티티와 삭제 상품의 변경 거절(STOCK-06)을 [상품 TDD](commerce-tdd-product-log.md)에서 검증했다.

### 2. 도메인 상태 저장과 동시성

ProductStock.deduct가 계산한 변경 후 상태와 저장소의 감소량 명령을 혼용하면 이중 차감이나 오래된 상태 저장을 만들 수 있다. 아래 두 안 모두 도메인에서 계산한 **변경 후 상태를 저장**하며, 별도의 감소량 SQL을 추가 실행하지 않는다.

| 대안 | 초기 비용 | 운영·변경 비용 | 테스트 비용 |
| --- | --- | --- | --- |
| A. 비관적 잠금으로 조회 → 도메인 변경 → 상태 저장 | 관련 행과 잠금 순서 설계 필요 | 경합 시 대기·교착 가능. 새 변경 경로도 같은 잠금 규칙을 따라야 함 | 같은 주문·공유 재고/잔액·상품 등록/브랜드 삭제 경합과 롤백 검증 |
| B. 조회 → 도메인 변경 → 버전 조건으로 상태 저장 | 버전 관리, 충돌 후 재조회·재검증·재시도 경계 필요 | 경합이 잦으면 재시도 비용 증가. 새 변경 경로가 버전 검사를 빠뜨리지 않아야 함 | 충돌·재시도 종료·재확정 거절(P16)·기존 확정 결과 보존·부분 반영 방지까지 검증 |

**선택된 기본 전략: A(P09).** 브랜드 CRUD부터 관련 변경 경로에 행 잠금 후 검증·변경·저장을 적용한다. P13의 READ_COMMITTED, 주문→사용자→브랜드 ID 오름차순→상품 ID 오름차순, 공유 잠금 범위, 3초 타임아웃·자동 재시도 없음·409 충돌 응답을 적용한다. B는 비교한 대안으로 보존한다. B로 변경한다면 상품 등록도 브랜드의 공유 버전 검사·변경에 참여해야 하며, 브랜드에 버전 필드만 추가하는 것으로 경합이 해결되지는 않는다. DB의 동시성 보호 조건은 업무 규칙을 중복 구현하지 말라는 이유로 제거하지 않는다.

**W3 검증 완료:** [갱신 유실 대조군 W3-LOST-UPDATE-01](../week3/stock-lost-update-control-plan.md)을 테스트로 구현해 **커밋 2·기술 오류 0·최종 재고 4와 불변식 위반**을 확인했다. P09·P13 선택과 생산 코드의 잠금은 유지했다.

| 검토한 점 | 대조군의 범위·해석 |
| --- | --- |
| 트랜잭션 선언만으로 충분한가 | 독립 READ_COMMITTED 트랜잭션 둘이 같은 재고를 읽고 오래된 계산값을 저장하면, 모두 커밋돼도 갱신이 유실되는 반례를 재현했다. 원자성 검증의 통과를 동시성 보호의 증거로 대신하지 않는다. |
| 의도적으로 제외한 보호 | 테스트 전용 경로에서 조회 시 `FOR UPDATE`를 사용하지 않는다. UPDATE 자체의 행 잠금까지 없애는 실험이나 `OrderService`·저장소의 잠금 제거가 아니다. |
| 실제 서비스 증거와의 구분 | 읽기 뒤 장벽은 이 대조군에만 사용한다. P13을 적용한 실제 서비스의 8개 주문 경쟁은 시작만 맞추는 별도 테스트 W3-STOCK-RACE-01에서 검증했다. 대조군의 통과를 그 검증의 완료 증거로 쓰지 않는다. |

**재고 경쟁 검증:** [W3-STOCK-RACE-01](../week3/stock-order-concurrency-tdd-log.md)에서 확정 5·재고 부족 3·기술 오류 0·재고 0과 구매자별 차감 보존을 확인했다. 기존 잠금 순서·타임아웃·재시도 정책을 바꾸지 않았다. 사용자·브랜드·상품 잠금이 함께 작동하는 실제 서비스의 결과이며, 상품 잠금만의 효과나 포인트 3주문 경쟁의 증거로 확대하지 않는다.

**포인트 경쟁 검증:** [W3-POINT-RACE-01](../week3/point-order-concurrency-tdd-log.md)에서 잔액 10,000원에 4,000원 주문 3개를 실행해 확정 2·포인트 부족 1·기술 오류 0·잔액 2,000원을 확인했다. 주문별 브랜드·상품을 분리하고 재고를 충분히 둔 것은 시험 조건이며 새로운 제품 정책이 아니다. 실패 주문의 재고 보존까지 확인했고 실제 서비스의 잠금·재시도 정책은 변경하지 않았다.

**충전·결제 경쟁 검증:** [W3-CHARGE-ORDER-01](../week3/point-charge-order-concurrency-tdd-log.md)에서 같은 사용자 행을 잠그는 실제 충전·주문 서비스를 실행해 두 요청 성공·잔액 5,000을 확인했다. 초기 10,000+충전 2,000−결제 7,000의 합산과 품목별 재고·확정 결과를 검증했다. 생산 코드·P09/P13은 유지했으며, 당시 관련 검사와 2026-10-09 전체 최종 검사 결과를 구분해 기록한다.

### 3. 동시 중복 좋아요와 200 응답

P04에 따라 중복 등록은 추가 관계 없이 200이다. DB 유일 제약은 필요하지만 일반 저장 예외를 잡는 것만으로 트랜잭션이 정상 완료되고 200이 반환된다고 가정하지 않는다.

| 대안 | 초기 비용 | 운영·변경 비용 | 테스트 비용 |
| --- | --- | --- | --- |
| A. 상품 잠금 후 사용 가능 여부·본인 관계 확인과 생성 | 잠금 조회와 관계 생성 순서를 연결 | 인기 상품에서는 서로 다른 사용자의 등록도 대기할 수 있음. 삭제·취소 경로와 잠금 규칙 조율 필요 | 동시 중복의 두 응답·관계 1행·개수, 상품 삭제와의 경합 검증 |
| B. 원자적 삽입의 생성/기존 결과를 저장소가 반환 | DB에 맞는 삽입 명령과 결과 계약 필요 | SQL·드라이버 의존과 갱신 행 수 해석 관리. 상품 삭제와의 경합은 별도 보호 필요 | 실제 DB에서 신규/중복·트랜잭션 상태·집계·삭제 경합 검증 |

**실제 구현: A, P13의 잠금 순서 적용.** 좋아요 등록·취소는 사용자 → 상품 순서로 행을 잠근 뒤 현재 상태와 본인 관계를 확인한다. 관계 INSERT의 사용자 FK 검사도 부모 행 잠금을 사용한다. 상품만 먼저 잠그면 사용자 → 상품 순서인 주문 확정과 잠금 순서가 엇갈릴 수 있어 사용자 행을 먼저 잠근다. 이는 새 업무 정책이 아니라 승인된 전체 잠금 순서를 FK 저장까지 적용한 구현이다. B의 원자적 삽입 SQL은 비교 대안으로 보존한다. P04의 중복 200·관계 실제 삭제와 동시 중복은 [포인트·좋아요 TDD](commerce-tdd-point-like-log.md)에서 검증했다.

### 4. 좋아요 정렬과 조회 결과 조합

좋아요 수 정렬은 전체 결과에 적용한 뒤 페이지를 잘라야 한다. 상품 페이지를 먼저 가져와 application에서 좋아요 수를 붙이고 정렬하면 목록 계약을 지킬 수 없다. 저장소 입력·출력에는 controller DTO나 application 소유 타입을 넣지 않는다.

| 대안 | 초기 비용 | 운영·변경 비용 | 테스트 비용 |
| --- | --- | --- | --- |
| A. domain 소유 조회 결과로 집계·정렬·페이지를 함께 조회 | 조회 결과 타입, 조인·집계·전체 건수 조회 설계 필요 | 실행 계획·인덱스 검토 필요. 조회 요구 변경이 저장소 조회 모델에도 전파됨 | 좋아요 0·동률·필터·페이지 경계·전체 건수 조건 일치 검증 |
| B. ID 순위 페이지를 조회한 뒤 관련 데이터를 batch로 결합 | 순위·상세 조회와 결과 순서 복원 필요 | 여러 조회의 비용과 시점 일관성 검토 필요. 순위와 상세를 따로 변경할 수 있음 | 누락·순서 뒤바뀜·중복·N+1 방지와 두 조회 사이 변경 검증 |

**상품 조회의 실제 구현: A.** infrastructure의 JPQL이 브랜드와 좋아요를 조인·집계하여 domain 소유 `ProductSummary`로 반환하고, 정렬 후 페이지를 구성한다. 전체 건수 조회에도 같은 필터 조건을 적용한다. application 결과와 HTTP 응답으로의 변환은 각 계층에서 수행하며 [상품 TDD](commerce-tdd-product-log.md)에서 집계·필터·정렬·페이지를 검증했다. 과거 주문은 현재 Product 정보가 아닌 저장된 OrderItem 스냅샷으로 구성한다. 주문 목록은 주문 ID 페이지와 항목 일괄 조회를 결합하여 컬렉션 조인으로 페이지가 달라지는 것을 피한다.

### 5. 업무 실패와 HTTP 매핑

기존 CoreException/ErrorType 경로는 ErrorType의 HttpStatus를 함께 사용한다. 이를 그대로 확장하면 업무 실패를 추가하거나 HTTP 계약을 바꿀 때 도메인 호출부와 공통 오류 타입을 함께 검토해야 한다. 구현된 ProductStockException은 HTTP 정보 없이 실패 사유만 표현한다.

| 대안 | 초기 비용 | 운영·변경 비용 | 테스트 비용 |
| --- | --- | --- | --- |
| A. 기존 CoreException/ErrorType 확장 | 기존 처리기를 활용하므로 낮음 | 업무 사유와 HTTP 상태의 결합 유지. 공통 변경이 기존 예시 API에도 영향을 줄 수 있음 | 새 업무 오류와 기존 관찰 계약 회귀 검증 |
| B. 업무 실패 사유와 API 오류 매핑 분리 | 사유 타입과 경계별 매핑 추가로 중간 수준 | 매핑 누락을 관리해야 하지만 HTTP 변경의 도메인 전파를 줄임 | 도메인 사유 테스트와 요청별 HTTP 계약 테스트를 각각 검증 |

**새 커머스 경계의 실제 구현: B.** domain·application은 HTTP 독립 실패 사유를 표현하고 `CommerceErrors`가 승인된 상태·코드·메시지로 변환한다. `CommerceWebConfiguration`은 커머스 경로의 프레임워크 오류도 같은 경계로 연결하며 기존 Example 처리기는 유지한다. 같은 유효성 실패도 요청 입력인지 저장 데이터의 불변식 위반인지 구분한다. 매핑은 P11·P12·P13과 [공통 HTTP 검증](commerce-tdd-admin-brand-http-log.md)을 따른다.

### 추가 검토: 공통 기반과 요청 진입 경계

다음은 기존 다섯 검토에서 이어진 경계의 비용 비교와 현재 구현이다. 기존 공통 기반을 일괄 개편하지 않으며, 이 검토를 이유로 ArchUnit·검사 규칙을 변경하지 않는다.

| 확인한 경계·변경 시나리오 | 대안 A와 비용 | 대안 B와 비용 | 결정 상태·검증할 점 |
| --- | --- | --- | --- |
| [BaseEntity](../../modules/jpa/src/main/java/com/loopers/domain/BaseEntity.java)의 public delete/restore를 상속하면 도메인의 삭제 조건을 거치지 않고 호출할 수 있음 | 상속을 유지하고 삭제 호출 경로를 관리. 초기 비용은 낮지만 새 호출과 복원 경로를 계속 점검해야 함 | 감사 필드 기반과 도메인 삭제 행동을 분리. 기반·매핑 변경 비용은 중간 수준이며 노출 행동을 더 강하게 제한할 수 있음 | 커머스 엔티티는 직접 JPA 매핑하고 기존 BaseEntity를 상속하지 않는다. 각 엔티티가 필요한 감사·상태 필드와 행동을 소유한다. 기존 BaseEntity 개편·새 공통 감사 기반·복원 기능을 추가한 것은 아님 |
| [공통 Advice](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/ApiControllerAdvice.java)의 프레임워크 오류 처리를 바꾸면 기존 예시와 신규 커머스 응답이 함께 변할 수 있음 | 단일 Advice에서 계약별 정책을 분기. 초기 파일 수는 적지만 모든 변경에서 분기와 기존 계약을 함께 관리 | 범위별 Advice로 분리. 초기 구성·우선순위 설계가 필요하고 각 계약의 변경 범위를 줄일 수 있음 | C01 전용 advice는 후속 구현에서 CommerceErrors·CommerceWebConfiguration의 커머스 공통 경계로 통합했다. 컨트롤러 선택 전 405·415·406을 포함하고 기존 Example 오류 처리와 관찰 계약을 회귀 검증한다 |
| 단일 fixture 매핑을 각 controller가 따로 해석하면 요청자 정보 변환·권한 검사가 흩어질 수 있음 | application 공통 기능이 외부 ID를 내부 ID·권한으로 해석. 초기 타입·기능 비용은 있지만 HTTP 외 호출에서도 재사용·단위 검증 가능 | HTTP 해석기가 요청자 정보를 제공. controller 중복은 줄지만 웹 경계 테스트와 application 직접 호출의 권한 보장이 별도로 필요 | 고객은 A의 UserResolver와 단일 fixture를 유지한다. 관리자는 P15에 따라 Spring Security의 ADMIN 역할을 검사하고 HTTP 경계에서 UserRole로 변환한다. application은 HTTP·Security 타입에 의존하지 않고 AdminAuthorization으로 전달된 역할을 재검사한다. 주문 소유권은 Order가 판단한다 |
| 공통 입력 우선 설명과 관리자 권한 우선 설명이 복합 오류에서 다른 결과를 만들 수 있음 | 형식 오류 우선. 입력 변환을 먼저 활용하기 쉬우나 관리자 요청에는 채택하지 않음 | 관리자 경로 진입의 권한 오류 우선. 진입 검사 연결과 이후 입력 검증 순서를 관리해야 함 | P10에서 B로 확정. 일반 고객의 잘못된 관리자 ID 요청으로 순서를 검증하며, 구체 HTTP 상태·코드·메시지는 P12의 승인 계약을 따른다 |

관리자 HTTP 요청은 [AdminBoundaryConfig](../../apps/commerce-api/src/main/java/com/loopers/config/AdminBoundaryConfig.java)의 Spring Security 필터 체인에서 ADMIN 권한과 필요한 CSRF를 검사한 뒤 MVC의 본문·경로 입력 처리로 진행한다. 컨트롤러는 [AdminRequester](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/commerce/AdminRequester.java)로 인증 주체를 `UserRole`로 변환해 application에 전달하고, application은 [AdminAuthorization](../../apps/commerce-api/src/main/java/com/loopers/application/user/AdminAuthorization.java)으로 역할을 재검사한다. 입력 바인딩 전 권한 우선 보장은 Security가 담당하며, HTTP를 거치지 않는 유스케이스 호출의 역할 검사도 application에 유지한다.

`UserResolver`는 domain의 `UserIdentityRepository`에 의존한다. 최초 식별·권한 규칙과 Spring 연결의 증거는 [사용자 식별 실행 기록](commerce-tdd-user-identity-log.md)에 보존했다. 현재 P03의 정방향·역방향 식별 조회는 `FixtureUserIdentityRepository`의 불변 Map 한 곳을 사용하며, 관리자 주문 응답도 별도 매핑을 만들지 않는다. `FixtureUserInitializer`는 시작 시 누락 DB 사용자만 잔액 0으로 생성하고 기존 잔액·감사 시각을 보존한다. 실제 초기화·포인트·좋아요 연결은 [해당 실행 기록](commerce-tdd-point-like-log.md), 현재 HTTP 진입 권한 우선은 [관리자 Security 보완 기록](commerce-admin-security-log.md)으로 확인한다.

### 동시성 테스트의 상태 보존 범위

다른 변경이 없는 실패 테스트에서는 요청 전후 DB 값이 같아야 한다. 동시 실행에서는 **실패한 요청이 추가 효과를 남기지 않았는지**와 전체 결과를 검증한다. 재고 5에 각 4개인 두 주문 중 하나가 성공했다면 최종 재고 1은 정상이며, 실패 요청의 전후 재고가 5로 같아야 한다고 단정하지 않는다. 실패 주문은 DRAFT를 유지한다.

정확한 실행 순서별 기대값은 동기화한 테스트로 검증하고, 순서를 고정하지 않은 경합 테스트는 허용 결과·차감 총량·최종 상태를 검증한다. 기술 전략에 맞춘다는 이유로 한 번 차감·비음수·부분 반영 금지 규칙을 완화하지 않는다.


## 금액 자료형의 공식 자료 확인과 비용 비교

2026-09-18 요청에 따라 비교한 근거를 보존한다. 비교 당시 금액 자료형은 보류였으나, 후속 추천 묶음 승인으로 원 단위 Long/BIGINT와 범위 초과 검사(P08)를 선택했다.

Java의 `BigDecimal`은 십진수 정밀도와 반올림을 제어한다. MySQL의 `DECIMAL`도 정확한 값을 저장하며 공식 문서는 금액을 사용 예로 든다. 정수 표현 역시 가능하다. Stripe는 결제 API의 금액을 최소 통화 단위의 정수로 받는다. 이는 금액 표현 방식의 사례이며 이 프로젝트에 외부 결제를 추가한다는 뜻은 아니다. [Java BigDecimal](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html), [MySQL DECIMAL](https://dev.mysql.com/doc/refman/8.0/en/fixed-point-types.html), [Stripe 금액 단위](https://docs.stripe.com/currencies).

| 대안 | 현재 규칙에 적용 | 초기·변경·검증 비용 |
| --- | --- | --- |
| A. Java Long / DB BIGINT | 원 단위 정수 금액과 정수 포인트를 저장. 10,000원은 10000으로 표현 | 구현이 단순하고 범위 내 정수 계산이 정확함. 합산·곱셈의 범위 초과를 검사해야 함. 소수 계산을 추가하면 정산 단위·반올림 정책과 계산 표현을 별도로 정해야 함 |
| B. Java BigDecimal / DB DECIMAL(p,s) | 현재 정수 금액 정책을 유지하려면 소수 금액을 거절하고 scale 0으로 저장하는 안을 고려 | 정밀도·scale·비교·변환·저장 상한을 설계하고 시험해야 함. 이후 소수 계산을 표현하기 편하지만 원 단위 반올림 정책을 자동으로 정해주지는 않음 |

**선택은 A(P08)를 유지한다.** 원 단위 정수, 1포인트=1원이다. 이 비교 당시에는 외부 결제가 범위 밖이었지만 W5는 같은 저장 금액으로 로컬 테스트 PG를 호출하며 PG 경로의 포인트 차감은 0이다. 세금·환율·쿠폰 통합은 추가하지 않는다. B는 미채택 비교 대안이다.

선택한 Long안에서는 `Math.addExact`·`multiplyExact`처럼 범위 초과를 검출하는 연산을 사용한다. BIGINT의 저장 범위와 Java 연산 범위의 상한을 `Long.MAX_VALUE`로 맞춘다. 현재 별도 일일·회당 업무 한도는 추가하지 않는다. [Java Math](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/Math.html), [MySQL 정수 타입](https://dev.mysql.com/doc/refman/8.0/en/integer-types.html).

변경에 취약한 부분은 금액 타입 자체보다 합산·곱셈·범위 검사가 User·Order·응답 변환에 흩어지는 것이다. 공통 금액 연산을 도메인 타입에 모으는 안은 초기 타입·매핑 비용이 들지만 계산 정책의 수정 지점을 줄인다. 기본 숫자 타입을 각 모델에서 직접 사용하는 안은 초기 비용이 작지만 동일한 경계 검증을 여러 곳에서 유지해야 한다. 잔액 부족 판정은 User, 주문 총액 구성은 Order가 계속 책임지며 공통 금액 타입에 유스케이스 조율을 넣지 않는다. 아직 Money 타입을 구현한 것은 아니다.
