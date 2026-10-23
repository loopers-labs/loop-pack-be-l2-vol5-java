# W5 재확인·callback HTTP 연결 TDD 기록

## 범위와 선택 권한

2026-10-23 사용자가 남은 계약 선택과 재확인 API·callback API·통합 동시성 구현을 위임했다. [고객 confirm 연결](customer-payment-api-tdd-log.md)에 이어 기존 단건 PG 조회와 TX B를 실제 두 HTTP 진입점에 연결한다. 실제 카드·상용 PG·사용자 DB·추가 DDL·커밋·PR은 이번 범위가 아니다.

사용자의 후속 속도 요청에 따라 이전 전체 회귀를 반복하지 않고 이번 변경 테스트·핵심 결제 회귀·ArchUnit·Checkstyle로 검사 범위를 제한한다. 기존 테스트·기대값을 삭제하거나 완화하지 않는다. 선행 전체 1,764개 통과는 당시 결과이며 이번 변경의 전체 검사 결과로 재사용하지 않는다.

## 선택한 계약

| 경계 | 선택 | 이유와 미채택 대안 |
| --- | --- | --- |
| 수동 재확인 | 본인 PG 시도의 기존 거래를 최대 1회 조회. 종결이면 HTTP 200, 미완료면 202. 이미 종결한 시도는 PG 없이 저장 결과 반환 | 반복 조회·180초 재대기보다 짧고 예측 가능한 수동 복구. 생성·새 예약·카드/HMAC 비교는 없음 |
| callback | 알림을 저장된 제공자 계정·주문과 연결하고 알림 transactionKey를 고정 PG에서 1회 조회. 검증·적용 뒤 `200 / meta.SUCCESS / data.acknowledged=true` | ACK는 알림 처리 완료이지 결제 성공이 아님. 알림 status는 힌트이며 저장 상태를 직접 바꾸지 않음. 무조건 즉시 ACK하는 방식은 별도 내구성 큐가 없으므로 미채택 |
| 조회 시간·회로 | 기존 조회 전체 10초 한도와 공통 Resilience4j 조회 회로 재사용. retry·sleep·새 scheduler 없음 | PG 호출 횟수·대기를 제한. DB 대기와 최종 처리를 포함한 정확한 10초 응답 보장은 아님 |
| callback 확인 불가 | PG 404·응답 오류·timeout은 UNKNOWN 관찰 후 503 PAYMENT_RESULT_UNAVAILABLE. 회로 차단은 새 관찰 없이 상태 보존 후 같은 503. 종결 상태는 퇴행하지 않음 | 확인하지 못한 알림을 처리 완료로 ACK하지 않음. 외부의 재알림을 보장하지 않으므로 수동 재확인 경로도 제공 |
| PG 비활성화 | 외부 확인이 필요한 복구는 503 PAYMENT_PROVIDER_UNAVAILABLE. 종결한 PG의 고객 재확인과 주문 GET은 유지 | POINT 대체·가짜 성공 금지 |
| 미시작·과거 POINT | 본인 DRAFT 재확인409 PAYMENT_NOT_STARTED, POINT409 ORDER_ALREADY_CONFIRMED. 타인은 주문404 우선 | 존재하지 않는 PG 시도를 만들거나 POINT 주문을 PG로 바꾸지 않음 |
| 잘못된 알림·결과 | 형식400, 저장된 제공자 주문을 찾지 못하면404 PAYMENT_ATTEMPT_NOT_FOUND. 다른 거래·금액·계정·반대 종결은409 PAYMENT_RESULT_CONFLICT | 원래 주문·시도·예약을 보존. callback 응답에 고객 주문 정보를 노출하지 않음 |
| 충돌 기록 | WARN 구조 로그에 내부 orderId·attemptKey와 고정 source/reason만 기록. 요청 원문·외부 식별 문자열·카드·HMAC 미기록 | 과제의 충돌 추적을 최소 변경으로 충족. 새 감사 테이블·outbox는 미도입하며 로그 유실 없는 영구 감사 보장은 아님 |

상세 JSON·메시지·대안은 [API 계약](../week2/commerce-api-contract.md)·[정책 선택](../week2/commerce-policy-decisions.md)을 따른다. callback의 고정 외부 계정은 PG 조회 범위이며 고객 인증을 대체하지 않는다. 운영용 callback 서명·네트워크 제한은 이 로컬 실습의 보안 보장으로 주장하지 않는다.

## 구현 경계

- `OrderPaymentRecoveryController`: 두 HTTP 요청·ACK·200/202 응답. callback의 status는 검증만 하고 업무 결과로 전달하지 않는다.
- `OrderPaymentRecoveryService`: 트랜잭션 밖에서 최대 1회 PG 조회·회로 fallback·오류 구분. 생성 POST를 호출하지 않는다.
- `OrderPaymentQueryService.callbackTarget`: 짧은 read-only TX에서 내부 주문/시도 식별자를 구한 뒤 종료한다.
- `OrderPaymentStockService.inspectCustomerRecovery/inspectRecovery`: 주문 → 시도 잠금으로 현재 저장 상태를 다시 확인한다. callback 대상 탐색과 분리하여 앞서 로딩한 JPA 객체가 최신 커밋을 가리는 위험을 피한다.
- 기존 `apply/markUnknown` TX B: 주문·브랜드·상품·시도의 기존 잠금 순서와 원자적 확정/반환을 그대로 재사용한다. HTTP 중 DB 트랜잭션·락을 유지하지 않는다. 조회 도중 다른 거래가 연결되거나 종결돼도 TX B에서 다시 검증한다.
- `CommerceErrors`·`CommerceWebConfiguration`: 세 복구 오류 및 callback의 공통 JSON 오류 처리. DB 저장 실패를 202/ACK 성공으로 숨기지 않는다.

## Red → Green

| 단계 | 실제 관찰 |
| --- | --- |
| callback 입력 계약 Red | PaymentCallbackRequest 부재로 컴파일 실패. 파서 구현 후 입력 테스트 17개 통과 |
| 실제 HTTP Red | 3개 스위트·34개 실행, 파서17개 통과·새 HTTP17개 실패. 기존 코드에 두 진입점이 없음을 확인 |
| 첫 연결 검사 | 확장된82개 중75개 통과·7개 실패. 테스트 호출자의 Apache 기본 HTTP503 재시도가 callback POST를 다시 보내 one-shot PG 실패 후200을 관찰했다. 로컬5.4.2 소스의 응답 재시도 정책을 확인하고 새 E2E의 요청 클라이언트만 자동 재시도 없는 방식으로 변경. 생산 코드와503·조회1회 기대값은 유지 |
| 최종 관련 Green | 신규106개를 포함한10개 스위트·198개 통과, 실패·오류·skip0. 실제 HTTP 계약·경합·SQL후rollback·복구와 기존confirm/조회 회귀를 함께 확인 |

## 이번 테스트의 핵심 증거

| 테스트 | 증거 |
| --- | --- |
| PaymentCallbackRequestTest · 17개 | strict3필드·외부 상태·길이/Unicode·비노출 입력 경계 |
| PgPaymentRecoveryApiE2ETest · 63개 | 실제 callback/reconcile HTTP와 로컬 PG. 권위 조회·알림 힌트 불신·단건 GET/생성0·미확인·회로 차단·충돌·소유권·HMAC 없이 복구 |
| PgPaymentRecoveryDisabledApiE2ETest · 9개 | PG OFF 미완료503·종결200·POINT/소유권 보존·callback503 |
| PaymentRecoveryConcurrencyIntegrationTest · 13개 | callback-first/reconcile-first, 실제 두 조회 결과를 함께 보류한 동시 적용, 중복 실패 반환1회, callback 선종결 뒤 늦은 생성 응답/timeout 퇴행0, HTTP 중 재고 잠금 미보유, 도중 거래키 선점 재검증 |
| PaymentRecoveryRollbackIntegrationTest · 4개 | 성공/실패 × 두 HTTP 진입점에서 실제 SQL1회 완료 뒤 다음 SQL 실패. 별도 물리 연결로 전체 rollback 확인 후 반대 진입점으로 같은 외부 결과를 한 번 반영 |
| 기존 핵심 회귀 · 91개 + ArchUnit1개 | confirm 활성45·비활성14·최초 요청 조율17·주문 조회15. 기존180/175초·POINT·HMAC·조회 계약과 계층 의존 유지 |

새 동시성 테스트는 latch와 worker의 유한한 기다림·finally 종료를 사용한다. 실제 PG 생성 timeout 경합은 테스트용3초로 단축했으며 운영180초 실험이라고 주장하지 않는다. SQL 실패 테스트는 dirty entity만 확인하지 않고 같은 작성 연결의 실제 변경 행과 독립 연결의 rollback 결과를 대조한다.

## 검사 결과

**2026-10-23 17:30 KST 최종 관련 검사:** 10개 스위트·198개 통과, 실패·오류·skip0. `BUILD SUCCESSFUL`, 1분10초, 종료코드0. ArchUnit1개 통과, commerce-api Checkstyle main/test 보고서2개·위반0. `git diff --check` 통과.

```shell
./gradlew :apps:commerce-api:test \
  --tests '*PaymentRecovery*Test' \
  --tests '*PaymentCallbackRequestTest' \
  --tests '*PgOrderConfirmApiE2ETest' \
  --tests '*PgOrderConfirmDisabledApiE2ETest' \
  --tests '*OrderPaymentConfirmationCoordinatorTest' \
  --tests '*OrderPaymentQueryIntegrationTest' \
  --tests '*ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```

로컬 [테스트 보고서](../../apps/commerce-api/build/reports/tests/test/index.html)는 재실행 시 갱신된다. 세션 임시 로그는 `/private/tmp/w5-payment-recovery.Jzyx9l/`이며 보존을 전제로 하지 않는다. 격리 Testcontainers DB와 로컬 HTTP만 사용하고 사용자 DB·실제 키·기존 데이터는 건드리지 않았다.

속도 요청에 따라 전체 `check`·W4 장시간 측정은 재실행하지 않았다. 이번 결과가 W5 최종9항목 재채점·기술 글·PR 제출의 완료를 뜻하지는 않는다. 실제 운영180초 하드 상한·공개 callback 인증·장기 미확인의 자동 해소도 별도 문제다.
