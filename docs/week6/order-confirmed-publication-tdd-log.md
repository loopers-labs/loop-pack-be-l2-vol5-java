# W6 주문 확정 after-commit 발행 TDD 기록

**2026-10-30 후속 안내:** 이 문서는 2026-10-29 주문 전용 발행 구현의 Red/Green·명령·862개 검사 결과를 보존한다. 이후 공통 `DomainEventListener`·`DomainEventPublisher`·Kafka publisher·타입별 route로 일반화·검증한 내용과 후속 검사 결과는 [공통 발행 TDD 기록](common-domain-event-publication-tdd-log.md)을 따른다. 아래 표는 당시 클래스 이름과 책임을 유지하되 삭제된 파일의 링크는 현재 대체 클래스로 연결했다. 선행 5개 adapter 테스트와 현재 공통 테스트의 범위는 같다고 간주하지 않는다.

## 작업 범위와 보존

2026-10-29 사용자가 after-commit listener를 거쳐 Kafka로 보내는 다음 증분을 요청했다. 최초 확정의 불변 이벤트 모델은 [선행 기록](order-confirmed-event-tdd-log.md)을 유지한다. 이번 증분은 공통 PG 결과 적용 → 실제 commit → 발행 포트 → Kafka adapter를 연결하며 streamer의 구매 활동 저장·offset 실험은 후속이다.

기준 HEAD는 `6e686d824d979ad16a7a2baa70afbeee2c3ce460`이며 미커밋 작업 트리에서 검증한다. 기존 트랜잭션의 격리 수준·전파, 주문·브랜드·상품·시도 잠금 순서와 포인트/예약 처리는 바꾸지 않는다. 사용자 DB·기존 broker·볼륨은 변경하지 않는다.

## 트랜잭션 경계의 Red → Green

| 순서 | 관찰 | 결과 |
| --- | --- | --- |
| Red 1 | 실제 Spring·격리 MySQL 테스트를 먼저 작성, 없는 `OrderConfirmedEventPublisher` 참조 | 컴파일 오류 2개, 종료 1 |
| Red 2 | 포트만 추가하고 실제 DB 테스트 실행 | 12개 중 5개 실패. 최초 commit 이후에도 publisher 호출이 없어 실패, 나머지 미발행 사례 통과 |
| Green 1 | 최초 `confirmPgPayment` 전이에서 불변 이벤트 발생, AFTER_COMMIT listener가 포트 호출 | 12개 통과, 실패·오류·skip 0. 2026-10-29 09:33 KST, 18초, 종료 0 |

```shell
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.application.order.OrderConfirmedPublicationIntegrationTest' \
  --offline --console=plain
```

| 확인한 경계 | 실제 관찰 |
| --- | --- |
| REQUIRED 참여 | 외부 TransactionTemplate 안에서 apply 반환·flush 뒤에도 전송 0회, 실제 외부 commit 뒤 1회 |
| commit 증거 | 발행 포트에서 REQUIRES_NEW의 다른 물리 DB 연결로 CONFIRMED·CONSUMED·저장 최초 시각을 확인. after-commit의 thread-bound 자원이 이미 사라졌다는 잘못된 가정은 사용하지 않음 |
| rollback | 실제 SQL 후 예외를 던지면 주문·예약·시도 원복, 발행 0회. 이후 같은 성공 관찰을 정상 적용하면 발행 1회 |
| 반복·경합 | 반복 성공·늦은 PENDING/UNKNOWN은 추가 발행 0. 두 worker의 성공 적용은 APPLIED 1·UNCHANGED 1·발행 1 |
| 발행 제외 | DRAFT·준비·PENDING·UNKNOWN·FAILED·생성 전 확정 거절·결과 충돌·과거 POINT에서 발행 없음. 트랜잭션 없는 로컬 이벤트도 실행하지 않음 |
| 발행 포트 즉시 예외 | 저장된 CONFIRMED·CONSUMED·포인트 변화 0 보존. 반복 성공으로 발행/PG 생성 재시도를 만들지 않음 |

이 12개는 실제 DB와 Spring 트랜잭션 검증이지만 발행 포트는 mock이다. 실제 Kafka 성공이나 구매 활동 완료 증거로 대신하지 않는다.

## Kafka 전송 연결과 선택

사용자는 W6-Q05에서 **전송 완료를 기다리지 않고 결과 callback으로 관찰**하는 방식을 선택했다. 별도 `@Async`·executor나 `get/join/flush`는 추가하지 않았다. `send`가 반환한 future를 관찰해 성공에는 eventId/orderId/topic/partition/offset, 실패에는 eventId/orderId/topic/예외 종류를 기록한다. 예외 본문·카드·원문 payload는 로그로 남기지 않는다. 실패 로그는 broker에 기록이 없다는 단정이 아니다.

| 변경 파일 | 책임 |
| --- | --- |
| [OrderPaymentStockService](../../apps/commerce-api/src/main/java/com/loopers/application/order/OrderPaymentStockService.java) | 기존 공통 TX B에서 `confirmPgPayment`가 true인 최초 전이만 불변 이벤트 구성·발생. 기존 잠금 조회·트랜잭션 선언 불변 |
| 당시 `OrderConfirmedEventListener` → 현재 [DomainEventListener](../../apps/commerce-api/src/main/java/com/loopers/application/event/DomainEventListener.java) | 실제 AFTER_COMMIT에서 발행 포트 호출. 트랜잭션 없는 이벤트는 fallback 실행하지 않고, 발행 예외가 확정 응답을 바꾸지 않도록 격리 |
| 당시 `OrderConfirmedEventPublisher` → 현재 [DomainEventPublisher](../../apps/commerce-api/src/main/java/com/loopers/domain/event/DomainEventPublisher.java) | 주문 계층이 Kafka 구현에 의존하지 않도록 전송 약속 정의 |
| 당시 `KafkaOrderConfirmedEventPublisher` → 현재 [KafkaDomainEventPublisher](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/event/KafkaDomainEventPublisher.java) | orderId 문자열 key로 한 번 send하고 즉시/지연 성공·실패를 관찰. 애플리케이션 재전송·PG 재생성 없음 |
| 당시 `OrderEventKafkaConfiguration` → 현재 [DomainEventKafkaConfiguration](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/event/DomainEventKafkaConfiguration.java) | API 전용 typed producer, ISO 시각 JSON·타입 헤더 제외·기본 full-record 오류 로그 대신 위 whitelist 관측. Kafka TX manager를 추가하지 않음 |
| [application.yml](../../apps/commerce-api/src/main/resources/application.yml) | 기본 topic `order-events`, 활성화, bootstrap 환경 설정과 아래 producer 초기값 |
| [application-test.yml](../../apps/commerce-api/src/test/resources/application-test.yml) | 무관한 회귀는 발행을 명시적으로 비활성화해 사용자 broker에 접근하지 않음. 새 실제 Kafka 테스트는 활성화·주소·topic을 전용 자원으로 재설정 |

API는 `spring-kafka` 라이브러리를 직접 의존한다. 기존 `modules:kafka`의 공유 batch listener 설정을 API까지 컴포넌트 스캔하거나 streamer의 설정을 이번 증분에서 변경하지 않기 위한 선택이다. 새 DB 테이블·DDL·스키마 적용은 없다.

| 설정 | 선택 이유·한계 |
| --- | --- |
| `max.block.ms=1000` | metadata 확보·buffer 대기를 제한해 후속 발행의 장애가 주문 응답을 오래 붙잡지 않게 하는 초기값. serializer·스케줄링 등을 포함한 API 전체의 1초 하드 상한은 아님 |
| `request.timeout.ms=3000`, `delivery.timeout.ms=10000`, `linger.ms=0` | broker 요청과 전체 전달 예산을 구분하고 짧은 일시 장애의 재시도 여지를 둠. 운영 부하 측정으로 확정한 SLA가 아니라 실습 초기값 |
| `retries=3`, `acks=all`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=5` | 호환되는 producer 설정으로 broker 전송 중 재시도 중복을 줄임. delivery 예산 등으로 재시도 횟수가 줄 수 있고, DB 중복 보호·발행 누락·종단 간 exactly-once를 보장하지 않음 |
| 미채택: 전송 접수 후 최대 2초 대기 | 주문 확정은 이미 저장됐으므로 후속 Kafka 확인 때문에 고객을 더 기다리게 하지 않음. 기다려도 DB와 broker 사이의 원자성은 생기지 않음 |

## 어댑터와 실제 broker 검증

어댑터 최초 3개 테스트를 먼저 작성한 실행은 없는 adapter 참조의 **컴파일 오류 2개**로 실패했다. 이후 사용자 선택에 따라 지연 성공·지연 실패의 반환 후 관찰 2개를 추가하고 구현해 **5개**가 통과했다. 경계 테스트와 함께 실행한 **17개**는 2026-10-29 09:44 KST, 24초, 종료 0이었다.

실제 broker 첫 실행은 테스트 AdminClient의 API 예산 10초가 기본 request timeout 30초보다 작아 초기화에 실패했다. 이 실패를 업무 Red나 전송 실패 증거로 세지 않는다. 테스트 전용 Admin/consumer 요청 timeout을 3초로 맞춘 뒤 **5개**가 23초에 통과했다. 이후 실제 metadata timeout도 HTTP 성공을 보존하는지 확인할 1개를 추가했으며 최종 관련 검사에 포함한다.

| 테스트 | 검증 범위 |
| --- | --- |
| 당시 `KafkaOrderConfirmedEventPublisherTest` · 5개 → 현재 [KafkaDomainEventPublisherTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/event/KafkaDomainEventPublisherTest.java) | 실제 record metadata 값 로그, 즉시/완료 future 오류, 미완료 future에서 먼저 반환한 후 성공·실패 관찰, 민감한 예외 메시지 비노출·추가 send 없음 |
| [PgOrderEventKafkaIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/PgOrderEventKafkaIntegrationTest.java) · 6개 | 실제 고객 HTTP·로컬 PG·MySQL·Kafka. confirm/callback/reconcile 각각 최초 확정, key·6개 JSON 필드·ISO 저장 시각·정규화 두 품목·type header 없음·broker 위치 로그 대조. 반복 세 경로에서 발행/PG 생성 1회. 실제 설정·발행 직전 누락·실제 metadata timeout 경계 |

Kafka는 `apache/kafka:3.8.1`의 테스트 소유 컨테이너이며 고유한 2-partition topic·검사용 group과 같은 bootstrap을 producer/admin/consumer에 주입한다. 검사용 consumer는 auto commit을 끄고 해당 테스트 시작 시의 end offset부터 읽는다. 이것을 group assignment나 committed offset 재전달 실험으로 간주하지 않는다. consumer·PG를 닫고 Testcontainers가 소유 broker와 격리 DB 자원을 정리한다. 사용자 DB·기존 topic/group·볼륨 삭제는 수행하지 않는다.

전송 직전 누락은 test spy가 실제 adapter 진입 전에 예외를 내도록 통제했다. 실제 주문 CONFIRMED·모든 품목 CONSUMED·포인트 불변·PG 생성 1회와 해당 테스트의 broker offset 증가 0을 확인한다. 이것은 OS 강제 종료 재현이 아니며, 아직 구매 활동 DB가 없어 W7 두 사례의 전체 인계 완료로 표시하지 않는다. 일반 Kafka timeout 역시 broker 미기록의 증거로 일반화하지 않는다.

## 최종 관련 검사

2026-10-29 **09:48 KST**, 아래 명령은 **BUILD SUCCESSFUL / 1분 52초 / 종료 0**이었다.

```shell
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.application.order.*' \
  --tests 'com.loopers.domain.order.*' \
  --tests 'com.loopers.domain.payment.*' \
  --tests 'com.loopers.interfaces.api.order.*' \
  --tests 'com.loopers.infrastructure.order.*' \
  --tests 'com.loopers.architecture.ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  --offline --console=plain
```

| 실제 실행 범위 | 테스트 수 |
| --- | ---: |
| application.order | 262 |
| domain.order | 182 |
| domain.payment | 125 |
| interfaces.api.order | 287 |
| infrastructure.order | 5 |
| API ArchitectureTest | 1 |
| 합계 | **47개 스위트·862개** |

- 실패·오류·skip **0**, API Checkstyle main/test 보고서 2개의 위반 **0**.
- 이번 증분의 신규 테스트 **23개**(커밋 경계 12·어댑터 5·실제 PG/DB/Kafka 6)는 위 862개에 포함된다. 이전 이벤트 모델 40개와 선행 실행 건수를 중복 합산하지 않는다.
- 기존 주문·결제의 재고/포인트·경합·rollback·복구·POINT 보존·소유권/삭제 계약과 새 Kafka 연결을 함께 실행했다. 전체 프로젝트 `check`나 streamer 검사를 실행한 것으로 확대하지 않는다.
- 최종 Kafka 검사에서 실제 producer의 acks/idempotence/retries/in-flight/timeout 및 비트랜잭션 factory, JSON wire를 확인했다. 실제 missing-topic metadata 오류는 `order_event_publication_failed … errorType=KafkaException`으로 관찰되었고 고객 HTTP 200·PG SUCCESS·예약 CONSUMED·PG 생성 1회가 유지됐다.
- 성공 3개 사례에서 실제 topic `order-events-test-f13b2303-04bd-4e52-a0ff-73b725b79ac4`의 partition 1, offset 0·1·2를 소비하고 해당 성공 로그와 대조했다. 각 사례는 DB fixture를 초기화한 독립 테스트이며, 같은 숫자 orderId가 다시 생성됐다는 이유로 이를 같은 역사적 사건의 재전달로 보지 않는다. 별도 소비 DB는 이 테스트에 연결하지 않았다.
- 검사용 consumer·로컬 PG·Spring context가 종료됐고, 프로세스 종료 뒤 `org.testcontainers=true`인 실행 중 컨테이너 조회 결과는 0개였다. 사용자 DB·기존 volume·topic/group 삭제는 없다.

XML·HTML 결과는 API의 `build/test-results/test`와 [테스트 보고서](../../apps/commerce-api/build/reports/tests/test/index.html)에 생성된다. 후속 실행에서 바뀔 수 있으므로 이 문서의 명령·시점·범위·실제 관찰값으로 이번 증거를 보존한다.

## 남은 범위

이번 요청인 **최초 PG 확정 commit → after-commit listener → Kafka 전송과 결과 관찰**은 구현·검증했다. 전체 W6 완료 판정, streamer의 소비 완료/모든 품목 저장·DB 중복/충돌·offset 재전달/실제 consumer 실패·partition assignment 실험·W7 두 사례 인계·복제 내구성 검증은 후속으로 남는다. 직접 발행의 commit/send 사이 누락은 여전히 존재하며 Outbox는 이번 증분에 추가하지 않았다.
