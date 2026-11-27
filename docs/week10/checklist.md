# 10주차 구현 체크리스트

`docs/week10/implementation-plan.md` 의 S1~S10 과 1:1로 대응한다. 각 단계는 끝에서 멈추고 `git diff --stat` 과 검증 결과를 보고한다.

---

## S1. 미확인 결제 건수 조회 (제품 코드)

- [ ] `domain/payment/PaymentAttemptRepository` 에 미종결 상태별 건수 메서드 추가
- [ ] **집계 쿼리 1회**로 세 상태를 함께 센다 (`GROUP BY status`). 상태마다 쿼리를 날리지 않는다
- [ ] `SUCCESS`·`FAILED` 는 WHERE 에서 **제외**
- [ ] 결과에 없는 상태는 **0건으로 채워** 세 state 가 항상 보고되게 한다
- [ ] `infrastructure/payment/PaymentAttemptRepositoryImpl` 에 QueryDSL 구현 (기존 스타일 유지, 불필요한 추상화 없음)
- [ ] repository 통합 테스트: 상태별 건수, 0건 상태도 0 으로 반환, 종결 후 감소, SUCCESS/FAILED 미포함
- [ ] 검증: `./gradlew :apps:commerce-api:test --tests '*PaymentAttemptRepository*'`

## S1b. 집계 확장 — 가장 오래된 기준 시각

- [ ] 같은 집계 쿼리에 `min(created_at)` 컬럼 추가 (쿼리 **1회 유지**)
- [ ] 반환 타입을 `List<UnresolvedStatusSummary>` record 로 확장 (`status`, `count`, `oldestCreatedAt`)
- [ ] 기준 시각은 `updated_at` 이 아니라 `created_at` (`recordConflict` 가 상태 없이 `updated_at` 을 갱신하므로)
- [ ] 새 컬럼·스키마 변경 **없음**
- [ ] 세 상태를 항상 포함. 0건이면 `count = 0`, `oldestCreatedAt = null` (epoch·0 으로 채우지 않음)
- [ ] 기존 S1 테스트 유지하고 반환 타입 변경에 맞춰 보완
- [ ] 테스트: 가장 오래된 시각이 올바르게 집계되는지, 0건 상태는 부재로 표현되는지
- [ ] 지표의 의미와 한계를 javadoc·계획서에 명시 (상태 머문 시간이 아니라 시도 전체의 미종결 시간)

## S2. 업무 지표 `commerce.payment.unresolved`

**수집 방식: 스케줄러 기반 메모리 스냅샷** (계획서 3-1-2)

- [ ] `application/payment/PaymentUnresolvedMetrics` 생성 (`CouponAdmissionMetrics` 관례)
- [ ] `interfaces/scheduler/PaymentUnresolvedMetricsScheduler` 생성 (`PaymentReconcileScheduler` 모양)
- [ ] 새 `TaskScheduler` 빈·스케줄링 config **만들지 않음** (기본 스케줄러 재사용)
- [ ] `fixedDelay` 사용 (`fixedRate` 아님 — 이전 집계 종료 후 일정한 대기를 보장하고, 밀린 실행이 연이어 따라붙지 않게)
- [ ] 집계 주기 `PT10S`, test profile 은 비활성 + 테스트가 직접 갱신 호출
- [ ] 지표 4종 등록

| 지표 | 타입 | label | 단위 |
| --- | --- | --- | --- |
| `commerce.payment.unresolved` | Gauge | `state` | 건수 |
| `commerce.payment.unresolved.oldest.created.timestamp` | Gauge | `state` | seconds |
| `commerce.payment.unresolved.refresh.last.success.timestamp` | Gauge | 없음 | seconds |
| `commerce.payment.unresolved.refresh` | Counter | `result` | 횟수 |

- [ ] 경과 시간을 애플리케이션이 계산해 노출하지 않음 (PromQL `time() - ts`)
- [ ] 불변 record 스냅샷을 `AtomicReference.set` 으로 통째 교체
- [ ] 스냅샷 참조는 싱글턴 빈의 `final` 필드 (Micrometer 약한 참조 대비)
- [ ] Gauge 는 생성자에서 한 번만 등록
- [ ] repository 의 0건은 실제 0 으로 노출, `oldestCreatedAt` 부재는 `NaN`
- [ ] DB 조회 실패 시 스냅샷 **건드리지 않음**. 마지막 성공값·마지막 성공 시각 유지, failure Counter 증가
- [ ] 최초 집계 전에는 **Gauge 시계열 7개 모두 `NaN`**, Counter 2개는 0 (지표 종류 4개 / 시계열 9개)
- [ ] 지표 description 에 의미·한계 명시 (DB 전체 기준, 다중 인스턴스 질의 주의)

### S2 검증 시나리오

- [ ] 상태별 Gauge 세 개 등록
- [ ] 최초 집계 전 미수집: 건수 3개 NaN, oldest 3개 NaN, 마지막 성공 1개 NaN, Counter 2개 0 (시계열 9개 전부)
- [ ] 미종결 0건 + 기준 시각 부재 처리 (`0` / `NaN`)
- [ ] UNKNOWN 발생 이후 Gauge 갱신
- [ ] 결제 종결 이후 Gauge 감소
- [ ] 가장 오래된 기준 시각이 올바른 값
- [ ] DB 조회 실패 시 마지막 성공 Snapshot 유지
- [ ] 실패 Counter 증가 + 마지막 성공 시각 유지
- [ ] DB 조회 복구 이후 Snapshot 정상 갱신
- [ ] **Gauge 조회가 repository 호출을 유발하지 않음**
- [ ] 실제 Actuator Prometheus 응답에서 네 지표 확인
- [ ] 시간 테스트는 고정 `Clock` 으로. 실제 시간 대기에 의존하지 않음

**S2 에서 검증하지 않고 S5 로 미루는 항목** (중복 테스트 추가 금지)

- [x] S5: `@Scheduled` 자동 실행으로 지표가 갱신됨
- [x] S5: `payment.metrics.unresolved.enabled=true`에서 자동 갱신
- [ ] Scheduler 비활성 전환 동작의 자동 실행 검증
- [x] S5: DB 상태 변경 뒤 유한 대기 안에 자동 갱신
- [ ] DB 집계 실패 뒤 다음 주기 자동 복구
- [ ] 검증: `./gradlew :apps:commerce-api:test --tests '*PaymentUnresolvedMetrics*'`

## S3. 구조화 로그 (4개 파일)

- [ ] 이벤트명·필드·레벨은 [monitoring-design.md](monitoring-design.md) §4의 실제 계약과 대조
- [ ] `HttpPaymentGateway`: `pg.create outcome=ACCEPTED` (INFO), `outcome=UNKNOWN` (WARN), `pg.lookup outcome=FOUND` (INFO), `outcome=UNAVAILABLE` (WARN)
- [ ] `PaymentResultService`: TX 안은 `payment.apply phase=attempt ... settled=` (INFO) 만. 성공 단정 문구 금지
- [ ] `afterCompletion(int status)` 에서 완료 상태별로 기록. `STATUS_COMMITTED` → `phase=committed` (INFO), `STATUS_ROLLED_BACK` → `phase=rolled-back` (WARN), 완료 불명 → `phase=completion-unknown` (WARN)
- [ ] `afterCompletion` 이 불렸다는 사실만으로 롤백이라고 쓰지 않음
- [ ] 직렬화 실패 원인 (ERROR)
- [ ] **롤백 시 성공으로 오인될 로그가 없음**을 테스트로 확인 (committed 줄이 생기지 않음)
- [ ] 판정 규칙을 설계서에 명시: `attempt` 만 있으면 **최종 결과 미확인**이고 DB 상태로 판정 (커밋 직후 프로세스 종료·로그 유실·수집 실패 가능성)
- [ ] 동기화 등록이 업무 로직·TX 경계·반환값을 바꾸지 않음
- [ ] 조건부 UPDATE 0행(`settled=0 result=NOT_APPLIED`)은 해당 적용이 없었다는 기록이다. 현재 상태와 원인은 DB 재조회로 판단
- [ ] 중복 callback·reconcile 을 새 결제 성공으로 기록하지 않음 (`ALREADY_SETTLED`)
- [ ] PG 거래 수락(`outcome=ACCEPTED`)과 내부 커밋(`phase=committed`)을 다른 줄로 구분
- [ ] `phase=committed` 는 TX 커밋이지 결제 성공이 아님. `appliedStatus=FAILED` 커밋도 정상
- [ ] 결제 결과(`targetStatus`/`appliedStatus`)·TX 완료 상태(`phase`)·실제 DB 상태를 구분
- [ ] `STATUS_UNKNOWN` 분기는 구현하되 실행 경로 미검증으로 기록 (억지 재현 금지)
- [ ] 예외 원인은 기존 경계(`ApiControllerAdvice`·스케줄러 catch·relay `last_error`)에 이미 기록됨. 중복 로그 추가 금지
- [ ] 내부 주문·시도 ID와 외부 `providerOrderId`는 기존 로그와 DB 매핑으로 연결
- [ ] 업무 거절은 INFO, 시스템 장애는 WARN, 롤백 원인만 ERROR
- [ ] Gateway 예외 상세의 `exception`·`causeChain`·`interrupted` 확인. 카드번호·인증 헤더·본문·예외 메시지 미기록
- [ ] `OrderFacade.applyCreateResult`: `payment.create outcome=UNKNOWN orderId= attemptId= providerOrderId= unknownRows= conflict=` (WARN)
- [ ] `PaymentFacade`: `payment.callback`과 `payment.reconcile`의 결정 로그 및 각 경로의 필드 확인
- [ ] 검증: `HttpPaymentGatewayContractTest`(카드번호 미노출 ListAppender) 통과
- [ ] 검증: `./gradlew :apps:commerce-api:test --tests '*Payment*' --tests '*Order*'`

## S4. 장애 fixture (테스트 전용)

- [ ] `TestPaymentPgServer` 에 거래 보관 모드 추가 (`enableTransactionStore`)
- [ ] 주문 단위 응답 유실 (`dropCreateResponseFor(providerOrderId)` / `clearResponseDrops`)
- [ ] 실행 순서 고정: ① create 수신 기록 → ② 주문별 호출 횟수 증가 → ③ 거래 생성(중복이면 재생성 안 함) → ④ 장애 대상이면 거래를 **SUCCESS 로 확정 저장** → ⑤ **그 다음에** 응답 유실
- [ ] 외부 성공 사실이 응답 유실 **이전에** 보관됨을 fixture 자체 테스트로 고정
- [ ] 정상 대상은 거래 PENDING + 202 응답, 최종 SUCCESS 는 `markSuccess` 로 따로 (첫 수락과 최종 결과 구분)
- [ ] `markSuccess(providerOrderId)` — callback 은 보내지 않음
- [ ] 주문별 create 호출 수 기록, 중복 create 시 거래는 늘지 않고 호출 사실은 남음
- [ ] 목록 조회가 요청받은 금액·providerOrderId 로 응답해 `conflictOf` 검증을 실제로 통과
- [ ] 기존 "정해진 응답" API 와 `holdResponses()` 보존
- [ ] `clearResponseDrops()` 는 주입만 해제하고 거래·호출 횟수를 **보존**
- [ ] 실제 adapter 의 실패 원인을 로그 원문으로 확인하고, timeout 과 그 밖의 통신 실패만 구분 (원인 임의 확정 금지)
- [ ] `FakePaymentGatewayConfig` 빈에 `@ConditionalOnProperty(name="test.fake-pg.enabled", matchIfMissing=true)`
- [ ] 운영 코드에 장애 주입 스위치 0개
- [ ] 검증: `HttpPaymentGatewayContractTest` + 가짜 PG 를 쓰는 기존 테스트 전부 통과

S5~S10은 과제 필수 단계다. S5 착수 전 권위 조회 금액 검증과 취소 예외의 확인 범위는 [monitoring-design.md](monitoring-design.md) §8.2에 기록한다.

## S5. 사고 테스트 — BASELINE / FAULT

- [x] `CheckoutIncidentE2ETest`에서 `CrossAppTestConfig` 재사용. API·Actuator·PG는 루프백 랜덤 포트, MySQL은 격리된 테스트 컨테이너
- [x] `payment.pg.enabled=true`, `payment.pg.read-timeout=PT0.5S`, `test.fake-pg.enabled=false`, `management.server.port=0`
- [x] 자동 결제 조회는 `payment.lookup.enabled=false`로 유지. 지표는 `payment.metrics.unresolved.enabled=true`, `payment.metrics.unresolved.fixed-delay=PT0.2S`로 자동 갱신
- [x] `@DynamicPropertySource`로 fixture의 base URL 및 callback URL 주입. 정상 callback을 실제 애플리케이션 HTTP API로 전달
- [x] 단일 테스트 메서드에서 BASELINE → FAULT 순으로 실행. 초기 주문·시도·예약·PG 거래·호출 수가 0이며 단계 사이 DB·PG·조회 서킷 초기화 없음
- [x] BASELINE: 정상 주문 10건, 내부 SUCCESS 10, UNKNOWN 0, PG SUCCESS 10, 주문별 create 1회
- [x] FAULT: 실제 생성한 별도 주문 10건 중 3건에만 유실 주입. 내부 SUCCESS 7, UNKNOWN 3, PG SUCCESS 10, 누적 SUCCESS 17/외부 20, 주문별 create 1회
- [x] 장애 대상 3건 모두 PG 호출 도달 및 거래 SUCCESS 저장. callback과 자동 조회 미전달, 내부 UNKNOWN·PAYMENT_PENDING·RESERVED, 추가 재고 차감 및 예약 반환 없음
- [x] 두 단계에서 `/actuator/health` HTTP 200/UP과 `/actuator/prometheus` 원문 수집
- [x] 자동 갱신의 마지막 성공 시각이 각 단계 완료 이후이고 5초 이내임을 확인. UNKNOWN 0 → 3
- [x] `IncidentEvidence`로 실행별 `baseline.json`, `fault.json`, `fault.json.sha256`, `run.json` 보존
- [x] `fault.json` 재기록이 거부되고 checksum이 원문과 일치함을 확인
- [x] `./gradlew :apps:commerce-api:crossAppTest --tests '*CheckoutIncidentE2ETest' --rerun --console=plain` 1건 통과(실패·오류·skip 0건)
- [x] [실험 결과 보고서](experiment-results.md) 작성 및 실행 `20261126-221445-c02e381c`의 제출 증거 보존. 원본 바이트 및 FAULT 체크섬 일치 확인

## S6. DIAGNOSE — 읽기 전용

- [x] 가설 3개(PG 지연 / 응답 유실 / 내부 TX 반영 실패)의 예상·실제·판정 기록. [실험 결과 보고서](experiment-results.md) §4 참조
- [x] `pg.create outcome=UNKNOWN`과 동일 거래의 권위 조회 및 TX 완료 기록을 대조. PG 저장 시각은 증거 부족으로 미확정 처리
- [x] fixture의 권위 목록 HTTP GET 3회. 제품 reconcile 및 추가 create 0회
- [x] 주문·시도·예약·Outbox는 DB 읽기만
- [x] 내부 업무 상태는 DB로 확인. 응답 유실은 가장 지지된 가설로 기록
- [ ] `attempt`만 있는 별도 사례의 실제 실행 검증
- [x] 진단 전후 동일 쿼리로 주문·시도·예약·Outbox 불변 확인. PG 거래·create 수·재고 불변 assert
- [ ] 달라졌다면 PG 조회가 바꿨다고 단정하지 않고 현재 상태로 재진단 후 기록
- [x] 증거 부족한 SUCCESS 저장과 timeout의 정확한 시간 순서는 미확정으로 기록
- [x] 같은 runId의 `diagnosis.json` 기록, `fault.json`과 체크섬 미변경 확인

## S6b. RANKING r1 — 복구 이전 실제 활동 17건 (완료: weekly-20261123-r1, score 17)

- [ ] `OrderConfirmedOutboxRelay.runOnce()` 반복 → 확정 17건 Outbox 전부 PUBLISHED
- [ ] consumer 수신 대기 → `product_purchase_activity` 17행, 장부 17행
- [ ] 장애 3건의 Outbox 행이 아직 없음 확인 (확정되지 않았으므로)
- [ ] `weeklyRanking` r1 실행: freeze(입력 17) → score → open
- [ ] 랭킹 API 가 r1 공개, 해당 상품 score 17
- [ ] 기대값을 손으로 넣지 않고 relay·Kafka·consumer 를 실제로 돌려 만든 입력인지 확인
- [ ] `ranking-r1.json` 기록

## S7. RECOVERY — 같은 주문 복구 (완료: 20261127-050500-5e8f1e57)

- [ ] `clearResponseDrops()` 후 drop 집합이 비었음을 assert
- [ ] 같은 3개 orderId 로만 `POST /api/v1/orders/{orderId}/payment/reconcile` (본인 `X-USER-ID`)
- [ ] 장애 집합 10/0, 거래 10건 동일, 누적 20
- [ ] **복구 중 create 증가 0** (FAULT 종료 스냅샷과 비교)
- [ ] 예약: UNKNOWN 동안 RESERVED 유지 → 복구 후 CONSUMED, 재고 추가 차감 없음
- [ ] 포인트 잔액·쿠폰 상태 불변
- [ ] reconcile 재호출 + callback 재전송 → 확정·예약·Outbox 사실 수 불변
- [ ] 타인 헤더 reconcile → 404
- [ ] 이미 저장된 confirmedAt 보존, 새 확정 3건에만 최초 확정 시각 생성
- [ ] 수동 reconcile: 200 CONFIRMED / 200 PAYMENT_FAILED / 202 미종결. `lookupUnavailable=false`는 0건·검증된 PENDING, `true`는 조회 불가·서킷 차단·충돌
- [ ] 202 + `lookupUnavailable=true`는 이번 요청의 `payment.reconcile` 결정, `pg.lookup` 사유, 최신 DB 충돌 기록을 [monitoring-design.md](monitoring-design.md) §6에 따라 대조. callback 충돌은 별도로 409 확인
- [ ] 통신 실패·시간 초과는 처리 안 됨으로 단정하지 않고 즉시 재호출하지 않음 (진단부터 재확인)
- [ ] 중단 조건 경로를 별도 테스트로 확인: 거래 0건 / 복수 / 금액 불일치 / 계속 PENDING 시 임의 성공 없음
- [ ] DB 직접 UPDATE 로 통과시키지 않음
- [ ] `recovery.json` 기록

## S8. RANKING r2 + 후속

- [ ] `runOnce()` → 복구 3건의 Outbox PUBLISHED
- [ ] consumer 수신 → `product_purchase_activity` 20행, `processed_order_confirmation` 20행 (주문×상품 1회)
- [ ] 발행 재시도 수와 업무 활동 수를 구분해 기록
- [ ] `weeklyRanking` r2 실행: freeze(입력 20) → score → open → 포인터 r2 로 이동
- [ ] 랭킹 API score 20 / rank 1 (원문 응답 보존)
- [ ] **r1 의 점수 행이 여전히 17** (이미 공개한 snapshot 불변)
- [ ] `confirmedAt` 을 PG 요청 시각·복구 시각으로 덮어쓰지 않았음 확인
- [ ] `ranking-r2.json`, `downstream.json` 기록
- [ ] `@AfterAll`: 자신이 켠 drop 해제, fixture close, `DatabaseCleanUp`(BATCH_* 제외). 실패해도 거래·증거 초기화 금지

## S9. Runbook

- [ ] `docs/runbook/week10.md` 생성
- [ ] 증상 / 영향 범위 산정
- [ ] health·`commerce.payment.unresolved` 확인 방법 (지표는 지연된 근사임을 명시)
- [ ] 관리 포트 접근 범위와 사고 증거에서 제외할 민감정보 확인. 현재 보안 경계의 검토 결과는 [monitoring-design.md](monitoring-design.md) §7 참조
- [ ] **다섯 상태 구분**: 실제 0건 / 정상 갱신 / 실패 후 마지막 성공값 유지 / 허용 시간 초과한 낡은 스냅샷 / 최초 집계 전 미수집
- [ ] 최신성은 `time() - ..._refresh_last_success_timestamp_seconds` 로 판단. 경과 시간 증가 자체를 실패로 읽지 않음
- [ ] 다중 인스턴스: `sum` 은 중복 합산, `max` 는 낡은 값 잔류. **최신성 필터 후 max** 질의를 적는다
- [ ] 최신성 필터를 거친 max 도 **정확한 업무 건수를 보장하지 않음**을 적는다 (정확한 값은 DB 직접 조회)
- [ ] 한 scrape에서 스냅샷 교체 전후 값이 섞일 수 있음을 명시
- [ ] 집계 쿼리 전용 query timeout 은 기술적으로 가능하나(QueryDSL `setHint`) 측정 근거가 없어 **운영 개선 항목**으로 남긴다
- [ ] 모든 인스턴스가 낡으면 빈 벡터이고 이는 "0건"이 아니라 **관측 불가**임을 적는다
- [ ] 미종결 지속 시간은 `created_at` 기준이며 상태에 머문 시간이 아님을 적는다
- [ ] 인덱스 재검토 트리거(행 10만, 1회 50ms)는 **잠정 기준**임을 명시해 적는다
- [ ] `payment_attempts`·`orders`·`stock_reservations`·`order_confirmed_outbox` 조회 SQL
- [ ] 로그 grep 키: `pg.create`, `pg.lookup`, `payment.create`, `payment.apply`, `payment.reconcile`
- [ ] 경쟁 가설 구분 질의
- [ ] 조치 전제: 본인 주문 / 단일 거래 / 금액 일치
- [ ] reconcile 실행 절차 (실제 엔드포인트)
- [ ] 중단 조건: 조회 불가 / 0건 / 복수 / 금액 불일치 / 계속 PENDING
- [ ] 복구 후 대사 항목 (상태 + 효과)
- [ ] 재진입 절차 (실패 상태 보존 후 안전한 다음 조회)
- [ ] 재발 방지: 확인 가능한 한도 문장으로 작성
- [ ] DB 강제 UPDATE·재결제 명령 **없음**

## S10. 회귀와 종료

- [ ] `./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check`
- [ ] `./gradlew :apps:commerce-batch:test --tests '*WeeklyRankingJobRestartTest'` (chunk restart 회귀 생략 금지)
- [ ] `./gradlew :apps:commerce-api:test --tests '*CouponIssueConcurrencyTest'`
- [ ] ArchUnit 3개 규칙 통과 (도메인·application·interfaces 의존 방향)
- [ ] Checkstyle 0건 (`AvoidStarImport`, `UnusedImports`)
- [ ] 주문별 **사고 대응 종료 상태** 4가지 중 하나로 기록 (원인 미확정·중단 / 복구 미완료 / 주문 복구 완료·후속 미완료 / 후속까지 확인 완료)
- [ ] 위 종료 상태와 **과제 완료 판단 기준**(계획서 7-2 의 14개 항목)을 섞지 않고 따로 보고
- [ ] 기존 불변 조건·테스트 검증 항목을 삭제하지 않았음 확인
- [ ] 기능별 "확인한 증거 / 미확인 위험" 표 작성 (상품·브랜드·좋아요·관리자 / 포인트·주문·PG / 이벤트·Outbox / 쿠폰 / 랭킹 / 이번 사고)
- [ ] `UP-TO-DATE`·skip·0건·컨테이너 연결 실패를 통과로 세지 않음
- [ ] 실패한 명령과 원인을 최종 요약에 기록
- [ ] 제출용 증거 1회분을 `docs/week10/evidence/` 로 복사
- [ ] 기술 글 주제 1개 선정 및 작성

---

## 전역 금지 사항

- [ ] 운영 환경·실계정·외부 PG 에 장애 주입 없음
- [ ] 테스트·기대값·ArchUnit·Checkstyle 규칙을 통과시키려 삭제·완화하지 않음
- [ ] 운영 코드에 장애 주입 로직 추가 없음
- [ ] 과제 범위 밖 리팩토링 없음
- [ ] 새 라이브러리·모듈 의존 추가 없음
- [ ] 사용자 요청 없는 commit·push·PR 없음
- [ ] 분산 추적을 구현하지 않았으므로 "분산 추적 완료"라고 쓰지 않음
- [ ] 로컬 통제 실험 성공을 운영 완료·SLO 달성으로 주장하지 않음
- [ ] 미커밋 `src/perfTest` 소스는 건드리지 않음 (source set 설정 없음, 참조 클래스 부재 — 사실만 기록)
