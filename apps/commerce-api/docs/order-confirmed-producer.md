# 주문 확정 이벤트 Producer 설계

`commerce-api`는 PG 성공을 최초 주문 확정으로 반영하고, DB commit 뒤 OrderConfirmed를 발행한다. payload·key·eventId·버전 정책은 [공통 이벤트 계약](../../../docs/system-design/use-cases/order-confirmed-event.md#이벤트-계약)을 따른다. Kafka topic과 연결 주소는 [Broker 설계](../../../modules/kafka/docs/broker.md)를 따른다.

## 책임과 완료 기준

주문 확정과 이벤트 전달의 완료 기준을 분리한다. 주문 확정의 기준은 TX B의 DB commit이고, producer 전송의 기준은 broker의 성공 응답이다. consumer의 구매 활동 저장은 이 앱의 완료 조건에 포함하지 않는다.

선택한 방식은 **최초 확정 commit 뒤 한 번 전송을 시도하는 직접 발행**이다. Outbox와 애플리케이션 재발행 기능은 없다. 주문 확정은 유지하면서 후속 저장 책임을 분리하지만, 발행 누락을 자동 복구하는 보장은 제공하지 않는다.

## 이벤트 구성

Producer는 저장된 주문 사실을 일정한 형태로 구성한다. 정상 발행과 재발행에서 같은 확정 사실은 같은 `eventId`, 구매 내용, 품목 순서를 유지한다.

1. `eventId`는 `order-confirmed:v1:{orderId}`로 계산한다. 한 주문의 최초 확정 사실은 하나이므로 producer에 별도 eventId 컬럼을 두지 않아도 같은 ID를 재구성할 수 있다.
2. `confirmedAt`은 최초 PG 성공으로 주문을 확정할 때 주문 상태와 같은 DB 트랜잭션에서 저장한다. 기존 코드의 마이크로초 절삭과 DB의 `DATETIME(6)` 기준을 유지한다. 중복 callback·reconcile에서 바꾸지 않으며, 발행·재발행 모두 저장된 값을 같은 순간의 UTC 문자열로 표현한다. producer의 발행 시각이나 consumer의 처리 시각을 넣지 않는다.
3. `items`는 저장된 주문 품목 스냅샷으로 구성한다. 같은 상품의 수량은 주문 생성 시 합산되어 있으므로 이를 재사용하고, producer가 `productId` 오름차순으로 정렬해서 발행한다. 재발행도 같은 구성 규칙을 사용한다.

`OrderConfirmedEvent`는 application 계층의 내부 이벤트이며, infrastructure의 `OrderConfirmedMessageV1`이 발행할 JSON을 구성한다. V2 명세가 생기면 별도 DTO를 추가한다. 다른 앱의 DTO를 import하거나 비즈니스 DTO를 공통 Kafka 모듈로 옮기지 않는다.

## 발행 조건

결과 반영에서 시도를 `SUCCEEDED`로 종결하고 주문을 `PAYMENT_PENDING → CONFIRMED`로 바꾼 조건부 갱신이 1행인 요청만 이벤트를 만든다. 생성 응답, callback, reconcile, 주기 조회가 모두 같은 TX B를 지나므로 경로와 관계없이 같은 조건이다.

| 상황 | 이벤트 등록·전송 시도 |
| --- | --- |
| 최초 PG 성공 확정, TX B commit | TX B에서 내부 이벤트 등록. commit 후 전송 단계까지 실행되면 send 1회 시도 |
| 처리 중·결과 미확인(`PENDING`·`UNKNOWN`), 충돌 | 없음 |
| 확정 실패(`PAYMENT_FAILED`) | 없음 |
| 최초 확정 TX B rollback | 없음 |
| 이미 종결된 시도에 같은 결과 반복 (중복 callback, reconcile, 주기 조회) | 없음. 조건부 갱신 0행 |
| 이전 POINT 확정 주문, 이번 변경 전 PG 확정 주문 | 없음. 소급 발행하지 않는다. |

## 발행 위치

TX B는 조건부 갱신 1행을 확인한 뒤 같은 트랜잭션 안에서 이벤트를 만들어 Spring application event로 등록한다. 전송은 `@TransactionalEventListener(AFTER_COMMIT)` listener가 TX B commit 뒤에 수행한다.

`PaymentResultTransaction.completeOrder`에서 `OrderConfirmedEvent`를 등록하고, `OrderConfirmedKafkaPublisher.onConfirmed`가 commit 뒤 메시지 변환과 전송을 담당한다.

- callback·reconcile이 공통으로 사용하는 결과 반영 TX에서 최초 확정 이벤트를 등록하고, Kafka 전송 책임은 after-commit listener에 둔다.
- listener에는 `@Async`를 붙이지 않고 애플리케이션의 별도 executor를 추가하지 않는다. Kafka Producer의 내부 I/O 스레드가 실제 네트워크 전송을 비동기로 처리하는 구조를 사용한다.
- TX B 메서드 끝에서 직접 send하지 않는다. 메서드가 끝나도 commit 전이므로 rollback된 확정의 이벤트가 나갈 수 있다.
- 이벤트는 TX B에서 읽은 `Order`의 품목 스냅샷과 저장한 확정 시각으로 미리 만든다. listener는 DB나 지연 로딩에 접근하지 않는다.
- listener는 TX B를 commit한 스레드에서 send를 호출한다. send 호출 자체에는 metadata 조회·buffer 할당 대기가 있을 수 있다. `max.block.ms`는 별도 지정하지 않고 Kafka client 기본값을 사용한다.

이벤트 구성 실패는 commit 전의 오류이므로 TX B를 rollback한다. commit 이후 메시지 변환·전송 실패는 listener에서 로그로 드러내며 주문을 되돌리지 않는다. 별도 executor를 두지 않는 선택은 실행 경로를 단순하게 하지만, send 진입의 대기까지 요청 경로에서 분리하는 선택은 아니다.

## 전송 결과와 실패

현재 단계의 전송 결과 관찰 수단은 완료 콜백으로 정한다. `KafkaTemplate.send(...)`가 반환하는 `CompletableFuture`에 콜백을 등록해 전송 결과를 비동기로 확인한다. 결과를 받기 위한 별도 조회를 반복하거나 요청 스레드에서 `get()`·`join()`으로 기다리지 않는다. 비동기 전송 결과와 send 호출 자체의 metadata·buffer 할당 대기는 구분한다.

이 콜백은 Kafka producer의 전송 완료 통지다. PG가 HTTP로 보내는 결제 결과 callback과는 별개다. `acks=all`은 producer가 broker의 현재 ISR 기록을 확인하는 기준이며, 주문 확정이나 구매 활동 저장의 완료 조건이 아니다. 주문 확정은 send 전에 TX B commit으로 끝나고, 구매 활동 저장은 consumer의 별도 트랜잭션에서 끝난다.

| 결과 | 처리 |
| --- | --- |
| 전송 성공 | 건별 성공 로그나 별도 DB 발행 이력을 남기지 않는다. 테스트에서는 성공 결과의 topic·partition·offset과 실제 record를 확인한다. |
| 비동기 전송 실패 | 완료 콜백에서 `eventId`, `orderId`와 원인을 애플리케이션의 error 로그로 남긴다. |
| send 호출에서 즉시 발생한 예외 | 호출 경계에서 같은 식별자와 원인을 error 로그로 남긴다. 완료 콜백에만 실패 처리를 맡기지 않는다. |

- 전송이 실패하거나 listener에서 예외가 나도 이미 commit된 주문을 실패·미확인으로 되돌리지 않고, PG 생성·조회를 다시 호출하지 않는다. 결과 반영의 응답은 저장된 주문 상태로 정한다.
- 전송 실패는 send 호출 경계의 예외 처리와 완료 콜백의 두 경로에서 error 로그로 관찰하며, 애플리케이션의 자동 재발행은 추가하지 않는다. Kafka producer의 설정에 따른 내부 재시도는 유지하고 애플리케이션의 새 발행과 구분한다.
- 로그는 전송 결과를 관찰한 기록이며, 주문과 함께 commit되는 영속 발행 이력이 아니다. 성공 로그를 생략해도 `acks=all`의 broker 확인은 유지된다.

broker 장애는 비동기 전송 실패뿐 아니라 send 진입의 대기로도 드러날 수 있다. 확정 응답의 상태는 DB로 결정하지만 응답 지연은 영향을 받을 수 있다. 완료 콜백은 응답 전후 어느 시점에도 실행될 수 있으며, API 응답 시점에 전송 결과를 확정했다고 가정하지 않는다.

## 발행 누락 구간

TX B commit과 broker 기록은 하나의 원자적 작업이 아니다. commit 뒤 send 전에 프로세스가 멈추면 주문은 `CONFIRMED`이고 예약은 소비됐지만, 해당 `eventId`의 record와 구매 활동은 없다. 이때는 전송 요청 자체가 없어 실패 콜백도 실행되지 않으며, 프로세스 중단을 애플리케이션 error 로그로 남긴다는 보장도 없다.

send 이후 오류나 timeout을 관찰한 경우에는 broker 기록 여부가 불명확할 수 있다. broker가 기록했지만 producer가 성공 응답을 확인하지 못했을 가능성도 있으므로, 전송 실패 로그만으로 record와 구매 활동이 없다고 단정하지 않는다.

commit 전에 보내면 rollback된 확정의 이벤트가 남으므로 택하지 않는다. `@Async`나 after-commit listener는 실행 시점만 바꿀 뿐 이 구간을 없애지 않는다.

완료 콜백은 전송 결과를 관찰하는 수단이고, Outbox는 주문 확정과 발행할 사실의 DB 기록을 같은 트랜잭션에 묶는 방식이다. 두 방식은 역할이 다르며, 콜백으로 이 누락 구간을 없앨 수 없다. Outbox를 도입해도 전송 결과는 콜백으로 확인할 수 있고, 발행 대상과 처리 상태의 영속화 정책은 그 단계에서 설계한다. 다음 단계에서 Outbox로 개선하고, 이번 단계는 [발행 누락 사례](../../../docs/system-design/use-cases/order-confirmed-event-verification.md#발행-누락-사례)를 테스트로 확인한다.

## 누락 판단과 관찰 범위

검증은 [발행 조건](#발행-조건)에 해당하는 확정 주문에서 출발해 Kafka record와 소비 완료 기록·구매 활동을 대조한다. 소급 발행하지 않는 이전 확정 주문은 대상에서 제외한다. 구매 활동은 주문당 한 행이 아니라 품목별 행이므로, 행 수만 비교하지 않고 상품 집합·수량·확정 시각까지 원본과 비교한다.

| 관찰 | 판단 |
| --- | --- |
| 같은 `eventId`의 record 있음, 소비 완료 기록과 구매 활동 없음 | 발행은 확인했지만 소비는 미완료다. 소비 지연·중단·저장 실패와 실제 committed offset을 확인한다. |
| record 있음, 소비 완료 기록과 모든 구매 활동이 원본과 일치 | 전달과 업무 처리를 확인했다. |
| record를 확인하지 못했고 소비 완료 기록과 구매 활동도 없음 | 미반영 후보다. 이 관찰만으로 미발행을 확정하지 않고 전송 경계와 broker의 조사 범위를 확인한다. |

- 소비 지연을 고려해 정해진 기한 안에서 관찰한다. 구매 활동이 즉시 보이지 않는다는 이유만으로 유실로 판단하지 않는다.
- 실패 로그가 없다는 것은 발행 성공의 증거가 아니다. send 전 중단은 실패 콜백에도 나타나지 않는다.
- consumer lag가 0이어도 발행 누락은 발견할 수 없다. broker에 들어오지 않은 이벤트는 group의 offset이나 lag에 포함되지 않는다.
- Kafka record의 존재는 보존 범위 안에서 확인한다. retention으로 이미 삭제된 record가 현재 없다는 사실만으로 과거 미발행을 증명하지 않는다.
- 원본 대조는 테스트와 검증용으로 수행한다. commerce-streamer의 정상 소비 경로에서 주문 테이블을 조회하지 않으며, 주기적 자동 대조와 보충 발행은 이번 범위에 넣지 않는다.

현재 실패 로그를 보고 자동으로 재발행하거나, 확정 이벤트를 재발행하는 API·관리 도구는 없다. 이미 종결한 결제의 reconcile도 전송 복구 수단이 아니다. 별도 복구 기능을 설계할 때는 저장된 주문의 같은 eventId·품목·확정 시각을 재사용해야 하며, 기존 record의 존재가 불명확한 재전송은 중복 가능성을 전제로 한다.

## Producer 설정

공통 [kafka.yml](../../../modules/kafka/src/main/resources/kafka.yml)의 producer 설정을 사용한다.

| 설정 | 값 | 의미와 한계 |
| --- | --- | --- |
| `acks` | `all` | 현재 ISR의 기록을 확인한 뒤 성공으로 본다. 로컬과 테스트는 broker 1대, 복제 1이라 복제 장애의 내구성은 확인하지 못한다. |
| `enable.idempotence` | `true` | 같은 producer 세션의 내부 전송 재시도로 생기는 중복을 막는다. 애플리케이션 재전송·세션 재생성 뒤의 업무 중복과 소비 DB 효과는 보호하지 않는다. |
| `retries` | `3` (공통 설정에서 명시) | 재시도 가능한 오류에 대한 producer 내부 재시도 상한이다. delivery timeout이나 재시도 불가능한 오류로 더 일찍 실패할 수 있다. 업무 요구에서 산정한 횟수는 아니다. |
| `request.timeout.ms` / `retry.backoff.ms` | `20000` / `500` (공통 설정에서 명시) | 개별 broker 요청의 응답 대기와 재시도 간 대기다. 주문 확정 또는 구매 활동 저장의 제한 시간이 아니다. |
| `max.block.ms` | 미지정, client 3.8.1 기본 `60000` | send 진입의 metadata·buffer 대기 상한이다. 호출 스레드의 응답 예산과 측정 결과에 따라 조정 여부를 정한다. |
| `delivery.timeout.ms` | 미지정, client 3.8.1 기본 `120000` | send 반환 뒤 전송 성공·실패를 보고하는 시간 상한이다. HTTP 응답 시간과 구분한다. |

이 값들로 API 응답 시간이나 복구 시간을 보장한다고 해석하지 않는다. 멱등 producer는 `acks=all`, 양수 retries, 허용되는 in-flight 수의 조합을 요구한다. 현재 in-flight 수는 client 기본값 5를 사용한다. 시간 제한과 호환 조건은 [Kafka 3.8 producer 설정](https://kafka.apache.org/38/generated/producer_config.html), 세션과 애플리케이션 재전송의 경계는 [KafkaProducer 계약](https://kafka.apache.org/38/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html)을 따른다.

## 검증

발행 조건·rollback·동시 확정은 `OrderConfirmedPublicationTest`, payload는 `OrderConfirmedEventTest`·`OrderConfirmedMessageV1Test`, 호출 예외·비동기 오류 로그는 `OrderConfirmedKafkaPublisherTest`로 검증한다. 실제 주문 API·테스트 PG·broker 기록과 send 직전 누락은 `OrderConfirmedKafkaDeliveryIntegrationTest`로 확인한다. 세부 방법은 [공통 검증 문서](../../../docs/system-design/use-cases/order-confirmed-event-verification.md)를 따른다.

주요 구현은 [PaymentResultTransaction](../src/main/java/com/loopers/application/payment/PaymentResultTransaction.java), [OrderConfirmedEvent](../src/main/java/com/loopers/application/order/event/OrderConfirmedEvent.java), [OrderConfirmedKafkaPublisher](../src/main/java/com/loopers/infrastructure/order/event/OrderConfirmedKafkaPublisher.java)다.
