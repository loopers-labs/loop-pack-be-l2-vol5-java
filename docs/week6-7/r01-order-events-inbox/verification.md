# 주문 이벤트 · 실제 검증 매핑과 한계

초기 예정 클래스 목록을 실제 존재하는 테스트·스크립트 기준으로 정리했다.
아래 매핑은 새 실행 결과가 아니다. 당시 실행 수·시점은 [단계별 결과](../r03-delivery-recovery/result.md)를 따른다.
[이벤트 계약](event-contract.md) · [저장 경계](persistence.md) · [현재 환경](../environment.md)

## 실제 검사 경계

| 실제 근거 | 확인하는 경계 |
|---|---|
| API/Streamer의 OrderConfirmedContractTest | 앱별 DTO·정규화·codec이 공통 order-confirmed-v1.json fixture와 같은 사실을 표현. 정수/UUID/시각/품목·추가 필드 계약 |
| Streamer PurchaseActivityIntegrationTest | 실제 MySQL/Kafka, 009/010 SQL, Inbox·모든 활동의 원자성·동시 중복·충돌·실패 보관 |
| 같은 PurchaseActivityIntegrationTest의 Kafka 사례 | 2-partition에 consumer1→2→3 배정, 실제 업무 commit 후 ack 전 실패 주입·동일 offset 재전달·no-op, 보관 실패 offset 유지와 재개 |
| API의 주문·Outbox 통합 검사 | 확정·Outbox 직렬화/insert 실패 전체 rollback, 중복 결과의 고정 identity, relay 실패·예약·BLOCKED·재개 |
| modules:kafka의 KafkaTestFixtureTest | 실제 broker·2-partition·발행 metadata·수신 연결 기반. 업무 검증과 구분 |
| docker/payment-lab/Test-OrderDeliveryRecovery.ps1 | 보존 W6 revision42d8ed8의 직접 발행 누락/정상 사례를 실제 앱·PG·DB·Kafka로 만든 뒤 같은 실행의 사실을 Outbox로 복구 |

초안의 OrderConfirmedWireContractTest·KafkaGroupAssignmentTest·KafkaDeliveryTest·PurchaseActivityListenerIntegrationTest·
W6PublicationGapReproductionTest는 독립된 현재 클래스명으로 사용하지 않는다. 관련 시나리오는 위 실제 시험과 결과에 매핑한다.
그 이름의 예정 목록이 있었다는 사실만으로 각 세부 기대를 모두 검증했다고 주장하지 않는다.

## 반드시 구분할 보장

- 최초 PG 성공 CAS의 UUID·저장 confirmedAt·품목을 재사용한다. POINT 과거 행의 null UUID를 새 사건으로 소급하지 않는다.
- Inbox와 모든 구매 활동은 같은 TX이며 commit 또는 기존 동일 사실 확인 뒤 ack한다. 사전 조회만으로 동시 중복을 보호하지 않는다.
- 동일 offset 재전달과 같은 eventId의 별도 Kafka record 재발행은 다른 실험이다. send 호출 수나 handler 두 번 호출만 세지 않는다.
- 실패 기록 commit 뒤 offset 진행과 기록 실패 시 offset 유지/중단을 구분한다. 유일 원본 record 키로 보관 중복을 막는다.
- hook/예외 주입을 OS 강제 종료로 표현하지 않는다. 재현 스크립트의 신규 ID와 과거 실험 ID를 혼동하지 않는다.
- 예상 상태·정책 계약과 실제 실행 근거를 구분한다. 지터 난수를 제어한 검증, 모든 경합 순서, 모든 운영 장애를 입증했다고 확장하지 않는다.

## 실행과 한계

현재 PowerShell 명령과 테스트 환경은 [환경 안내](../environment.md#kafka-테스트-fixture와-실행)를 따른다.
과거 검증은 초기 기반1건, R01 관련43/42건, R02 집중45/전체516건, R03전체542건을 각각 구분한다.
서로 중복되는 수치를 합산하거나 현재 쿠폰 포함 전체 테스트 수로 사용하지 않는다.
공개 인증·다중 relay 조율·범용 CDC/Saga·외부 거래 재생성·POINT 전체 소급은 제공하는 보장이 아니다.
실제 운영 복구는 [단건 복구 절차](../r03-delivery-recovery/operations.md)를 읽고 기존 데이터와 자신의 실행 자원을 구분한다.
