# 7주차 결정 기록

> 설계 대화에서 합의한 결정과 근거, 버린 대안을 남긴다. 6주차 결정을 가리킬 때는 `W6 D-xx`로 쓴다.

## 이번 주의 목표

6주차는 "commit 뒤 메모리 큐에 넘겨 보낸다"였다. 서버가 commit과 send 사이에서 죽으면 이벤트가 사라졌고(W6 D-4), 그걸 찾을 기록도 없었다.
7주차는 확정과 "보낼 사실"을 한 트랜잭션에 함께 저장하고(Outbox), relay가 그 기록을 읽어 broker가 받을 때까지 다시 보낸다.
얻는 것은 **누락 없는 at-least-once**다. exactly-once가 아니다 — 같은 eventId가 Kafka에 두 번 이상 실릴 수 있고, 그건 6주차 consumer의 중복 처리(W6 D-18)가 받는다.

## 불변식

| ID | 불변식 | 지키는 곳 | 깨지면 생기는 일 |
|---|---|---|---|
| INV-701 | 처음 CONFIRMED가 된 주문은 같은 TX에서 Outbox 행을 정확히 하나 갖는다. 둘은 함께 commit되거나 함께 rollback된다. PENDING·UNKNOWN·FAILED 결과는 행을 만들지 않는다 | `PaymentResultApplier`의 확정 TX, `event_id` unique | 확정만 남으면 6주차와 같은 누락. 행만 남으면 거짓 이벤트 |
| INV-702 | Outbox의 payload는 만든 뒤 바뀌지 않는다. Kafka로 나가는 value는 payload와 바이트가 같고, key는 저장된 `message_key`다 | 확정 TX에서 한 번 생성, relay는 문자열 그대로 전송 | 재발행 때 내용이 달라지면 consumer가 같은 eventId를 conflict로 기록한다(W6 D-18) |
| INV-703 | PUBLISHED는 broker가 받았다고 응답한 뒤에만 기록한다 | relay의 TX-2 | send 전에 PUBLISHED로 바꾸고 죽으면 다시 집을 행이 없다 |
| INV-704 | 한 행은 한 번에 한 relay 회차만 보낸다 | 조건부 UPDATE로 집기, 임대 시간 ≥ producer 최대 전송 시간 | 아직 보내는 중인 행을 다른 회차가 집어 또 보낸다 |
| INV-705 | 안 나간 행(PENDING·BLOCKED)은 자동으로 지우거나 포기하지 않는다. BLOCKED를 다시 보낼 때는 같은 행, 같은 eventId로 보낸다 | 삭제 작업 없음, 재개는 상태만 PENDING으로 | 조용한 손실. 새 eventId로 보내면 consumer가 다른 이벤트로 본다 |
| INV-706 | 빠진 이벤트를 복구할 때는 저장된 사실만으로, 확정 TX와 같은 코드로 만든다. PG를 부르지 않는다 | Outbox 행 생성 지점 하나, 로컬 전용 진입점 | 다른 내용이 같은 eventId로 나가 conflict가 된다 |

## Outbox 행

### D-1. payload는 확정 TX에서 JSON 문자열로 한 번 만들어 TEXT로 저장한다

- **결정**:
  - 확정 TX 안에서 계약 JSON 문자열을 만들어 `payload`(TEXT)에 저장한다.
  - relay는 이 문자열과 저장된 `message_key`를 그대로 보낸다. 다시 만들지 않는다.
  - `event_id`는 unique이고, `order_id`는 조회용으로 둔다.
- **컬럼**: `event_id`, `order_id`, `event_type`, `schema_version`, `message_key`, `payload`, `status`, `created_at`, `published_at`, `attempt_count`, `next_attempt_at`, `last_error`.
- **근거**: 보낼 때 다시 만들면 그 사이 바뀐 상품 정보나 코드 변경이 섞일 수 있다. 확정 순간의 사실을 고정하는 건 문자열 그대로 저장하는 것뿐이다.
- **MySQL JSON 타입을 쓰지 않는 이유**: JSON 타입은 저장할 때 key 순서와 공백을 정규화한다. 꺼낸 값이 넣은 값과 글자가 달라져 INV-702를 지킬 수 없다.
- **계약 테스트**: JSON 테스트가 없어지는 게 아니라 확인 지점이 둘로 나뉜다.
  - ① Outbox `payload` == 계약 JSON
  - ② Kafka value == Outbox `payload`(바이트 단위), Kafka key == `message_key`

## relay

### D-2. 한 회차는 짧은 TX-1 → TX 밖에서 send·대기 → 짧은 TX-2다. 집을 때는 길게 잡아 두고, 실패하면 백오프로 바꾼다

- **흐름**:
  1. **TX-1**: 보낼 행을 집는다. `attempt_count + 1`, `next_attempt_at = 지금 + 임대 시간`.
  2. **TX 밖**: send하고 결과가 나올 때까지 기다린다.
  3. **TX-2**: 성공이면 `PUBLISHED`, `published_at`. 실패면 `last_error`, `next_attempt_at = 지금 + 백오프`.
- **send를 TX 밖에 두는 이유**: broker 응답을 기다리는 동안 DB 연결과 행 잠금을 붙잡지 않는다. TX-1이 commit된 뒤 죽어도 행은 PENDING으로 남아 다시 집힌다.
- **임대 시간**: producer의 최대 전송 시간(`delivery.timeout.ms`, 기본 2분)에 여유 1분을 더한다. 숫자를 코드에 박지 않고 producer 설정에서 계산한다.
- **바로잡은 것**: 처음에는 집을 때부터 `next_attempt_at = 지금 + 백오프(첫 회 1초)`로 했다. 그런데 이 칸은 "보내는 중이니 건드리지 마"(임대)와 "실패 뒤 언제 다시"(백오프)를 함께 뜻한다. 1초가 지나면 producer가 아직 내부 재시도 중인 행을 다른 회차가 집어 또 보낼 수 있었다. 그래서 같은 칸에 시점마다 다른 값을 넣는다.
- **relay는 중간에 포기하지 않는다**: 더 짧은 대기 상한을 두면 producer 안의 첫 전송이 살아 있는 채로 실패로 기록되고, 임대가 일찍 풀린다. producer는 정해진 시간 안에 반드시 성공이나 실패를 돌려준다.
- **서버가 send 도중 죽으면**: 임대가 남아 있어 재시작 뒤 최대 "임대 시간"만큼 기다린 다음 다시 집힌다. broker가 이미 받았다면 같은 eventId가 두 번 실린다(at-least-once).

### D-3. 실패는 원인과 상관없이 같은 백오프로 다시 시도하고, 24시간이 지나면 BLOCKED로 뺀다

- **백오프**: 1초부터 두 배씩 늘리고, 5분에서 멈춘다.
- **BLOCKED**: `created_at`부터 24시간이 지나도 실패하면 BLOCKED로 바꾼다. 행은 지우지 않고, 로그와 지표로 드러낸다.
- **재개**: 같은 행을 PENDING으로 되돌린다. eventId도 payload도 그대로다(INV-705). 로컬 전용 진입점으로만 연다.
- **BLOCKED는 DLQ가 아니다**: DLQ는 consumer가 처리하지 못한 메시지를 따로 모아 두는 topic이다. BLOCKED는 producer 쪽에서 아직 Kafka에 넣지 못한 행이다. "해결되지 않음" 상태로 남는다.
- **원인을 나누지 않은 이유**: 메시지 크기 초과나 권한 오류처럼 다시 해도 안 될 실패도 있다. 하지만 예외 종류로 나누면 잠깐 지나갈 실패를 영구로 잘못 분류할 위험이 있다. 타임아웃처럼 broker가 받았는지조차 모르는 실패도 있다. 24시간은 대부분의 broker 장애보다 길고, 다음 근무일 안에 드러난다는 기준으로 정했다. 지표로 정하는 법은 멘토님께 여쭌다.

### D-4. relay는 한 대만 켜고, 실수로 겹쳐도 조건부 UPDATE로 막는다

- **결정**:
  1. `@Scheduled(fixedDelay)`로 돈다. 수동 진입점과 함께 쓰는 "이미 돌고 있으면 건너뜀" 장치를 둔다.
  2. `outbox.relay.enabled`는 기본 off이고, 한 인스턴스에서만 켠다.
  3. 집기는 `UPDATE … WHERE id = ? AND status = 'PENDING' AND next_attempt_at <= 지금`으로 한다. 0줄이면 다른 회차가 집은 것이니 건너뛴다.
- **근거**: 서버가 다르면 메모리를 공유하지 않는다. 겹침을 막는 판정은 DB 한 곳에서 해야 한다. 조건부 UPDATE는 집기와 임대를 한 문장으로 끝낸다.
- **비교한 대안**:
  - `@Version` 낙관적 락: 같은 효과지만 엔티티 저장 흐름에 묶인다.
  - `SELECT … FOR UPDATE SKIP LOCKED` + 임대: 여러 relay가 행을 나눠 가질 때 쓴다. 이번 범위 밖이다.
  - `claimed_by`·`claimed_until` 컬럼: 누가 집었는지 보이지만 이번에는 필요 없다.
  - ShedLock: relay 전체를 한 대만 돌게 잠근다.
  - CDC(Debezium): 범위 밖이다.
  - 여러 relay와 같은 key의 순서 문제는 PR "남은 한계"에 적는다.

## 복구와 6주차 결정 정리

### D-5. 6주차에 빠진 이벤트는 저장된 사실로, 확정 TX와 같은 코드로 Outbox 행을 만든다

- **대상**: 직접 지정한 CONFIRMED 주문만 복구한다. 과제의 주문 A(한 번도 안 나감)와 B(이미 소비됨)이고, 지난 주문 전체를 자동으로 소급하지 않는다.
- **입력**: `orders.confirmed_at`, 저장된 주문 품목, orderId로 계산한 eventId만 쓴다. 현재 상품 정보와 `now()`는 쓰지 않는다.
- **코드**: Outbox 행을 만드는 지점은 하나다. 확정 TX와 복구가 같은 팩토리를 부른다(INV-706).
- **이미 행이 있으면**: 새로 만들지 않고 payload가 같은지 비교한다. 다르면 오류로 드러낸다.
- **사실이 모자라면**: 추측해서 채우지 않고 오류로 드러낸다. `confirmed_at`이 없는 경우가 그렇다.
- **동시에 두 번 돌면**: `event_id` unique가 두 번째를 막는다.
- **진입점**: 로컬 전용이다. 테스트 도구나 로컬 프로필 실행기로 두고, 공개 관리자 API로 열지 않는다. PG는 부르지 않는다.
- **같은 코드여야 하는 이유**: 다른 payload가 같은 eventId로 나가면 consumer는 이를 중복이 아니라 conflict로 기록한다(W6 D-18). 주문 B는 같은 payload가 다시 가서 아무 일도 없이 끝나야 한다.

### D-6. Outbox 저장이 실패하면 확정도 rollback한다 (W6 D-26을 뒤집음)

| | 6주차 (W6 D-26) | 7주차 |
|---|---|---|
| 발행 준비 실패 시 | 확정을 유지하고, 미발행으로 기록 | 확정도 rollback |
| 근거 | PG 결제가 끝났으니 확정을 지킨다. 이벤트 누락은 받아들인다 | 확정과 보낼 사실이 함께 남거나 함께 사라져야 누락이 없다 |
| 그 뒤 주문 | — | DRAFT로 남는다. 5주차 "같은 거래 조회 → 결과 적용" 경로가 다시 확정한다 |

- **근거**: 6주차 판단은 "이벤트는 빠질 수 있다"는 전제 위에 있었다. Outbox가 그 전제를 없앴다. "PG 성공을 잃지 않는다"는 원칙은 그대로이고, 지키는 방법이 "확정 유지"에서 "rollback 후 다시 적용"으로 바뀐다.

### D-7. 보존 기간은 Kafka ≤ Outbox PUBLISHED < 처리 기록 순서다. 삭제 작업은 만들지 않는다

- **PUBLISHED는 소비됐다는 뜻이 아니다**: consumer가 Kafka 보존 기간(기본 7일)보다 오래 멈추면, 아무도 읽지 않은 record가 지워진다. 그때 다시 보낼 원본은 Outbox 행뿐이다. 그래서 **Outbox 보존 ≥ Kafka 보존 + 장애를 알아채는 여유**다.
- **처리 기록이 먼저 사라지면**: 같은 eventId가 다시 도착할 수 있는 기간은 Outbox 행이 남아 있는 동안과, 그 재발행 record가 Kafka에 남아 있는 동안이다. 재발행은 새 record라 Kafka 보존이 그때부터 다시 시작된다. 그래서 **처리 기록 보존 ≥ Outbox 보존 + Kafka 보존**이다.
- **이번 과제에서 할 것**: 안 나간 행은 지우지 않는다(과제 조건). PUBLISHED 행 삭제 작업도 만들지 않는다. 처리 기록은 영구 보존을 유지한다.
- **남은 질문**: 판매량처럼 자연키가 없는 집계는 처리 기록이 유일한 중복 방어선이다. 이번 구매 활동은 `(order_id, product_id)` unique가 한 번 더 막는다.

### D-8. conflict·계약 깨짐 기록은 Kafka 주소 unique를 유지한다

- **부딪힌 점**: W6 D-22는 같은 record의 재전달을 막으려고 주소(topic, partition, offset)에 unique를 걸었다. Outbox 재발행은 같은 eventId를 새 offset으로 싣는다. 그래서 원래 conflict인 이벤트가 재발행되면 기록이 여러 줄로 남고, `purchase.activity.rejected`도 그만큼 오른다.
- **결정**: 그대로 둔다. 이 기록과 지표는 "충돌 사건 수"가 아니라 "충돌 record 도착 수"로 읽는다. 사건 수는 `event_id`로 묶어 본다.
- **근거**:
  - producer 버그와 relay의 애매한 실패가 같은 이벤트에 겹쳐야 생긴다.
  - 정확히 하려면 `(event_id, new_fact 해시)` unique와 해시 컬럼이 필요하다. W6 D-22와 그 테스트를 다시 고쳐야 한다.
- **PR "남은 한계"**: 사건 수가 정확해야 하면 위 키로 바꾸는 길이 있다.

### D-9. 6주차 메모리 큐와 미발행 기록을 지우고, Kafka 전송 코드는 "key와 JSON을 받아 결과를 돌려주는" 모양으로 바꾼다

| 6주차 부품 | 7주차 |
|---|---|
| `OrderConfirmedPublication` (afterCommit, 메모리 큐, 발행 스레드) | 삭제. Outbox 테이블이 줄이고, relay가 꺼내 보낸다 |
| `OrderConfirmedUnpublished` (미발행 Counter) | 삭제. 안 나간 것은 PENDING·BLOCKED 행으로 남는다 |
| `OrderConfirmedPublisher` / `KafkaOrderConfirmedPublisher` | 남긴다. `publish(payload 객체) → void`를 `send(key, 저장된 JSON) → 성공/실패`로 바꾼다 |
| `OrderConfirmedJson` | 남긴다. 확정 TX에서 Outbox payload를 만들 때 쓴다 |

- **근거**: 메모리 줄은 서버가 죽으면 같이 사라진다. 6주차 누락이 생긴 자리가 바로 여기였다. DB 줄은 남는다. relay는 결과를 보고 PUBLISHED로 바꿀지, `last_error`를 남길지 정해야 하므로 결과를 돌려받아야 한다.

## 6주차 누락 재현

### D-10. 6주차 버전(ad7f2c8) 서버를 Kafka 주소만 바꿔 두 번 띄워 주문 A·B를 만든다. consumer도 실행 jar로 따로 띄운다

- **필요한 상태**: 주문 A는 CONFIRMED·예약 소비인데 Kafka 기록과 구매 활동이 없다. 주문 B는 발행·소비까지 끝나 구매 활동이 있다. 둘 다 Outbox 행은 없다.
- **7주차 코드로 못 만드는 이유**: 7주차 확정은 Outbox 행을 항상 함께 만든다. 과제는 DB 직접 삽입, Outbox 행 삭제, 제품 코드의 우회 스위치를 막는다.
- **결정**:
  1. ad7f2c8로 빌드한 api 실행 jar를 테스트가 띄운다. 코드는 고치지 않고, 실행 설정만 바꾼다.
  2. **A**: Kafka 주소(`BOOTSTRAP_SERVERS`)를 아무도 없는 주소로 주고 띄워 확정한다. send가 `max.block`(5초) 뒤 실패하고 미발행으로만 기록된다. 서버를 끈다.
  3. **B**: 올바른 Kafka 주소로 다시 띄워 확정한다. 실제로 발행된다. 서버를 끈다.
  4. consumer는 현재 streamer 코드로 만든 실행 jar를 별도 프로세스로 띄운다. B를 소비해 구매 활동을 만든다. streamer 코드는 바꾸지 않는다 — 7주차에 바뀌는 건 보내는 쪽뿐이다. 구매 활동은 테스트가 DB를 직접 조회해 확인한다.
  5. 그 뒤 같은 DB·Kafka에 7주차 코드를 연결해 복구 → relay → 확인한다(D-5).
- **6주차 테스트의 `SwitchablePublisher`를 쓰지 않는 이유**: 테스트 코드라 실행 jar에 들어가지 않는다. 쓰려면 6주차 소스에 준비용 테스트를 새로 더해 돌려야 하는데, 그러면 "6주차 그대로"가 아니게 된다.
- **과제 문장과 다른 점**: 과제는 A를 "send 직전에 멈춤"으로 만든다. 이 방식은 "send 실패"다. 복구가 보는 DB·Kafka 상태(CONFIRMED·예약 소비·기록 없음·활동 0)는 같다. PR에 이 차이를 적는다.
- **운영 의존성은 만들지 않는다**: `implementation`으로 streamer를 넣으면 api 서버 안에 consumer가 같이 떠서, 따로 배포하려고 나눈 두 앱이 한 덩어리가 된다.
- **바로잡은 것 — 테스트 의존성도 쓰지 않는다**: 처음에는 `testImplementation(project(":apps:commerce-streamer"))`로 consumer를 불러오려 했다. 그런데 두 앱 모두 `com.loopers` 아래를 통째로 훑고, 공용 `JpaConfig`도 `@EntityScan("com.loopers")`다(`modules/*`라 수정 불가). 그래서 streamer가 api 테스트 클래스패스에 들어오면 api의 모든 테스트가 streamer의 listener·엔티티·저장소까지 띄운다. `apps/*`는 일반 jar 빌드도 꺼져 있다. 실행 jar로 따로 띄우면 클래스패스를 건드리지 않고, 운영처럼 두 서버가 따로 뜬다. D-13의 jar 실행 장치를 함께 쓴다.
- **DB를 지우지 않게 — 첫 서버만 `create`**: 테스트 환경은 `ddl-auto: create`라서 서버가 켜질 때 자기 테이블을 지우고 새로 만든다. 그대로 두면 6주차 2회차가 A를, 7주차 연결이 A·B를 지운다. 첫 6주차 서버만 `create`로 띄우고, 이후의 6주차 서버·streamer·7주차 연결은 실행 설정으로 `update`를 준다. `update`는 데이터를 두고 없는 테이블(Outbox)만 더한다. 과제의 "복구가 끝날 때까지 DB를 초기화하지 않는다"와 같은 이유다.

## relay 테스트 이음매

### D-11. relay의 결과 기록(TX-2)은 별도 빈으로 떼고, 시간 조건은 주입한 `Clock`으로 판단한다

- **결과 기록 빈**:
  - relay에서 "PUBLISHED 저장"과 "실패 기록"을 맡는 빈을 따로 둔다. PUBLISHED 저장 호출에는 broker가 돌려준 partition·offset을 함께 넘긴다.
  - 이유 1: Spring `@Transactional`은 같은 클래스 안에서 자기 메서드를 부르면 걸리지 않는다. TX-1·TX-2를 relay 안에 두면 트랜잭션이 생기지 않는다.
  - 이유 2: "broker 확인 뒤 PUBLISHED 저장 전 중단" 테스트의 이음매가 된다. 테스트는 이 빈을 감싼 래퍼를 끼워 PUBLISHED 저장 호출에서 멈춘다. 이 호출은 broker 성공 뒤에만 오므로 broker 확인까지는 진짜로 끝난 상태이고, 첫 발행의 offset도 남는다. 6주차 `SwitchablePublisher`(W6 D-16)와 같은 방식이다.
- **`Clock`**:
  - relay는 "지금"을 주입받은 `java.time.Clock`에서 얻는다. 행을 고르는 조회 조건(`next_attempt_at <= 지금`)과 임대·백오프·24시간 계산이 모두 같은 Clock을 쓴다. DB `NOW()`는 쓰지 않는다.
  - 운영은 `Clock.systemUTC()`, 테스트는 시각을 앞으로 넘길 수 있는 시계를 넣는다.
- **비교한 대안**:
  - 임대 시간만 설정값으로 줄이기: 임대가 producer 최대 전송 시간보다 짧아져, 테스트에서만 INV-704가 깨진 조건을 검증하게 된다. Kafka 대기 시간까지 함께 줄여도 실제로 몇 초씩 기다려야 하고, 테스트용 값이 따로 생긴다.
  - 수동 재개 진입점이 시간을 무시하고 바로 집기: 보내는 중인 행도 집을 수 있는 문이 제품 코드에 생기고(INV-704), "새 relay가 저절로 그 행을 다시 찾는다"는 장면을 건너뛴다.

## 계층 배치

### D-12. Outbox 엔티티는 domain에, JPA 저장과 JSON 직렬화는 infrastructure에 두고 포트로 잇는다

- **결정**:
  - `domain/outbox`: Outbox 엔티티와 저장소 포트. 엔티티가 상태 전이 규칙을 지킨다 — payload 불변(INV-702), broker 확인 뒤에만 PUBLISHED(INV-703), 재개는 같은 행을 PENDING으로(INV-705).
  - `infrastructure/outbox`: 저장소 포트의 JPA 구현.
  - `application/outbox`: relay와 결과 기록 빈(D-11). 집기 → 보내기 → 결과 기록의 순서를 조립한다.
  - JSON 직렬화: application에 인터페이스를 두고, Jackson을 쓰는 구현(6주차 `OrderConfirmedJson`)은 infrastructure에 남긴다. 확정 TX와 복구가 같은 포트를 지나 같은 문자열을 만든다(D-5, INV-706).
- **근거**:
  - "기술 장치니까 infrastructure"로 두면 확정 TX(`PaymentResultApplier`, application)가 부를 수 없다 — application → infrastructure 의존은 ArchUnit이 막는다.
  - Outbox 행에도 지켜야 할 불변식이 있다. 불변식은 domain 모델이 캡슐화한다(CLAUDE.md). 6주차 streamer도 중복 방지 기록(`ProcessedOrderConfirmation`)을 `domain/purchase`에 뒀다.
  - JSON 직렬화는 Jackson을 쓰는 기술 코드다. 6주차 `OrderConfirmedPublisher`(application) ← `KafkaOrderConfirmedPublisher`(infrastructure)와 같은 포트·어댑터 모양으로 둔다.

### D-13. 6주차 jar는 Gradle 작업이 ad7f2c8을 worktree로 꺼내 빌드하고, 커밋을 확인해 다시 쓴다

- **결정**: 복구 테스트 앞에 "6주차 jar 준비" 작업을 둔다.
  - jar가 있고 함께 적어 둔 커밋이 ad7f2c8이면 그대로 쓴다.
  - 없거나 커밋이 다르면 `git worktree`로 ad7f2c8을 별도 폴더에 꺼내 `bootJar`로 빌드하고, jar 옆에 커밋을 적는다. 끝나면 worktree를 지운다.
- **비교한 대안**:
  - 테스트마다 새로 빌드: 확실하지만 매번 1~2분이 든다.
  - 손으로 미리 빌드해 둔 jar: 다른 컴퓨터(멘토님, CI)에는 없어서 실패하고, 다른 커밋으로 만든 jar가 같은 자리에 있어도 테스트가 알아채지 못한다.
  - jar를 git에 올리기: 수십 MB 바이너리가 저장소에 쌓인다.
- **근거**: 처음 한 번만 느리고, 어느 컴퓨터에서든 스스로 준비하며, 커밋 확인으로 엉뚱한 jar를 막는다.

### D-14. 복구 판단은 가벼운 테스트로 check에 넣고, 6주차 재현 E2E는 태그를 붙여 check에서 뺀다

- **나눈 기준**: 복구 테스트는 두 가지를 확인한다. ① 6주차 서버로 "확정됐는데 안 나간 주문"을 만들 수 있다. ② 복구 코드가 빠진 건 채우고 이미 있는 건 늘리지 않는다. ①은 6주차 코드가 더는 바뀌지 않으니 한 번 보이면 된다. ②는 7주차 제품 코드라 고칠 때마다 확인해야 한다.
- **결정**:
  - 복구 판단 테스트(check에 넣음): 주문 사실과 기존 행을 넣고 결과만 본다. 행 없음 → 새 행 / 같은 행 있음 → 아무것도 안 함 / 다른 행 있음 → 오류 / 사실 모자람 → 오류.
  - 6주차 재현 + 복구 E2E(check에서 뺌): 6주차 C-3(W6 D-21)처럼 태그를 붙여 기본 `test`에서 빼고 별도 작업으로 돌린다.
- **과제 조건과의 관계**: "skip을 통과로 세지 않는다"에 맞게, 따로 돌린 결과(실행 명령, 통과 테스트 수, PG 호출 수 C·T)를 PR에 남긴다.

## 구현 범위

### D-15. 배달 E2E는 check에 넣고, relay는 id 순서로 최대 10건씩 차례로 보내며, BLOCKED 재개는 빈 메서드로만 둔다

- **배달 E2E(`OrderConfirmedOutboxDeliveryE2ETest`)는 check에 넣는다**: "주문 → Outbox → broker → 구매 활동" 전체 흐름은 고칠 때마다 지켜야 한다. 6주차 재현(D-14)과 달리 과거 버전이 필요 없다. 대가로 api 테스트 전에 streamer 실행 jar 빌드가 돈다(D-10).
- **relay 한 회차**: 보낼 차례가 된 PENDING을 `id` 순서로 최대 10건 고르고, 한 건씩 집기 → 보내기 → 결과 기록을 차례로 한다. future를 한꺼번에 만들어 병렬로 보내지 않는다. 과제의 "작은 묶음, 안정된 순서, 병렬로 늘리지 않음"에 맞춘다. 조회 인덱스는 `(status, next_attempt_at, id)`.
- **BLOCKED 재개**: HTTP API 없이 `OutboxResume` 빈 메서드(대상 eventId 지정)로만 둔다. 테스트와 로컬에서만 부른다. 공개 관리자 API로 열지 않는다(과제 조건).

### D-16. Outbox 엔티티는 `BaseEntity`를 상속하고 그 `created_at`을 그대로 쓴다. 테스트 시계는 "지금"에서 시작해 앞으로만 넘긴다

- **부딪힌 점**: 공용 `BaseEntity`(`modules/jpa`, 수정 불가)가 이미 `created_at`을 매핑하고, 저장 순간 `@PrePersist`가 JVM 시각으로 채운다. 그래서 `Clock`에서 온 시각을 넣어도 덮어써지고, 같은 이름의 컬럼을 하나 더 둘 수도 없다. D-3의 "만든 지 24시간 → BLOCKED"가 이 컬럼을 쓴다.
- **결정**: 상속하고 `BaseEntity`의 `created_at`을 쓴다. 팩토리가 받는 생성 시각은 `next_attempt_at`의 첫 값에만 쓴다. relay 테스트의 시계는 실제 현재 시각에서 시작해 앞으로만 넘긴다. 과거나 고정 시각에서 시작하는 시계는 쓰지 않는다.
- **근거**: 운영의 `Clock`도 `Clock.systemUTC()`라 운영 동작은 같다. 차이는 테스트에서 시간을 다루는 자유도뿐이고, "지금부터 앞으로"만으로 24시간 판정을 검증할 수 있다. 모든 엔티티가 `BaseEntity`를 상속하는 관례도 지킨다.
- **비교한 대안**:
  - 상속하지 않고 `id`·`created_at`을 직접 둔다: 시각을 완전히 `Clock`으로 통제하지만 관례에서 벗어난다.
  - `@AttributeOverride`로 `BaseEntity` 쪽 컬럼을 옮겨 생성 시각 컬럼을 둘로 둔다.
  - 우리 컬럼 이름을 바꾼다(`event_created_at`).
  - 뒤의 두 안은 헷갈리는 컬럼이 생긴다.
- **함께 받아들인 것**: `BaseEntity`의 `delete()`(소프트 삭제)가 Outbox에도 열려 있다. 지우지 않는다는 규칙(INV-705)은 삭제 경로를 만들지 않는 것으로 지킨다.
