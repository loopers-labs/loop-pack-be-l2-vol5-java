# R01 검증 계획

[상세 설계](plan.md)의 예정 검사다. 아래 이름/검사 건수는 아직 구현·통과 결과가 아니다.
사용자 설계 선택 전 초안이므로 [트레이드오프 논의](trade-offs.md)의 결과에 따라 검사 세부도 달라진다.
공통 테스트 기반은 [초기 환경](../environment.md)을 사용한다. 각 변경의 관련 검사부터 실행한다.

## 계약·시각

| 예정 검사 | 핵심 기대 |
|---|---|
| OrderConfirmedContractTest | 정수 범위/양수/빈 품목/불변 list, 합산과 overflow, 정렬 |
| OrderConfirmedWireContractTest | 실제 producer bytes → 실제 parser → 같은 고정 사실 |
| Parser 실패 | 누락/null/unknown/중복 JSON key·잘못된 숫자 타입·잘못된 key·지원 밖 type/version·세밀한 시각 거절 |
| 이벤트 동등성 | 속성/품목 순서·중복 상품 합산·timezone 표현이 달라도 같은 사실 |
| 확정 시각 저장 | 고정 Clock의 마이크로초 값을 DB에서 새로 읽어 event confirmedAt과 정확히 비교 |
| 기존 사실 보존 | 중복 callback/reconcile과 이전 POINT의 기록·시각 불변 |

공통 계약을 공유해도 두 codec의 wire 검사를 생략하지 않는다. 공유 record 객체를 두 번 호출하는 검사로 대체하지 않는다.

## Inbox·구매 활동

| 예정 검사 | 실제 DB에서 확인할 것 |
|---|---|
| PurchaseActivityIntegrationTest 정상 | Inbox 1행, 상품 7×2·8×1은 활동 2행 |
| 동일 사건 반복/동시 소비 | 행·수량·최초 processedAt 불변 |
| 같은 상품의 다른 주문 | 별도 활동과 수량 합 반영 |
| eventId/orderId/payload 충돌 | 오류, 기존 Inbox·활동 불변 |
| 중간 품목 실패 | Inbox와 앞서 저장한 품목까지 rollback |
| Inbox insert 경합 | 실제 named 유일 제약, 패자 rollback 뒤 새 TX에서 동일 사실 확인 |
| 다른 무결성 오류 | 중복으로 오인하지 않음, ack 대상 아님 |
| 원본 상품 삭제/비노출 | 과거 productId/수량/confirmedAt 유지, 상품 재조회 없이 처리 |
| datetime(6) 재조회 | 재시작/새 EntityManager에서 복원한 사건이 원래 사건과 동등 |

JPA 쓰기·새 TX 조회를 사용한다. 기존 규칙을 우회하는 JdbcClient/테스트용 INSERT IGNORE는 추가하지 않는다.
모든 실패는 예외뿐 아니라 새 DB 조회의 기존 상태/행 수도 확인한다.

## 실제 Kafka 경계

### KafkaGroupAssignmentTest

고유 topic 2 partitions, 같은 group의 실제 consumer 1→2→3을 시작하고 안정된 assignment를 deadline 안에 확인한다.
3개 중 최소 하나는 미배정이며 두 partition 모두에 실험 record를 명시 발행해 읽는다.
업무 topic과 분리하고 key 실험에서는 producer metadata의 partition을 비교한다.

### KafkaDeliveryTest

실제 구매 활동 handler·DB·topic·group을 고정하고 첫 consumer만 교체한다.

1. producer metadata의 target offset n을 얻는다.
2. 첫 consumer가 배정된 뒤 n을 group 시작 offset으로 commit하고 target을 읽는다.
3. 실제 handler DB commit 후 Inbox/활동을 새 TX로 확인한다. n+1은 commit 요청하지 않는다.
4. auto commit/pending commit/종료 commit callback 없이 bounded close한다. committed offset n 유지 확인.
5. 새 consumer는 같은 group으로 subscribe/poll한다. seek/reset 없이 같은 record n을 다시 읽는다.
6. ALREADY_PROCESSED 후 n+1을 commit한다. Inbox·활동 불변을 확인한다.

두 번째 consumer에서는 auto.offset.reset=none을 사용해 commit된 시작 위치가 없는데 fallback한 결과를 재전달로 오인하지 않는다.
실제 committed offset `n → n → n+1`과 topic/partition/offset을 기록한다.

### PurchaseActivityListenerIntegrationTest

실제 record factory·parser·Facade·DB를 사용한다. target 처리 TX의 중간 품목에서 오류를 주입한다.
Inbox/활동 전체 rollback, target 이후 offset 미진행, 컨테이너 중단/원인 표시를 확인한다.
오류 제거 후 같은 group으로 컨테이너를 재시작해 같은 record의 정상 저장과 ack를 확인한다.
별도 DB commit 후 ack 직전 오류도 검증해 DB 결과는 남고 재수신은 no-op임을 확인한다.

## 주문 확정·직접 발행

### OrderConfirmedPublicationTest

- 최초 검증 PG SUCCESS의 업무 TX commit 뒤 실제 send이며 실패/UNKNOWN/rollback/중복 적용에서는 새 사건 없음.
- APPLIED인 확정 실패에서도 발행 없음. 성공 CAS 경합 패배자/재시도 rollback에서도 거짓 발행 없음.
- 별도 TX 조회로 commit을 확인한다. after-commit의 active 플래그만 보지 않는다.
- 전송 호출 예외/future 실패 후에도 주문 CONFIRMED·예약 CONSUMED·PG 거래 수·기존 응답은 유지된다.
- 기존 PaymentApplicationTransaction rollback 증거·데드락 한정 재시도·현재 결과 우선 처리의 관련 회귀를 확인한다.

기존 W5 검사에는 전송 포트만 테스트 구성으로 대체해 broker 의존을 늘리지 않는다.
실제 발행 검사와 E2E는 대체 publisher 없이 실제 broker를 사용하고 그 차이를 명시한다.

### W6PublicationGapReproductionTest

실제 주문 API·로컬 PG·실제 DB/Kafka·실제 consumer로 두 사례를 한 실행에서 만든다.

| 사례 | 주입과 새 조회 기대 |
|---|---|
| A | commit 후 send 직전 테스트 전용 경계에서 중단. CONFIRMED·CONSUMED, broker 해당 사건 없음, Inbox/활동 없음 |
| B | 실제 발행·소비 완료. CONFIRMED·CONSUMED, broker metadata, Inbox 1행·상품별 활동 |

중단은 테스트 전용 publisher decorator/테스트 부트스트랩에서만 주입한다.
제품 flag·공개 endpoint를 만들지 않는다. 같은 실제 AFTER_COMMIT 호출을 통과해야 한다.

한 harness가 MySQL·Kafka·로컬 PG와 두 주문의 수명을 소유한다. 통합 재현은 다음 실행 구조로 정한다.

- API 테스트에서 실제 CommerceApiApplication을 RANDOM_PORT로 실행하고 클래스도 명시한다.
- 테스트가 새 MySQL/Kafka를 시작한다. API 테스트 클래스패스에는 streamer main 코드를 넣지 않는다.
- 실제 apps:test-pg와 commerce-streamer는 각 Boot jar의 별도 Java 프로세스로 시작한다.
  필요한 bootJar 작업을 해당 재현 검사의 준비 의존성으로 연결하고 생성된 실제 경로를 사용한다.
- 새 MySQL의 PG 전용 스키마와 소비 테이블은 명시 SQL로 준비하며 이미 보존 중인 DB를 사용하지 않는다.
- API 테스트 구성의 @Primary publisher decorator만 첫 확정 사건의 send 직전을 한 번 중단한다.
  나머지 전송은 명시한 실제 Kafka publisher에 위임한다. 제품 main 코드에는 주입용 flag/endpoint를 만들지 않는다.
- API/PG HTTP 준비, streamer group assignment, 결과 대기에 각각 deadline을 둔다.
- 시작한 Process handle과 container만 추적해 정리한다. 다른 Java/Docker 프로세스를 일괄 종료하지 않는다.
- API와 streamer가 같은 DB를 쓰더라도 독립 프로세스/업무 TX의 commit을 새 조회로 확인한다.

이 방식은 앱 간 runtime 의존성과 전체 component scan 충돌을 피하면서 실제 API·PG·consumer를 검증한다.
streamer 내부의 rollback/listener 검사는 해당 모듈에서 실제 factory/parser/handler만 가져오는 작은 context로 별도 수행한다.

보존 자료는 W6 revision/실행 방법·고정 clock/품목/수량·사례 역할과 실제 다음 값이다.

```text
orderId, providerOrderId, transactionKey, eventId, confirmedAt, items
주문 상태, 예약 소비, broker topic/partition/offset 또는 미발행 근거
Inbox/활동 행 수·수량, PG create 누적 횟수·외부 거래 수
```

R02가 직접 발행을 제거하기 전 이 구성/revision으로 새 격리 DB에서 재현 가능한지 확인한다.
W6 revision과 테스트 bootstrap 실행 방법을 함께 고정해 R03에서도 W6 실제 직접 발행 경로를 준비 단계에 사용할 수 있게 한다.
임시 DB를 장기 보존하지 않는다. R03에서 새로 생성된 사건을 과거 원본 사건이라고 부르지 않는다.

## 설정·구조·마무리

- root bootstrap 주소가 admin/producer/consumer에 동일하게 주입되는지 확인한다.
- 실제 consumer config의 deserializer·enable.auto.commit·ack mode·error handler를 검사한다.
- API/streamer의 demo 비활성화와 전용 factory 선택, 앱 이름·포트·보존 프로필을 확인한다.
- contracts runtimeClasspath에 Spring/JPA/Jackson/앱 코드가 없는지 확인한다.
- streamer에 Checkstyle·기존 계층/ordering domain 순수성 gate를 연결하고 실제 새 클래스를 검사한다.
- 테스트 0건·skip·연결 실패를 성공으로 세지 않는다. 고유 자원·bounded 대기·자기 자원만 정리한다.
- 기능별 관련 검사 후 PR 마무리에서 API/streamer/Kafka/contracts의 필요한 check를 실행한다.
- PR에는 Inbox 1행/품목별 활동·assignment·offset 전후·직접 발행 누락과 남은 한계를 기록한다.

설계 문서만 변경한 현재 단계에서는 기능 테스트/DB SQL을 실행하지 않는다.
문서 링크·JSON 예시·변경 범위만 검사하고 구현 검증 완료로 기록하지 않는다.
