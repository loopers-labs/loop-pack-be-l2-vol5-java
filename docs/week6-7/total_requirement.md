# 6~7주차 통합 요구사항 · 주문 확정 전달과 상품 구매 활동

## 기준과 범위

| 항목 | 기준 |
|---|---|
| 준비일 | 2026-11-04 (Asia/Seoul) |
| 출발점 | 로컬 `volume-5/main`의 `b4e499d47876c0205e0c3c47dab314210cd308a8` |
| 원격 확인 | fetch 후 `origin/volume-5/main`은 `0e4005defb700197bd94bd95e066aa79a2218e9e` |
| 통합 기준 브랜치 | `volume-6-7/main` |
| 사용자 확정 | 6~7주차를 함께 진행하고 consumer Inbox까지 적용 |
| R01 사용자 선택 | 앱별 DTO와 JSON 계약 검사, 기존 확정 기록 createdAt 재사용, 정규화 snapshot 필드 비교 |
| R01 추가 선택 | 최초 UUID 생성·저장, 모르는 추가 필드 무시, 개별 최종 실패의 DB 보관과 commit 뒤 offset 진행, 공통 DB 장애 시 리스너 중단·offset 유지 |
| 최종 구조 | 주문 확정과 Outbox 원자적 저장 → 단일 relay → Kafka → Inbox와 구매 활동 원자적 저장 |
| 현재 상태 | 초기 준비 완료. R01은 T01~T08 사용자 선택을 반영한 설계이며 업무 기능은 미구현 |

기존 5주차 PG·예약(선차감 기록)·callback/reconcile·소유권·이전 POINT·상품 삭제/비노출 규칙을 보존한다.
로컬 출발 커밋은 사용자의 과거 인계 문서 삭제를 포함한다. 해당 삭제와 사용자 JMeter 수정은 보존한다.
준비·현재 검증은 [실행 환경](environment.md)과 [준비 결과](initial-setup.md), 순서는 [개발 계획](development-plan.md)을 따른다.
R01의 구체 계약·저장·실행 설정과 검증은 [상세 설계 초안](r01-order-events-inbox/plan.md)에 제안했다.
[트레이드오프와 질문](r01-order-events-inbox/trade-offs.md)의 사용자 선택을 반영했다. 세부 기술 수치는 구현 제안으로 구분한다.

원문: [W6 학습](reference/week6-learning.md) · [W6 과제](reference/week6-implementation-quest.md) ·
[W7 학습](reference/week7-learning.md) · [W7 과제](reference/week7-implementation-quest.md).

## R01 · 이벤트 계약과 실제 구매 활동 소비

### 이벤트 계약

| 항목 | 계약 |
|---|---|
| topic | `order-events`; 테스트는 고유 suffix로 격리 |
| eventType / schemaVersion | `OrderConfirmed` / `1` |
| key | 내부 orderId의 문자열. payload orderId와 일치 |
| eventId | 최초 PG 확정 TX에서 UUID를 생성·저장. 재발행/복구는 저장 UUID 재사용 |
| orderId / confirmedAt | 실제 주문 ID / DB에 저장한 최초 확정 시각 |
| items | 상품별 productId·양수 quantity. 중복 상품은 수량을 합산 |

전송마다 ID·시각을 새로 만들지 않는다. JSON 속성/품목 순서가 달라도 같은 사실로 비교한다.
fingerprint는 정규화한 고정 필드에서 계산한다. 현재 상품 정보나 소비 시각으로 과거 사실을 덮어쓰지 않는다.
카드·PG 비밀·불필요한 개인정보와 임의 발행 공개 API는 제외한다.

### W6 직접 발행과 누락 실험

- 검증된 PG SUCCESS의 최초 내부 확정 TX가 실제 commit된 뒤에만 직접 발행한다.
- callback·reconcile은 같은 확정 경계를 사용한다. 중복 결과 적용은 새 사건을 만들지 않는다.
- 대기·UNKNOWN·실패·rollback에서는 발행하지 않는다. 이전 POINT를 자동 소급하지 않는다.
- send 실패 후 확정 주문을 실패/미확인으로 되돌리거나 PG create를 추가하지 않는다.
- producer future 결과에서 실제 topic·partition·offset과 오류를 확인한다.
- 실제 주문 API·로컬 PG·업무 반영 경로로 A(확정 commit 후 send 직전 중단), B(발행·소비 완료)를 만든다.
- 두 사례의 고정 clock·품목·수량·실행 방법·W6 revision/테스트 구성을 보존한다.
- 실행 시 실제 orderId·providerOrderId·transactionKey·eventId·confirmedAt·items와 DB/broker 결과를 기록한다.
- 실제 after-commit 경로를 다시 실행할 수 있어야 한다. W7 Outbox를 지워 누락을 꾸미지 않는다.
- 임시 DB를 장기간 유지할 필요는 없다. 재현 코드를 남기고 해당 실행 자원을 정리한다.

### Consumer Inbox · 사용자 추가 조건

원문의 '소비 완료 이벤트 기록'을 Inbox로 구체화한다. 같은 책임의 별도 dedup 테이블을 추가하지 않는다.

| 저장 사실 | 규칙 |
|---|---|
| Inbox | eventId·orderId 각각의 DB 유일 제약, eventType·schemaVersion, 고정 사실 비교 정보, processedAt |
| 구매 활동 | `(orderId, productId)`별 하나, quantity·최초 confirmedAt 보존 |
| 반영 범위 | Inbox 기록과 모든 품목을 같은 DB TX에서 commit/rollback |

- 실제 `commerce-streamer` consumer와 파싱·검증·업무 handler를 연결한다.
- 신규 사건은 Inbox와 품목을 함께 저장한다. commit된 Inbox가 소비 완료를 뜻하는 최소 구조로 충분하다.
- 같은 eventId·같은 사실은 정상 no-op이다.
- 같은 eventId의 다른 주문·수량·시각 또는 같은 orderId의 다른 eventId/사실은 충돌이며 ack하지 않는다.
- 메모리 Set·사전 조회만으로 보호하지 않는다. 동시 중복은 DB 유일 제약으로 막는다.
- 중간 품목 실패 시 Inbox와 모든 품목을 rollback한다. Inbox만 별도 TX로 먼저 확정하지 않는다.
- 유일 제약 예외의 TX는 rollback하고 별도 TX에서 동일 사실인지 확인한다. 다른 오류를 중복으로 숨기지 않는다.
- 과거 productId를 보존하며 현재 상품의 존재·가격·노출 여부를 소비의 필수 조건으로 삼지 않는다.
- 기존 소비 완료 이력이 있다면 Inbox로 재사용/이관해 중복 방지 이력을 보존한다.
- 별도 Inbox worker·RECEIVED/PROCESSING 선행 commit·범용 프레임워크는 필수가 아니다.

상품 7×2·상품 8×1 주문은 Inbox 1행과 활동 2행이다. 반복 전달 후에도 수량 2·1이다.
다른 주문에서 상품 7×3이 추가되면 활동은 총 3행이고 상품 7의 총 수량은 5다.

### Listener와 Kafka 실험

- DB TX commit 성공 또는 동일 사실의 기존 완료 확인 후 ack한다.
- 실제 `ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG=false`와 key/value 역직렬화를 확인한다.
- 기존 demo batch factory를 일괄 변경하지 않고 이 이벤트용 record factory를 선택할 수 있다.
- 개별 이벤트의 최종 실패는 별도 DB 트랜잭션에 보관한다. 기록 commit을 확인하면 원본 offset을 진행한다.
- 공통 DB 장애가 재시도 뒤에도 이어지거나 실패 기록 저장에 실패하면 리스너를 중단하고 원본 offset을 유지한다. 복구 후 같은 group에서 재개한다.
- 실패 기록 commit과 offset commit 사이 중단에 대비해 group과 원본 record 위치를 유일 키로 보호한다. Inbox와 구매 활동으로 업무 완료를 판정한다.
- ack mode·error handler·recover·ackAfterHandle과 실제 committed offset을 함께 본다.
- producer·consumer·admin은 같은 실제 테스트 broker 주소를 쓴다. 테스트 fetch/대기에는 deadline을 둔다.
- 격리 2-partition topic에서 같은 group consumer 1→2→3의 실제 안정된 assignment를 확인한다.
- consumer 3개 중 최소 하나는 미배정이며, 두 partition 모두에 실험 record를 보내 읽는다.
- 같은 key의 실제 metadata로 partition 매핑을 확인한다. 다른 key가 반드시 다른 partition인 것은 아니다.
- acks·producer 멱등성 선택과 단일 broker의 복제/장애 보장 한계를 설명한다.

실제 handler의 DB commit 후 offset 미커밋 재전달을 따로 검증한다.
metadata에서 얻은 offset n을 시작 위치로 commit하고 첫 소비자는 업무만 commit한다.
n+1 commit 요청·자동 commit·종료 callback 없이 유한하게 종료한다. committed offset은 n이다.
같은 group의 새 소비자는 seek/reset 없이 같은 topic·partition·offset n을 다시 읽고 no-op 후 n+1을 commit한다.
position이나 handler 두 번 호출만으로 이를 증명하지 않는다.

실제 Spring listener의 실패는 별도 검증한다. Inbox·활동 rollback과 실패 기록 commit 전 offset 유지를 확인한다.
실패 기록 commit 후 offset 진행, 공통 DB 장애나 기록 실패 시 중단, 같은 group 재시작 후 원본 재전달도 검증한다.

## R02 · Transactional Outbox와 단일 polling relay

### 로컬 원자성

검증된 관찰 저장 이후 기존 공통 업무 반영 TX에 주문 확정·예약 소비·PG 성공 사실·Outbox를 함께 둔다.
외부 PG 호출과 relay 전송은 이 TX 밖이다. 기존 별도 관찰 저장 경계를 합치지 않는다.

| Outbox 데이터 | 규칙 |
|---|---|
| identity | 고정 eventId 유일 제약, orderId·eventType·schemaVersion |
| 메시지 | 생성 뒤 변경 없는 messageKey·payload |
| 전달 | 최초 PENDING, broker 확인과 상태 TX 성공 후 PUBLISHED |
| 관찰·재시도 | createdAt·publishedAt·attemptCount·nextAttemptAt·lastError |

- 같은 데이터 소스·로컬 TX다. REQUIRES_NEW 또는 after-commit insert로 분리하지 않는다.
- 대기·UNKNOWN·확정 실패에서는 Outbox 없음. 중복 결과 적용에도 Outbox 한 행이다.
- insert/serialization 실패 시 확정·예약 소비까지 rollback한다. 기존 미완료 주문·예약은 TX 직전 상태다.
- 외부 PG SUCCESS는 남을 수 있다. 기존 거래 재조회/저장 관찰 재적용으로 회복하고 새 create는 하지 않는다.
- 최종 확정 경로에서 W6 직접 send를 교체한다. relay와 직접 send를 병행하지 않는다.

### Relay

1. 재시도 시간이 된 PENDING을 작은 묶음·안정된 순서로 조회한다. 조회 인덱스를 검토한다.
2. 같은 eventId·저장 key·payload로 시도를 기록하고 전송한다.
3. 실제 producer future 성공 후 별도 DB TX에서 PUBLISHED·publishedAt을 저장한다.
4. 실패/timeout/상태 저장 실패면 같은 사건을 재시도할 수 있게 남긴다.

- 한 실행 흐름만 허용하고 scheduler tick·수동 호출이 겹치지 않게 한다. 다른 인스턴스의 동시 활성화도 피한다.
- DB TX와 외부 전송 대기를 분리한다. 무제한 future 병렬화는 하지 않는다.
- 대기 한도·회차 처리 개수·재시도 간격을 정하고 빠른 무한 재시도를 피한다.
- timeout은 수신 0회 증거가 아니다. 늦은 callback이 상태를 임의 변경하지 않게 한다.
- PUBLISHED를 먼저 저장하지 않는다. broker 성공 후 상태 저장 전 중단은 재발행 가능성을 받아들인다.
- 영구 오류를 노출한다. BLOCKED 선택 시 미해결 상태로 보존하고 같은 행을 재개한다.
- 대상 eventId로 로컬 재개 도구를 둔다. 공개 관리자 API는 만들지 않는다.

실제 DB에서 Outbox insert 전후 실패/전체 rollback과 중복 결과 적용을 검증한다.
commit 후 relay 전 중단 → 저장 PENDING에서 새 relay가 복구하는 장면과,
실제 broker 성공 metadata 확보 후 PUBLISHED 저장 전 중단 → 같은 행 재발행을 검증한다.
중단 후 상태를 직접 PENDING으로 덮어쓰지 않는다. 통제 hook 시험을 OS 강제 종료로 표현하지 않는다.

## R03 · 누락 복구와 최종 E2E

### W6 상태 재현 → 같은 실행에서 복구

- 새 격리 MySQL·Kafka·로컬 PG에서 보존한 W6 직접 발행 경로로 A·B를 실제 확정한다.
- 두 주문 모두 CONFIRMED·예약 소비·Outbox 없음이며 A만 활동 0임을 새 DB 조회로 확인한다.
- 이번 실행의 실제 사건 identity·PG 식별자와 PG create 누적 C·외부 거래 수 T를 기록한다.
- 이후 DB·broker·PG를 초기화하거나 준비와 복구 사이에 정리하지 않는다.
- 직접 발행을 중단하고 같은 DB에 W7 Outbox/복구 경로와 단일 relay를 연결한다.
- 저장된 eventId·confirmedAt·품목으로 없는 Outbox만 추가하고 이미 있으면 일치를 확인한다.
- 원본 사실 부족/충돌은 추측하지 않는다. SUCCESS 직접 삽입·Outbox 삭제·제품 우회 스위치는 금지한다.
- A에는 Inbox·누락 활동이 생성되고 B의 Inbox·활동은 불변이다. 두 번째 복구도 행/효과를 늘리지 않는다.
- 복구 중 추가 PG create·조회·callback·외부 거래·재확정·예약 추가 소비는 0이다.
- 실제 W6 DB를 보존한 경우만 원본 사건 복구라고 부른다. 새 실행의 재현 ID와 역사적 ID를 혼동하지 않는다.
- POINT 주문 전체 소급은 제외하며 모든 확인 후 자기 실행의 자원만 정리한다.

### 실제 API부터 Inbox·구매 활동까지

하나의 E2E에서 기존 API·로컬 PG·실제 MySQL/Kafka·실제 listener/parser/handler를 연결한다.
첫 broker 성공 후 상태 저장 전 relay를 중단하고 같은 DB·broker·eventId에서 relay를 새로 만들어 재발행한다.

| 상품 7×2·상품 8×1 주문의 관찰 | 기대 |
|---|---|
| 주문·PG 거래 | 같은 확정 주문·외부 거래 각각 1 |
| Outbox | 같은 eventId 1행, 최종 PUBLISHED |
| 실제 Kafka record | 같은 eventId로 별도 record 2개 이상 |
| Consumer | 수신 2회 이상 |
| Inbox | 같은 주문·사건의 완료 기록 1행 |
| 구매 활동 | 상품별 2행, 수량 2·1 유지 |
| 기존 효과 | 예약 추가 소비/반환·PG 주문 포인트 추가 차감 없음 |

W6는 같은 offset의 재전달, W7은 같은 eventId의 별도 record 재발행이다. send 호출 횟수만 세지 않는다.
자동 rollback 테스트 안에 전체 실행을 가두지 않고 실제 commit을 확인한다.
제품 runtime에 commerce-api→commerce-streamer 의존성을 만들지 않는다.

## 운영·회귀·제출

- PENDING·PUBLISHED·Inbox 완료·실제 구매 활동을 구분한다.
- eventId·orderId, PENDING 수/최고 대기 시간, 시도/전송 오류, consumer lag/실패를 SQL·구조화 로그로 확인한다.
- 미발행/격리 행은 자동 삭제하지 않는다. Outbox·Kafka retention·Inbox/dedup 보존 기간의 관계를 설명한다.
- 5주차 PG·예약·소유권·POINT와 W6 Inbox 멱등성·listener 실패 정책의 관련 회귀를 보호한다.
- 필요한 핵심 테스트를 작성하고 Checkstyle·ArchUnit을 유지한다. 실패/skip/0건/연결 오류는 통과가 아니다.
- 고유 topic/group/합성 주문과 유한한 준비·배정·결과 대기를 사용한다. 자신이 만든 자원만 정리한다.
- 각 실패 위치의 DB·broker·활동 상태를 먼저 예측하고 AI 제안의 반례를 검토한다.
- 제출: 구현·설계·관련 실험/테스트·복구 도구/방법·PR·기술 글. 전달/멱등성과 Outbox/복구 학습을 각각 담는다.
- PR에 대표 주문 결과, assignment, 실제 offset 전후, 단일 relay 제약·재개 방법·실험 한계를 남긴다.

랭킹 API·새 결제수단/상품 CRUD/주문 도메인·범용 Saga·CDC 플랫폼·다중 relay·새 모니터링 서버는 필수가 아니다.
R01의 실패 보관 범위는 DB에 원문과 사유를 기록하는 단계까지다. BLOCKED·실제 OS 강제 종료·별도 Inbox worker는 선택 사항이다.
