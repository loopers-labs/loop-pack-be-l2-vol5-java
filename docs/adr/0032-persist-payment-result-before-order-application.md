# ADR 0032: 검증한 결제 결과를 TX B에 저장하고 TX C에서 주문·예약·재고에 반영한다

Status: Accepted
Related: [ADR 0029](0029-order-payment-switches-to-pg-with-reservation-on-order.md), [ADR 0030](0030-pg-payment-split-transactions-and-apply-once.md), [ADR 0031](0031-pg-lookup-retry-circuit-breaker-and-fallback.md)

## Context

기존 TX B는 PG 결과 저장과 주문·예약·재고 처리를 함께 커밋했다. 주문 처리에 실패하면 이미 확인한 결제 결과와 이력도 롤백되어, 복구할 때 PG를 다시 조회해야 했다. 어느 구조든 미완료 주문을 다시 처리할 경로는 필요하다. 이번 변경은 확인한 결과를 DB에 보존하고, 그 결과를 주문에 반영하는 작업을 독립적으로 복구하기 위한 선택이다.

이 결정은 ADR 0030의 외부 HTTP 분리·발송 권한·식별자 대조·충돌 판단을 유지하면서 결과 저장과 주문 반영의 원자성 범위를 바꾼다. 조회 retry·회로의 설정값 선정과 분리해서 구현·검증한다.

## Decision

| 구간 | 책임 | 함께 커밋·롤백되는 것 |
| --- | --- | --- |
| TX A `start` | 재고 예약과 결제 시도·발송 권한 기록 | 주문 PAYMENT_PENDING·예약 RESERVED·가용 재고 차감·REQUESTED 시도 |
| TX 밖 PG HTTP | 생성 POST 한 번 또는 기존 거래 GET | 외부 PG의 효과. DB 롤백에 참여하지 않음 |
| TX B `recordCreateResponse` / `applyLookup` | 관찰 검증, 결제 시도 상태와 이력 저장 | payment_attempt·payment_event. 주문·재고는 변경하지 않음 |
| TX C `applyRecordedResult` | 저장된 종결 결과를 주문에 한 번 반영 | 주문 상태·paidAmount·예약 상태·실패 시 재고 반환 |

`OrderFacade`에는 바깥 트랜잭션을 두지 않는다. 별도 빈의 public `@Transactional` 메서드를 순서대로 호출해 B 커밋 뒤 C가 시작되게 한다. 같은 클래스 내부 호출이나 바깥 TX에 묶어 두 구간이 합쳐지는 것을 피한다. TX B·C 모두 주문 → 결제 시도 순서로 잠그고, C의 실패 반환에서만 상품 ID 오름차순으로 잠근다.

**결제 종결과 주문 반영 완료를 구분한다**

- TX B는 주문 번호·금액·거래 키·거래 수·기존 종결 결과를 검증한다. 같은 결과는 IGNORED, 불일치·반대 종결 결과는 CONFLICT로 기록한다. `APPLIED`는 결제 시도 전이를 적용했다는 뜻이며 주문 반영 완료를 뜻하지 않는다.
- TX C는 전달받은 HTTP 관찰을 재사용하지 않고 DB의 결제 시도를 다시 읽는다. SUCCESS이면 주문 CONFIRMED·예약 CONSUMED·paidAmount를 반영한다. FAILED이면 현재 가용 재고에 OrderItem 수량을 더하고 주문 PAYMENT_FAILED·예약 RELEASED로 바꾼다. 접수 전 확정 거절의 키 없는 FAILED도 같은 C를 거친다.
- PENDING·UNKNOWN·REQUESTED에는 주문 확정·예약 반환을 수행하지 않는다.
- `hasFinalPaymentResult()`는 결제 SUCCESS·FAILED 여부다. `isFinished()`는 SUCCESS+CONFIRMED+CONSUMED 또는 FAILED+PAYMENT_FAILED+RELEASED가 맞는 경우다. 같은 완료 상태의 C 재실행은 효과가 없다. 중복 C가 동시에 실행돼도 주문 잠금 안에서 확인한다.
- 별도 반영 상태 칸·예약 테이블·작업 큐는 추가하지 않는다. 결제 종결과 PAYMENT_PENDING·RESERVED의 조합으로 미반영을 식별한다. C 반영 완료는 주문·예약 상태 자체에 남는다. payment_event는 B의 관찰 이력이며 작업 수신함이나 C 완료 이력이 아니다.

**복구 경로**

1. reconcile·callback은 저장 상태를 먼저 읽는다. 이미 주문 반영까지 완료했으면 외부 조회를 생략한다.
2. 결제는 종결됐지만 주문이 미반영이면 PG 조회·회로를 거치지 않고 C를 실행한다. 같은 키 confirm도 새 POST 없이 이 경로로 복구한다.
3. 결제 결과를 아직 모르면 기존 PG 거래를 조회하고 B에 기록한 뒤, 저장된 결과가 종결이면 C를 실행한다. transactionKey가 없으면 기존 providerOrderId로 조회한다.
4. B 오류는 B의 결과·이력을 롤백한다. C는 시작하지 않으며 다음 복구 실행은 PG 조회부터 한다. C 오류는 C의 주문·예약·재고 변경만 롤백하고 B의 결제 결과·이력은 유지한다. 다음 실행은 C부터 한다.
5. B 커밋 직후 C 시작 전에 종료돼도 DB 조합으로 복구할 수 있다. 재처리 계기는 수동 reconcile·callback·같은 키 confirm이다. 이후 요청이 없어도 10분 뒤 배치가 PG 조회 없이 C를 실행한다([ADR 0033](0033-payment-recovery-batch-for-unfinished-attempts.md)).

**API와 회복 도구**

- 정상 confirm·reconcile은 C까지 수행한 뒤 반환한다. 완료 판단은 결제뿐 아니라 주문·예약 반영도 포함한다. 중간 상태를 응답으로 구성할 때는 202이며, C에서 예외가 나면 기존 예외 응답으로 전파한다. 오류를 결제 FAILED로 바꾸지 않는다.
- callback은 B·필요한 C를 마친 뒤 200이다. PG 조회 불가는 기존처럼 503이며, C 오류도 성공 ACK로 바꾸지 않는다. 재전송 여부는 PG 계약에 달렸다.
- `checked`는 결제 결과 확인 여부이며 C 완료 여부를 대신하지 않는다. 저장된 종결 결과로 C를 복구한 응답은 true다.
- 조회 retry·회로·fallback은 PG GET에만 적용한다. C 오류를 HTTP 조회 retry로 감싸지 않는다. 저장된 결과의 C 복구는 조회 회로가 OPEN이어도 실행 가능하다. 조회 횟수·timeout·예산·회로 설정값은 이번에 바꾸지 않는다.

## Alternatives and costs

- **기존 TX B 유지**: 같은 DB의 결과와 주문을 항상 함께 저장해 상태가 단순하다. 주문 처리 오류 뒤 PG 재조회가 필요하다. 기존 구현도 유효한 대안이며 재처리 필요성만으로 분리의 우열이 결정되지는 않는다.
- **B·C 분리 — 채택**: B 커밋 뒤에는 PG 장애와 무관하게 저장된 결과로 주문 처리를 재개한다. 대신 결제 SUCCESS/FAILED·주문 PAYMENT_PENDING·예약 RESERVED의 중간 상태가 정상적으로 존재하며, 완료 판단과 모든 복구 입구가 이를 처리해야 한다.
- **메모리 이벤트·비동기 실행만 추가**: B 커밋 직후 종료되면 실행 계기를 잃을 수 있다. DB에서 미반영 대상을 다시 찾는 경로가 필요하다. 메시지 발송을 추가할 경우 DB 커밋과 발송 사이의 유실도 별도 다뤄야 한다.
- **한계**: 재처리 횟수 제한·장기 실패 알림은 구현하지 않았다. 자동 미완료 탐색은 ADR 0033의 배치가 맡는다. 두 단계의 commit 자체 오류·JVM 종료와 재기동을 주입한 검증도 구분한다. 시간 경과나 C 실패만으로 재고를 풀거나 새 결제를 만들지 않는다.

## 검증

- `PaymentResultApplicationTest`: B 저장만으로 주문·재고가 바뀌지 않음, B의 실제 flush 뒤 예외·롤백·PG 재조회, C의 성공 확정/실패 반환 flush 뒤 예외·B 보존·PG 추가 조회 없는 복구, 같은 키 confirm의 C 복구, 동시 중복 B·C의 한 번 효과.
- `PaymentRecoveryRestartTest`: 같은 MySQL·PG 거래를 유지하고 서비스·HTTP 클라이언트를 재생성한다. A 뒤 POST 전/POST 뒤 B 전의 REQUESTED 복구, B 뒤 C 전의 SUCCESS·FAILED 복구를 확인한다. 프로세스 종료·재기동 자체를 실행한 테스트는 아니다.
- `PaymentCallbackE2ETest`: 저장된 종결 결과가 미반영이면 조회 회로 OPEN·위조 본문에도 저장 결과로 C를 실행하고 200, 반복 callback은 추가 효과가 없다.
- `OrderV1ControllerTest`: 결제만 종결되고 주문은 대기인 상태를 완료 200으로 표현하지 않는다.

실행 범위·결과는 [week5 구현 기록](../week5/implementation-log.md#split-b-c)에 남긴다.
