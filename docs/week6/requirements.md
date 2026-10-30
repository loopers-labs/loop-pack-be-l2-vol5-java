# 요구사항 명세서 (W6) · 확정 주문을 이벤트로 전달하고 구매 활동을 한 번만 남긴다

원문 과제(`ref/week6/assignment.md`, 학습 자료 `ref/week6/study.md`)를 구현 · 테스트의 기준이 될 수 있는 수준으로 정제한 문서다.
2주차 명세(`docs/week2/requirements.md`)와 5주차 명세(`docs/week5/requirements.md`, 아래 **W5**)의 규칙 ID 와 출처 구분을 그대로 쓰고, 이번 주에 생긴 것만 적는다.

| | |
|---|---|
| **원문** | Vol5 W6 — PG 로 확정된 주문을 이벤트로 전달하고, 재전달돼도 상품 구매 활동을 한 번만 남긴다 |
| **이 문서가 하는 일** | ① `OrderConfirmed` 계약과 발행 조건을 못 박는다 ② 구매 활동 소비의 멱등 · 충돌 규칙을 적는다 ③ listener 설정과 실험이 증명할 것을 적는다 ④ 아직 답이 없는 것을 질문으로 남긴다 |
| **이 문서가 하지 않는 일** | 발행 위치 · 트랜잭션 경계 · 테이블 구조 · 설정값의 근거. 설계 기록(`docs/week6/design.md`)의 몫이다 |

## 출처 구분

W2 의 표기(`원문` · `정함` · `선택` · `보안` · `범위` · `질문`)를 그대로 쓴다.
**이벤트 계약은 이 실습이 정의하는 로컬 약속이다.** 실제 서비스의 이벤트 규격이라고 주장하지 않는다.

## 규칙 ID

W5 의 갈래에 넷을 더한다. 번호를 다시 쓰지 않는 규칙도 같다.

| 접두사 | 다루는 것 |
|---|---|
| `EVT` | `OrderConfirmed` 계약과 commerce-api 의 발행 |
| `ACT` | commerce-streamer 의 구매 활동 소비 — 멱등 · 충돌 · 원자성 |
| `KAFKA` | producer · consumer · listener 설정과 ack |
| `EXP` | 실제 broker 로 확인하는 실험 — 배정 · 재전달 · 발행 누락 |

---

## 바뀌는 기존 계약

| 기존 규칙 | 내용 | 이번 주 |
|---|---|---|
| `SETTLE-001` | 결과 반영은 한 경계다 | 그 경계에서 **최초 `CONFIRMED` 전이가 commit 되면** `OrderConfirmed` 를 발행한다(`EVT-010`). 반영 결과 · 응답 코드(`SETTLE-009`)는 바뀌지 않는다 |
| `PAY-013` | `PENDING` → `CONFIRMED` | 확정 시각을 **마이크로초로 잘라** 저장한다(`EVT-006`). 이벤트의 `confirmedAt` 이 DB 사실과 같아야 하기 때문이다 |
| `modules:kafka` 의 `kafka.yml` | 두 앱이 공유하는 시작 설정 | consumer 키 오류를 고치고 producer 의 멱등 · `acks` · `max.block.ms` 를 명시한다(`KAFKA-002` · `KAFKA-003` · `EVT-014` · `EVT-016`). 효과 없는 클라이언트 키 `auto.create.topics.enable` 을 지운다(`EVT-001`). producer 의 value 직렬화기를 `JsonSerializer` 에서 `ByteArraySerializer` 로 바꾼다(`EVT-003`) — 지금 공용 `KafkaTemplate` 을 쓰는 코드는 없다. 두 앱 모두에 적용된다 |
| `infra-compose.yml` 의 broker | 자동 생성 기본값(켜짐) | broker 의 topic 자동 생성을 끄고 `order-events` 와 demo 용 `demo.internal.topic-v1` 을 init 에서 만든다(`EVT-001`). 켜 두면 첫 발행이 partition 1 개짜리 topic 을 조용히 만든다 |

W5 범위 밖의 "inbox 테이블" 은 PG callback 을 **받는 쪽**(commerce-api)의 선택지다. 이번 주의 처리 기록(`ACT-002`)은 Kafka 를 **소비하는 쪽**(commerce-streamer)에 두며, 원문이 요구한다.

유지하는 것: PG 계약 · 결제 시도 · 예약(`PG-*` · `PAY-*` · `RSV-*`) · 이전 POINT 확정 주문의 조회 사실(`PAY-011`) · 소유권 · 삭제 상품 비노출.

---

## EVT — 이벤트 계약과 발행

| ID | 규칙 | 출처 | 깨는 입력 | 기대 |
|---|---|---|---|---|
| `EVT-001` | topic 은 `order-events`. 테스트는 고유 suffix 를 붙여 격리한다. topic 은 자동 생성에 기대지 않고 명시적으로 만든다 — 로컬은 compose init, 테스트는 fixture 가 만든다. broker 의 자동 생성을 끄고(`auto.create.topics.enable` 은 **broker 설정**이라 클라이언트에 넣어도 효과가 없다), consumer 는 `allow.auto.create.topics=false` 를 둔다 | 원문 + 정함(명시 생성 · 누가 만드나) | topic 없이 확정 | topic 이 생기지 않고 발행 실패 1(`EVT-014`) |
| `EVT-002` | record key 는 내부 `orderId` 의 10진 문자열이다. payload 의 `orderId` 와 같아야 한다 | 원문 | — | 주문 42 → key `"42"` |
| `EVT-003` | payload 는 JSON `{eventType:"OrderConfirmed", schemaVersion:1, eventId, orderId, confirmedAt, items:[{productId, quantity}]}`. `confirmedAt` 은 UTC ISO-8601 · 소수 6 자리 고정. 직렬화는 앱의 `ObjectMapper` 로 만든 바이트를 보낸다 — 공용 `JsonSerializer` 는 자기 `ObjectMapper` 를 써서 시각 형식을 보장하지 않는다 | 원문 + 정함(시각 표기 · 직렬화기) | 끝자리가 0 인 확정 시각 | `…T01:02:03.120000Z` 그대로 |
| `EVT-004` | `eventId` 는 `order-confirmed:v1:{orderId}` 로 **유도한다.** 전송 시도 횟수 · 현재 시각 · 새 UUID 를 넣지 않는다. `v1` 은 **식별자를 만드는 규칙의 버전**이고 `schemaVersion` 과 따로 움직인다 | 원문(예시) + 정함(`v1` 의 뜻) | `schemaVersion` 을 2 로 올림 | `eventId` 그대로. 바뀌면 소비자가 모든 과거 주문을 충돌로 본다(`ACT-005`) |
| `EVT-005` | `eventId` 는 서버가 만든다. 요청 · 헤더에서 받지 않는다. 고객이 `OrderConfirmed` 를 발행하는 공개 endpoint 를 두지 않는다 | 원문 + 보안 | 남의 주문과 같은 `eventId` 를 꾸민 요청 | 받을 경로가 없다 |
| `EVT-006` | `confirmedAt` 은 저장된 `Order.confirmedAt` 이다. 최초 PG 성공 전이에서 한 번 정해지고 중복 반영이 바꾸지 못한다. 저장 전에 **마이크로초로 자른다** — MySQL 은 넘치는 소수 자리를 반올림하므로 자르지 않으면 메모리 값과 DB 값이 갈린다 | 원문 + 정함(정밀도) | 나노초가 있는 `now` 로 확정 | payload 의 `confirmedAt` == DB 를 다시 읽은 값 |
| `EVT-007` | `items` 는 확정 주문의 **저장 품목**에서 만든다. 같은 상품은 합산해 하나로, 수량은 양수. 배열 순서는 의미가 없다. 소비 시점의 상품 조회로 만들지 않는다 | 원문 | — | — |
| `EVT-008` | 카드 정보 · PG 비밀 · 사용자 식별자를 넣지 않는다. 구매 활동에 필요한 것만 담는다 | 원문 + 보안 + 정함(`userId` 제외) | — | payload 에 `userId` · `cardNo` 없음 |
| `EVT-010` | **발행 계기는 결과 반영 경계(`PaymentTransactions.apply`)의 `CONFIRM_ORDER` 효과 하나다.** 생성 응답 · callback · reconcile · sweeper 네 경로가 모두 이 경계를 지나므로 어느 경로가 먼저 와도 조건이 같다 | 원문 + 정함(지점) | — | 설계 |
| `EVT-011` | 발행하지 않는 경우: `DRAFT` · `PENDING` · `UNKNOWN` · `FAILED` · `CONFLICT`, 확정 TX rollback, 종결 뒤 반복 반영(효과 `NONE`), 이전 POINT 확정 주문(소급 발행 없음) | 원문 | 같은 `SUCCESS` callback 2 회 · callback 과 reconcile 동시 | 이벤트 1 건 |
| `EVT-012` | 발행은 확정 TX 가 **commit 된 뒤**에 한다. 같은 `@Transactional` 메서드 끝에서 보내지 않는다. 이벤트 값은 확정과 같은 자리(`Order.completePayment`)에서 메모리의 값으로 만들어 두고 commit 뒤 엔티티 지연 로딩에 기대지 않는다. **값 만들기는 확정과 한 몸이다** — 메모리의 값만 쓰므로 여기서 나는 예외는 프로그래밍 오류이고 확정과 함께 rollback 된다. **직렬화 · 전송 실패는 commit 뒤 publisher 가 잡아 발행 실패로 기록하고 확정은 그대로 둔다** — PG 는 이미 성공했으므로 확정을 되돌리면 "결제됐는데 미확정" 이 된다(`EVT-013`) | 원문 + 정함(실패 지점) | 확정 TX 가 commit 직전에 실패 · 직렬화 중 예외 | broker record 0 · 앞의 것은 주문 rollback, 뒤의 것은 주문 `CONFIRMED` · 실패 지표 1 |
| `EVT-013` | 발행이 실패해도 **확정 주문을 되돌리지 않고**, PG 생성을 다시 부르지 않고, confirm · callback · reconcile 의 응답을 바꾸지 않는다. 주문 상태는 저장된 결과로 답한다 | 원문 + 정함(응답 불변) | broker 없음 · topic 없음 | 주문 `CONFIRMED`, callback 200 |
| `EVT-014` | `send()` 는 topic metadata 가 없거나 버퍼가 가득 차면 **호출 스레드를 막고**(`max.block.ms`), 직렬화 오류 등은 동기로 던진다. 발행은 확정 요청 · callback 스레드에서, 커넥션 반납 전에 일어나므로 **`max.block.ms` 를 1 초로 둔다**(기본 60 초) — PG 결과 조회 타임아웃(1 초)과 같고 Hikari 커넥션 대기(3 초)보다 확실히 짧아, 발행 장애가 커넥션 풀 고갈로 번지지 않는다. 기동 직후 첫 발행이 metadata 를 받느라 막히지 않도록 **기동 시 `order-events` 의 metadata 를 미리 받는다** — 시간 초과는 기동을 막지 않고 로그만 남기며(첫 발행 때 다시 받는다), 설정 오류는 기동을 막는다(`EVT-016`). 동기 예외도 잡아 실패로 기록한다(`EVT-015`) — 잡지 않으면 after-commit listener 에서는 Spring 이 ERROR 로그 한 줄만 남기고 삼켜 실패 지표에 잡히지 않는다 | 정함 | metadata 가 없는 producer 로 broker 를 멈춘 채 확정 | 응답 지연 1 초 안, 200, 주문 `CONFIRMED`, 실패 지표 1 |
| `EVT-015` | 전송 결과를 관찰한다. 성공은 `eventId` 와 topic · partition · offset 을, 실패는 `eventId` 와 원인을 남기고 지표로 센다. **애플리케이션 재시도는 하지 않는다** — 메모리 재시도는 프로세스와 함께 사라지고, 누락은 W7 의 대상이다 | 원문 + 정함(재시도 없음) | — | 실패 지표 1 |
| `EVT-016` | producer 는 `enable.idempotence=true` · `acks=all` 을 **명시한다.** 명시하지 않으면 충돌 설정이 들어올 때 멱등이 조용히 꺼진다. `retries` 는 지정하지 않고 상한은 `delivery.timeout.ms` 로 둔다. 설정은 `kafka.yml` 한 곳에 둔다 — `spring.config.import` 로 불러온 파일이 불러온 쪽보다 우선해, 앱 yml 에 같은 키를 두면 조용히 진다. 유효 설정은 yml 이 아니라 **실제 producer 의 config** 로 확인한다. producer 는 첫 send 에서 만들어지므로 잘못된 설정이 기동에서 드러나는 것은 기동 시 metadata 확보(`EVT-014`) 덕이다 | 원문(acks 설명) + 정함 | `acks: 1` 을 추가 | 기동 실패(`ConfigException`) |
| `EVT-017` | **정상 종료에서도 버퍼에 남은 record 는 누락이 될 수 있다.** 종료는 producer 의 close 대기 상한까지 버퍼를 비우고, 그 안에 못 보낸 record 는 실패 콜백으로 실패 지표에 남긴다. 강제 종료(SIGKILL)로 사라진 record 는 흔적이 없다 — W7 의 대상이다 | 정함 | 버퍼에 record 가 있는 채 종료 | 상한 안 전송 또는 실패 지표. 값은 설계 |

## ACT — 구매 활동 소비

| ID | 규칙 | 출처 | 깨는 입력 | 기대 |
|---|---|---|---|---|
| `ACT-001` | `commerce-streamer` 에 실제 consumer 를 연결한다. 출력만 하는 demo listener 는 이 기능이 아니다 | 원문 | — | — |
| `ACT-002` | 두 사실을 저장한다. **처리 기록**(`eventId` 유일 · `orderId` 유일 · 비교용 정규화 값)과 **구매 활동**(`orderId` · `productId` 쌍 유일 · `quantity` · `confirmedAt`). 테이블 이름은 설계 | 원문 + 정함(이름) | — | — |
| `ACT-003` | 처리 기록과 **모든 품목**의 구매 활동을 같은 DB TX 에서 commit 한다. 중간 품목 저장이 실패하면 처리 기록과 앞서 저장한 품목도 rollback 한다 | 원문 | 품목 2 개 중 둘째 저장 실패 | 처리 기록 0 · 활동 0 |
| `ACT-004` | 같은 `eventId` · 같은 사실의 재전달은 **효과 없이 정상 완료**하고 ack 한다 | 원문 | 같은 record 재전달 | 활동 행 수 · 수량 불변 |
| `ACT-005` | **충돌** — 같은 `eventId` 인데 다른 주문 · 수량 · 확정 시각, 또는 같은 `orderId` 인데 다른 `eventId` — 은 구매 활동을 더하지 않는다. 원본 payload 와 위치(topic · partition · offset)를 **충돌 보류 기록**에 남기고 지표 · 로그로 드러낸 뒤 ack 한다. 보류는 업무 완료가 아니다. 보류 기록은 **위치가 유일**하다 — ack 전에 죽어 같은 record 가 재전달돼도 한 줄이다 | 원문(드러냄 · 효과 없음) + 정함(기록 후 진행 Q-1 · 위치 유일) | 같은 `eventId` · 수량 2 → 3 · 그 record 재전달 | 활동 불변, 보류 1 줄, 뒤 record 는 계속 소비 |
| `ACT-006` | "같은 사실" 은 `orderId` · `confirmedAt` · 상품별로 정규화한 `items` 로 비교한다. JSON 속성 순서 · 배열 순서는 보지 않는다 | 원문 | items 순서만 바꾼 재발행 | 같은 사실 → no-op |
| `ACT-007` | 중복은 **DB 유일 제약**으로 막는다. 메모리 Set 이나 먼저 조회만 하는 판단으로 끝내지 않는다 | 원문 | 같은 이벤트 동시 소비 2 건 | 처리 기록 1 · 활동 한 벌 |
| `ACT-008` | 유일 제약에 막힌 record 는 TX 를 rollback 한 뒤 **새 TX 에서** 기존 처리 기록을 읽어 no-op(`ACT-004`) 인지 충돌(`ACT-005`) 인지 가른다. 이 경로는 **`ACT-002` 의 유일 제약 위반일 때만** 탄다 — 예외 종류가 아니라 **제약 이름**으로 가른다. 다른 무결성 위반 · 다른 오류를 중복으로 바꾸지 않는다. 새 TX 에서 다시 읽었는데 기존 기록이 없으면 중복이 아니므로 원래 오류로 실패한다(`ACT-010`) | 원문 + 정함(제약 이름으로 가름 · 기록 없음) | NOT NULL 위반 · 고아 활동 행과 부딪힌 유일 위반 | 중복이 아니라 실패(`ACT-010`) |
| `ACT-009` | 해석할 수 없는 record — JSON 오류 · key 와 `orderId` 불일치 · 빈 `eventId` · 빈 `items` · 0 이하 수량 · **`items` 안 같은 `productId` 중복** — 는 소비자를 고쳐도 결과가 같다. `ACT-005` 와 같이 보류 기록 후 ack 한다. `eventId` 의 형식은 검사하지 않는다 — 형식 검사가 "같은 `orderId` 에 다른 `eventId`" 를 먼저 가로채 `ACT-005` 의 충돌이 보이지 않게 된다. 상품 중복은 insert 전에 거른다 — 거르지 않으면 `(orderId, productId)` 유일 위반이 `ACT-008` 로 가는데 비교할 기록이 없다 | 정함 | key `"42"` · payload `orderId` 43 · 같은 상품 두 줄 | 활동 0, 보류 1 줄 |
| `ACT-010` | 그 밖의 오류는 ack 하지 않는다. 처리 기록 · 활동 전체를 rollback 하고, 그 record 가 있는 partition 의 소비를 그 자리에 세운 채 다시 시도한다(`KAFKA-006`). 다시 읽으면 성공할 수 있는 것 — DB 연결 · 잠금 대기, 그리고 **모르는 `schemaVersion`** (소비자를 고쳐 배포하면 그 record 부터 처리된다. 보류하면 배포 뒤에도 영영 반영되지 않는다) — 이 여기 든다. **DB 무응답도 오류가 되어야 한다** — streamer 의 JDBC 에 응답 상한을 두지 않으면 listener 가 예외 없이 멈춰 error handler 가 돌지 않고, `max.poll.interval.ms` 뒤 rebalance 만 난다 | 원문 + 정함(모르는 버전 · 무응답 상한 · partition 단위로 세움) | DB 오류 주입 · `schemaVersion` 2 | 활동 0, 그 partition 의 committed offset 그대로, 다른 partition 은 진행, 컨테이너 실행 중 |
| `ACT-011` | commerce-api 와 같은 MySQL 스키마를 쓰되 **주문 · 상품 테이블을 읽지 않고 외래키를 걸지 않는다.** 과거 주문의 `productId` 를 사실의 식별자로 보존하고, 현재 상품 행의 존재 · 가격에 기대지 않는다. 삭제 · 비노출 상품을 다시 노출하지 않는다 | 원문 + 정함(스키마, Q-2) | 삭제된 상품이 든 이벤트 | 활동 저장, 상품은 여전히 비노출 |
| `ACT-012` | 활동의 `confirmedAt` 은 이벤트의 값이다. 소비 시각을 쓰지 않는다 | 원문 | — | — |
| `ACT-013` | 주문 42 가 상품 7 을 2 개 · 상품 8 을 1 개 → 활동 2 행. 같은 이벤트 재수신 → 여전히 2 행. 주문 43 이 상품 7 을 3 개 → 총 3 행, 상품 7 의 수량 합 5 | 원문 | — | 테스트의 기준 숫자 |
| `ACT-014` | 처리 기록은 지우지 않는다. 늦은 중복(offset 되감기 · 재발행)이 언제 올지 모르기 때문이다 | 정함 | — | 뒤집을 조건은 설계 |
| `ACT-015` | 멱등이 보장되는 효과는 **처리 기록과 같은 DB TX 에 든 쓰기**뿐이다. 다른 저장소의 효과(W9 의 Redis 등)는 이 사실에서 다시 만들 수 있게 하거나 자기 멱등 키를 따로 가진다 | 정함 | — | — |
| `ACT-016` | `order-events` 에서 `OrderConfirmed` 가 아닌 record 는 이 소비자가 다루지 않는다. 활동 · 처리 기록 · 보류 기록을 남기지 않고 정상 완료한다. 종류는 payload 의 `eventType` 으로 가르고, 다른 종류의 `schemaVersion` 은 보지 않는다. JSON · 필드 타입이 깨졌거나 `eventType` 이 없으면 종류를 알 수 없으므로 `ACT-009` 의 해석 불가로 보류하고, `OrderConfirmed` 인데 `schemaVersion` 이 없어도 그렇다 | 정함 | `eventType` 이 `OrderCancelled` 인 payload | 활동 · 보류 0, 뒤 record 계속 소비 |

## KAFKA — 설정과 listener

| ID | 규칙 | 출처 | 기대 |
|---|---|---|---|
| `KAFKA-001` | producer · consumer · admin 이 **같은 broker** 를 쓴다. 테스트는 fixture 가 준 실제 bootstrap 주소를 셋 모두에 주입한다. 컨테이너 내부 주소(`kafka:9092`)와 호스트 주소(`localhost:19092`)를 섞지 않는다 | 원문 | 세 클라이언트의 `bootstrap.servers` 가 같다 |
| `KAFKA-002` | key 는 `String`, value 는 `byte[]` 로 받아 소비자가 직접 해석한다. 공유 설정의 `value-serializer` 오기를 `value-deserializer` 로 고친다 | 원문 | consumer 의 유효 deserializer 확인 |
| `KAFKA-003` | `enable.auto.commit=false` 를 Kafka 가 읽는 키로 둔다. 사용자 정의 키(`enable-auto-commit`)에 기대지 않는다. Spring 컨테이너의 consumer **와 테스트가 직접 만드는 consumer** 모두에서 `ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG` 의 실제 값을 확인한다 | 원문 | 두 곳 모두 `false` |
| `KAFKA-004` | 구매 활동용 **record listener factory 를 따로** 둔다(batch listener 아님 · concurrency 3, partition 수와 같음 · ack mode 는 기본 `BATCH`). 기존 batch factory 는 바꾸지 않는다. concurrency 를 partition 수에 맞추는 것은 한 partition 이 재시도로 기다리는 동안 다른 partition 이 같은 스레드에 묶이지 않게 하기 위해서다(`KAFKA-006`). ack mode 는 정확성이 아니라 commit 횟수와 재전달 양 사이의 선택이다 — 유실 없음은 예외 전파와 건너뛰지 않는 error handler 가, 효과 한 번은 처리 기록이 지킨다. handler 가 DB TX 하나라 재전달이 no-op 으로 싸므로 commit 을 poll 묶음에 맞춘다 | 원문(record listener) + 정함(ack mode · concurrency, 원문 예시는 `MANUAL_IMMEDIATE`) | — |
| `KAFKA-005` | offset 은 handler 의 DB TX 가 **commit 된 뒤**에만 commit 된다. listener 는 handler 가 정상 반환한 뒤에만 정상 반환하고, 다시 하면 성공할 수 있는 실패를 **잡아서 정상 반환하지 않는다** — 정상 반환이 곧 처리 완료다. handler 는 별도 Bean 이라 자기 호출로 트랜잭션을 우회하지 않는다 | 원문 | 실패를 잡고 반환하는 listener 에서 offset 이 실패 record 를 지난다 |
| `KAFKA-006` | 실패 record 를 건너뛰지 않는다. 보류 대상(`ACT-005` · `ACT-009`)이 아닌 실패는 모두 error handler 가 그 record 에서 다시 시도한다 — 간격은 1 초에서 2 배씩 늘어 최대 30 초이고 **횟수 제한이 없다.** 실패 record 를 "처리됨" 으로 commit 하지 않으며, 원인이 풀리면(DB 복구 · 소비자 배포) 사람이 재시작하지 않아도 그 record 부터 이어서 처리한다. 기다리는 것은 그 partition 뿐이고 다른 partition 은 진행한다. 재시도마다 지표 `purchase.activity.retry{cause}` 와 WARN 로그를 남긴다. **판정은 실제 group 의 committed offset 으로** 한다 | 원문 + 정함(무기한 재시도 · 간격 · partition 단위) | 실패 동안 그 partition 의 committed offset 이 실패 record 를 지나지 않고, 원인 제거 뒤 사람 없이 지난다 |
| `KAFKA-007` | 구매 활동 consumer 의 fetch 는 **Kafka 기본값**(`fetch.min.bytes=1` · `fetch.max.wait.ms=500`)이다. 기존 `1MiB` · `5000` 은 batch factory 안에서만 넣는 값이라(`KafkaConfig` 의 batch factory) 새 factory 는 물려받지 않는다. 테스트 전용 값을 따로 두지 않고, 긴 sleep 으로 덮지 않는다 | 원문 + 정함 | 컨테이너 consumer 의 유효 fetch 값이 기본값 |
| `KAFKA-008` | consumer group 은 `purchase-activity`. **`auto.offset.reset=earliest`** — 공유 설정은 `latest` 라, 처음 배정되기 전(첫 배포 · 새 group · committed offset 만료)에 쌓인 확정 사실을 조용히 건너뛴다. 처리 기록이 재처리를 막으므로 처음부터 읽어도 효과는 한 번이다(`ACT-004`). assignor · session timeout · static membership 은 시작 설정 그대로 둔다 | 원문(예시) + 정함 | consumer 를 띄우기 전에 확정한 주문이 반영된다 |

## EXP — 실험

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `EXP-001` | 실험은 Testcontainers 로 띄운 **실제 broker 하나**에서 한다. broker 버전은 compose 와 같은 계열(3.5)이다. endpoint 는 fixture 에서 얻고, 시작한 consumer · 컨테이너 · HTTP fixture 를 정리한다. 다른 실행의 topic · group · DB 를 지우지 않는다. **topic 은 테스트 사이에 남고 주문 ID 는 반복된다** — DB 정리가 `TRUNCATE` 라 AUTO_INCREMENT 가 되돌아가 매 테스트의 첫 주문이 `order-confirmed:v1:1` 이다. 그래서 테스트 정리 때 producer 버퍼를 비우고(앞 테스트의 늦은 record 가 다음 테스트에 섞이지 않게), broker 를 읽는 검증은 동작 **전의 end offset 부터** 읽고, 처음부터 읽는 consumer(earliest · 새 group)는 **실행마다 고유한 topic · group** 을 쓴다 | 원문 + 정함(Testcontainers · 격리 방식) | 앞 테스트가 남긴 주문 1 의 record | 다음 테스트의 검증이 그것을 읽지 않는다 |
| `EXP-002` | partition 2 개인 격리 topic 에서 같은 group 의 consumer 를 1 → 2 → 3 개로 바꾸며 **안정된 assignment** 를 유한 대기로 기다린다. poll 없이 sleep 하거나 consumer 객체 수만 세지 않는다. 실험 record 는 업무 topic 과 분리한다 | 원문 | 1 개: 둘 다 · 2 개: 하나씩 · 3 개: 적어도 하나는 배정 없음 |
| `EXP-003` | 두 partition 에 명시적으로 보낸 record 가 읽힌다. key 매핑은 producer 가 partition 을 고르게 하고 **같은 key 의 send metadata** 를 비교한다. 서로 다른 key 가 다른 partition 으로 간다고 assert 하지 않는다 | 원문 | 같은 key → 같은 partition |
| `EXP-004` | `acks` 선택과 **단일 broker 로 확인하지 못하는 복제 장애 보장**(leader 장애 · ISR 축소 · `min.insync.replicas`)을 설명한다 | 원문 | PR · 설계에 한계 명시 |
| `EXP-005` | **DB commit 뒤 offset 미커밋 재전달.** 실제 `OrderConfirmed` 한 건과 실제 handler 로, 같은 broker · topic · group · DB 에서 consumer 만 새로 만든다. 첫 consumer 는 target 의 다음 위치를 commit 하지 않고 bounded close, 둘째 consumer 는 **seek · offset reset 없이** 같은 group 으로 읽는다. offset 값은 send metadata 에서 얻는다. **target 앞에 준비용 record 를 하나 둔다** — committed offset 을 잃고 처음부터 다시 읽으면 준비용 record 도 다시 와서 실험이 실패하게 한다. 활동은 행 수뿐 아니라 행 ID · 수정 시각까지 비교한다 | 원문 + 정함(판정 강화) | 전달 2 회 · 같은 topic · partition · offset · 활동 불변(행 ID · 수정 시각 포함) · committed `n → n → n+1` |
| `EXP-006` | **실제 Spring listener 의 실패 경로.** 별도 격리 topic · group 에서 DB commit 전 오류를 주입한다. 테스트 더블 없이 실제 DB 오류로 만든다 — 둘째 품목만 거절하는 `CHECK` 제약을 잠시 걸어 `ACT-003` 의 전체 rollback 까지 함께 본다. 실패 동안의 committed offset · 처리 기록 · 활동과 컨테이너 상태를 읽고, 제약을 걷어 낸 뒤 **재시작 없이** 반영되는지 본다 | 원문 + 정함(더블 없음 · 수단 · 판정) | 실패 동안 활동 · 처리 기록 0 · committed offset 정지 · 컨테이너 실행 중 → 제약을 걷으면 재시작 없이 저장 · ack |
| `EXP-007` | **발행 누락 재현.** 기존 주문 API · PG fixture · 실제 결과 반영 경로로 두 주문을 확정한다. 첫째는 commit 뒤 broker 에 record 가 닿지 않게 하고 — topic 의 `max.message.bytes=1` 로 broker 가 실제로 거절한다(Q-6) — 값을 되돌린 뒤 둘째는 broker · consumer 까지 처리한다. 두 앱을 프로세스로 띄우는 **재현 스크립트**로 한 실행에서 하고(Q-4), broker 상태는 우리 코드가 아닌 `kcat` 으로 관찰한다. 고정 clock · 품목 · 수량과 실행 방법을 재현 코드에 남긴다. 누락은 두 종류다 — **흔적이 남는 누락**(send 실패 → 실패 지표)과 **흔적이 없는 누락**(send 전 프로세스 종료). 지표로는 앞의 것만 찾는다 | 원문 + 정함(누락의 두 종류) | 첫째: 주문 `CONFIRMED` · 예약 소비 · broker record 0 · 활동 0. 둘째: 전부 있음 |
| `EXP-008` | `EXP-007` 의 결과에 실제 `orderId` · `providerOrderId` · `transactionKey` · `eventId` · `confirmedAt` · `items` 와 주문 · 예약 · broker · 활동 상태를 기록한다. 카드 값 · 비밀은 빼고, 실행 뒤 테스트 자원은 정리한다 | 원문 | W7 의 출발점 |
| `EXP-009` | **일시 오류에서 사람 없이 회복.** 다른 TX 가 같은 `eventId` 의 처리 기록을 insert 한 채 commit 하지 않고 잠금 대기 상한(3 초)보다 오래 쥔다. handler 의 insert 가 그 유일 키 잠금을 기다리다 실제 잠금 대기 초과로 실패하고, 재시도 전에 그 TX 를 rollback 한다. 테스트 더블 없이 만든다 | 정함(`KAFKA-006` 의 재시도) | 재시도로 반영 · 컨테이너 멈추지 않음 · committed offset 이 그 record 를 지남 · 활동 한 벌 |

### 원문 7장 검증과 규칙

| 원문의 검증 | 규칙 |
|---|---|
| 실제 PG 확정 → Kafka → streamer | 두 반쪽과 공유 계약(Q-4): commerce-api 는 실제 확정 뒤 broker 의 record 를 저장된 주문과 비교(`EVT-003` · `EVT-007`), streamer 는 계약 예시로 활동을 만든다(`ACT-013`). 한 실행으로 잇는 것은 `EXP-007`(둘째) |
| 대기 · UNKNOWN · 실패 · 확정 rollback 에 발행 없음 | `EVT-011` · `EVT-012` |
| 중복 callback · reconcile 에 새 이벤트 · 추가 차감 없음 | `EVT-011` · `SETTLE-004` |
| 같은 record 재전달에 효과 한 번 | `ACT-004` · `EXP-005` |
| 같은 ID 의 payload 충돌 | `ACT-005` · `ACT-006` |
| 동시 중복 · 품목 저장 중 실패 | `ACT-003` · `ACT-007` · `ACT-008` |
| commit 뒤 발행 공백 | `EVT-013` · `EVT-017` · `EXP-007` |
| 기존 기능 보존 | 「바뀌는 기존 계약」의 유지 목록 |

---

## 물을 질문

| # | 질문 | 무엇이 바뀌나 |
|---|---|---|
| ~~Q-1~~ | ~~충돌을 감지하면 소비를 멈추는가, 기록하고 진행하는가~~ → **기록하고 진행한다.** 충돌 · 해석 불가는 다시 읽어도 결과가 같아서, 멈추면 사람이 offset 을 손으로 넘기기 전까지 뒤의 모든 주문이 묶인다. 다시 읽으면 성공할 수 있는 오류(`ACT-010`)만 멈춘다. 모르는 `schemaVersion` 은 "소비자를 고쳐 배포하면 성공하는" 오류라 멈추는 쪽이다 — 보류하면 배포 뒤에도 그 record 는 반영되지 않는다. 보류 기록은 업무 완료와 구분하고 지표로 드러낸다. **뒤집을 조건**: 같은 key 의 뒤 이벤트(예: `OrderCancelled`)가 앞 이벤트의 반영을 전제하게 될 때 — 그때는 보류가 뒤 이벤트를 잘못 반영하게 하므로 key 단위로 멈추는 장치를 둔다 | `ACT-005` · `ACT-009` |
| ~~Q-2~~ | ~~streamer 는 어느 DB 에 쓰는가~~ → **commerce-api 와 같은 스키마, 외래키 없음, 상대 테이블을 읽지 않음.** 기존 `jpa.yml` · MySQL fixture 를 그대로 쓴다. streamer 가 주문 테이블을 읽어도 된다면 이벤트가 필요 없었다는 뜻이다. **뒤집을 조건**: streamer 를 독립 배포해 별도 DB 가 필요해질 때, 또는 공유 스키마에서 상대 테이블을 읽는 코드가 생길 때 | `ACT-011` |
| ~~Q-3~~ | ~~broker 를 3 개로 늘리는가~~ → **필수 범위는 단일 broker, 3 대는 선택 심화로 별도 compose 에서.** 원문이 단일 broker 실험을 전제로 그 한계를 설명하라고 한다(원문 5장 · 제출 · 체크리스트). 필수 실험(배정 · key 매핑 · 재전달 · 누락 구간)은 복제와 무관하고 Testcontainers 로 돌아 compose 를 쓰지 않는다. 공용 `infra-compose.yml` 을 3 대로 바꾸면 로컬 메모리 · KRaft quorum · offsets topic 복제 수 3 때문에 한 대만 늦게 떠도 group 이 만들어지지 않는 비용만 생긴다. **뒤집을 조건**: W7 relay 가 "ack = 복제 완료" 를 전제로 발행 완료를 기록하게 될 때, 또는 복제 장애 실험이 명시적으로 요구될 때 | `EXP-001` · `EXP-004` · 선택 심화 |
| ~~Q-4~~ | ~~"실제 PG 확정 → Kafka → streamer" 를 한 실행에서 어떻게 잇는가~~ → **7 장 검증은 앱별 테스트 두 반쪽과 공유 계약 JSON 으로, 한 실행이 필요한 `EXP-007` · `EXP-008` 만 재현 스크립트로 한다.** 원문의 검사 예시가 발행(`OrderConfirmedPublicationTest`)과 소비(`PurchaseActivityIntegrationTest`)를 앱별로 나누고, 누락 실험은 "재현 코드 · 실행 방법 · 관찰" 을 요구할 뿐 테스트 스위트 안을 요구하지 않는다. 스크립트는 compose 의 broker · DB 에 두 앱을 프로세스로 띄우고 결과를 문서에 남긴다 — Gradle 에 jar 의존을 엮지 않아 `check` 가 가볍다. 한 JVM 에 두 앱을 넣는 방식은 두 앱의 Bean · 엔티티가 섞여 실제와 다른 조건에서 통과할 수 있어 두지 않는다. **뒤집을 조건**: W7 이 이 실행을 outbox 경로로 매번 자동으로 다시 돌려야 할 때 — streamer `bootJar` 를 자식 JVM 으로 띄우는 테스트로 옮긴다 | `EXP-007` · `EXP-008` |
| ~~Q-5~~ | ~~기존 통합 테스트(423 개)가 발행을 시도할 때 Kafka 를 어떻게 붙이는가~~ → **`modules:kafka` testFixtures 의 static Kafka 컨테이너를 모든 통합 테스트가 공유한다.** 발행 경로가 실제로 돌아 깨지면 바로 드러나고, 테스트 더블을 두지 않는 규칙과 맞는다. 비용은 컨테이너 기동 한 번이다. fixture 는 업무 topic 을 미리 만들고, metadata 를 미리 받으며(`EVT-014` 의 1 초 상한), broker 주소는 test yml 이 아니라 `@DynamicPropertySource` · Initializer 로 넣는다(import 된 `kafka.yml` 이 test yml 을 이기기 때문, `EVT-016`). 주문 ID 가 반복되므로 격리는 `EXP-001` 의 방식을 따른다. 대기만 줄이는 대안은 모든 테스트가 "발행 실패" 를 정상으로 지나가 발행 경로가 깨져도 모른다. **뒤집을 조건**: 공유 컨테이너가 테스트 시간을 크게 늘리거나, 실행 사이 topic 잔여로 불안정해질 때 | `EVT-014` · `EXP-001` · 테스트 fixture |
| ~~Q-6~~ | ~~`EXP-007` 첫째 주문을 "send 직전에서 멈춤" 으로 어떻게 만드는가~~ → **topic 의 `max.message.bytes=1` 로 broker 가 실제로 거절하게 한다.** 끝 상태(주문 `CONFIRMED` · 예약 소비 · record 0 · 활동 0)가 원문의 hook 과 같고, 테스트 더블도 시험용 운영 코드도 없다. 원문의 수단과 다르며 실패 지표가 남는 "흔적 있는 누락" 이라는 점을 PR · 기록에 밝힌다. hook 을 테스트에서 갈아 끼우면 테스트 더블이고, main 코드에 차단 스위치를 두면 시험용 장치가 운영 코드에 들어간다. **뒤집을 조건**: W7 이 "흔적 없는 누락"(지표에도 없는 것)을 outbox 가 복구함을 보여야 할 때 — 그때는 프로세스를 커밋 뒤 send 전에 강제 종료하는 재현을 스크립트에 더한다 | `EXP-007` · `EXP-008` |

## 범위 밖

Outbox · 발행 재시도(W7) · 랭킹 API(W9) · DLQ · Kafka 트랜잭션 ·
이전 POINT 확정 주문의 소급 발행(원문).

## 선택 심화 (본 구현 뒤)

- **static membership** — 세션 안에 재시작하면 배정이 그대로이고, 넘기면 다시 배정되는지 실제 assignment 로 본다.
- **assignor 비교** — consumer 를 더할 때 eager 와 cooperative 에서 `onPartitionsRevoked` 가 어떤 partition 으로 불리는지 기록한다.
- **KIP-848**(`group.protocol=consumer`) — broker · client 를 4.x 로 올리는 조건이 붙는다.
- **Parallel Consumer** — partition 을 늘리지 않고 key 단위로 병렬 처리할 때의 재전달 범위 · DB 부하.
- **broker 3 대** — controller 1 대 · broker 3 대를 띄운 별도 compose(복제 수 3, `min.insync.replicas=2`)에서 `acks=all` 로 보낸다. 한 대를 멈추면 계속 성공하는지, 두 대를 멈추면 `NotEnoughReplicas` 뒤 `delivery.timeout.ms` 에 실패하는지, leader 가 바뀌는 동안 producer 가 metadata 를 새로 받는지 — 그리고 그것이 `max.block.ms` 1 초(`EVT-014`) 안에 끝나는지 — 기록한다. `acks=1` 로 복제 전 leader 를 강제 종료해 ack 받은 record 가 사라지는 것도 비교한다(Q-3).
- **consumer 처리량** — 같은 양의 확정 이벤트를 쌓아 두고 concurrency(1 · 3) × ack mode(`BATCH` · `MANUAL_IMMEDIATE`) × commit(동기 · 비동기) × 저장(record 단위 · 묶음 insert)의 처리 시간과 DB TX 수를 비교한다. 병목은 producer 가 아니라 consumer 의 DB 효과라서, 설계의 "처리량이 모자랄 때 푸는 순서" 를 실측으로 채운다.
- **producer 배치 · 압축** — 몰아서 보낼 때와 드문드문 보낼 때 각각 `linger.ms` · `batch.size` · `compression.type`(none · lz4 · zstd)을 바꿔 producer 지표(`batch-size-avg` · `compression-rate-avg` · `request-latency-avg`)와 broker 에 저장된 바이트를 비교한다. 확정 하나에 record 하나(약 200 바이트)인 지금 패턴에서는 효과가 거의 없을 것으로 보고, 그것을 숫자로 확인한다. W7 relay 가 outbox 를 묶어 보낼 때 의미가 생긴다.
- **DLT 비교** — 같은 실패를 spring-kafka `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` 로 처리해 본다. DLT topic 은 broker 자동 생성이 꺼져 있어 직접 만든다. 해석 불가 · 충돌을 보류 테이블 대신 DLT 로 보냈을 때와, 기본 설정대로 DB 장애 · 모르는 `schemaVersion` 까지 DLT 로 넘어갈 때 구매 활동에서 무엇이 빠지는지 기록한다. `@RetryableTopic` 이 같은 key 의 순서를 어떻게 깨는지도 본다. 보류 테이블을 고른 근거(설계 8 장)를 실측으로 채운다.
- **Share consumer(KIP-932)** — partition 하나를 여러 consumer 가 나눠 읽고 record 마다 승인(accept · release · reject)하며 전달 횟수를 broker 가 센다. broker · client 4.x 가 필요해 본 구현과 분리된 broker 컨테이너와 원시 `KafkaShareConsumer` 로 한다. 같은 주문 이벤트의 순서가 깨지는 것(Q-1 의 뒤집을 조건), record 단위 승인이 "뒤 record 가 앞까지 commit 한다" 를 없애는 것, 손으로 만든 "일시 오류는 멈춤 · 해석 불가는 보류" 를 전달 횟수와 reject 로 대신할 수 있는지를 비교한다.
