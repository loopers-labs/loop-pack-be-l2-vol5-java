# Week 10 구현 계획

## 목표와 완료 기준

실제 commerce API·MySQL 상태를 사용해 health가 UP인 결제 미확정 사고를 합성 PG 거래로 재현한다. 정상 집합 10건, 별도 장애 집합 10건을 같은 테스트 lifecycle에서 baseline → fault → recovery로 처리한다. 장애 집합의 같은 주문 10건을 추가 PG create 없이 복구하고, 재고 예약·Outbox·Kafka 구매 활동·새 주간 랭킹 snapshot/API까지 대사한다.

완료 기준은 [요구사항](requirements.md) 및 [검증 체크리스트](checklist.md)의 모든 항목을 실제 assertion과 실행 결과로 확인하는 것이다. 로컬 통제 실험 결과를 운영 사고나 배포 검증으로 표현하지 않는다.

## 책임·파일·검증 제안

| 책임 | 변경 파일/위치 | 관련 검증 |
| --- | --- | --- |
| 요구사항·용어·도메인/API 계약 | `requirements.md`, `glossary.md`, `domain-rules.yaml`, `domain-relations.md`, `api-contract.md`, `representative-flows.md` | 기존 계약과 주문·결제 상태 전이 일치, 문서 규칙 ID 일관성 |
| 본인 주문 reconcile API 계약 구현 | `apps/commerce-api/.../order/interfaces/OrderV1Controller.java`, `OrderV1Dto.java`, `order/application/OrderUseCase.java`, 필요 시 결제 전용 application service | reconcile HTTP 테스트: 소유권, 성공/실패/미확정, 금액·주문 식별 검증, create 미호출 |
| 실제 경계 관측 | `PendingPaymentRecoveryService`, `OrderPaymentTransactionService`, 결제 repository/query 및 신규 metric component | gauge DB값·주기 갱신, 구조화 로그 상관 필드, 실제 Actuator Prometheus 조회 |
| 최소 응답 유실 fixture | `TossPgFixture`와 adapter 관련 테스트 지원 | 저장된 SUCCESS 거래, 생성 응답 timeout/reset, create 시도 수, 동일 금액·계정·주문 조회 |
| baseline/fault/recovery 통합 시나리오 | `apps/commerce-api/src/week6E2eTest/java/com/loopers/week10/CheckoutIncidentTest.java` 및 테스트 task 설정 | 동일 DB·PG 상태, 10/10·7/3·10/0, 동일 장애 ID, 추가 create 0, 예약/Outbox/Kafka/활동/랭킹 assertion |
| 재현 절차와 증거 | `runbook.md`, `evidence/`, `technical-writing.md` | health/Prometheus 원문, fault 원문 hash, 가설 비교, 복구 전후 ID와 효과 대사 |
| 누적 회귀와 품질 검사 | 기존 결제·쿠폰·랭킹 테스트 및 API/streamer/batch 관련 검사 | 신규 통합 테스트, 기존 회귀, checkstyle/lint/ArchUnit 결과를 실행 기록에 구분 |

W8 쿠폰 발급·본인 목록 코드는 변경하지 않는다. W5 조회·응답 검증·재고 예약·중복 방지 의미를 약화하지 않는다. 테스트에서 자동 재조회를 보류하는 제어는 테스트 경계에서만 사용한다.

## 작업 순서

1. 요구사항 → 용어집 → 도메인 규칙 → 도메인 관계 → API 계약 → 대표 흐름을 고정한다.
2. C-13 본인 주문 reconcile의 누락 구현을 기존 API 계약대로 완성하고 HTTP 경계 테스트를 추가한다.
3. `PENDING + UNKNOWN` 현재 주문 수 gauge를 DB 조회 결과로 5초마다 갱신하고, 주문/attempt 상관 로그를 결제 경계에 남긴다.
4. W5 fixture에 거래 저장 후 응답 유실을 제어하는 최소 기능과 생성·조회 원장을 추가한다.
5. 실제 MySQL과 격리 Kafka topic을 사용하는 단일 사고 연결 테스트를 작성한다. 이 테스트는 사용 가능한 동적 management port를 주입받고 health·Prometheus 원문을 저장한다.
6. runbook, 실행별 증거, 기술 글을 완성하고 요구사항별 완료/미확인 결과를 기록한다.
7. 신규 시나리오와 지정된 W5/W8/W9 회귀, 변경 모듈의 check/lint/ArchUnit를 실행한다. 테스트 0건·skip·인프라 미기동은 성공으로 기록하지 않는다.

## 커밋 단위

| 순서 | 커밋 내용 | 검토 단위 |
| --- | --- | --- |
| 1 | week10 요구사항·용어집·도메인/API 계약·구현 계획 | 계약 기준선 |
| 2 | 본인 주문 reconcile API 구현과 HTTP 테스트 | 주문 소유권과 결과 반영 경계 |
| 3 | 미확인 결제 gauge와 상관 구조화 로그 | 관측 동작 및 갱신 지연 |
| 4 | 응답 유실 fixture 및 baseline/fault/recovery 연결 테스트 | 같은 상태의 사고·복구와 후속 결과 |
| 5 | runbook·실행 증거·기술 글·검증 결과 | 재현 가능성과 제출 자료 |

각 커밋 전 diff와 해당 변경에 직접 연결된 검증 결과를 확인한다. 테스트 기대값·규칙·기존 계약을 줄여 통과시키지 않는다.

## 현재 저장소에서 확인한 차이

- `docs/week2/api-contract.md`와 `apps/commerce-api/src/main/resources/static/openapi.yaml`에는 `POST /api/v1/orders/{orderId}/payment/reconcile`가 있지만 실제 controller/use case는 아직 없다.
- 현재 시작 시 복구 listener와 batch 대사 구성은 존재한다. 신규 scheduler는 만들지 않고, 수동 reconcile 계약의 누락 구현을 추가한다.
- 기존 `TossPgFixture`는 WireMock 기반 HTTP 계약 fixture이며 상태 조회용 저장 원장은 없다. 이번 fixture 변경은 사고 시나리오에 필요한 SUCCESS 거래 상태와 create 수만 추적한다.
- 현재 health/Prometheus 노출은 `supports/monitoring`의 제한된 설정을 따른다. 테스트 관리 port는 동적으로 선택한다.
