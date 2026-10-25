# 주문 결제 회복

생성 응답·callback 유실, 결과 반영 실패와 프로세스 재시작으로 남은 미완료 결제를 다시 확인하는 경로와 복구 한계를 정리한다.
PG 조회는 트랜잭션 밖에서 수행하고, 확인한 관찰은 [결과 반영](order-payment-result.md)에 넘긴다.
새 결제 시도나 PG 생성 재요청은 하지 않는다. 취소·환불·정산 파일 대사는 이번 범위에 포함하지 않는다.

## 결제 결과 재확인

reconcile은 본인 주문의 기존 결제 결과를 PG에 다시 조회하고, 확인한 결과를 반영하는 수동 API다.<br>
웹훅(callback) 누락, 생성 응답 유실 또는 이전 결과 반영 실패로 미완료 상태가 남았을 때 사용한다.

Commerce가 PG 조회 API를 호출한다. 저장된 `transactionKey`가 있으면 단건 조회하고, 없으면 `providerOrderId`로 거래 목록을 조회한다.<br>
PG 조회는 TX 밖에서 수행하며, 조회 결과의 검증과 상태·재고 변경은 [공통 결과 반영 규칙](order-payment-result.md#관찰-반영-규칙)을 따른다.<br>
새 결제 시도를 만들거나 PG 생성 요청을 다시 보내지 않는다.

일반 주문 상세 조회는 저장된 상태를 반환한다. reconcile은 PG를 조회한 뒤 결과를 저장에 반영할 수 있으므로 `POST`로 제공한다.<br>
응답은 [고객 API 계약](../api-contracts.md#포인트주문)을 따른다. 이 API는 주문 한 건을 재확인하는 경로다.<br>
미종결 시도의 자동 확인은 [주기 조회](#주기-조회)가 맡고, 정산 파일 대사는 포함하지 않는다.

reconcile은 저장된 상태와 이번 조회의 불가 여부를 함께 반환한다. `lookupUnavailable: true`는 timeout, 네트워크 오류, 회로 차단, 호출 제한, 전체 조회 시간 초과 등으로 이번 조회를 완료하지 못했다는 뜻이다. 마지막으로 확인한 `PENDING`을 `UNKNOWN`이나 `FAILED`로 바꾸거나 예약을 반환하지 않는다.
`false`는 조회 불가를 관찰하지 않았다는 뜻이며 결제 성공을 뜻하지 않는다. 거래 없음, 결과 반영 rollback, 종결된 시도의 조회 생략도 포함한다.

## 조회 회복 정책

조회 정책은 구현과 비교 실험을 마쳤다. 아래 값은 로컬 실험값이며 운영 임계값으로 확정하지 않았다. 기본 `local`과 `test`에서는 비활성화하고, 조회 회복 정책 네 가지 전체는 `local,pg-time-limit-experiment` 프로필로 실행한다. 모든 실험 프로필은 주기 조회를 끄고 수동 reconcile을 사용한다. 재시작 뒤 주기 조회로 종결하는 검증은 별도의 [주기 조회](#주기-조회) 설정과 테스트를 따른다.
설정은 `src/main/resources/pg-test/*.yml`에 모으고 `application.yml`에서 import한다. [실험 절차와 원본](../../performance/payment-lookup/README.md)을 함께 확인한다.

| 프로필 | 적용 정책 |
| --- | --- |
| `pg-lookup-experiment` | CircuitBreaker, 실제 HTTP 응답 timeout 500ms |
| `pg-retry-experiment` | 위 정책 + 조회 Retry |
| `pg-rate-limit-experiment` | 위 정책 + RateLimiter |
| `pg-time-limit-experiment` | 위 정책 + 전체 조회 TimeLimiter |

| 정책 | 실험값과 집계 기준 |
| --- | --- |
| CircuitBreaker | 두 조회 GET이 `pgLookup` 회로 공유. COUNT_BASED 최근 5회, 최소 5회, 실패율 50%, OPEN 2초, HALF_OPEN 탐색 2회. 대기 뒤 다음 호출이 탐색을 시작한다. |
| 실패 표본 | 재시도를 마친 논리적 조회의 `Unavailable` 또는 전체 시간 초과 1회를 실패로 센다. 거래의 `FAILED`는 정상 조회 결과다. 로컬 호출 제한과 호출자 취소는 성공, 실패 표본 모두에서 제외하고 OPEN 차단은 표본을 추가하지 않는다. 느린 호출은 60초, 100%로 두어 이번 실험에서 실패율 효과를 분리한다. |
| Retry | 최초 포함 최대 3회. 100ms, 200ms 대기에 ±20% jitter. HTTP 502/503/504, 네트워크 오류, 응답 timeout만 재조회한다. 생성 POST, 다른 HTTP 오류, 잘못된 응답, 취소, 로컬 차단은 재시도하지 않는다. |
| RateLimiter | 인스턴스당 1초 주기 20회, 허가 대기 0. 재시도도 실제 HTTP 조회마다 허가를 소비한다. 전체 인스턴스 공통 한도는 아니다. |
| TimeLimiter | 재시도와 대기를 포함한 논리적 조회 전체 900ms. Future 취소와 하위 HTTP 중단을 요청하고, 취소 후 추가 조회를 막는다. 외부 거래를 취소하거나 DB 결과 반영 시간을 제한하는 장치는 아니다. |
| Fallback | 저장된 주문, 결제, 재고를 유지한다. reconcile은 `lookupUnavailable: true`, callback은 기존 `503`으로 조회 불가를 표현한다. 가짜 성공, 포인트 대체, 재고 반환, 새 결제를 만들지 않는다. |

중첩 순서는 `CircuitBreaker → TimeLimiter → Retry → RateLimiter → HTTP`다. create는 모든 조회 정책을 우회하며 자동 재발송하지 않는다. 실험 프로필은 주기 조회를 꺼 추가 표본이 섞이지 않게 한다.

TimeLimiter 활성화 여부와 무관하게 Retry backoff 중 호출자가 interrupt되면 `CANCELLED` 관찰로 반환하고 interrupt 상태를 보존한다. 추가 HTTP 조회를 보내지 않으며 회로의 성공·실패 표본 모두에서 제외한다. [취소 경계 검증](../../performance/payment-lookup/runs/interrupt-20261023/summary.md)

의존성은 `resilience4j-circuitbreaker`, `resilience4j-retry`, `resilience4j-ratelimiter`, `resilience4j-timelimiter`만 선언하고 Resilience4j BOM 2.4.0으로 직접, 전이 모듈 버전을 맞춘다. 직접 decorator를 연결하므로 Spring Boot starter, 자동 Registry와 AOP aspect는 추가하지 않는다. PG 호출 수와 회로 전이는 실험 로그와 테스트 PG 통계로 확인하며, Resilience4j의 Prometheus 지표 연결은 이번 범위에 포함하지 않는다.

## 주기 조회

callback 유실, 생성 응답 유실, 결과 반영 실패와 프로세스 재시작으로 남은 미종결 시도를 시스템이 다시 확인하는 경로다.<br>
재시작 뒤 첫 실행이 재시작 전에 남은 시도를 확인하므로 기동 시점의 별도 스캔은 두지 않는다.

| 항목 | 규칙 |
| --- | --- |
| 실행 | `commerce-api` 안의 고정 간격 스케줄러. PG adapter와 결과 반영 서비스를 그대로 쓰므로 `commerce-batch`로 옮기지 않는다. |
| 대상 | `REQUESTED`·`UNKNOWN`·`PENDING` 시도 중 마지막 상태 변경(`updated_at`) 후 기준 시간이 지났고, 생성 후 조회 대상 기간 안에 있는 것. `last_checked_at` 갱신은 `updated_at`을 바꾸지 않는다. |
| 순서·건수 | `last_checked_at`이 비었거나 오래된 시도부터 한 번에 정해진 건수까지. 조회는 `(status, last_checked_at)` 인덱스를 사용한다. 조회할 때마다 짧은 트랜잭션에서 `last_checked_at`만 갱신해, 결과가 나오지 않는 시도가 다른 시도의 조회를 막지 않게 한다. |
| 처리 | 시도마다 reconcile과 같은 결과 반영 경로를 쓴다. 생성 요청을 보내지 않는다. PG 조회가 실패하거나 결과 반영이 저장 오류·정합성 오류로 rollback된 시도는 그 시도만 건너뛰고 다음 시도를 처리하며, 다음 실행에서 다시 확인한다. 저장 오류와 정합성 오류는 시도·주문 ID와 함께 error 로그를 남긴다. |
| 여러 인스턴스 | 같은 시도를 동시에 조회할 수 있지만 조건부 갱신으로 한 번만 반영한다. 분산 잠금은 두지 않고 중복 조회 비용을 감수한다. |
| 설정 | 간격, 기준 시간, 건수, 조회 대상 기간을 설정으로 둔다. 기본값 예: 1분 간격, 마지막 갱신 후 1분, 100건, 생성 후 24시간. `local`에서는 켜고, `test`에서는 끄고 테스트가 서비스를 직접 호출한다. |

조회 대상 기간이 지난 미종결 시도는 자동 조회에서 빠지고 장기 미확인으로 남는다. 고객 reconcile은 계속 사용할 수 있다.

## 생성 전 거절의 복구 한계

`REJECTED_NO_EFFECT`는 PG에 조회할 거래가 없어서, 반영이 실패한 뒤의 복구는 관찰을 아직 보유하는지에 달려 있다.

- 관찰을 메모리에 보유하면 이전 TX B의 rollback을 확인한 뒤 새 TX B로 재적용한다. PG 생성 요청은 보내지 않으며, 자동 재적용 기준은 [미정 사항](#미정-사항)으로 남긴다.
- 반영 commit 전에 프로세스가 종료돼 관찰까지 유실되면 `REQUESTED`와 조회 0건만으로 거절을 복원할 수 없다. 거절 관찰의 영속 보관과 PG의 거절 이력 조회는 이번 범위가 아니다.
- 이 경우 주문은 미완료, 재고는 차감 상태로 남는다. 조회 0건이나 시간 경과로 실패 확정·반환·재발송하지 않고 [주기 조회](#주기-조회)도 상태를 바꾸지 않는다. 장기 미확인으로 드러내 별도 판단하며, 모든 시도의 자동 종결은 보장하지 않는다.

## 미정 사항

위 조회 회복 정책과 별도로 운영 적용값, 자동 재적용과 알림은 미정이다. 실험 프로필의 값을 기본 프로필에 자동 반영하지 않는다.

- 운영 요청량과 목표 지연에 맞춘 조회 정책값 및 기본 활성화 여부
- 생성 전 거절 관찰을 보유한 경우의 자동 재적용 대상 오류·횟수
- 장기 미확인 알림과 후속 운영 확인 기준

## 검증 기준

실제 adapter와 [로컬 테스트 PG](../external-systems/test-payment-gateway.md)를 연결한다.
재고는 초기 10·주문 수량 2를 기준으로 하며, 트랜잭션 종료 뒤 새 조회 경계에서 상태와 효과를 확인한다.

| 영역 | 상황 | 기대값 |
| --- | --- | --- |
| reconcile | PG 거래 `SUCCESS`, 웹훅 누락으로 `PAYMENT_PENDING` | 주문 확정 `200`, PG 생성·재고 추가 차감 없음 |
| 조회 불가 응답 | 저장된 `PENDING`에서 timeout, 회로 차단, 호출 제한, 전체 시간 초과 | `202`, `lookupUnavailable: true`, `WAITING`과 예약 유지, 생성 추가 없음 |
| 조회 불가와 구분 | 정상 `PENDING` 조회, 결과 반영 rollback, 종결 시도 재확인 | `lookupUnavailable: false`. 각각 상태 유지, rollback 후 재적용 가능, PG 추가 조회 없음 |
| 거절 복구 한계 | 거절 관찰을 보유한 재적용 / 관찰까지 유실된 재시작 | 재고 10으로 반환 1회 / 조회 0건에도 8 유지. 둘 다 생성 추가 없음 |
| 재시작 | `DROP_RESPONSE` 뒤 DB를 재사용해 Commerce를 새 인스턴스로 실행 | 저장된 시도·주문·품목 스냅샷으로 이어서 처리, reconcile 없이 주기 조회만으로 종결, 생성 증가 없음. `ddl-auto: create`는 쓰지 않음 |
| 발송 전 종료 | `REQUESTED`, 거래 0건 | 주기 조회 뒤에도 상태·재고 유지, 조회 대상 기간이 지나면 자동 조회 대상에서 제외 |
| 주기 조회 순서 | 결과가 나오지 않는 시도가 건수 한도보다 많음 | `last_checked_at` 순서로 다른 시도도 조회 |
