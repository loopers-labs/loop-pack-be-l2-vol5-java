# W6 주문 확정 이벤트와 상품 구매 활동 채점 기준

2026-10-27 사용자가 제공한 [과제 원문](assignment-original.md)을 이번 주 평가 기준으로, [학습 자료 원문](learning-original.md)을 개념 근거로 사용한다. 두 원문은 첨부 내용을 보존했으며 파일 끝 개행만 추가했다. 이 정리와 과제 원문의 해석이 다르면 과제 원문을 우선한다.

목표는 **기존 PG 확정 주문 → Kafka → commerce-streamer → 상품 구매 활동 저장**을 연결하고, 메시지 재전달과 DB 업무 효과를 구분하는 것이다. W5의 결제·예약을 다시 만드는 과제가 아니다. 공식 체크 항목은 **11개**이며 별도 숫자 배점·성능 목표·필수 테스트 개수를 만들지 않는다. 현재 구현 상태와 실행 증거는 [W6 구현·검증 체크리스트](implementation-checklist.md)에서 별도로 관리한다.

## 공식 11개 항목과 확인 증거

| ID | 원문의 체크 항목 | 충족 여부를 확인할 증거 |
| --- | --- | --- |
| W6-C01 | 실제 PG 확정 주문에서 stable 이벤트를 발행하고 구매 활동으로 저장한다. | 기존 주문 API·로컬 PG·실제 결과 적용 → 성공한 commit → 실제 Kafka metadata → streamer handler → 새 DB 조회의 저장 품목·수량·최초 확정 시각 일치 |
| W6-C02 | 대기·실패·rollback·중복 결과 적용에서 거짓 이벤트나 새 결제가 생기지 않는다. | DRAFT·PAYMENT_PENDING·UNKNOWN·FAILED·최초 확정 rollback의 발행 0. 중복 callback/reconcile의 새 논리 이벤트·추가 PG 생성·재고 효과 0 |
| W6-C03 | 저장된 구매 사실을 이후 랭킹 입력으로 사용할 수 있고 민감정보를 보내지 않는다. | 주문·상품별 quantity·confirmedAt 보존, 다른 주문의 구매는 별도 누적. 카드·PG 비밀·불필요한 개인정보 제외, 현재 상품의 삭제·가격·존재로 과거 구매 사실을 변경하지 않음 |
| W6-C04 | partition 2개와 같은 group의 consumer 1→2→3 실제 배정을 확인했다. | 실제 assignment와 안정화 조건, partition별 단일 담당·3개 중 최소 1개 무배정. 두 partition의 읽기, 고정 partition 수에서 같은 key의 실제 metadata 비교 |
| W6-C05 | 같은 record가 DB commit 뒤 미커밋 offset에서 재전달됨을 관찰했다. | 같은 broker/topic/group/DB에서 consumer만 교체. 동일 topic·partition·offset n의 전달 2회, 구매 효과 불변, 실제 committed offset n → n → n+1 |
| W6-C06 | 중복 방지와 모든 품목 저장이 원자적이며 같은 사실·충돌을 구분한다. | eventId·orderId 및 (orderId, productId) 유일성, 동시 소비 보호, 중간 품목 저장 실패의 전체 rollback. 같은 사실 no-op, 식별·payload 충돌 노출 |
| W6-C07 | 실제 listener의 DB 실패·error handler·ack·재시작 결과를 확인했다. | 실제 Spring listener의 DB 실패 → 완료 기록·전체 품목 rollback → 실패 record를 지나지 않는 committed offset·선택한 실패 정책 → 오류 제거·같은 group 재시작·재전달·저장·ack |
| W6-C08 | 직접 after-commit 발행의 누락 구간을 재현하고 주문 상태를 되돌리지 않는다. | 확정 commit 후 send 직전 중단/명확한 전송 실패 주입. CONFIRMED·예약 소비 유지, 해당 broker record·구매 활동 없음. PG 재생성·FAILED/UNKNOWN 퇴행 없음 |
| W6-C09 | 미발행 1건·이미 소비된 1건의 재현 코드와 실제 사건 사실을 남기고 임시 DB는 정리했다. | 기존 API·PG 경로로 만든 두 사례, W6 직접 발행 경로의 재현 코드·revision·실행 방법·고정 clock·품목, 실제 식별자·상태 관찰, 자신의 임시 자원 정리 |
| W6-C10 | 실제 설정·broker 주소·단일 broker 한계를 설명할 수 있다. | producer·consumer·admin의 동일 실제 bootstrap, 유효 deserializer·auto commit·ack·error handler·fetch 설정. acks 선택 이유와 단일 broker로 확인하지 못한 복제 장애 보장 구분 |
| W6-C11 | 관련 회귀·lint·ArchUnit을 실행하고 테스트 자원을 정리했다. | API/streamer 관련 회귀·Checkstyle·계층 검사 실행 건수·실패·skip·종료 결과, 유한 대기·worker/consumer/fixture 정리. W5 결제·예약·POINT·소유권·삭제 계약 보존 |

최종 평가는 **충족·부분 충족·미충족·확인 불가**로 구분하고 코드·테스트·실행 결과를 붙인다. 계획이나 설정의 존재, W5의 과거 통과 수만으로 W6 충족을 판정하지 않는다. PR·기술 글의 제출 여부는 11개 구현·검증 항목과 별도로 확인한다.

| 판정 | 적용 기준 |
| --- | --- |
| 충족 | 해당 항목의 필수 동작과 실험을 코드·실제 실행 결과로 확인했다. |
| 부분 충족 | 일부 구현·검증은 확인했지만 필수 경로나 실험·실행 증거가 남아 있다. 확인한 부분과 남은 부분을 함께 적는다. |
| 미충족 | 필수 기능이 없거나, 재현된 결과가 요구한 업무 규칙·전달 경계를 위반한다. 반례와 수정 방향을 적는다. |
| 확인 불가 | 자료 접근이나 실행 환경의 제약으로 판단할 근거를 확보하지 못했다. 제약을 코드 결함이나 통과로 바꾸어 표현하지 않는다. |

## 과제로 고정된 이벤트 계약

| 항목 | 기준 |
| --- | --- |
| topic | `order-events`. 테스트에서 고유 suffix를 붙여 격리 가능 |
| eventType / schemaVersion | `OrderConfirmed` / `1` |
| key | 내부 orderId의 문자열 표현. payload의 orderId와 일치 |
| eventId | 최초 확정 사실에 고정. 최초 확정 때 저장하거나 안정적으로 유도하며, send/재전달마다 새 UUID·시각을 넣지 않음 |
| orderId / confirmedAt | 저장된 확정 주문 ID / 최초 확정 시각 |
| items | 상품별 productId·양수 quantity. 같은 상품의 중복 품목은 합산해 하나로 정규화 |
| 사실 비교 | JSON 속성·items 배열 순서가 아니라 정규화한 상품별 수량과 고정 필드 비교 |
| 제외 데이터·진입점 | 카드·PG 비밀·불필요한 개인정보 제외. 고객이 임의 이벤트를 발행하는 공개 API를 만들지 않으며 합성 이벤트 주입은 로컬 테스트 도구에서만 허용 |

`order-confirmed:v1:{orderId}`는 안정적인 eventId의 **예시**다. 별도 UUID를 최초 한 번 저장하는 설계도 가능하며, 예시를 유일한 정답으로 강제하지 않는다. W5의 HMAC 요청 지문을 이번 이벤트의 ID·payload fingerprint로 재사용해야 한다는 요구도 없다.

## 발행과 소비의 책임·트랜잭션 경계

### 발행: 최초 PG 확정의 실제 commit 뒤

- 검증된 PG SUCCESS를 최초 CONFIRMED로 반영하고 예약을 소비한 **DB commit 성공 후** 직접 발행한다. confirm·callback·reconcile의 공통 결과 적용 경계를 유지한다.
- after-commit listener 또는 프록시 트랜잭션이 실제 commit된 뒤의 orchestration 중 선택한다. `@Transactional` 메서드 끝의 send를 commit 이후라고 간주하지 않는다.
- 저장 품목·수량·최초 시각으로 payload를 준비한다. commit 뒤 lazy entity 접근이나 현재 상품 재조회에 의존해 과거 사실을 바꾸지 않는다.
- PG 대기·미확인·실패, 최초 확정 rollback, 이미 종결한 동일 결과 적용에서 새 논리 이벤트를 만들지 않는다. 이전 POINT 확정 주문을 자동 소급 발행하지 않는다.
- send의 broker 성공 metadata(topic·partition·offset)와 전송 오류를 구분한다. broker 수락은 소비자의 DB 완료가 아니다.
- commit 뒤 발행 실패는 주문 확정을 취소하지 않는다. 고객 주문 상태는 저장 결과로 답하고 발행 실패는 별도로 드러낸다. PG POST 재호출·결제 FAILED/UNKNOWN 퇴행은 금지한다.

이번 직접 발행은 DB와 broker 사이 누락을 없애지 못한다. **누락을 관찰·기록하는 것이 이번 필수이며 Outbox 구현은 W7 범위**다. 전송 오류만으로 broker에 record가 없다고 단정하지 않고, 누락 실험에서는 send 직전 통제 hook 등 확실한 경계와 실제 broker·DB 결과를 함께 확인한다.

### 소비: 완료 기록과 모든 품목을 같은 DB TX에

| 수신 장면 | 기대 결과 |
| --- | --- |
| 처음 받은 유효 확정 사실 | 완료 기록과 모든 상품 구매 활동을 한 DB TX에서 저장하고, commit 성공 후 ack |
| 같은 eventId·같은 주문 사실 | 정상 no-op. 구매 행·수량을 늘리지 않고 처리 완료 |
| 같은 eventId에 다른 주문·수량·확정 시각 | 충돌 노출, 추가 효과 없음 |
| 같은 orderId에 다른 eventId | payload가 같아도 정상 재전달로 숨기지 않고 충돌 노출, 추가 구매 없음 |
| 상품 중복 항목·items 순서 차이 | 상품별 합산·정규화한 사실로 판단. 잘못된 형식·버전·식별·수량은 검증 실패 |
| 동시 중복 | DB 유일 제약으로 보호. 메모리 Set·조회 후 저장만으로 해결했다고 하지 않음 |
| 중간 품목 저장/commit 실패 | 완료 기록과 앞선 품목도 모두 rollback, 업무 성공 ack 없음 |

완료 기록에는 eventId와 orderId의 유일성을, 구매 활동에는 (orderId, productId)의 유일성을 보장한다. 테이블·Entity 이름은 선택 사항이다. JPA 유일 제약 예외가 난 TX에서 예외를 삼켜 정상 commit하려 하지 않는다. 경쟁 패자를 rollback한 뒤 별도 TX에서 기존 사실을 확인하는 등 저장 방식에 맞는 경계를 설계하며, 다른 DB 오류를 모두 정상 중복으로 분류하지 않는다.

consumer는 결제·재고 차감을 다시 하지 않는다. 삭제 상품도 과거 productId를 구매 사실로 보존하지만 상품을 재노출하지 않는다. 현재 상품 행 존재·가격 조회를 구매 사실 저장의 전제로 삼지 않는다. 예시 주문 42의 두 품목은 재전달 후에도 2행이며, 다른 주문 43의 구매는 별도 행·수량으로 남는다. 이것은 랭킹 API 구현 요구가 아니다.

## 반드시 구분해서 실행할 실험

### A. Partition·group 배정과 key 매핑

1. 실제 broker에 partition 2개의 고유 실험 topic을 만든다. 단순 설정·consumer 객체 수가 아니라 poll과 실제 assignment로 준비를 확인한다.
2. 같은 group의 consumer를 1→2→3개로 바꾸고 유한 deadline 안에 안정된 배정을 확인한다. 각 partition의 담당은 group 안에서 한 member이며, 3개면 최소 한 member는 배정이 없다.
3. 두 partition에 명시적으로 보낸 실험 record가 읽히는지 확인한다. 업무 topic과 분리하여 가짜 구매 활동을 만들지 않는다.
4. 별도 key 매핑 검증에서는 partition을 producer가 고르게 하고 같은 key의 send metadata를 비교한다. 서로 다른 key가 반드시 다른 partition에 간다고 단언하지 않는다.

같은 key의 매핑은 같은 직렬화·partitioner·partition 수 조건의 관찰이다. 단일 broker·replication factor 1의 acks=all로 다중 broker 장애 내구성을 증명했다고 하지 않는다. idempotent producer도 새 업무 record의 이중 send나 외부 DB 중복 효과를 자동 해결하지 않는다.

### B. DB commit 후 offset 공백의 실제 재전달

| 순서 | 실제 확인할 값 |
| --- | --- |
| target 발행 | send metadata에서 실제 topic·partition·offset n 확보 |
| 첫 consumer 준비 | 구독·배정 뒤 다음 읽을 위치 n을 commit하고 n에서 읽기 |
| 첫 업무 처리 | 실제 parser·구매 활동 handler가 DB commit. 완료 기록·활동 존재 |
| offset 보류·종료 | n+1을 요청하지 않아 committed offset은 n. auto commit false, pending/종료 commit callback 없이 bounded close |
| 새 consumer | broker/topic/group/DB는 유지하고 consumer만 교체. **seek·reset 없이** 저장 offset에서 동일 record n 재전달 |
| 중복 처리·완료 | 같은 사실 no-op 뒤 n+1 commit. 전달 2회, 구매 효과 불변, committed offset n → n → n+1 |

record 위치는 임의 숫자를 고정하지 않는다. poll position·lag·출력 로그만으로 실제 committed offset과 DB 결과를 대체하지 않는다. 이 실험은 통제한 consumer 종료이며 OS 강제 종료·모든 장애의 재현이라고 주장하지 않는다.

### C. 실제 Spring listener의 실패와 재시작

별도 격리 topic/group에서 **DB commit 전 오류**를 주입한다. 완료 기록·모든 품목 rollback, 실패 record 이후로 진행하지 않은 실제 committed offset, 선택한 중단·원인 표시를 확인한다. 오류를 제거하고 같은 group으로 컨테이너를 재시작하여 재전달·정상 저장·ack를 확인한다.

이 실험은 B의 low-level consumer 실험과 목적이 다르다. handler 두 번 직접 호출, mock ack 횟수만으로 두 실험을 대신하지 않는다. 자동 commit·listener ack mode·error handler의 재시도 소진/recover·ackAfterHandle·최종 offset을 함께 본다. `commitRecovered=false` 하나를 모든 commit 차단의 증거로 쓰지 않는다.

### D. 발행 누락과 W7 인계용 두 사례

기존 주문 API·로컬 PG fixture·실제 결과 적용 경로로 **미발행 1건과 정상 발행·소비 1건**을 만든다. 첫 사례만 확정 commit 후 send 직전 hook에서 멈추고 둘째는 실제 broker·consumer까지 처리한다.

| 보존할 증거 | 내용 |
| --- | --- |
| 재현 코드 | W6 직접 발행 경로를 다시 실행할 준비 함수/실행물, 사용한 W6 commit, 테스트 명령, 고정 clock·품목·수량 |
| 실제 사건 사실 | 각 사례의 역할, orderId·providerOrderId·transactionKey·eventId·confirmedAt·items |
| 결과 | 주문·예약·broker record·구매 활동 상태. 카드·비밀정보 제외 |
| 정리 | 두 사례와 재현 정보를 남긴 뒤 자신이 만든 테스트 consumer·컨테이너·임시 DB 정상 정리 |

지금의 W6 작업 전 commit은 완료된 W6 재현 revision이 아니다. 구현 뒤 실제 사용한 revision을 기록한다. W7 새 격리 실행에서는 생성된 주문의 새 사건 ID를 준비→복구 사이에 고정한다. W7 Outbox를 실행하고 행을 지워 W6 누락을 꾸미거나, 임시 DB를 일주일 유지해야 한다고 해석하지 않는다.

## 설정·기반과 선택할 것

- 실제 broker fixture는 Testcontainers 또는 직접 시작한 로컬 Kafka 중 선택한다. 기존 Kafka/Testcontainers 의존성과 testFixtures 참조만으로 실행 가능한 fixture가 있다고 보지 않는다.
- producer·consumer·admin에 같은 실제 bootstrap address를 넣는다. 호스트용 주소와 Docker 내부 주소를 혼용하지 않는다.
- String key와 선택한 JSON bytes/DTO의 serializer·deserializer를 맞추고, 실제 ConsumerConfig의 `enable.auto.commit=false`를 확인한다. YAML의 `value-serializer` 오기·사용자 정의 `enable-auto-commit` 표기를 그대로 성공 근거로 사용하지 않는다.
- 기존 batch/MANUAL/concurrency 3 factory를 전역 변경한다고 먼저 정하지 않는다. 전용 record factory, MANUAL_IMMEDIATE, concurrency 1, 중단형 error handler는 **예시·후보**이며 같은 DB/offset 보장을 다른 구성으로 검증할 수도 있다.
- 작은 테스트의 fetch 크기·대기를 선택하고 이유를 남긴다. 긴 sleep으로 준비·배정·소비 확인을 대신하지 않는다. timeout·retry 등의 구체값은 구현 설계에서 선택한다.
- acks·idempotent producer 사용 여부와 설정의 호환 조건·보장 범위를 설명한다. 선택한 값의 존재와 실제 broker 전달 성공은 구분한다.

2026-10-29 사용자가 결정적 eventId·after-commit listener(Q01/Q02)에 이어 Kafka 완료를 기다리지 않는 callback 관찰(Q05)을 선택했다. 이후 사용자는 두 테이블·필드 비교(Q03)와 실패 중단·같은 group 재시작(Q04)을 선택했다. 전용 소비 factory·공동 DB 저장·중복/충돌 처리를 구현했고 생산자/소비자 fixture는 격리 Testcontainers로 구체화했다. [소비 증분의 실제 증거](purchase-activity-consumer-tdd-log.md)와 [점검표의 남은 실험](implementation-checklist.md#구현-전에-구체화할-선택)을 구분한다. [첫 모델 증거](order-confirmed-event-tdd-log.md)와 [후속 발행 연결 증거](order-confirmed-publication-tdd-log.md)를 구분한다. 후속 관련 47개 스위트·862개와 API 정적 검사 통과는 발행 증분의 증거이며 후속 소비 증거와 단순 합산하거나 DB commit 뒤 offset 공백·배정·W7 등 공식 11개 항목 전체 충족으로 확대하지 않는다.

## 실행·제출·범위

Java의 관련 검사 예시는 다음과 같다. 테스트 클래스명은 원문의 예시이며 실제 만든 이름으로 실행한다.

```shell
./gradlew :apps:commerce-api:check :apps:commerce-streamer:check
```

관련 회귀·Checkstyle·ArchUnit의 실제 적용 범위와 실행 건수·실패·오류·skip·종료 결과를 기록한다. API의 ArchUnit만으로 별도 streamer 클래스까지 검사됐다고 주장하지 않는다. 테스트 0건·skip·broker 연결 실패를 성공으로 세지 않는다. 유한한 대기를 사용하고 자신이 시작한 자원만 정리하며, 기존 사용자 DB·볼륨·다른 실행의 topic/group을 삭제하지 않는다.

원문의 AI 반례 검토도 검증 근거에 연결한다. “DB 저장 뒤 commit 전 중단”, “handler가 예외를 삼킴”, “같은 ID로 다른 수량 수신”을 검토하고 채택한 보호 규칙을 코드·테스트로 확인한다. 이를 별도의 12번째 평가 항목이나 특정 이름의 테스트 파일 요구로 늘리지는 않는다.

제출물은 발행·소비 코드, 이벤트/트랜잭션 설계, 관련 테스트, PR과 기술 글이다. PR에는 대표 주문의 활동 결과·실제 배정표·offset 전후·DB→broker 누락 구간과 단일 broker·통제 종료 실험의 한계를 적는다. 기존 [전체 설계](../week2/commerce-erd-draft.md)의 버드뷰·계층·도메인 관계·세 흐름을 확장하고 [정책](../week2/commerce-policy-decisions.md)·[TDD 계획](../week2/commerce-tdd-plan.md)·필요한 [API 계약](../week2/commerce-api-contract.md)과 연결한다. 설계 작성·코드 존재·실행 성공·제출 완료는 각각 구분한다.

**이번 범위 밖:** Outbox 구현, 랭킹 API, 새 결제/상품 CRUD, 운영 DLQ 제품·관리 화면·공개 이벤트 주입 API. OS 프로세스 강제 종료·다중 broker 장애 실험은 선택 심화이며 필수로 추가하지 않는다. 미완료 보장은 정직하게 남기되 원문 밖 기능을 완료 조건으로 확대하지 않는다.

**2026-10-27 문서화 상태:** 과제 원문 보존·평가 기준 정리만 수행했다. 이번에 Kafka·DB·Gradle 검사나 W6 기능 구현은 하지 않았다. 실행 증거가 쌓이면 별도 체크리스트에서 판정한다.

같은 날 다시 제공된 학습·과제 첨부와 저장된 두 원문을 대조하여 파일 끝 개행 외의 내용이 동일함을 확인했다. 공식 11항목은 유지하고 판정의 의미·반례 검토 근거를 보완했다.
