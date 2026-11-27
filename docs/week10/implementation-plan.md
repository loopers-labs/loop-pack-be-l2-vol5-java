# 10주차 구현 계획 — 결제 사고 관측·진단·복구

작성 기준: `docs/week10/reference/learning.md`, `practice.md`, `reference/implementation-plan.md`(요구 프롬프트) 및 현재 작업 트리(`applejeong/volume-9`) 실제 코드.

이 문서는 단계별 계획과 기존 실행 기록을 담는다.

현재 S1~S4 관측 계약, S5~S7에서 사용할 진단·복구 판정 기준 및 계획 초안과의 차이는 [monitoring-design.md](monitoring-design.md)를 참조한다.

---

## 1. 과제 핵심 요구사항

| # | 요구 | 성질 |
| --- | --- | --- |
| R1 | health 는 UP 인데 특정 주문이 미확인으로 남는 상황을 통제된 실험으로 재현 | 필수 |
| R2 | 업무 지표 1개 이상(미확인 결제 수) + 주문 단위 경계 구조화 로그 | 필수 |
| R3 | 응답 유실 장애 fixture(거래는 저장, 생성 응답만 유실, 주문 단위 ON/OFF, create 호출 수 기록) | 필수 |
| R4 | 한 테스트 lifecycle 로 BASELINE → FAULT → 증거 보존 → RECOVERY, 중간 DB·PG 초기화 금지 | 필수 |
| R5 | 경쟁 가설 3개의 예상·실제·판정 기록, 진단 조회는 읽기 전용 | 필수 |
| R6 | 같은 주문·같은 거래를 기존 reconcile 로 복구, create 추가 0, 불변 조건 assert | 필수 |
| R7 | 복구 주문의 Outbox → Kafka → 구매 활동 → 새 주간 snapshot → 랭킹 API 연결 | 필수 |
| R8 | 실행별 증거 디렉터리 보존(fault 원문 덮어쓰기 금지) | 필수 |
| R9 | `docs/runbook/week10.md` | 필수 |
| R10 | 누적 회귀·Checkstyle·ArchUnit 결과와 미검증 위험 구분 | 필수 |
| R11 | 분산 추적(trace 수집·backend) | 선택, 이번엔 **하지 않음** |

기대 수치(practice.md §1 표)는 baseline 10/0/10, fault 7/3/10(누적 거래 20), recovery 10/0/10(누적 거래 20, 복구 중 create 증가 0).

---

## 1-1. 확정 사항 (사용자 승인)

| 항목 | 확정 | 근거 |
| --- | --- | --- |
| 업무 지표 모양 | `commerce.payment.unresolved`, 태그 `state` ∈ `{requested, pending, unknown}` 3개 | UNKNOWN 포함 여부 모호함이 없고 합계가 미종결 전체. label 은 유한 3개로 고정 |
| 지표 수집 방식 | 스케줄러가 집계해 메모리 스냅샷 교체, Gauge 는 스냅샷만 읽음. 기본 `taskScheduler` 재사용, `fixedDelay` | scrape 와 DB 집계를 분리한다. 전용 스레드 풀·스케줄링 config 는 만들지 않는다 |
| 미종결 지속 시간 | 포함. 기준 시각은 `updated_at` 이 아니라 **`created_at`** | `recordConflict` 가 상태를 안 바꾸면서 `updated_at` 을 갱신하므로 상태 진입 시각이 아니다. 스키마는 바꾸지 않는다 |
| 최신성 노출 | 경과 초가 아니라 **마지막 집계 성공 Unix timestamp** Gauge. 경과는 PromQL `time() - ts` | Prometheus 관례이고, 애플리케이션이 시간을 계산해 노출하지 않는다 |
| S1 반환 타입 | `Map<Status, Long>` → `List<UnresolvedStatusSummary>` record | 상태별로 건수와 최소 시각 두 값을 내보내야 하므로 맵으로 모양이 맞지 않는다. 쿼리는 1회 유지 |
| 미종결 집계 범위 | `SUCCESS`·`FAILED` 는 집계에서 **제외**. 결과에 없는 상태는 **0건**으로 채운다 | 종결 상태는 미확인이 아니다. 0건을 누락이 아니라 0으로 보고해야 지표가 끊기지 않는다 |
| 집계 방식 | 가능한 한 **집계 쿼리 1회**(`GROUP BY status`)로 세 값을 모두 얻는다 | 상태마다 count 쿼리를 날리면 같은 시점의 값이 아니게 되고 왕복이 3배가 된다 |
| 랭킹 revision | r1 은 **복구 이전의 실제 구매 활동 17건**을 처리한 뒤 공개, r2 에서 20점 확인 | 공개된 snapshot 이 늦은 사실로 바뀌지 않음과 새 revision 이 복구를 반영함을 같은 테스트에서 보인다 |
| 증거 위치 | `build/incident-evidence/<runId>/` 에 쌓고 제출분 1회만 `docs/week10/evidence/` 로 복사 | 실패한 실행으로 저장소가 오염되지 않는다 |
| 참고 문서 활용 | 외부 Runbook 예시는 **분량·분기 구조의 목표치**로만 사용하고, 테이블·컬럼·지표·로그 이름은 이 저장소 기준으로 새로 쓴다 | 객체명 일치는 요구되지 않음. 베끼면 존재하지 않는 스키마가 된다 |

---

## 2. 현재 구현 상태

### 2-1. 이미 있어서 그대로 쓸 것 (재사용)

| 요구 | 기존 자산 | 비고 |
| --- | --- | --- |
| 결제 상태 모델 | `domain/payment/PaymentAttempt` — `Status{REQUESTED, PENDING, UNKNOWN, SUCCESS, FAILED}`, `attemptKey`, `providerOrderId`, `transactionKey`, `conflictReason` | **UNKNOWN 상태가 이미 1급 개념**이다. 새 상태를 만들 필요가 없다 |
| 응답 유실 → UNKNOWN 기록 | `HttpPaymentGateway.create` 가 `RestClientException` 을 `PaymentCreateResult.unknown()` 으로 변환 → `OrderFacade.applyCreateResult` → `PaymentResultService.recordUnknown` → `markUnknownIfRequested`(WHERE status='REQUESTED') | 장애 경로가 **이미 제품 코드에 있다**. 실험은 PG 쪽만 조작하면 된다 |
| reconcile API | `POST /api/v1/orders/{orderId}/payment/reconcile` → `PaymentV1Controller.reconcile` → `PaymentFacade.reconcile` | 본인 주문만(`order.isOwnedBy` 아니면 404), 종결 시도면 PG 호출 0, `findAllByOrderId(providerOrderId)` 권위 조회 |
| 거래 검증·중단 조건 | `PaymentFacade.conflictOf` — MULTIPLE_TRANSACTIONS / ORDER_MISMATCH / TRANSACTION_KEY_MISSING / AMOUNT_MISMATCH, PENDING 은 `recordPending` 만 | 요구 R6 의 "임의 성공 금지"가 이미 구현돼 있다 |
| 1회 적용 보장 | `settleIfOpen`(WHERE status IN REQUESTED/PENDING/UNKNOWN), `finishIfPaymentPending`(WHERE PAYMENT_PENDING), `consumeIfReserved`/`releaseIfReserved`(WHERE RESERVED) | 중복 callback·중복 reconcile 수렴을 조건부 UPDATE 로 보장 |
| 재고 예약 | `domain/product/StockReservation{RESERVED, CONSUMED, RELEASED}`, 예약은 TX A, 소비/반환은 TX B | UNKNOWN 동안 RESERVED 유지, 성공 시 추가 차감 없음(`consumeIfReserved` 만) |
| Outbox 원자성 | `PaymentResultService.applyFinal` 이 주문 CONFIRMED 와 같은 TX 에서 `order_confirmed_outbox` PENDING insert | 복구가 확정을 만들면 Outbox 사실도 자동으로 생긴다 |
| relay·Kafka·소비 | `OrderConfirmedOutboxRelay.runOnce()`, `KafkaOutboxMessageSender`, `order-events` 토픽, `OrderConfirmedListener`, `PurchaseActivityService.consume` + `processed_order_confirmation` 장부 | 테스트 profile 은 relay scheduler OFF 이므로 테스트가 `runOnce()` 를 직접 부른다 |
| 구매 활동 멱등 | `product_purchase_activity` UK `(order_id, product_id)` + 장부 UK `(event_id)`,`(order_id)` | 발행 재시도 수와 활동 수가 분리된다 |
| 주간 랭킹 | `weeklyRanking` Job(Step1 freeze → Step2 score → Step3 open), `WeeklySnapshotId weekly-YYYYMMDD-rN`, `weekly_ranking_publications` 포인터 조건부 UPDATE, `GET /api/v1/rankings/weekly` | revision·frozen input 정책 그대로 사용 |
| 주간 소속 기준 | Step1 freeze SQL 이 `product_purchase_activity.confirmed_at` 의 반열림 구간 `[periodStart, +7d)` | confirmedAt 은 `PaymentResultService.applyFinal` 에서 주입된 `Clock` 으로 1회 생성 → **테스트가 Clock 을 고정해 주 소속을 통제할 수 있다** |
| 교차 모듈 테스트 기반 | `apps/commerce-api` 의 `crossAppTest` source set(streamer + batch 를 같은 JVM 에), `CrossAppTestConfig`, 선례 `WeeklyRankingFlowE2ETest` | 사고 연결 테스트가 들어갈 자리가 이미 있다 |
| 로컬 PG HTTP fixture | `src/test/.../payment/TestPaymentPgServer` — JDK `HttpServer`, 랜덤 포트, 요청 전수 기록, `createCount()`, `holdResponses()`/`releaseResponses()` 래치 | 응답 보류(=timeout) 수단이 이미 있다 |
| Actuator·Prometheus | `supports/monitoring/monitoring.yml` — health(+liveness/readiness) 와 prometheus 만 노출, management port 8081, `AdminBoundaryConfig` 는 permitAll | 의존·설정 추가 불필요 |
| Micrometer 관례 | `application/coupon/CouponAdmissionMetrics` — 점 구분 이름 + `result`/`reason` 태그, 태그 값 snake_case, Gauge 등록 방식 | 새 지표는 이 선례를 그대로 따른다 |

### 2-2. 없어서 추가해야 할 것 (gap)

| gap | 근거 |
| --- | --- |
| G1. 미확인 결제 업무 지표 | Micrometer 사용처는 쿠폰·상품 캐시뿐. 결제 지표 0개 |
| G2. 결제 경계 구조화 로그 | `MDC` 사용 0건, 상관관계 필터 0건. `HttpPaymentGateway` 는 카드번호 노출 방지를 위해 **의도적으로 로그가 없다**. `PaymentResultService`/`PaymentFacade` 도 로그 없음 |
| G3. 거래를 실제로 보관하는 장애 fixture | `TestPaymentPgServer` 는 **정해진 응답만** 돌려주고 거래를 만들지 않는다. "거래 SUCCESS 저장 후 응답 유실"을 표현할 수 없다 |
| G4. 사고 연결 테스트 | `CheckoutIncident*` 류 테스트 없음 |
| G5. 증거 기록 | 증거 직렬화 코드·디렉터리 없음 |
| G6. Runbook | `docs/runbook/` 디렉터리 자체가 없음 |
| G7. 미확인 건수 count 쿼리 | `PaymentAttemptRepository` 에 count 메서드 없음(`OrderConfirmedOutboxRepository.countPending()` 선례만 있음) |

### 2-3. 수정이 필요한 기존 파일

| 파일 | 수정 이유 | 범위 |
| --- | --- | --- |
| `domain/payment/PaymentAttemptRepository` + `infrastructure/payment/PaymentAttemptRepositoryImpl` | G7. 상태별 미종결 건수 count 추가 | 메서드 1개 |
| `infrastructure/payment/HttpPaymentGateway` | G2. create/lookup 결과 경계 로그 | 로그 3~4줄 |
| `domain/payment/PaymentResultService` | G2. 적용 결과(settled 0/1, 최초 확정 여부) 로그 | 로그 2~3줄 |
| `application/payment/PaymentFacade` | G2. callback·reconcile 진입과 판정 로그 | 로그 3~4줄 |
| `src/test/.../payment/TestPaymentPgServer` | G3. 거래 보관 모드 + 주문 단위 응답 유실 제어 추가(기존 정해진 응답 API 는 보존) | 기존 계약 비파괴 |
| `src/test/.../payment/FakePaymentGatewayConfig` | 사고 테스트에서 `@Primary` 가짜 PG 를 비활성화해야 실제 adapter 가 쓰인다 | `@ConditionalOnProperty` 1줄 |

---

## 3. 설계

### 3-1. 업무 지표 (R2 / G1)

- **이름·태그**: `commerce.payment.unresolved`, 태그 `state` ∈ `{requested, pending, unknown}` 3개 고정.
  - 유한한 상태 집합만 label 로 쓴다. `orderId`·`userId`·`traceId`·`attemptKey` 는 label 로 쓰지 않는다.
  - UNKNOWN 만 세지 않고 state 로 분리하므로 "UNKNOWN 포함 여부" 모호함이 없다. 합계가 곧 미종결 전체다.
- **관찰 범위**: 전체 계정의 `payment_attempts` 중 `status IN ('REQUESTED','PENDING','UNKNOWN')`. 소프트 삭제·사용자 범위 필터 없음.
- **갱신 방식**: **스케줄러가 주기적으로 집계해 메모리 스냅샷을 교체**하고, Gauge 는 그 스냅샷만 읽는다. Prometheus 의 scrape 와 DB 집계를 완전히 분리한다. 비교와 선택 근거는 3-1-2 에 있다.
- 지표는 **지연된 근사**임을 문서와 runbook 에 명시한다. 특정 주문을 찾는 데는 쓰지 않는다(그건 로그·DB 조회의 일).
- **집계 규약**: repository 는 `status IN ('REQUESTED','PENDING','UNKNOWN') GROUP BY status` 한 번으로 상태별 건수와 가장 오래된 `created_at` 을 함께 돌려준다. `SUCCESS`·`FAILED` 는 WHERE 에서 제외한다. 행이 없는 상태도 **항상 포함**해 `count = 0`, `oldestCreatedAt = null` 로 채운다. 반환 타입은 `List<UnresolvedStatusSummary>` 다(3-1-2 참조). Gauge 가 "값 없음"으로 사라지면 운영자가 0 건과 수집 실패를 구분할 수 없다.
- **구현 위치**: `apps/commerce-api/src/main/java/com/loopers/application/payment/PaymentUnresolvedMetrics.java`.
  - `CouponAdmissionMetrics` 가 `application` 계층에 있는 선례를 따른다. ArchUnit 규칙(application → interfaces/infrastructure 금지)과 충돌하지 않는다.
  - `MeterRegistry` + `PaymentAttemptRepository` 주입, 생성자에서 state 별 `Gauge.builder` 3개 등록.
- **검증**: `MeterRegistry` 직접 assert(기존 관례) **와** 실제 `/actuator/prometheus` 본문 모두 확인.
  - 주의: `monitoring.yml` 의 `management.server.port: 8081` 에 test profile 오버라이드가 없다. `RANDOM_PORT` 테스트에서도 관리 포트는 8081 고정이라 포트 충돌 위험이 있다. 사고 테스트는 `management.server.port=0` 을 properties 로 주고 `LocalManagementPort` 로 실제 주입된 포트를 읽는다(practice.md 의 "8081이 항상 비어 있다고 가정하지 않는다" 요구와 일치).

### 3-1-1. 집계 쿼리의 부하와 인덱스 판단 (S1 사후 기록)

**측정한 것과 예상한 것을 구분한다.**

실제로 실행한 것: 로컬 MySQL 8.0(`docker-mysql-1`)의 `loopers` 스키마에서 `payment_attempts` 의 `SHOW CREATE TABLE` 과 `EXPLAIN` 을 떴다. 현재 이 테이블의 행 수는 0 이므로 EXPLAIN 은 "쓸 수 있는 인덱스가 없다"만 확인해 준다.
이어서 같은 구조의 probe 테이블(`pa_probe`)에 5만 행을 넣고 인덱스 유무를 비교 측정한 뒤 테이블을 삭제했다. 분포는 SUCCESS 49,500 / PENDING 450 / UNKNOWN 50 / REQUESTED 0 이다.

| 조건 | EXPLAIN | 측정 소요 |
| --- | --- | --- |
| `status` 인덱스 없음 | `type=ALL`, `key=NULL`, `rows=50000`, `Extra: Using where; Using temporary` | 29.7 ms |
| `status` 인덱스 있음 | `type=range`, `key=idx_probe_status`, `key_len=2`, `rows=501`, `Extra: Using where; Using index`(커버링) | 0.42 ms |

같은 측정에서 얻은 부수 사실 두 가지도 기록한다. 5만 행에서 `status` 단일 인덱스의 크기는 약 1.6 MB(데이터 4.7 MB 대비)였다. 그리고 결과에 `REQUESTED` 키가 아예 없었다. **0 건 채우기가 실제 분포에서 반드시 필요하다는 근거**다.

**추가 부하는 있다.** 앞서 "기존 `findAllOpenDueForLookup` 도 전체 스캔하니 새 부담이 아니다"라고 적었던 것은 잘못된 근거였다. 기존 쿼리가 비효율적이라는 사실은 새 쿼리의 비용을 면제해 주지 않는다. 이 집계는 **실행 빈도에 비례해 DB 부하를 새로 더한다.**

| 행 수 | 1회 비용(측정/추정) | 10초 주기 대비 실행 시간 비율 | 판단 |
| --- | --- | --- | --- |
| 0 ~ 수천 | 1 ms 미만 (추정) | 무시 가능 | 인덱스 불필요 |
| 5만 | 29.7 ms (측정) | 약 0.3 % | 허용 |
| 50만 | 약 300 ms (선형 추정, **미측정**) | 약 3 % | 인덱스 추가 검토 |

50만 행 수치는 전체 스캔이 행 수에 선형이라는 가정에서 나온 **추정**이며 측정하지 않았다. 버퍼 풀에 다 들어가지 않으면 더 나빠질 수 있다.

위 비율은 **"쿼리 실행 시간 ÷ 집계 주기"일 뿐이고 DB 자원 점유율이 아니다.** CPU·IO·버퍼 풀·락 경합을 측정한 값이 아니며, 같은 DB 를 쓰는 다른 작업과의 간섭도 포함하지 않는다. 한 인스턴스가 10초에 한 번 30 ms 동안 쿼리를 돌린다는 사실만 뜻한다.

**인덱스를 추가하지 않는 결정(유지)과 그 근거**

- **현재 데이터 규모**: `payment_attempts` 는 PG 결제를 시작한 주문당 1행이고 지금 0행이다. 과제 실험에서도 20행 수준이다. 전체 스캔 비용이 1 ms 미만이다.
- **집계 빈도**: 스냅샷 갱신 주기 1회당 쿼리 1회다. scrape 마다 때리지 않으므로 빈도가 낮다(3-1-2 참조).
- **데이터 분포**: 미종결은 소수(측정 분포에서 1 %)이고 종결이 대다수다. 즉 인덱스가 있으면 선택도가 매우 높아 효과가 크다. 지금 안 만드는 이유는 효과가 없어서가 아니라 **지금 규모에서 절대 비용이 작아서**다.
- **인덱스 유지 비용**: `status` 는 수명 주기에서 `REQUESTED → PENDING → SUCCESS` 처럼 2~3회 바뀐다. 보조 인덱스를 두면 주문 1건마다 insert 1회와 UPDATE 2~3회가 추가로 인덱스를 갱신한다. 결제 쓰기 경로는 사용자 대기 구간이므로, 읽기 1회를 위해 쓰기 쪽에 비용을 옮기는 거래가 된다.
  - **측정한 것**: 5만 행에서 인덱스 크기 약 1.6 MB, 인덱스 생성(`ALTER TABLE ... ADD INDEX`) 241 ms.
  - **측정하지 않은 것(일반적 예상)**: 상태 전이 UPDATE 1회가 보조 인덱스 때문에 더 쓰는 비용. 보조 인덱스 갱신이 쓰기에 비용을 더한다는 것은 InnoDB 의 일반적 성질이고, 이 저장소에서 쓰기 지연을 측정해 비교한 적은 없다.
- **범위**: 인덱스 추가는 스키마 변경이고 `ddl-auto` 로 관리되는 테이블이라 S1 범위를 넘는다.

**재검토 트리거(잠정)**: 행 수가 10만을 넘거나 1회 집계가 50 ms 를 넘으면 `status` 단일 인덱스 도입을 **검토**한다. 이 숫자는 운영 SLO 나 승인된 성능 기준이 아니라, 위 로컬 측정에서 뽑은 **잠정 기준**이다. 실제 도입 판단은 그때의 운영 지표(쓰기 지연, DB 부하, 집계 지연)를 근거로 다시 한다. runbook 에는 이 성격을 함께 적는다.

### 3-1-2. 지표 수집 방식과 최종안 (S2 설계)

**최종안: 스케줄러 기반 메모리 스냅샷.** scrape 가 DB 를 때리지 않는다.

| 항목 | TTL 캐시 (scrape 가 유발) | 스케줄러 스냅샷 (채택) |
| --- | --- | --- |
| scrape 와 DB 결합 | 결합됨. 외부 요청 빈도가 DB 부하를 조종한다 | 분리됨. DB 집계 횟수가 scrape 수와 무관하다 |
| scrape 지연 | 캐시 만료 시 scrape 가 집계를 기다린다 | scrape 는 메모리 읽기다 |
| DB 조회 실패 | scrape 스레드에서 예외가 나며 `/actuator/prometheus` 응답을 깨뜨릴 수 있다 | 스케줄러 스레드에 격리된다. scrape 는 마지막 성공값을 돌려준다 |
| 값의 시점 | 세 Gauge 가 각자 캐시를 보면 시점이 섞인다 | 한 번의 집계에서 나온 한 스냅샷이다 |
| 추가 비용 | 클래스 1개 | 클래스 1개 + `@Scheduled` 메서드 1개 + property |
| 최신성 관측 | 노출이 번거롭다 | 마지막 성공 시각·성공/실패 횟수를 자연스럽게 노출한다 |

**기존 스케줄러 재사용 (실제 코드 확인)**

- `CommerceApiApplication` 에 `@EnableScheduling` 이 이미 있다.
- `config/scheduling/OutboxRelaySchedulingConfig` 가 `@Primary` 기본 `taskScheduler` 를 살려 둔다. **새 `TaskScheduler` 빈·스케줄링 config 를 만들지 않는다.**
- `interfaces/scheduler/PaymentReconcileScheduler` 가 property 로 켜고 끄는 단순 `@Scheduled` 선례다. 같은 모양만 따른다.
- 늘어나는 것은 스케줄러 클래스 1개와 property 2개다.

**fixedDelay 를 쓴다 (fixedRate 아님)**

- `fixedDelay` 는 **이전 집계가 끝난 시점부터** 간격을 센다. 그래서 집계가 느려져도 다음 집계 사이에 일정한 대기 시간이 보장된다.
- `fixedRate` 는 시작 시점 기준으로 주기를 맞추려 하므로, 집계가 주기보다 오래 걸리면 **밀린 실행이 연이어 따라붙는다.** 기본 스케줄러를 `PaymentReconcileScheduler`(PG 호출 최대 3초)·`CouponAdmissionScheduler` 와 공유하는 상황에서 그 연속 실행은 다른 업무의 tick 을 더 밀어낸다.
- 정정 기록: 앞서 `fixedRate` 가 "작업을 큐에 쌓거나 중복 실행한다"고 적었던 것은 정확하지 않다. 단일 스레드 스케줄러에서 같은 `@Scheduled` 메서드가 동시에 두 번 실행되지는 않는다. 차이는 **느려진 뒤의 간격 보장**이다.
- 집계가 느려지면 주기가 자연히 늘어나고, 낡음은 마지막 집계 성공 시각으로 드러난다.
- 기존 `OutboxRelayScheduler`·`PaymentReconcileScheduler` 도 `fixedDelay` 다. 컨벤션이 일치한다.

**공유 스케줄러와 timeout (실제 설정 확인)**

- `modules/jpa/src/main/resources/jpa.yml` 에 **쿼리 timeout 설정이 없다.** `jakarta.persistence.query.timeout` 도 `hibernate.query.timeout` 도 없다. 있는 것은 Hikari 의 `connection-timeout: 3000`(커넥션 **획득** 대기), `validation-timeout: 5000`, `initialization-fail-timeout: 1` 뿐이다.
- 즉 커넥션을 못 얻으면 3초 안에 실패하지만, **커넥션을 얻은 뒤의 느린 쿼리는 시간 제한이 없다.** 집계가 오래 걸리면 공유 스케줄러 스레드를 그만큼 점유하고 `PaymentReconcileScheduler`·`CouponAdmissionScheduler` 의 tick 이 밀린다.
**집계 쿼리에만 timeout 을 걸 수 있는가 (검토 결과)**

기술적으로 가능하다. 실제 의존성을 확인했다.

- QueryDSL `5.1.0` 의 `AbstractJPAQuery` 에 `setHint(String, Object)` 가 있다(jar 에서 확인). 그래서 이 집계 쿼리 한 건에만 `jakarta.persistence.query.timeout` 힌트를 붙일 수 있다. 전역 설정을 건드리지 않는다.
- Hibernate 는 그 힌트를 JDBC `Statement.setQueryTimeout` 으로 넘긴다. **JDBC 의 단위는 초**이므로 실효 해상도가 초 단위다. 밀리초 단위의 촘촘한 제한은 걸 수 없다.
- MySQL Connector/J 는 `setQueryTimeout` 을 별도 감시 스레드가 `KILL QUERY` 를 보내는 방식으로 구현한다. 즉 timeout 이 걸리면 **추가 커넥션을 쓴다.**

**이번 과제에서는 추가하지 않고 운영 개선 항목으로 남긴다.** 근거는 네 가지다.

1. **정할 근거가 없다.** 지금 테이블은 0 행이고 5만 행에서도 29.7 ms 다. 1초든 3초든 측정 근거 없이 고른 숫자가 된다. 임계를 추측으로 박는 것은 이 과제의 원칙에 어긋난다.
2. **막고 싶은 사고와 어긋난다.** 걱정한 상황은 "DB 가 장시간 응답하지 않음"인데, 그때는 결제 조회·쿠폰 스케줄러·사용자 요청도 함께 막힌다. 지표 쿼리만 끊어도 공유 스레드 외의 문제는 남는다.
3. **이미 영향이 제한돼 있다.** `fixedDelay` 라 느린 집계가 연속 실행을 부르지 않고, 집계는 락을 잡지 않는 읽기 1회이며, 결과는 "지표가 낡는다"이고 그 낡음은 마지막 성공 시각으로 관측된다.
4. **비용이 공짜가 아니다.** Connector/J 의 timeout 은 추가 커넥션으로 `KILL QUERY` 를 보내므로, DB 가 아플 때 커넥션을 더 요구한다.

도입 시점의 판단 근거는 이렇게 적어 둔다. 집계 지연이 실제로 관측되거나 다른 스케줄 업무의 tick 밀림이 측정되면, 그때 측정된 p99 를 바탕으로 초 단위 힌트를 이 쿼리에만 붙인다.

- 이번 범위에서 전역 JPA timeout 을 추가하지 않는다. 전역 설정 변경은 다른 모든 쿼리에 영향을 주므로 과제 범위를 넘는다. 대신 다음으로 영향을 제한한다.
  - `fixedDelay` 라 느린 집계가 작업을 쌓지 않는다.
  - 집계는 단일 `GROUP BY` 읽기 1회이고 락을 잡지 않는다.
  - 밀림의 결과는 "지표가 낡는다"이고, 마지막 집계 성공 시각으로 관측된다.
- **남은 한계로 기록한다**: DB 가 장시간 응답하지 않으면 다른 스케줄 업무도 함께 지연된다. 전용 스레드 풀 분리나 쿼리 timeout 도입은 이 지표를 운영에 올릴 때의 후속 과제다.

**지표 구성 (최소 구현 범위, Micrometer·Prometheus 규약 확인)**

지표 **종류 4개**, 실제 노출되는 **시계열 9개**다. Gauge 7개(건수 3 + oldest 3 + 마지막 성공 1)와 Counter 2개(success·failure)다. 미수집 검증은 9개 전부를 대상으로 한다.

Micrometer 는 점을 밑줄로 바꾸고, `baseUnit` 을 이름 뒤에 붙이며, Counter 에 `_total` 을 붙인다. Prometheus 의 시각 표현 관례는 **Unix epoch 초 Gauge** 이고 이름은 `..._timestamp_seconds` 다(`process_start_time_seconds` 와 같은 계열).

| Micrometer 이름 | 타입 | label | 단위 | Prometheus 노출 이름 |
| --- | --- | --- | --- | --- |
| `commerce.payment.unresolved` | Gauge | `state` ∈ `{requested, pending, unknown}` | 건수(무단위) | `commerce_payment_unresolved` |
| `commerce.payment.unresolved.oldest.created.timestamp` | Gauge | `state` 동일 | seconds | `commerce_payment_unresolved_oldest_created_timestamp_seconds` |
| `commerce.payment.unresolved.refresh.last.success.timestamp` | Gauge | 없음 | seconds | `commerce_payment_unresolved_refresh_last_success_timestamp_seconds` |
| `commerce.payment.unresolved.refresh` | Counter | `result` ∈ `{success, failure}` | 횟수 | `commerce_payment_unresolved_refresh_total` |

경과 시간을 애플리케이션이 계산해 노출하지 않는다. PromQL 이 `time()` 과의 차로 계산한다.

```promql
# 마지막 집계 이후 경과 초
time() - commerce_payment_unresolved_refresh_last_success_timestamp_seconds

# 가장 오래된 미종결 결제 시도의 나이(초)
time() - commerce_payment_unresolved_oldest_created_timestamp_seconds
```

**미종결 지속 시간의 기준 시각 (설계 수정)**

`min(updated_at)` 을 쓰지 않는다. 실제 코드를 확인한 결과 `updated_at` 은 상태 진입 시각이 아니다.

- `BaseEntity` 는 Spring Data Auditing 이 아니라 JPA 콜백(`@PrePersist`/`@PreUpdate`)으로 `updated_at` 을 찍는다. `created_at` 은 `updatable = false` 라 한 번만 쓰인다.
- 상태 전이는 QueryDSL 벌크 UPDATE 라 콜백을 타지 않고 `updated_at` 을 **명시적으로** 갱신한다: `settleIfOpen`, `markPendingIfNotSettled`, `markUnknownIfRequested`.
- 문제는 `recordConflict` 다. 이것은 **상태 컬럼을 건드리지 않으면서** `updated_at` 을 갱신한다(`PaymentAttemptRepositoryImpl` 의 `recordConflict`). 즉 상태가 그대로인데 `updated_at` 이 미래로 움직인다.
- `recordLookup` 은 반대로 `updated_at` 을 **일부러 갱신하지 않는다**(코드 주석에 근거가 있다). 그래서 재조회는 시각을 밀지 않는다.
- 결론: `updated_at` 은 "마지막 업무 변경 시각"이고 **특정 상태에 진입한 시각이 아니다.** 충돌 기록 한 번으로 "UNKNOWN 이 된 지 얼마"가 리셋돼 보인다. 지표의 기준으로 쓰면 사고를 실제보다 젊게 보고한다.

그래서 **`created_at` 을 기준으로 전체 결제 시도의 미종결 지속 시간을 측정한다.**

| 구분 | 뜻 | 이번 선택 |
| --- | --- | --- |
| 결제 시도 전체의 미종결 지속 시간 | 시도가 만들어진 뒤 아직 끝나지 않은 시간 | **채택**. 기준은 `created_at` |
| 특정 상태 진입 이후의 지속 시간 | 예: UNKNOWN 이 된 뒤 흐른 시간 | **측정하지 않음**. 신뢰할 수 있는 상태 진입 시각 컬럼이 없다 |

- 상태 진입 시각 컬럼(`unknown_at` 같은 것)을 **추가하지 않는다.** 과제에 불필요한 스키마 변경이다.
- 지표의 정확한 의미: `state` 로 분해되지만 값은 "그 상태에 있는 미종결 시도 중 **가장 먼저 만들어진** 시도의 생성 시각"이다. "그 상태에 머문 시간"이 아니다.
- 한계: 한 시도가 `REQUESTED → PENDING → UNKNOWN` 으로 옮겨 다니면 나이는 계속 자라지만 어느 상태에서 얼마나 머물렀는지는 이 지표로 알 수 없다. 그 질문은 로그와 DB 조회로 답한다. 이 문장을 runbook 에 그대로 적는다.

**집계 쿼리와 반환 타입**

쿼리는 한 번을 유지하고 컬럼만 하나 늘린다.

```sql
select status, count(id), min(created_at)
from payment_attempts
where status in ('REQUESTED', 'PENDING', 'UNKNOWN')
group by status
```

반환 타입은 `List<UnresolvedStatusSummary>` 다. `PaymentLookupResult`·`OutboxSendResult` 처럼 도메인 패키지의 record 라는 기존 컨벤션에 맞춘다.

- 구성: `(PaymentAttempt.Status status, long count, ZonedDateTime oldestCreatedAt)`.
- repository 는 **항상 세 상태를 모두** 돌려준다. 행이 없는 상태는 `count = 0`, `oldestCreatedAt = null` 이다.
- 조회 성공 + 0 건은 "0 건이라는 사실"이고, 가장 오래된 시각은 **존재하지 않는 값**(`null`)으로 표현한다. 0 이나 epoch 로 채우지 않는다. 0 으로 채우면 1970년부터 미확인이 있는 것처럼 보인다.

**다섯 상태의 구분 (필수)**

| 상태 | `commerce_payment_unresolved` | `..._oldest_created_timestamp_seconds` | `..._last_success_timestamp_seconds` | `..._refresh_total` |
| --- | --- | --- | --- | --- |
| 최초 집계 전(미수집) | `NaN` | `NaN` | `NaN` | success 0 |
| 실제 미종결 0 건 | `0` | `NaN` (해당 시도가 없으므로) | 최근 값 | success 증가 |
| 정상 갱신 중 | 실제 건수 | 실제 시각 | 최근 값 | success 증가 |
| 집계 실패 후 마지막 성공값 유지 | 직전 성공값 **유지** | 직전 성공값 유지 | **과거에 고정** | failure 증가 |
| 허용 시간을 넘긴 낡은 스냅샷 | 값은 남아 있음 | 남아 있음 | `time() - ts` 가 한도 초과 | 프로세스가 멈췄다면 둘 다 멈춤 |

- **`last success timestamp` 가 자라는 것 자체는 정상이 아니다.** 반대로 이 값은 집계가 성공할 때마다 **앞으로 점프**한다. 낡음의 신호는 `time() - ts` 가 허용 한도를 넘는 것이다. 집계 주기 10초라면 한도는 예컨대 30초(3주기)로 정한다.
- 경과 시간이 단순히 커지는 것을 실패로 읽지 않는다. 정상 주기 안에서도 scrape 시점에 따라 0~10초 사이를 오간다.
- 실패 시 건수 Gauge 를 0 으로 쓰지 않는다. 0 으로 쓰면 "미확인 없음"이라는 거짓 안전 신호가 된다.
- 미수집과 0 건은 `NaN` 과 `0` 으로 구분된다. `NaN` 은 "아직 모름", `0` 은 "없다는 사실"이다.
- 미수집 구간을 줄이려고 첫 tick 을 짧게 둔다. 기동 시점에 동기 집계를 넣어 기동을 지연시키지 않는다.

**Snapshot 일관성 (한계 수용)**

- 스냅샷은 불변 record 하나이고 `AtomicReference.set` 으로 통째 교체한다. 스냅샷 **내부**의 값들은 항상 한 번의 집계에서 나온 같은 시점이다.
- 그러나 **AtomicReference 가 한 scrape 안의 모든 Gauge 가 같은 스냅샷을 읽는 것을 보장하지는 않는다.** Gauge 는 각자 독립적으로 참조를 읽으므로, scrape 도중 교체가 일어나면 한 응답 안에 이전 값과 새 값이 섞일 수 있다.
- 섞이는 두 값의 시각 차이가 **항상 한 주기라고 말하지 않는다.** 집계 실패가 반복되면 직전 성공 스냅샷이 오래 남으므로, 교체 순간에 걸린 두 값의 간격은 한 주기를 크게 넘을 수 있다.
- 이번 설계는 이 한계를 **수용한다.** 이 지표의 용도는 추세와 규모이지 정밀한 원자적 일관성이 아니다. 특정 주문의 판정은 지표가 아니라 로그와 DB 조회로 한다.
- 이를 없애려고 락이나 커스텀 Collector 를 추가하지 않는다.

**Gauge 생명주기와 참조 유지**

- Micrometer Gauge 는 대상 객체를 약한 참조로 들고 있어 대상이 GC 되면 이후 scrape 가 `NaN` 이 된다.
- 스냅샷 `AtomicReference` 는 싱글턴 빈의 `final` 필드로 두고 Gauge 를 그 필드에 등록한다. 빈이 애플리케이션 수명 동안 살아 있으므로 참조가 끊기지 않는다. `CouponAdmissionMetrics` 가 `AtomicInteger` 필드를 그렇게 쓰는 선례와 같다.
- Gauge 는 생성자에서 한 번만 등록한다. **Gauge 를 읽는 것이 repository 를 호출하지 않는다**(테스트로 확인한다).

**다중 인스턴스 집계 (정확한 서술)**

`max by (state)` 가 항상 정확한 DB 상태를 돌려준다고 말하지 않는다. 각 인스턴스는 같은 DB 를 보지만 **스냅샷 갱신 시각이 서로 다르다.**

| 질의 | 문제 |
| --- | --- |
| `sum by (state)` | 같은 DB 사실을 N 대가 각자 보고하므로 **N 배로 중복 합산**된다 |
| `max by (state)` | 중복은 피하지만, 한 인스턴스의 **낡은 스냅샷이 더 큰 값이면 그 값이 남는다.** 이미 복구된 뒤에도 과거 피크가 보일 수 있다 |

그래서 **최신성으로 걸러낸 뒤 집계한다.**

```promql
# 동일 환경·DB 범위에 쿼리를 한정한 경우의 예시. 30초는 설명용 값이다.
max by (state) (
  commerce_payment_unresolved
  and on (instance)
  (time() - commerce_payment_unresolved_refresh_last_success_timestamp_seconds < 30)
)
```

- S9에서 두 지표 모두 같은 서비스·환경·DB 집합으로 좁히고 실제 타깃 라벨이 일치하는지 확인한다. `instance`가 범위 안에서 유일하지 않으면 매칭 키에 타깃을 구분하는 라벨을 포함한다.
- 필터는 마지막 성공 시각의 최신성만 확인한다. 상태별 max는 시각이 다른 최근 스냅샷의 최댓값이며 정확한 업무 건수는 DB로 확인한다. 필터 결과가 빈 벡터면 오래된 값, 미수집 또는 시계열 부재로 관측할 수 없는 상태다.
- 분산락·리더 선출·외부 집계 시스템을 추가하지 않는다. 질의와 해석 규약으로 다룬다.
- 부수 효과: DB 집계 횟수가 인스턴스 수에 비례한다. 인스턴스가 늘면 첫 대응은 집계 주기를 늘리는 것이다.

**스케줄러 검증 범위 (S2 에서 한 것과 하지 않은 것)**

S2 테스트는 모두 `refresh()` 를 **직접 호출**한다. `@Scheduled` 자동 실행은 검증하지 않았다. test profile 이 `payment.metrics.unresolved.enabled=false` 이고, 기존 스케줄러(relay·결제 조회·쿠폰 승격)도 모두 같은 방식으로 자동 실행을 끄고 메서드를 직접 부르는 컨벤션을 따랐다.

| 항목 | S2 | 어디서 |
| --- | --- | --- |
| 집계 로직·스냅샷 교체·실패 유지·복구 | **검증** | `PaymentUnresolvedMetricsTest` |
| 실제 Prometheus 응답에 9개 시계열 노출 | **검증** | `PaymentUnresolvedMetricsPrometheusTest` |
| `@Scheduled` 자동 실행 | 미검증 | S5 사고 테스트 |
| enabled true/false property 동작 | 미검증 | S5 사고 테스트 |
| DB 상태 변경 뒤 자동 갱신 | 미검증 | S5 사고 테스트 |
| 집계 실패 뒤 다음 주기 자동 복구 | 미검증 | S5 사고 테스트 |

S5 는 실제 사고를 만들며 유한 대기 안에 지표가 변하는지를 봐야 하므로, 거기서 스케줄러를 켜고(`enabled=true`, `fixed-delay=PT0.2S`) 자동 갱신을 확인한다. 같은 것을 두 번 테스트하지 않는다.

### 3-1-3. S2 구현 결과 (실제 Prometheus 응답)

테스트가 실제 관리 포트에서 가져온 원문이다. 네 지표 모두 의도한 이름·label·단위로 노출된다.

```text
commerce_payment_unresolved{application="commerce-api",state="pending"} 0.0
commerce_payment_unresolved{application="commerce-api",state="requested"} 1.0
commerce_payment_unresolved{application="commerce-api",state="unknown"} 1.0
commerce_payment_unresolved_oldest_created_timestamp_seconds{application="commerce-api",state="pending"} NaN
commerce_payment_unresolved_oldest_created_timestamp_seconds{application="commerce-api",state="requested"} 1.795714636178E9
commerce_payment_unresolved_oldest_created_timestamp_seconds{application="commerce-api",state="unknown"} 1.79571463603E9
commerce_payment_unresolved_refresh_total{application="commerce-api",result="failure"} 0.0
commerce_payment_unresolved_refresh_total{application="commerce-api",result="success"} 1.0
commerce_payment_unresolved_refresh_last_success_timestamp_seconds{application="commerce-api"} 1.795714637026E9
```

- `state="pending"` 의 건수는 `0.0`(실제 0 건)이고 같은 state 의 시각은 `NaN`(해당 시도 없음)이다. 두 표현이 실제로 구분된다.
- `application` label 은 `monitoring.yml` 의 `management.metrics.tags.application` 이 붙인 것이다.
- 같은 응답에서 `/actuator/health` 는 `{"status":"UP","groups":["liveness","readiness"]}` 였다. **health UP 과 미확인 결제 공존**이 한 관측 시점에서 확인된다.

### 3-2. 구조화 로그 (R2 / G2)

이벤트명·필드·레벨과 업무 식별자 연결은 [monitoring-design.md의 구조화 로그 계약](monitoring-design.md)을 따른다. 새 MDC 필터는 추가하지 않는다.

**적용 시도와 commit 성공의 분리 (중요)**

`applyFinal` 은 `@Transactional` 이라 메서드 안에서 찍은 로그는 **아직 커밋되지 않은 사실**이다. `settled=1` 과 `order.confirmed` 를 TX 안에서만 남기면, 뒤에서 롤백돼도 로그만 보면 성공한 것처럼 보인다. 가설 3(성공 결과의 내부 반영 실패)을 가설 2(응답 유실)와 가르는 신호가 바로 이 지점이므로 반드시 분리한다.

- TX 안 로그는 `phase=attempt` 로만 쓴다. 성공을 단정하는 문구(`confirmed`, `success`)를 쓰지 않는다.
- 완료 콜백 `afterCompletion` 이 `STATUS_COMMITTED` 를 받으면 `phase=committed` 를 남긴다.
- 롤백은 **추론하지 않고 확인한다.** `afterCompletion(int status)` 에서 인자가 `STATUS_ROLLED_BACK` 인 경우에만 `phase=rolled-back` 을 남기고, `STATUS_UNKNOWN` 이면 `phase=completion-unknown` 을 남긴다. `afterCompletion` 이 불렸다는 사실만으로 롤백이라고 쓰지 않는다.
- 이 동기화 등록은 로그 전용이고 업무 로직·TX 경계·반환값을 바꾸지 않는다.

**로그 부재로 실패를 단정하지 않는다 (판정 규칙 정정)**

`phase=attempt` 는 있는데 `phase=committed` 가 없는 상태를 "DB 반영 실패"로 단정하면 틀릴 수 있다. 같은 관찰을 만드는 다른 원인이 최소 셋 있다.

- 커밋은 성공했는데 완료 로그가 기록되기 전에 프로세스가 종료됐다. 업무 사실은 반영돼 있다.
- 로그가 유실됐다. 비동기 appender 버퍼, 프로세스 강제 종료, 파일 로테이션 경합에서 마지막 줄이 사라질 수 있다.
- 로그 수집·검색 쪽에서 누락됐다. 수집 지연, 색인 실패, 조회 시간 범위를 잘못 잡은 경우다.

그래서 판정 규칙은 다음과 같다.

| 관찰 | 판정 | 다음 행동 |
| --- | --- | --- |
| `attempt` + `committed` | 반영됨 | DB 상태로 확인만 한다 |
| `attempt` + `rolled-back` | 반영되지 않음(적극적 증거) | 가설 3 을 지지한다. 롤백 원인 예외를 찾는다 |
| `attempt` + `completion-unknown` | **최종 결과 미확인** | TX 결과를 알 수 없었다. DB 상태로 판정한다 |
| `attempt` 만 있음 | **최종 결과 미확인** | 로그로 결론내지 않는다. `payment_attempts.status`·`orders.status`·`confirmed_at`·예약 상태·Outbox 행의 **실제 DB 상태**로 판정하고, 프로세스 재시작 기록과 로그 유실 가능성을 함께 확인한다 |
| 아무 로그도 없음 | 미확정 | 해당 주문의 호출·TX 기록을 확보한 뒤 재진단한다 |

핵심은 **로그는 DB 상태를 대체하지 않는다**는 것이다. 로그의 역할은 "어느 경계에서 무슨 일이 있었나"를 좁히는 것이고, 반영 여부의 최종 권위는 DB 상태다. 사고 테스트의 DIAGNOSE 단계도 이 순서를 따른다. 로그로 가설을 좁히고, DB 로 확정하고, 증거가 부족하면 "미확정"으로 적는다.

레벨 기준: 평상시 상태 전이는 INFO, 사람이 봐야 하지만 자동 수렴 가능한 비정상(UNKNOWN·조회 불가·충돌·롤백)은 WARN, 롤백의 원인이 된 예외처럼 업무 사실이 깨진 지점만 ERROR.

금지: 카드번호·`X-USER-ID` 외 인증 헤더·요청 본문 원문. `HttpPaymentGateway` 가 카드번호 유출 방지로 본문을 raw byte 로 쓰고 있고 `HttpPaymentGatewayContractTest` 가 Logback `ListAppender` 로 이를 검증한다 — **새 로그는 그 테스트를 깨지 않아야 한다**(회귀 확인 필수).

### 3-2-1. S3 구현 결과 (실제 로그 원문)

**적용 시도 / commit / rollback / 중복 적용** (`PaymentApplyLogIntegrationTest` 실행 로그에서 메시지 발췌)

```text
payment.apply phase=attempt attemptId=1 orderId=1 storedStatus=PENDING targetStatus=SUCCESS settled=1 firstConfirmation=true
payment.apply phase=rolled-back attemptId=1 orderId=1 targetStatus=SUCCESS
payment.apply phase=attempt attemptId=1 orderId=1 storedStatus=PENDING targetStatus=SUCCESS settled=1 firstConfirmation=true
payment.apply phase=committed attemptId=1 orderId=1 appliedStatus=SUCCESS confirmedAt=2026-11-27T06:31:32.344+09:00[Asia/Seoul] outboxEventId=order-confirmed:v1:1
payment.apply phase=attempt attemptId=1 orderId=1 storedStatus=SUCCESS targetStatus=SUCCESS settled=0 result=NOT_APPLIED
```

- 1~2행: 적용을 시도했고(`settled=1`) 되돌아갔다. **`phase=committed` 가 없다.**
- 3~4행: 같은 결과를 다시 적용해 커밋됐다. `confirmedAt` 과 `outboxEventId` 는 커밋 뒤에만 나온다.
- 5행: 조건부 UPDATE 가 0 행이다. 이것은 **`NOT_APPLIED` 라는 사실만** 뜻한다. 중복 적용인지, 다른 결과가 먼저 종결된 것인지, 그 사이 다른 트랜잭션이 무엇을 했는지는 이 줄로 알 수 없다.
- `storedStatus` 는 **이 TX 가 시작할 때 읽은 값**이고, UPDATE 가 실패한 **이후의 현재 상태가 아니다.** 그 사이 다른 트랜잭션이 같은 결과를 먼저 커밋했을 수 있다. 그래서 `storedStatus != targetStatus` 를 근거로 "다른 결과가 먼저 종결됐다"고 단정하지 않는다.
- 현재 업무 상태가 필요하면 **DB 를 다시 조회해 판단한다.** 로그를 위해 운영 코드에 추가 조회를 넣지 않는다.

**경계 결정** (`PaymentV1ApiE2ETest`·`PaymentReconcileIntegrationTest` 실행 원문에서 발췌)

```text
payment.create outcome=ACCEPTED orderId=1 attemptId=1 providerOrderId=LP-ORD-0000000001 pendingRows=1
payment.create outcome=UNKNOWN  orderId=1 attemptId=1 providerOrderId=LP-ORD-0000000001 unknownRows=1 conflict=null
payment.reconcile orderId=1 attemptId=1 attemptStatus=PENDING decision=APPLIED
payment.reconcile orderId=1 attemptId=1 attemptStatus=SUCCESS decision=ALREADY_SETTLED
payment.reconcile orderId=1 requesterId=2 decision=NOT_OWNED
payment.reconcile orderId=1 attemptId=1 attemptStatus=PENDING decision=CIRCUIT_OPEN
payment.callback  orderId=1 attemptId=1 attemptStatus=PENDING decision=CONFLICT reason=AMOUNT_MISMATCH
payment.callback  orderId=1 attemptId=1 attemptStatus=REQUESTED decision=LOOKUP_UNAVAILABLE reason=CALL_FAILED
```

`outcome=ACCEPTED` 는 **거래 수락**이고 결제 성공이 아니다. 외부 PG 성공과 내부 commit 은 서로 다른 줄이다.

**세 가지를 구분한다 (상태 필드 정의)**

| 개념 | 로그 | 뜻 |
| --- | --- | --- |
| 결제 결과 | `targetStatus` / `appliedStatus` | 적용하려는·적용한 **목표 상태**(SUCCESS 또는 FAILED). 저장 확정과 별개다 |
| 내부 TX 완료 상태 | `phase=committed` / `rolled-back` / `completion-unknown` | 트랜잭션이 커밋됐는지다. **결제 성공을 뜻하지 않는다** |
| 실제 DB 업무 상태 | `storedStatus`(TX 시작 시점) + DB 조회 | 저장된 사실. 최종 권위 |

`phase=committed appliedStatus=FAILED` 는 **정상**이다. PG 실패를 제대로 반영한 커밋이고, 그때 주문은 `PAYMENT_FAILED` 가 되며 예약은 반환된다. 실제 원문이다.

```text
payment.apply phase=attempt attemptId=2 orderId=2 storedStatus=PENDING targetStatus=FAILED settled=1 firstConfirmation=false
payment.apply phase=committed attemptId=2 orderId=2 appliedStatus=FAILED confirmedAt=null outboxEventId=null
```

`storedStatus` 는 이 TX 가 시작할 때 읽은 값이고, 적용 결과도 아니며 UPDATE 실패 이후의 현재 상태도 아니다. 현재 상태는 DB 재조회로 판단한다.

**예외 원인의 기록 위치 (중복 로그 추가하지 않음)**

직렬화 실패 말고도 DB 예외·Outbox 저장 실패·커밋 실패가 있다. 이들은 기존 경계에서 이미 원인과 스택이 기록된다.

| 경로 | 기록 위치 | 레벨 |
| --- | --- | --- |
| HTTP 요청 (confirm·callback·reconcile) | `ApiControllerAdvice.handle(CoreException)` | WARN + 스택 |
| HTTP 요청의 그 밖의 모든 예외(DB·Outbox·커밋 실패) | `ApiControllerAdvice.handle(Throwable)` | ERROR + 스택 |
| 스케줄러 조회 | `PaymentFacade.runScheduledLookups` 의 catch | WARN + 스택 |
| Outbox relay 발행 | `OrderConfirmedOutboxRelay` 의 결과 분기와 `last_error` 컬럼 | 기존 구현 |

그래서 `PaymentResultService` 에는 예외 로그를 더하지 않는다. 더하는 것은 `phase=rolled-back` 한 줄뿐이고, 그 줄의 역할은 원인 기록이 아니라 **적용 시도가 되돌아갔다는 사실**이다. 원인은 위 경계의 스택과 짝지어 읽는다.

**로그 상관관계 (기존 문맥으로 충분한지 확인)**

실제 실행 로그의 대괄호에 trace id 와 span id 가 있다. `supports/logging` 이 `micrometer-tracing-bridge-brave` 를 포함하고 logback 패턴이 이를 찍는다.

```text
[6b08731966e27717dc393a69eba778c7-a3272411c2a90ba3] c.l.application.payment.PaymentFacade : payment.reconcile orderId=1 ... decision=APPLIED
```

- 한 HTTP 요청 안의 `payment.reconcile`·`payment.apply phase=attempt`·`phase=committed` 는 **같은 trace id** 를 공유한다. 같은 `attemptId` 에 여러 callback·reconcile 이 와도 요청이 다르면 trace id 가 달라 실행 단위를 가를 수 있다. 그래서 **새 식별자를 만들지 않는다.**
- 다만 trace id 가 개별 트랜잭션과 **1:1 은 아니다.** 한 요청 안에서 `applyFinal` 이 여러 번 호출되면 같은 trace id 에 여러 TX 가 들어가고, 반대로 스케줄러 경로는 요청 trace 와 이어지지 않는다. 트랜잭션 단위를 가르는 값은 `attemptId` 와 로그 순서이고, trace id 는 실행 단위를 좁히는 보조 수단이다.
- `HttpPaymentGateway` 는 `providerOrderId` 를 남긴다. 형식이 `LP-ORD-%010d` 로 orderId 를 포함하므로 같은 주문과 이어진다. **infrastructure 계층에 orderId 를 따로 전달하지 않는다.**
- 한계: `@Scheduled` 경로(스케줄러 조회·relay)는 HTTP 요청이 아니므로 trace 문맥이 요청과 이어지지 않는다. 그 구간은 `attemptId`·`orderId`·`providerOrderId` 로 잇는다. 분산 추적을 구현한 것이 아니다.

**로그 레벨 기준 (업무 거절 vs 시스템 장애)**

| 레벨 | 사건 |
| --- | --- |
| INFO | 거래 수락, 업무 거절(`REJECTED_NO_EFFECT`·`NOT_OWNED`), 중복 적용(`ALREADY_SETTLED`·`settled=0`), 적용 시도, 커밋, 조회 FOUND |
| WARN | `outcome=UNKNOWN`, 조회 불가·서킷 차단, 충돌, `phase=rolled-back`, `phase=completion-unknown` |
| ERROR | 롤백의 원인이 된 지점(`reason=payload-serialize`) |

**S3 테스트 검증 범위**

| 상황 | 상태 | 어디서 |
| --- | --- | --- |
| SUCCESS 결과의 정상 commit | 검증 | `PaymentApplyLogIntegrationTest` (로그 + 주문 CONFIRMED) |
| FAILED 결과의 정상 commit | 검증 | 같은 테스트 (`appliedStatus=FAILED` + 주문 PAYMENT_FAILED) |
| rollback 시 committed 미출력·DB 상태 유지 | 검증 | 같은 테스트 (시도 PENDING·주문 PAYMENT_PENDING 재확인) |
| 조건부 UPDATE 0행의 NOT_APPLIED | 검증 | 같은 테스트 (`storedStatus=SUCCESS targetStatus=SUCCESS`) |
| 민감정보 미노출 | 검증 | 같은 테스트 + 기존 `HttpPaymentGatewayContractTest` |
| `STATUS_UNKNOWN` 과 rollback 구분 | **미검증** | 아래 |

`STATUS_UNKNOWN` 은 트랜잭션 매니저가 커밋 결과를 확정하지 못한 경우에만 나온다. 로컬 단일 DB 와 `DataSourceTransactionManager`·JPA 조합에서 이 상태를 재현하려면 커밋 중 커넥션을 강제로 끊는 등 인프라를 흔들어야 하고, 그렇게 만든 상황은 실제 운영의 그 상태와 같다고 보장할 수 없다. 분기는 코드로 구현했고(`default ->`), **실행 경로는 검증하지 않았다**고 남긴다. 억지로 만든 재현을 증거로 쓰지 않는다.

### 3-3. 장애 fixture (R3 / G3)

`TestPaymentPgServer` 를 확장한다. 새 클래스를 만들지 않는 이유는 practice.md 가 "fixture 에 작은 제어를 추가합니다"라고 명시하고, 같은 역할의 클래스를 둘로 늘리면 어느 쪽이 W5 계약인지 불분명해지기 때문이다. 기존 "정해진 응답" API(`respondToCreate`/`respondToTransaction`/`respondToList`)와 `holdResponses()` 는 그대로 두고, 상태 보관 모드를 더한다.

추가 API 초안:

```text
enableTransactionStore()                 // POST /api/v1/payments 를 실제 거래 생성으로 처리
dropCreateResponseFor(String providerOrderId)   // 해당 주문만: 거래 저장 후 응답 유실
clearResponseDrops()                     // RECOVERY 전에 해제
markSuccess(String providerOrderId)      // 외부 최종 성공 사실을 기록 (callback 은 보내지 않는다)
long createCount()                       // 기존
long createCount(String providerOrderId) // 주문별 create 호출 수 (중복 create 탐지)
List<Transaction> transactionsOf(String providerOrderId)
```

동작 규약:

- `POST /api/v1/payments` 수신 시 **정확히 이 순서로** 처리한다. 순서가 뒤바뀌면 "외부에는 성공 사실이 있는데 내부만 모른다"가 성립하지 않으므로 fixture 자체 테스트로 순서를 고정한다.

  1. 요청 본문의 `providerOrderId`·금액·카드 종류를 읽고 **create 수신 사실을 먼저 기록**한다(주문별 호출 수 증가). 뒤에서 무엇을 하든 이 기록은 지우지 않는다.
  2. 같은 `providerOrderId` 의 거래가 없으면 거래 1건을 **생성**한다. 이미 있으면 새로 만들지 않는다(중복 create 로 거래가 늘지 않음).
  3. 장애 대상이면 그 거래를 **`SUCCESS` 로 확정 저장**한다. 즉 외부 거래의 최종 성공 사실이 **응답을 쓰기 전에** 이미 보관된다.
  4. 그 **다음에** 응답을 쓰지 않는다. 래치로 보류하거나 소켓을 닫아 호출자가 read timeout 을 맞게 한다.
  5. 장애 대상이 아니면 거래를 `PENDING` 으로 두고 202 를 정상 응답한다. 최종 `SUCCESS` 는 테스트가 `markSuccess` 로 따로 만든다(첫 수락의 PENDING 과 최종 SUCCESS 구분).
- `GET /api/v1/payments?orderId=` 는 저장된 거래를 그대로 돌려준다. 금액·providerOrderId 는 요청받은 값을 쓰므로 `PaymentFacade.conflictOf` 의 금액·주문 검증이 실제로 통과한다.
- 같은 `providerOrderId` 로 create 가 두 번 오면 **거래를 두 번 만들지 않고** `createCount(providerOrderId)` 를 올린 사실을 보존한다. 테스트는 이 값으로 중복 create 를 실패 처리한다. 오류 응답을 돌려줬다고 호출 사실을 지우지 않는다.
- 운영 코드에는 어떤 장애 주입 스위치도 넣지 않는다. 제어는 전부 이 테스트 fixture 안이다.

응답이 유실되면 실제 `HttpPaymentGateway` 의 HTTP 호출 경계에서 결과 미확인 예외를 받아 `PaymentCreateResult.unknown()` 으로 수렴한다. 테스트는 `payment.pg.read-timeout` 을 짧게(예 `PT0.5S`) 주어 3건 × timeout 대기 시간을 줄인다.

**선택지 비교(기록용)**: `FakePaymentGateway.willCreateButLoseResponse()` 가 이미 있으나 (가) `HttpPaymentGateway`·timeout·HTTP 경계를 전부 건너뛰고 (나) "다음 create 1회" 단위라서 주문 3개를 지정하는 통제가 안 된다. 요구 R3 의 "실제 PG adapter 를 거쳐"와 "특정 주문만"을 못 만족하므로 사고 테스트에서는 쓰지 않는다. 기존 테스트들의 가짜 PG 사용은 건드리지 않는다.

### 3-3-1. S4 구현 결과 (실제 adapter 응답과 로그)

**기존 fixture 와의 차이**

| 항목 | 기존 | S4 이후 |
| --- | --- | --- |
| create 처리 | `respondToCreate` 로 미리 정한 응답 1개를 모든 요청에 반환 | `enableTransactionStore()` 를 켜면 요청 본문을 읽어 **실제 거래를 만들어 보관** |
| 거래 상태 | 보관하지 않음(정해진 응답만) | `providerOrderId` 당 1건, PENDING → SUCCESS 전이 |
| 응답 유실 | `holdResponses()` 로 **전체** 보류 | `dropCreateResponseFor(providerOrderId)` 로 **주문 단위** |
| create 횟수 | 전체 합계만 | 전체 + 주문별 (`createCount(providerOrderId)`) |
| 목록 조회 | 정해진 응답 또는 빈 배열 | 정해진 응답이 없으면 **보관된 거래**로 응답 |
| 기존 계약 | — | `respondToCreate`·`respondToTransaction`·`respondToList`·`holdResponses` 모두 보존. 보관 모드를 켜지 않으면 동작이 같다 |

운영 코드에는 장애 주입 스위치를 추가하지 않았다. 제어는 전부 이 fixture 안이다.

**실행 순서 (fixture 의 계약)**

1. `record()` 가 요청을 먼저 기록한다. 뒤에서 무엇을 하든 호출 사실은 지워지지 않는다.
2. 주문별 create 수신 횟수를 올린다. 중복이어도 올린다.
3. 그 주문의 거래가 없으면 1건을 PENDING 으로 만든다. 있으면 **재생성하지 않는다.**
4. 장애 대상이면 그 거래를 **`SUCCESS` 로 확정 저장한다.**
5. 그 **다음에** 응답을 쓰지 않는다. 호출자는 read timeout 을 맞는다.

그래서 "외부에는 성공 사실이 있는데 내부만 모른다"가 성립한다. 순서를 테스트로 고정했다.

**실제 adapter의 장애 응답** (`TestPaymentPgServerIncidentTest` 실행 로그에서 메시지 발췌)

```text
pg.create outcome=ACCEPTED providerOrderId=LP-ORD-0000000001 transactionKey=TEST-TX-000001
pg.create outcome=UNKNOWN providerOrderId=LP-ORD-0000000002 exception=ResourceAccessException causeChain=[ResourceAccessException, HttpTimeoutException] interrupted=false
pg.create outcome=UNKNOWN providerOrderId=LP-ORD-0000000001 exception=ResourceAccessException causeChain=[ResourceAccessException, ConnectException, ClosedChannelException] interrupted=false
pg.lookup outcome=FOUND orderId=LP-ORD-0000000002 count=1 statuses=[SUCCESS]
```

**예외 분류 규칙 (정정)**

- 로그는 `exception=`(잡은 예외 타입)과 `causeChain=`(cause chain 의 클래스 이름)까지만 적는다. **원인을 분류하지 않는다.**
- `HttpTimeoutException` 을 read timeout 으로 단정하지 않는다. `HttpConnectTimeoutException` 이 그 **하위 타입**이라 연결 단계 timeout 도 같은 상위 타입으로 보인다. 그래서 chain 의 이름을 그대로 남겨 둘이 구분되게 한다.
- `CancellationException` 도 timeout 처리 과정에서 나올 수 있다. 이것만으로 read timeout 이나 거래 성공을 확정하지 않는다.
- `ClosedChannelException` 을 연결 거부의 고정 결과로 가정하지 않는다. 이 환경에서 그렇게 관측됐을 뿐이고 JDK·환경에 따라 달라진다.
- 그래서 **테스트는 root cause 이름이 아니라 adapter 의 업무 결과(UNKNOWN)와 상태 보존을 먼저 검증한다.** 이름은 `causeChain=[...]` 이 존재하는지까지만 본다.

**거래 수와 호출 횟수**

- 같은 주문으로 create 2회 → 거래 1건, `createCount` 2. 중복을 무시해 통과시키지 않고 **호출 사실을 남긴다.**
- 응답을 유실한 create 도 횟수에 포함된다.
- `clearResponseDrops()` 는 주입만 해제하고 **거래와 횟수를 보존한다.** 복구 단계가 같은 거래를 조회해야 하기 때문이다.
- `reset()` 만 전체를 비운다(서로 다른 테스트 사이).

**남은 한계**

- 이 fixture 는 PG 의 상태 기계를 흉내 낸 것이고 실제 PG 의 동작과 같다고 보장하지 않는다. 지원하는 전이는 PENDING 과 SUCCESS 뿐이고 부분 취소·환불·만료는 없다.
- 응답 유실은 "응답을 쓰지 않음"으로 만든다. 실제 운영의 유실 원인(네트워크 단절, LB 종료, 프로세스 종료)은 호출자에게 다른 예외로 보일 수 있다.
- 거래는 주문당 1건만 만든다. 복수 거래 충돌(`MULTIPLE_TRANSACTIONS`)은 정해진 응답 API 로 따로 만들어야 한다.
- `markSuccess` 는 callback 을 보내지 않는다. 알림 경로는 테스트가 직접 호출한다.
### 3-3-2. CancellationException 결함과 해결 (S5 전 선행 작업)

**취소 가능 경로와 의존성 버전**

| 항목 | 값 |
| --- | --- |
| Spring Boot | 3.4.4 (`gradle.properties`) |
| spring-web (runtimeClasspath) | **6.2.5** |
| 테스트 실행 JDK | **23.0.2** (루트에만 toolchain 21 이 선언돼 서브프로젝트는 Gradle 기본 JVM 을 쓴다) |
| HTTP client / factory | Spring `RestClient` + `JdkClientHttpRequestFactory` (JDK `HttpClient`) |
| 기존 서킷 예외 처리 | `guardedByCircuit` 은 `CallNotPermittedException` 만 잡고, 결과 기반 실패 판정(`recordResult`)을 쓴다 |

상류 이슈 `spring-framework#33973`에 기록된 경로는 `restClient.exchange` → `JdkClientHttpRequest.executeInternal` → `CompletableFuture.get`이다. timeout 처리 중 future가 취소되어 `CancellationException`이 전파되면 기존 `RestClientException` catch만으로는 UNKNOWN을 반환하지 못한다. 상류 수정 버전은 6.2.10이다.

**대안 비교**

| 안 | 내용 | 평가 |
| --- | --- | --- |
| 1. 패치 버전 적용 | Boot 3.4.x 를 올리거나 `spring-framework.version` 을 6.2.10+ 로 고정 | 상류 근본 수정이지만 **core 라이브러리를 세 앱 전체에 교체**한다. 영향 범위가 가장 크고 이번 결함 하나를 위해 치를 비용이 크다 |
| 2. 호출 경계에서 제한적 처리 | `create`·두 lookup의 HTTP catch에 `CancellationException` 추가 | 의존성 교체 없이 해당 예외를 create UNKNOWN과 조회 CALL_FAILED로 처리한다. |
| 3. 유지 | — | 사고 재현의 전제가 깨진다. 거부 |

**2 안을 선택했다.** 이유는 셋이다. 결제 결과가 불확실할 때 UNKNOWN 으로 수렴시키는 것은 Framework 버전과 무관하게 이 adapter 가 지켜야 할 계약이다. 의존성 교체는 세 앱의 전체 회귀를 다시 요구하는데 이번 변경의 목적에 비해 범위가 넓다. timeout 을 늘려 빈도를 낮추는 것은 해결이 아니므로 fixture 테스트의 read timeout 도 원래 값(500 ms)으로 되돌렸다.

1 안은 버린 것이 아니라 **다음 Boot 업그레이드 때 함께 적용할 항목**으로 남긴다. 그때 이 catch 는 이중 방어로 남는다.

**안전 범위**

- `RuntimeException`·`Exception`·`Throwable` 전체를 잡지 않는다. 개발 오류·설정 결함을 UNKNOWN 으로 숨기지 않기 위해서다.
- `CancellationException` 이 났다는 이유로 **read timeout 이나 PG 거래 성공을 확정하지 않는다.** 로그는 예외 타입과 cause chain 까지만 남긴다.
- 이 프로젝트에는 PG 호출을 의도적으로 취소하거나 인터럽트하는 경로가 없다. 그래서 여기서의 취소는 "요청은 보냈고 결과를 확인하지 못함"을 뜻한다. 혹시 그런 정책이 생기면 구분할 수 있도록 `interrupted=` 를 함께 남긴다.
- 두 조회의 HTTP catch는 `CancellationException`을 `UNAVAILABLE(CALL_FAILED)`로 반환한다. Circuit Breaker의 `recordResult`는 이를 실패 표본으로 센다. OPEN 차단은 HTTP 호출 없이 `CIRCUIT_OPEN`으로 반환하며 create는 조회 서킷을 통과하지 않는다.
- 예외 주입 테스트가 catch·매핑을 검증했다. 실제 HTTP fixture에서는 응답 유실과 timeout을 확인했으며, 실제 JDK HTTP 호출이 직접 `CancellationException`을 발생시키는 경로는 미검증이다. 검증 기록과 권위 조회의 금액 정합성 수정은 [monitoring-design.md](monitoring-design.md) §8.2에 기록한다.

### 3-4. 사고 연결 테스트 (R4 / G4)

위치: `apps/commerce-api/src/crossAppTest/java/com/loopers/crossapp/CheckoutIncidentE2ETest.java`.
`crossAppTest` 를 쓰는 이유는 복구 후 R7(구매 활동 → 주간 batch → 랭킹 API)까지 한 lifecycle 로 이어야 하고, 그 wiring(`CrossAppTestConfig`, streamer·batch 동일 JVM, `@MockitoBean Clock`)이 이미 `WeeklyRankingFlowE2ETest` 에 선례로 있기 때문이다.

컨텍스트 구성:

- S5는 `CrossAppTestConfig`와 테스트 전용 `@EnableScheduling`을 사용한다. API·Actuator·PG는 루프백의 랜덤 포트에 바인딩한다. 결제 자동 조회·relay·Kafka listener·Batch 실행은 보류한다. 지표 집계는 `payment.metrics.unresolved.enabled=true`, `fixed-delay=PT0.2S`로 켜고 PG 읽기 timeout은 `PT0.5S`를 사용한다.
- `@BeforeAll`에서 PG fixture를 띄우고 `@DynamicPropertySource`로 PG base URL과 callback URL을 주입한다. 정상 callback은 실제 애플리케이션 HTTP API로 전달한다.
- `test.fake-pg.enabled=false` 가 필요한 이유: `CrossAppTestConfig` 가 `FakePaymentGatewayConfig`(`@Primary PaymentGateway`)를 `@Import` 하므로 그대로면 실제 adapter 가 절대 호출되지 않는다. `FakePaymentGatewayConfig` 의 빈에 `@ConditionalOnProperty(name="test.fake-pg.enabled", matchIfMissing=true)` 를 붙여 기존 테스트는 영향 없이, 이 테스트만 실제 adapter 를 쓰게 한다.
- BASELINE과 FAULT는 단일 테스트 메서드의 순차 단계다. 중간에 DB·PG·조회 서킷을 초기화하지 않는다. 실행 종료 시 DB를 정리하고 테스트 컨텍스트를 닫는다.
- S5는 자동 집계 성공 시각의 전진과 최신성을 실측하기 위해 UTC 시스템 Clock을 사용한다. 실제 지표 노출에는 `@AutoConfigureObservability(tracing = false)`를 적용한다. 주간 랭킹 입력 시각 통제는 후속 단계에서 확인한다.
- 조회 서킷은 제품 설정을 유지한다. 최근 호출 10건, 최소 5건, 실패율 50%, OPEN 대기 10초, HALF_OPEN 시험 2건이다. create는 조회 서킷의 적용 대상이 아니며, FAULT의 세 건 모두 fixture의 주문별 create 수신 횟수 1건을 확인한다.

실행 순서(S5는 BASELINE과 FAULT까지 구현):

```text
BASELINE
  빈 결제 데이터 및 빈 PG 거래 원장 확인 / 합성 사용자 1명 / 상품 1종 재고 30
  정상 주문 10건: 주문 생성 → 결제 시작(실제 PG create, 202 PENDING)
  fixture.markSuccess(각 providerOrderId) → callback API 호출(기존 알림 경로)로 확정
  assert 10 SUCCESS / UNKNOWN 0 / 거래 10 / createCount 10
  증거: baseline.json (주문 ID·attemptKey·providerOrderId·거래키·금액·상태·health·prometheus)

FAULT
  장애 집합 10건 생성 (baseline 과 겹치지 않는 별도 주문)
  실제 생성된 ID 목록에서 3건을 고정 선택 → dropCreateResponseFor(각 providerOrderId)
  10건 모두 결제 시작
    3건: fixture 가 거래 SUCCESS 저장 → 응답 유실 → adapter 결과 미확인 → UNKNOWN
    7건: 202 PENDING → markSuccess → callback 으로 확정
  3건에는 callback 을 보내지 않는다 (외부 성공 사실만 존재)
  assert 장애집합 SUCCESS 7 / UNKNOWN 3 / 장애집합 거래 10 / 누적 거래 20
  assert 전체 확정 주문 17건 (baseline 10 + fault 7)
  assert health UP, prometheus 의 commerce_payment_unresolved{state="unknown"} = 3
  증거: fault.json (+ sha256) — 이후 단계는 읽기만, 쓰기 시도 시 테스트 실패

@Order(3) DIAGNOSE   // 읽기 전용, reconcile 호출 금지
  가설 3개의 예상/실제/판정을 실제 관찰로 채운다
  관측 설계 문서 §5에 따라 주문별 pg.create outcome=UNKNOWN, 동일 PG 거래 상태와 내부 적용·TX 완료 기록을 대조한다.
  적용 로그 부재와 DB 거래 키 null은 보조 신호다. 직접 증거가 부족하면 원인 미확정으로 남긴다.
  fixture의 권위 목록 HTTP GET으로 PG 거래를 읽는다. 주문·시도·예약·Outbox는 DB 읽기만 수행한다.
  사후 확인: 진단 시작 때 찍은 스냅샷과 같은 조회를 다시 실행해
            attempt 상태·거래 수·createCount·Outbox 상태가 그대로인지 assert
            달라졌다면 "PG 조회가 바꿨다"고 단정하지 않고 현재 상태로 재진단 후 기록
  증거: diagnosis.json

@Order(4) RANKING r1   // 복구 이전의 실제 활동 17건으로 공개
  relay.runOnce() 반복 → 확정 17건의 Outbox 전부 PUBLISHED
  streamer consumer 수신 대기(Awaitility) → product_purchase_activity 17행, 장부 17행
  assert 장애 3건의 Outbox 행이 아직 없음 (확정되지 않았으므로)
  weeklyRanking r1 실행: freeze(입력 17) → score → open
  assert 랭킹 API 가 r1 공개, 해당 상품 score 17
  증거: ranking-r1.json (snapshotId·input_count·score·API 원문)

@Order(5) RECOVERY
  clearResponseDrops() + assert drop 집합이 비었음
  같은 3개 orderId 로만 POST /api/v1/orders/{orderId}/payment/reconcile (본인 헤더)
  응답 코드별 분기 검증 (아래 표)
  assert 장애집합 SUCCESS 10 / UNKNOWN 0 / 거래 여전히 10 / 누적 20
  assert createCount 증가분 0 (FAULT 종료 스냅샷과 비교)
  assert 예약: UNKNOWN 동안 RESERVED 유지 → 이제 CONSUMED, 재고 추가 차감 없음
  assert 포인트 잔액·쿠폰 상태 불변
  assert 이미 저장된 confirmedAt 보존, 새 확정 3건에만 최초 확정 시각 생성
  반복: reconcile 재호출 + callback 재전송 → 확정·예약·Outbox 사실 수 불변
  타인 헤더로 reconcile → 404
  증거: recovery.json

@Order(6) RANKING r2 + 후속
  relay.runOnce() → 복구 3건의 Outbox PUBLISHED
  consumer 수신 → product_purchase_activity 20행, 장부 20행 (주문×상품 1회)
  발행 재시도 수와 업무 활동 수를 구분해 기록
  weeklyRanking r2 실행: freeze(입력 20) → score → open → 포인터 r2 로 이동
  assert 랭킹 API score 20, rank 1
  assert r1 의 weekly_ranking_scores 행이 여전히 17 (공개본 불변)
  증거: ranking-r2.json, downstream.json (랭킹 API 원문 포함)

@AfterAll / finally
  자신이 켠 drop 해제, fixture close, DatabaseCleanUp (BATCH_* 는 건드리지 않음)
  실패해도 거래·증거 파일은 초기화하지 않는다
```

**reconcile 응답 코드별 분기** (@Order(5) 에서 각 케이스를 테스트로 확인한다)

| 응답 | 의미 | 테스트가 확인할 것 |
| --- | --- | --- |
| 200 + `CONFIRMED`/`SUCCESS` | 내부 확정 반영 | 같은 거래키 저장, 예약 CONSUMED, Outbox 1건, 다음 단계로 진행 |
| 200 + `PAYMENT_FAILED` | PG 실패 결과 반영, 예약 반환 가능 | 성공 복구로 처리하지 않고 실패 원인·반환 이력 보존 |
| 202 + `lookupUnavailable=false` | 0건 또는 검증된 PENDING | 조회 결과와 저장 상태를 대조한다. PENDING과 거래 키가 기록될 수 있으며 임의 확정 없이 중단한다. |
| 202 + `lookupUnavailable=true` | 조회 불가·서킷 차단·거래 충돌 | 관측 설계 문서 §6에 따라 이번 요청의 결정 로그와 최신 DB 기록을 대조하고 중단한다. |
| 그 밖의 오류 | 소유자·접속 대상 문제 | 상태 보존 후 중단. 타인 reconcile 404 가 이 범주 |
| 통신 실패·시간 초과 | 결과를 받지 못함 | **처리 안 됨으로 단정하지 않는다.** 즉시 재호출하지 않고 DIAGNOSE 부터 현재 상태 재확인 |

충돌 HTTP 409는 callback 경로에서 확인한다. 수동 reconcile의 HTTP 202만으로 조회 실패와 거래 충돌을 구분할 수 없다.

랭킹 revision 설계 판단: r1 은 **복구 이전에 실제로 저장된 구매 활동 17건**(baseline 10 + fault 정상 7)을 freeze 해 공개하고, 복구 후 r2 가 20건을 freeze 해 공개한다. r1 을 기대값으로 손으로 넣지 않고 relay·Kafka·consumer 를 실제로 돌려 만든다. 그래서 "이미 공개한 snapshot 은 늦게 도착한 사실로 바뀌지 않는다"(r1 은 끝까지 17)와 "새 revision 이 복구를 반영한다"(r2 는 20)를 같은 테스트에서 보인다. 기대 점수 20 은 그 주 전체 입력이 baseline 10 + fault 10 뿐일 때의 값이며 baseline 을 몰래 제외하지 않는다.

### 3-5. 증거 (R8 / G5)

- 위치: `apps/commerce-api/build/incident-evidence/<runId>/` (`runId` = `yyyyMMdd-HHmmss-<랜덤8>`). 실행마다 새 디렉터리이므로 과거 실행을 덮어쓸 수 없다. 빌드 산출물이라 저장소를 오염시키지 않고, 제출 시 필요한 1회분만 `docs/week10/evidence/` 로 복사한다.
- 파일: `baseline.json`, `fault.json`, `fault.json.sha256`, `diagnosis.json`, `ranking-r1.json`, `recovery.json`, `ranking-r2.json`, `downstream.json`, `run.json`(실행 ID·시각·단계 결과·종료 상태·실패 여부).
- 내용은 전부 **실제 실행 결과**를 직렬화한다. 기대값 하드코딩 JSON 을 증거로 만들지 않는다. health·prometheus 는 HTTP 로 받은 본문 원문을 그대로 넣는다.
- `fault.json` 은 FAULT 종료 시 쓰고 **이후 단계는 열지만 쓰지 않는다**. 뒤 단계가 쓰기를 시도하면 테스트가 실패하도록 "이미 존재하면 실패" 규약을 둔다.
- 증거 작성 헬퍼는 테스트 전용: `src/crossAppTest/java/com/loopers/crossapp/IncidentEvidence.java` (작은 파일 1개, Jackson 사용).
- marker 파일 존재만으로 통과시키지 않는다. 모든 기대값은 테스트 assertion 으로도 검증한다.
- S5 완료 실행 `20261126-221445-c02e381c`의 실측값, 선행 실험 기록 및 제출 증거는 [experiment-results.md](experiment-results.md)에 보존했다.
- S6은 별도 실행 `20261126-222348-37dab0a6`에서 BASELINE과 FAULT를 재현한 뒤 같은 테스트 메서드에서 진행했다. 가설별 판정과 읽기 전후 상태는 [실험 결과 보고서](experiment-results.md)에 기록했다.

### 3-6. Runbook (R9 / G6)

`docs/runbook/week10.md` 를 실제 엔드포인트·테이블·컬럼 이름으로 쓴다. 담을 절: 증상 / 영향 범위 산정 / health·`commerce.payment.unresolved` 확인 / `payment_attempts`·`orders`·`stock_reservations`·`order_confirmed_outbox` 조회 SQL / 로그 grep 키(`pg.create`, `pg.lookup`, `payment.create`, `payment.apply`, `payment.reconcile`) / 경쟁 가설 구분 질의 / 조치 전제(본인 주문·금액 일치·단일 거래) / `POST /api/v1/orders/{orderId}/payment/reconcile` 절차 / 중단 조건(거래 0건·복수·금액 불일치·계속 PENDING·조회 불가) / 복구 후 대사 항목 / 재진입 절차 / 재발 방지.

DB 를 직접 성공으로 바꾸는 UPDATE 나 재결제(create 재호출) 명령은 넣지 않는다.

---

## 4. 단계별 구현 순서

각 단계 끝에 `git diff --stat` 과 아래 검증을 확인하고 멈춘다.

| 단계 | 작업 | 파일 수 | 검증 |
| --- | --- | --- | --- |
| S1 | 미종결 상태별 건수 **집계 쿼리 1회** 메서드 + Impl + 통합 테스트 | 3 | 새 repository 테스트 통과 |
| S1b | 같은 쿼리에 `min(created_at)` 추가, 반환 타입을 `List<UnresolvedStatusSummary>` record 로 확장 | 4 | 기존 S1 테스트 유지·보완 + 최소 시각 집계 테스트 |
| S2 | `PaymentUnresolvedMetrics` + 스케줄러 + property + 단위·통합 테스트 | 5 | 지표 4종, 미수집 NaN, 실패 시 유지, 실제 Prometheus 응답 |
| S3 | 구조화 로그 4곳 추가 | 4 | `HttpPaymentGatewayContractTest`(카드번호 미노출) 포함 결제 테스트 전체 통과 |
| S4 | `TestPaymentPgServer` 거래 보관 모드 + 주문별 응답 유실 + `FakePaymentGatewayConfig` 조건부화 | 2 | `HttpPaymentGatewayContractTest` 통과(기존 계약 비파괴), 신규 fixture 자체 테스트 |
| S5 | 사고 테스트 BASELINE + FAULT, Scheduler 자동 갱신, 증거 헬퍼 | 2 | 7/3/10, 누적 20, health UP, Scheduler 자동 집계로 Prometheus의 UNKNOWN 건수 3 |
| S6 | DIAGNOSE(읽기 전용) + 가설 표 + **사후 불변 확인** | 1 | 단계 전후 attempt·거래·createCount·Outbox 불변 assert |
| S6b | RANKING r1 (relay → Kafka → 활동 17 → r1 freeze·공개) | 1 | 활동 17행, r1 score 17, 랭킹 API 공개 |
| S7 | RECOVERY + 불변 조건 + **응답 코드별 분기** | 1 | 10/0/10, create 증가 0, 예약·재고·포인트·쿠폰 불변, reconcile 200/202/404·통신 실패, callback 충돌 409 |
| S8 | RANKING r2 + 후속 (활동 20 → r2 → 랭킹 API) | 1 | 활동 20행, r2 score 20, r1 행은 17 그대로 |
| S9 | `docs/runbook/week10.md` | 1 | 실제 경로·SQL 대조 |
| S10 | 회귀 전체 + 기능별 확인/미확인 정리 | 0 | §5 명령 전부 |

S5~S10은 과제 필수 단계다. S4 이후 발견한 결제 어댑터 결함의 정정은 관측 설계 §8.2에 기록한다. 운영 알림 임계값, 대규모 집계 비용, 별도 집계 시스템 및 분산 추적은 향후 개선으로 둔다.

---

## 5. 검증 명령

```bash
# 단위·통합
./gradlew :apps:commerce-api:test --tests '*PaymentUnresolvedMetrics*'
./gradlew :apps:commerce-api:test --tests '*HttpPaymentGatewayContractTest'
./gradlew :apps:commerce-api:test --tests '*Payment*'
./gradlew :apps:commerce-api:test --tests '*Outbox*'
./gradlew :apps:commerce-api:test --tests '*CouponIssueConcurrencyTest'

# 사고 연결 테스트 (crossAppTest source set)
./gradlew :apps:commerce-api:crossAppTest --tests '*CheckoutIncidentE2ETest' --rerun

# 랭킹 회귀
./gradlew :apps:commerce-batch:test --tests '*WeeklyRankingJobRestartTest'
./gradlew :apps:commerce-batch:test --tests '*Ranking*'

# 아키텍처·Checkstyle 포함 최종
./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check
```

`UP-TO-DATE` 는 실행이 아니다. 사고 증거를 만드는 실행에는 `--rerun` 을 붙인다. 테스트 0건·skip·컨테이너 연결 실패는 통과로 세지 않는다.

---

## 6. 예상 문제점과 트레이드오프

| # | 문제 | 대응 |
| --- | --- | --- |
| P1 | `CrossAppTestConfig` 가 `@Primary` 가짜 PG 를 import 해 실제 adapter 가 안 쓰인다 | `FakePaymentGatewayConfig` 빈을 `@ConditionalOnProperty(matchIfMissing=true)` 로. 기존 테스트 동작 불변 |
| P2 | `management.server.port: 8081` 에 test 오버라이드가 없어 포트 충돌 가능 | 사고 테스트에서 `management.server.port=0` + 주입된 실제 포트 사용 |
| P3 | read timeout 3건 × `PT3S` = 9초 | 사고 테스트만 `payment.pg.read-timeout=PT0.5S` |
| P4 | `payment.pg.enabled=false`(test profile) 때문에 `requireEnabled()` 가 던진다 | 사고 테스트 properties 에서 `true` 로 올린다. 이 플래그가 "가짜 PG 를 잊으면 크게 실패"하게 하는 안전장치이므로 다른 테스트에서는 그대로 둔다 |
| P5 | 가설 2(응답 유실)와 가설 3(내부 TX 실패)이 같은 관찰(PG SUCCESS + 내부 UNKNOWN)을 만든다 | 관측 설계 §5에 따라 동일 주문·거래의 외부 결과와 내부 완료 증거를 대조한다. 증거가 부족하면 원인 미확정으로 기록한다 |
| P6 | Gauge 가 캐시 지연된 근사치 | 테스트는 Awaitility 유한 대기로 값 변화를 본다. 상태 JSON 숫자를 Prometheus 원문처럼 저장하지 않고, 두 출처를 따로 기록한다 |
| P7 | relay scheduler 가 test profile 에서 OFF | 테스트가 `runOnce()` 를 명시적으로 호출한다(기존 선례와 동일). 새 scheduler 를 추가하지 않는다 |
| P8 | `weekly_ranking_*` / `BATCH_*` 테이블 생성 | `spring.batch.jdbc.initialize-schema=always`, `DatabaseCleanUp` 은 `BATCH_*` 를 건드리지 않는다(기존 결정 유지) |
| P9 | 고정 Clock 이 `confirmedAt` 과 `frozenAt` 을 동시에 지배 | 단계별로 Clock 값을 전진시키되 전부 같은 ISO 주 안에 둔다. PG 요청 시각이나 복구 시각으로 `confirmedAt` 을 덮어쓰지 않는다 |
| P13 | **Spring Boot 가 테스트에서 metrics export 를 끈다.** `@SpringBootTest` 가 `management.defaults.metrics.export.enabled=false`·`management.simple.metrics.export.enabled=true` 를 주입해 `MeterRegistry` 가 `SimpleMeterRegistry` 가 되고 `/actuator/prometheus` 가 **404** 가 된다 | 실제 Prometheus 응답을 보는 테스트에 `@AutoConfigureObservability(tracing = false)` 를 붙인다. 운영 설정 문제가 아니므로 `monitoring.yml` 을 건드리지 않는다. 사고 테스트(S5)에도 같은 애너테이션이 필요하다 |
| P11 | 인스턴스 N대가 같은 DB를 집계해 `sum()`이 중복 집계된다 | 동일 환경·DB 범위에서 최신성 필터를 적용한 뒤 `max by (state)`로 근사 관측한다. S9에서 실제 라벨을 확인한다. |
| P12 | 기본 스케줄러 스레드를 결제 조회·쿠폰 스케줄러와 공유해 집계가 늦어질 수 있다 | 값이 틀리는 것이 아니라 낡는다. 마지막 집계 성공 시각의 경과로 관측하고 전용 풀은 두지 않는다 |
| P10 | 미커밋 `src/perfTest` 소스가 있으나 source set 설정이 없고 참조 클래스가 존재하지 않는다 | 이번 과제 범위 밖. 손대지 않고 **사실만 기록**한다. `:commerce-api:check` 에 영향 없음 |

---

## 7. 종료 판단

두 가지는 서로 다른 질문이다. 7-1 은 **사고 1건을 어디까지 처리했는가**(운영자·runbook 용어)이고, 7-2 는 **과제를 다 만들었는가**(제출 기준)이다. 섞으면 "테스트는 통과했는데 사고는 미해결" 같은 상태를 표현할 수 없다.

### 7-1. 사고 대응 종료 상태 (대상 주문별로 하나를 고른다)

| 종료 상태 | 뜻 | 남겨야 할 것 |
| --- | --- | --- |
| 원인 미확정·중단 | 증거가 부족하거나 모순돼 결과 반영을 하지 않았다 | 확보하지 못한 조회·호출·TX 기록, 재진단 진입점 |
| 복구 미완료 | reconcile 이후 성공 확정을 확인하지 못했다(202·실패 결과·통신 실패 등) | 실제 응답 원문, 보존된 상태, 중단 사유 |
| 주문 복구 완료·후속 미완료 | 결제·주문은 확정됐으나 Outbox·활동·랭킹 중 일부가 안 맞는다 | 어느 경계까지 됐는지, 결제 복구를 반복하지 않았다는 사실 |
| 후속까지 확인 완료 | 결제·주문·예약·Outbox·활동·랭킹이 모두 대사됐다 | 복구 전후 동일 ID 대조 결과 |

규칙: 일부만 끝났으면 완료 대상과 미완료 대상을 나눠 적는다. 후속 결과가 맞아도 관측·보존 실패를 숨기지 않는다. 상태가 사라진 주문을 복구했다고 기록하지 않는다.

### 7-2. 과제 완료 판단 기준

1. `payment_attempts` 기준 baseline 10/0, fault 7/3, recovery 10/0 이 **같은 주문 집합**에서 재조회로 확인된다.
2. fixture `createCount` 가 FAULT 종료 시점과 RECOVERY 종료 시점에 동일하다(복구 중 create 증가 0).
3. 장애 집합의 PG 거래가 10건으로 유지되고 전부 검증된 SUCCESS 다.
4. 예약 CONSUMED 20건, 상품 재고가 예약 시점 차감분 그대로이고 추가 차감이 없다.
5. 포인트 잔액·쿠폰 상태가 복구 전후 동일하다.
6. reconcile·callback 반복 호출 후 확정·예약·Outbox·구매 활동 수가 늘지 않는다. 타인 reconcile 은 404.
7. `product_purchase_activity` 20행, 장부 20행.
8. r1 이 복구 이전 실제 활동 17건으로 공개돼 점수 17 이고, 복구 후 r2 가 점수 20 이며, r1 의 점수 행은 끝까지 17 로 남는다.
9. `/actuator/health` UP 과 `/actuator/prometheus` 의 `commerce_payment_unresolved` 값 변화가 원문으로 보존됐다.
10. 실행별 증거 디렉터리에 위 9개 파일이 있고 `fault.json` 이 덮어써지지 않았다. 주문별 7-1 종료 상태가 `run.json` 에 기록됐다.
11. `docs/runbook/week10.md` 가 실제 엔드포인트·SQL·중단 조건을 담는다.
12. §5 의 모든 명령이 새 실행으로 통과하고, 기능별 "확인한 증거 / 미확인 위험"이 최종 요약에 구분돼 있다.
13. 지표가 `state` 3개를 항상 보고하고, 해당 상태가 0건일 때 Gauge 가 사라지지 않고 0 을 보고한다.
14. 롤백은 `phase=rolled-back` 이라는 적극적 증거로만 판정하고, `phase=attempt` 만 있는 경우는 "최종 결과 미확인"으로 분류한 뒤 DB 상태로 결론낸다.
15. 지표가 0 건과 수집 실패를 구분한다. 집계 실패 시 마지막 성공값이 유지되고 마지막 성공 시각 이후 경과 시간이 늘며 `commerce_payment_unresolved_refresh_total{result="failure"}` 이 증가한다.

**이번 실험이 증명하지 않는 것**(미검증 위험으로 명시): 운영 배포, 실계정·실 PG, 부하·처리량, 월간 SLO, 모든 종류의 장애, 분산 추적, broker 장기 장애, 전체 기간 랭킹 정확성.
