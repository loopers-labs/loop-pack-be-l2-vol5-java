# Week 10 모니터링 설계·사고 복구 계획

## 목표

health가 UP인 상태에서 PG 거래는 SUCCESS지만 commerce 주문은 UNKNOWN인 사고를 로컬 합성 환경에서 재현한다. 관측 신호로 원인을 구분하고, 같은 주문을 추가 PG create 없이 복구해 후속 업무 결과까지 대사한다.

이 과제의 중심은 기존 W5/W7/W8/W9 기능을 다시 설계하는 것이 아니라 결제 경계의 **health·metric·로그를 실제 사고 사실에 연결하는 것**이다. 기존 계약과 회귀를 보존한다.

## 책임과 파일 제안

| 책임 | 변경 파일/위치 | 관련 검증 |
| --- | --- | --- |
| 관측 설계와 실행 절차 | `monitoring-design.md`, `runbook.md`, `checklist.md` | 지표 의미·갱신 지연·가설/중단/재진입 절차 일치 |
| 본인 주문 결제 재조정의 누락 경로 | 기존 C-13 계약을 구현하는 `OrderV1Controller`, `OrderV1Dto`, `OrderUseCase`/application service | owner 경계·동일 거래 조회·금액 및 provider order ID 검증·미확정 보존·create 미호출 HTTP 테스트 |
| 업무 metric·경계 로그 | order repository의 미확정 count, 결제 application/adapter 경계의 로그, 신규 gauge component | Prometheus 실제 응답 및 PENDING/UNKNOWN 변화, 구조화 필드, 유한 대기 검증 |
| 최소 사고 fixture와 동일 상태 실험 | 기존 `TossPgFixture`, 신규 `PaymentIncidentRecoveryE2eTest` (week6 E2E source set) | baseline 10, fault 7/3, recovery 10/0; 같은 20개 주문·거래·DB lifecycle |
| 후속 결과 | 같은 E2E에서 Outbox/Kafka/streamer 활동 및 새 ranking snapshot/API | orderId·productId·quantity·confirmedAt, 전체 주간 점수·순위 |
| 제출 증거·기술 글 | 실행별 증거 JSON, 기술 글 | 사용자가 이번 작업에서 생성을 보류함. 체크리스트에 미완료로 기록 |

W8 쿠폰 구현과 문서는 수정하지 않는다. 기존 W5 PG·W7 Outbox·W9 ranking 계약을 복제하지 않고 링크한다.

## 실행 순서

1. `monitoring-design.md`에서 health·metric·로그의 질문과 관찰 범위를 고정한다.
2. 기존 C-13 `/api/v1/orders/{orderId}/payment/reconcile` 계약을 확인한다. 현재 controller/use case 구현이 없는 차이를 메우고 HTTP 테스트를 추가한다.
3. DB count 기반 `commerce.payment.unresolved` gauge를 추가한다. 값은 `PENDING + UNKNOWN`, 갱신 주기는 5초이며 마지막 성공 query 시각 기준 지연을 기록한다.
4. 실제 PG/내부 상태/적용 경계를 `orderId`·`paymentAttemptKey`로 연결하는 구조화 로그를 남긴다. high-cardinality metric label은 두지 않는다.
5. W5 WireMock fixture에 응답 유실 제어와 테스트 원장 관측을 최소 추가한다.
6. 실제 API·DB·격리 Kafka topic을 쓰는 단일 테스트에서 baseline → fault → recovery를 수행한다. 동일 주문 집합, health·Prometheus 응답, PG create/query 수, 예약·Outbox·활동·랭킹을 assertion한다.
7. `runbook.md`에 진단·중단·복구·재진입 절차를 정리한다. 실행별 증거 JSON과 기술 글은 사용자가 작성하지 않도록 요청해 이번 제출 범위에서 보류한다.
8. 신규 시나리오·기존 W5/W8/W9 회귀·영향 모듈 검사 결과와 미확인 위험을 구분한다.

## 커밋 단위

| 순서 | 내용 |
| --- | --- |
| 1 | 모니터링 설계·runbook·계획·체크리스트 |
| 2 | C-13 재조정 경로와 HTTP 테스트 |
| 3 | 미확정 결제 gauge와 경계 상관 로그 |
| 4 | 응답 유실 fixture와 baseline/fault/recovery 연결 테스트 |
| 5 | 체크리스트·검증 결과와 남은 범위 기록 |

각 커밋은 diff와 관련 검증을 확인한 뒤 만든다. 테스트 기대값·기존 계약·검사 규칙을 완화하지 않는다.

## 저장소에서 확인한 선행 사실

- C-13 reconcile은 `docs/week2/api-contract.md`와 `apps/commerce-api/src/main/resources/static/openapi.yaml`에 있지만 현재 controller/use case에 구현이 없다.
- 시작 시 `PendingPaymentRecoveryService`가 PENDING/UNKNOWN 결제를 조회한다. 이번 과제는 scheduler를 추가하지 않는다.
- `TossPgFixture`는 WireMock HTTP fixture이고 기존 거래 저장 원장은 없다. 응답 유실과 조회 결과만 통제하는 최소 합성 기능을 추가한다.
- 관리 endpoint는 `supports/monitoring/src/main/resources/monitoring.yml`에서 health·prometheus만 노출한다. integration test는 `management.server.port=0`과 실제 주입 port를 사용한다.
