# W7 Outbox 구현과 검증 체크리스트

[과제 원문](assignment-original.md) · [학습 자료](learning-original.md) · [채점 기준](grading-criteria.md) · [전체 설계](../week2/commerce-erd-draft.md) · [W6 재현 가이드](../week6/fixed-clock-w7-reproduction.md)

## 최신 상태 — 2026-11-06

**공식 11항목의 구현·검증을 완료했다.** 남았던 C11의 전체 검사 연결 한도 문제를 API 테스트 context cache 상한으로 보완했고, 양 앱 전체 `check --rerun-tasks`가 성공했다. API 155개 스위트·2,091개와 streamer 9개 스위트·99개가 실패·오류·skip 0이며 양 앱 ArchUnit·Checkstyle 보고서 12개 위반 0, 소유 테스트 자원 종료를 확인했다.

[최종 검사 기록](final-verification.md)에 원인·변경·전체 범위·자원 정리를, [W6 복구 기록](w6-outbox-backfill-tdd-log.md)에 같은 실행의 실제 PG 거래 식별자와 상품 ID별 수량을 남겼다. 아래 날짜별 기록은 당시의 증분 상태를 보존한다. PR·기술 글과 실제 제출 완료 여부는 별도이며 이번에는 커밋·푸시하지 않았다.

## 2026-11-03 출발 상태

2026-11-03 기준 브랜치는 `volume-7`, HEAD는 `9737e4c7737db050858ea7dbebd803be070362e1`이다. 문서 갱신 전 작업 트리는 깨끗했다. 당시 주문 경로는 W6의 최초 PG 확정 → AFTER_COMMIT 직접 발행 → 구매 활동 소비다. 앱·모듈의 Java/SQL/YAML에서 W7 Outbox·relay 구현은 확인되지 않았다.

W6 재현 기준 `dd86f49a9edf7a7ccdfc672be3f708f0f4a3ddd1`이 존재하고 위 HEAD와 구현·테스트·빌드 경로가 같다. W6 C09의 revision 연결은 완료된 기반으로 유지한다. **2026-11-03 기준 문서, 2026-11-04 1단계 설계에 이어 첫 Outbox 저장 증분을 구현했다. 이어서 단일 relay 증분을 구현했다. 전체 W7 완료가 아니라 아래의 증분별 상태로 구분한다.** 아래 상태는 작업 추적이며 새 구현을 채점한 점수가 아니다.

## 2026-11-04 1단계 설계

사용자는 **W7-Q01 공통 Outbox·완성된 JSON 문자열 저장**, **W7-Q02 공통 리스너의 커밋 전 같은 TX 기록**을 선택했다. 첫 연결 대상은 주문 확정 이벤트다. 원문에서 정한 원자성·기존 계약·두 중단·실제 중복 발행·W6 복구 기대값을 다음 문서에 연결했다.

| 문서 | 이번 설계 내용 |
| --- | --- |
| [전체 설계](../week2/commerce-erd-draft.md#w7-outbox와-단일-relay-설계) | W7 버드뷰·계층·Outbox 관계·결제 흐름. 관리자/좋아요 흐름은 유지하고 PUBLISHED 기록과 소비 완료를 별도 경계로 표현 |
| [정책 선택](../week2/commerce-policy-decisions.md#w7-outbox-설계의-선택과-질문) | Q01/Q02의 선택 이유·미채택 대안, 원문 필수 계약과 후속 미정 사항 구분 |
| [TDD 계획](../week2/commerce-tdd-plan.md#w7-outbox-원자성과-복구-tdd-계획) | 첫 실제 DB 공동 저장·rollback 기대값, 후속 relay·E2E·같은 실행 W6 복구 검증 |
| [API 계약](../week2/commerce-api-contract.md) | W7에서 고객 응답의 근거와 확정 TX 실패/커밋 뒤 전달 실패를 구분. 새 공개 발행·복구 API를 추가하지 않음 |

설계 당시에는 코드·DDL·테스트를 변경하지 않았다. 이후 같은 날 사용자가 첫 구현을 요청하고 topic 공동 저장을 선택해 아래 저장 증분을 진행했다. 당시 후속이었던 relay 설정·반복 오류 정책은 아래 2단계에서 선택·구현했다. W6 준비→복구의 같은 실행 연결은 후속이다.

## 2026-11-04 첫 저장 구현

공통 `outbox_message`·추가 012 DDL·명시적 JSON mapping·BEFORE_COMMIT listener·MANDATORY recorder를 연결했다. 주문·시도·예약의 기존 TX/잠금은 유지했다. 신규 확정은 Outbox PENDING까지만 기록하며 Kafka 직접 send는 하지 않는다. 이 첫 저장 시점에는 relay가 없었다. 후속 전달 구현은 아래 2단계, 실제 구매 활동까지의 W7 전체 E2E는 다음 단계로 구분한다.

[원자성 TDD 기록](outbox-atomicity-tdd-log.md)에 실제 SQL 뒤 rollback·외부 PG SUCCESS 보존·동일 거래 GET 복구와 검사 명령/결과를 연결한다. API 관련 51개 스위트·809개 테스트는 실패/오류/skip 0으로 통과했다. streamer도 실제 재실행한 9개 스위트·99개가 실패/오류/skip 0이며 양 앱 ArchUnit과 main/test Checkstyle 위반 0을 확인했다. 정적 검사 수정 후 마지막 명령은 BUILD SUCCESSFUL·종료 0이고 테스트 컨테이너 종료를 확인했다. 세부 실행 이력은 기록을 따른다. 사용자 DB·기존 topic/group·볼륨을 변경하거나 커밋·푸시하지 않았다.

## 2026-11-04 2단계 relay 구현

사용자 선택 W7-Q03~05에 따라 API 내부 전용 단일 worker·자동 실행 기본 OFF·회차 종료 뒤 1초·최대 10건 순차 처리·전송 예산 12초·5→10→20→40→60초 재시도를 연결했다. 반복 오류는 PENDING으로 보존한다. due 조회 → 짧은 시도 TX → DB TX 밖 전송 → 실제 broker 확인 → 별도 PUBLISHED TX 순서이며, 기존 주문/재고 잠금은 유지했다.

[relay TDD 기록](outbox-relay-tdd-log.md)에 신규 10개 스위트·42개와 관련 회귀·정적 검사 범위 및 한계를 연결한다. 이번 API 관련 회귀 61개 스위트·851개와 ArchUnit·main/test Checkstyle을 실제 실행해 통과했다. 실패·오류·skip·lint 위반은 0이다. streamer check는 UP-TO-DATE로 기존 통과 결과를 재사용했으며 새 실행으로 합산하지 않는다. 실제 DB·Kafka 정상 전달은 검증했지만 두 중단 구간의 주문/PG/streamer 전체 E2E와 W6 두 사례 복구 완료를 의미하지 않는다.

## 2026-11-04 3단계 중단·재발행 전체 흐름 검증

[중단·재발행 E2E 기록](outbox-republication-e2e-tdd-log.md)의 새 테스트 한 개에서 두 중단 장면을 같은 격리 자원으로 연결했다. 실제 주문 API·PG·MySQL·Kafka·별도 streamer JVM/DB를 사용했다. 전송 전 중단은 새 relay로 활동이 생성되고, 실제 ACK 뒤 PUBLISHED 저장 전 중단은 같은 payload가 새 offset으로 재발행돼도 활동 PK·수량·전체 사실이 유지된다. 소비자의 APPLIED→DUPLICATE와 offset commit을 실제로 확인했다.

재시도를 막은 임시 변이에서 기대한 assertion 실패를 먼저 확인하고 원본을 복원했다. 최종 관련 검사 18개 스위트·101개와 API ArchUnit·test Checkstyle이 통과했으며 실패·오류·skip·lint 위반 0이다. API main Checkstyle과 streamer check는 UP-TO-DATE 결과를 구분한다. 생산 코드·기존 트랜잭션·락은 바꾸지 않았다. **W6 Outbox 없는 두 사건의 보충은 이번 실험과 별개로 남아 있다.**

## 2026-11-04 4단계 W6 누락·정상 소비 사건 복구

사용자는 Q06의 보존된 테스트 전용 W6 직접 발행 구성을 선택했다. [W6 Outbox 보충·복구 기록](w6-outbox-backfill-tdd-log.md)에서 실제 HTTP·PG로 정상 소비/발행 누락 두 주문을 준비하고 같은 자원으로 W7 보충·relay·consumer를 연결했다. 초기 두 사건의 Outbox는 0이며, 준비 C=2/T=2 뒤 복구 중 PG create·lookup·요청 증가 0과 주문·재고·포인트 불변을 확인했다.

내부 `OrderOutboxBackfillService.backfill(orderId)`는 원본 사실을 검증해 없는 Outbox만 추가한다. 기존 행은 사실·목적지 일치를 검사하고 전달 상태를 보존한다. 실제 INSERT 후 rollback·같은 주문의 동시 복구 1행·부족한 사실/충돌 거절도 검증했다. E2E의 누락 사건은 APPLIED로 활동이 생기고, 정상 사건은 DUPLICATE로 기존 PK·필드가 그대로다. 두 번째 보충과 relay 실행도 추가 효과가 없다.

신규 4개 스위트·42개와 이를 포함한 관련 33개 스위트·430개가 실패/오류/skip 0으로 통과했다. API ArchUnit·main/test Checkstyle 통과·lint 위반 0이며 streamer check의 UP-TO-DATE 결과는 새 실행과 구분한다. **다음은 W7 전체 최종 점검이다.** C09·C10 관찰/재개/보존과 C11 최종 범위를 함께 대조하며 제출 완료로 간주하지 않는다.

## 2026-11-06 장애 관찰·재개 검증

[Kafka 장애·재개 기록](outbox-kafka-outage-tdd-log.md)에서 테스트가 소유한 broker만 pause해 실제 producer 실패를 두 번 확인했다. 기존 Outbox PENDING·오류·시도 1→2·재시도 5→10초를 보존하고 실제 JDBC로 건수·가장 오래된 생성 시각을 대조했다. 기한 전 추가 전송은 없고, unpause 후 같은 행을 새 relay가 실제 broker에 전달해 PUBLISHED·시도 3으로 전환했다. PK·eventId·topic·key·payload·createdAt은 그대로다.

최종 실행의 실제 업무 record는 2개이며 성공 metadata와 수신 내용을 대조했다. pause는 TCP 버퍼를 없애지 않으므로 실패한 시도의 늦은 도착도 허용한다. PUBLISHED를 소비 완료로 해석하지 않으며 새 장애 테스트에 PG·streamer를 중복 구현하지 않았다. 기존 두 중단 E2E와 W6 복구 E2E는 관련 회귀에서 실제 재실행했다.

ACK 후 재시도의 PUBLISHED 저장을 건너뛰는 임시 변이는 기대 assertion 실패를 검출했고 생산 파일은 원본 그대로 복원했다. 최종 **22개 스위트·143개**, 실패·오류·skip 0, API ArchUnit·test Checkstyle 통과를 확인했다. main Checkstyle은 UP-TO-DATE이며 streamer 전체 check는 이번 명령에 포함하지 않았다. 생산 코드·기존 TX/락·운영 timeout은 변경하지 않았다. **C10 보존·운영 설명과 C11 전체 검사·공식 11항목의 최종 대조는 남아 있다.**

## 2026-11-06 운영·보존 문서 정리

[실행·재개·보존 안내](outbox-operations-retention.md)에 실제 설정·코드에 맞춘 relay ON/OFF·단일 실행 인계, API local의 스키마 보존 인자, 장애별 같은 행 재개와 소비 listener 중단 후 같은 group/DB 재시작 방법을 정리했다. Outbox PENDING·PUBLISHED·소비 DB 완료와 offset을 구분하고 읽기 전용 SQL·Kafka 관찰 예시를 연결했다.

Outbox·소비 기록의 자동 정리가 없다는 현재 동작과 영구 보존 SLA를 구분했다. Kafka 실효 보존값은 미확인으로 남기고 임의 일수를 정하지 않았다. Kafka에서 미소비 record가 만료돼도 PUBLISHED의 자동 재발행은 없다는 한계와, dedup가 과거 PENDING/복구 가능 범위 및 품목 사실을 함께 보존해야 하는 이유를 설명했다. 새 정책·기능·설정 적용이나 테스트 실행 없이 문서 링크·코드 정합성을 점검했다. **다음은 양 앱 전체 검사와 공식 11항목 최종 대조 한 단계다.**

## 공식 항목별 구현과 실행 증거

| ID | 확인할 내용 | 현재 상태 | 완료 시 연결할 증거 |
| --- | --- | --- | --- |
| W7-C01 | 최초 확정·예약·Outbox 공동 TX | 첫 저장 증분 검증 완료 | [원자성 기록](outbox-atomicity-tdd-log.md): 같은 connection/TX, serialization·실제 INSERT 거절·INSERT 후 실패, 독립 재조회로 전체 rollback |
| W7-C02 | 중복·실패·UNKNOWN의 추가 효과 방지 | 첫 저장 증분 검증 완료 | 비성공/POINT/충돌 Outbox 0, 반복·동시 SUCCESS 1행, PG create 증가 0. 기존 API/결과 적용 회귀 포함 |
| W7-C03 | 기존 주문·시도·예약의 rollback 보존 | 첫 저장 증분 검증 완료 | 실제 INSERT 후 예외에도 기존 미완료 snapshot·HELD 보존, 외부 거래 SUCCESS 유지·같은 거래 GET 재적용 |
| W7-C04 | 저장 사실을 보내는 단일 relay | 2단계 구현·검증 | [relay 기록](outbox-relay-tdd-log.md): 실제 DB/broker의 불변 wire·ACK 뒤 PUBLISHED, due/limit·중첩 방지·timeout/재개 |
| W7-C05 | 두 중단 구간과 relay 재생성 | 전체 흐름 검증 완료 | [중단 E2E 기록](outbox-republication-e2e-tdd-log.md): 같은 자원의 실제 commit/ACK 경계, 상태 덮어쓰기 없이 새 runner로 재개. OS 강제 종료와 구분 |
| W7-C06 | 실제 재발행의 구매 효과 불변 | 전체 흐름 검증 완료 | 같은 사건의 별도 offset 0/1·실제 APPLIED/DUPLICATE·commit 2, 완료 1·활동 2·PK/수량/사실 불변 |
| W7-C07 | W6 누락·정상 소비 두 사례 복구 | 실제 같은 실행 복구 검증 완료 | [W6 복구 기록](w6-outbox-backfill-tdd-log.md): 보존한 W6 직접 경로의 정상/누락 준비→원래 사실로 보충, 누락 생성·기소비 PK/필드 보존·두 번째 복구 불변 |
| W7-C08 | 준비와 복구의 PG 호출·업무 효과 구분 | 실제 기준/증분 검증 완료 | 준비 C=2/T=2. 복구 중 PG create/lookup/요청 증가 0·결제/재고 처리 및 재확인 서비스 호출 0, 거래·주문·예약·재고·포인트 snapshot 불변 |
| W7-C09 | 상태·backlog·실패 관찰과 재개 | 충족 — 실제 장애·재개 및 운영 관찰 확인 | [장애·재개 기록](outbox-kafka-outage-tdd-log.md)의 두 실제 실패·시도/오류/대기량·같은 행 복구, [운영 안내](outbox-operations-retention.md)의 소비 오류·offset·활동 별도 관찰. 늦은 callback 보호는 기존 relay 검사와 연결 |
| W7-C10 | 미발행 보존·단일 실행·재시도 한계 | 충족 — 현재 구현과 보존·운영 설명 대조 | [보존 안내](outbox-operations-retention.md): 자동 정리 없음, Outbox/Kafka/dedup 관계, 실제 Kafka 값 미확인·PUBLISHED 만료 복구 한계·단일 실행 제약 |
| W7-C11 | 관련 회귀·lint·ArchUnit·정리 | 충족 — 양 앱 전체 check 재실행 통과 | [최종 검사 기록](final-verification.md): API 2,091개·streamer 99개, 실패/오류/skip 0, 양 앱 ArchUnit 및 Checkstyle 보고서 12개 위반 0, BUILD SUCCESSFUL·종료 0·소유 컨테이너/자식 JVM 종료 |

## 이어 쓸 코드와 바꿀 책임

경로는 출발점이며 새 파일명·구조를 확정하지 않는다.

| 현재 위치 | W7에서 확인하거나 바꿀 책임 |
| --- | --- |
| [OrderPaymentStockService](../../apps/commerce-api/src/main/java/com/loopers/application/order/OrderPaymentStockService.java) | 공통 결과 적용 TX의 최초 SUCCESS·예약 소비·confirmPgPayment에 Outbox 공동 저장을 연결. serialization/insert 실패를 상위 TX로 전파 |
| [OrderConfirmedEvent](../../apps/commerce-api/src/main/java/com/loopers/domain/order/OrderConfirmedEvent.java) | stable identity·저장 confirmedAt·품목 정규화 유지. 복구에서도 현재 상품을 읽어 과거 사실을 바꾸지 않음 |
| [DomainEventListener](../../apps/commerce-api/src/main/java/com/loopers/application/event/DomainEventListener.java) | BEFORE_COMMIT으로 교체해 같은 TX의 Outbox recorder를 호출. AFTER_COMMIT에 insert를 추가하는 방식과 구분 |
| [DomainEventPublisher](../../apps/commerce-api/src/main/java/com/loopers/domain/event/DomainEventPublisher.java) · [KafkaDomainEventPublisher](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/event/KafkaDomainEventPublisher.java) | W6 보존용 void·callback 경계는 유지. W7은 별도 OutboxSender future·KafkaOutboxSender로 broker 결과를 받고 주문 직접 발행과 병행하지 않음 |
| [DomainEventKafkaConfiguration](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/event/DomainEventKafkaConfiguration.java) · [KafkaEventRoute](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/event/KafkaEventRoute.java) | 기존 topic·key·wire·acks 계약 유지. 저장 JSON을 객체용 serializer에 넣어 JSON 문자열로 이중 인코딩하지 않도록 실제 wire 검증 |
| [PaymentConfirmationConfiguration](../../apps/commerce-api/src/main/java/com/loopers/application/order/PaymentConfirmationConfiguration.java) | 주문 업무 Clock과 경과 시간 예산 구분 유지. Outbox 기록·재시도·발행 확인은 outboxClock, 전송 예산은 monotonic 경과 시간으로 분리 |
| [PurchaseActivityHandler](../../apps/commerce-streamer/src/main/java/com/loopers/application/purchase/PurchaseActivityHandler.java) · [PurchaseActivityTransactions](../../apps/commerce-streamer/src/main/java/com/loopers/application/purchase/PurchaseActivityTransactions.java) | 완료 기록·전체 품목의 공동 TX와 중복/충돌 판정, 실패 TX 종료 뒤 재조회 유지 |
| [OrderActivityKafkaListener](../../apps/commerce-streamer/src/main/java/com/loopers/interfaces/consumer/OrderActivityKafkaListener.java) | 실제 DB 성공 뒤 ack·실패 전파·같은 group 재시작 정책을 W7 재발행에서도 보호 |
| [W6 전체 흐름 테스트](../../apps/commerce-api/src/test/java/com/loopers/application/order/PgOrderPurchaseActivityEndToEndTest.java) · [OwnedStreamerProcess](../../apps/commerce-api/src/test/java/com/loopers/support/kafka/OwnedStreamerProcess.java) | W6 준비에서 W7 복구까지 같은 자원의 수명을 소유하도록 테스트 구성. 기존 @AfterEach 정리 뒤 별도 테스트로 복구하면 같은 실행의 사건을 이어받은 것이 아님 |
| [API schema 디렉터리](../../apps/commerce-api/src/main/resources/db/schema) · [스키마 적용 안내](../week2/commerce-schema-operations.md) | 기존 DB 운용 규칙에 맞는 Outbox 추가 DDL·유일 제약·조회 인덱스·JPA 저장 검증. 사용자 DB에 문서 작업만으로 적용하지 않음 |
| [API 빌드](../../apps/commerce-api/build.gradle.kts) · [streamer 빌드](../../apps/commerce-streamer/build.gradle.kts) | 격리 테스트 연결·검사 범위 유지. API 제품 runtime의 streamer 의존성 없이 실제 consumer를 검증 |

W6의 고객 응답에서 broker 완료를 기다리지 않는 선택과 **W7 relay가 broker 완료를 확인하는 책임**은 구분한다. 고객의 확정 TX 밖에서 relay가 유한하게 기다리거나 결과를 확인하고 상태를 저장해야 한다.

## 구현 전에 선택할 사항

아래는 선택 상태와 후속 결정이다. 같은 TX·불변 payload·단일 relay·broker 확인 후 PUBLISHED·두 중단 실험·복구 중 PG 추가 호출 0은 이미 원문이 정한 조건이므로 다시 선택하지 않는다. 선택의 근거는 [W7 정책](../week2/commerce-policy-decisions.md#w7-outbox-설계의-선택과-질문)을 따른다.

| ID | 선택할 것 | 검토할 비용과 조건 |
| --- | --- | --- |
| W7-Q01 | **구현: 공통 Outbox + 완성된 JSON 문자열 + topic 보관** | outbox_message·eventId 유일 제약·불변 payload/topic/key·outboxClock·due 인덱스. 저장 세부는 정책/스키마 문서 참조 |
| W7-Q02 | **선택: 공통 리스너가 커밋 전 같은 TX에서 기록** | 동기 BEFORE_COMMIT·serialization/저장 예외 전파·공동 rollback. 기존 AFTER_COMMIT 직접 send를 교체. W6 재현은 테스트 전용 listener로 보존. Kafka 결과 포트는 relay에 연결했고 실제 W6 보충·복구도 후속 4단계에서 검증 |
| W7-Q03 | **선택·구현: API 내부 자동 실행·기본 OFF** | 전용 worker·회차 종료 1초 후, 내부 runOnce와 같은 중첩 방지. 지정한 한 인스턴스만 활성화하며 다중 relay 보장 없음 |
| W7-Q04 | **선택·구현: 10건 순차·12초·5→60초** | due 시각/ID 순서, 전송 전 시도 TX, 실패 회차 종료, late callback 무갱신. 운영 초기값과 짧은 테스트 대기 구분 |
| W7-Q05 | **선택·구현: PENDING 유지·재시도** | BLOCKED·자동 삭제 없음. 실제 장애 후 같은 행 재개·오류/backlog SQL을 검증했고 [운영 안내](outbox-operations-retention.md)에 소비 관찰·보존 관계를 정리했다. 새 보존 일수는 선택하지 않음 |
| W7-Q06 | **사용자 선택·검증: 보존한 W6 테스트 구성** | 실제 직접 발행의 정상/누락을 준비한 같은 DB/Kafka/PG에서 보충·재전달. 끝에만 정리하며 운영용 전환 스위치 없음 |

## 권장 진행 순서

1. Q01~Q06의 필요한 선택과 기존 전체 설계의 TX·버드뷰·도메인 관계·대표 흐름을 맞춘다. 구현된 상태와 계획을 구분한다.
2. relay 없이 실제 DB에서 최초 확정·Outbox 원자성과 기존 주문 rollback을 검증한다. 중복·실패·UNKNOWN 및 같은 거래 재적용을 포함한다.
3. 단일 relay의 저장 payload 전송·broker 확인·PUBLISHED 저장·실패/timeout·다음 재시도를 구현하고 DB TX 밖 대기를 확인한다.
4. 두 중단 구간과 새 relay의 재개를 검증하고 실제 주문→broker 중복 record→consumer 구매 활동 불변 E2E를 연결한다.
5. 별도 격리 실행에서 W6 정상·누락 상태를 준비하고 자원 정리 없이 W7으로 이어 두 사례를 복구한다. 준비/복구 PG 호출·사건 사실·두 번째 복구 결과를 기록한다.
6. backlog 관찰·로컬 재개·보존 한계를 정리하고 관련 회귀·정적 검사 결과와 소유 자원 종료를 기록한다.

## 제출 준비

- [ ] Outbox·relay 교체 코드와 원자성·중단·E2E 테스트를 제출한다.
- [x] W6 준비 코드/revision과 W7 복구 연결 방법, 같은 실행의 사건별 결과를 남긴다.
- [x] 설계·정책·TDD 문서를 실제 구현과 맞춘다.
- [ ] PR에 주문→Outbox→broker→구매 활동 결과, 단일 relay 제약·미해결 오류 재개 방법을 적는다.
- [ ] 기술 글에 선택과 이유·중단 실험의 관찰을 설명한다.
- [x] 실제 검사 범위·결과와 자원 정리를 기록한다.
- [ ] 실제 제출 여부를 확인한다.

구현과 필수 실험이 끝났다면 남은 PR·글·revision 표기 같은 제출 기록을 기능 결함처럼 표현하지 않는다. 이전 주에 완료된 W6 revision 연결을 이번 주의 새 미완료 항목으로 되돌리지 않는다.
