## 📌 Summary

health는 **UP**인데 결제 3건이 **UNKNOWN**으로 남은 사고(PG는 SUCCESS로 저장, 생성 응답만 유실)를 진짜 프로세스로 일으키고, 증거로 원인을 가른 뒤 **같은 주문을 기존 reconcile API로 복구**해 Outbox → Kafka → 구매 활동 → 주간 랭킹까지 이었습니다.

- 연결 테스트 = `docs/week10/run.sh` 한 번 (과제 예시 `CheckoutIncidentTest` 대신) — **검사 154개 중 154개 통과** (사고·복구 135 + 관측 저장소 19)
- 증거 = `docs/week10/results/w10-20261125-192654/` (커밋 `c421a264`, 첫 성공 실행은 `w10-20261124-231516/`) · runbook = `docs/runbook/week10.md` · 설계 = `docs/week10/plan.md`
- 회귀 = 세 앱 테스트 707개 통과 · Checkstyle 0 · ArchitectureTest 통과

| 단계 | 재고 | 선점 RESERVED·CONSUMED | 확정 | 결제 UNKNOWN | 업무 지표 UNKNOWN | PG 거래·create | health |
|---|---|---|---|---|---|---|---|
| baseline | 90 | 0·10 | 10 | 0 | 0.0 | 10·10 | UP |
| fault | 80 | **3**·17 | 17 | **3** | **3.0** | 20·20 | **UP** |
| recovery | **80** | 0·20 | 20 | 0 | 0.0 | **20·20** | UP |
| 랭킹 | 상품 1 점수 **20** · 1위 (정상 10 + 장애 10) | | | | | | |

| 가설 | 예상 | 실제 | 판정 |
|---|---|---|---|
| 1. PG가 아직 처리 중 | PG 조회 PENDING | PG 조회 SUCCESS 3/3 | 기각 |
| 2. PG 성공 응답을 잃음 | 로그 ② `UNKNOWN cause=READ_TIMEOUT` · ⑤ 없음 · PG 호출 span ≈ 3초 | 3/3 · ⑤ 0줄 · 느린 PG span 3/3 | **지지** |
| 3. 성공 조회 뒤 내부 반영 실패 | ② ACCEPTED · ⑤ 있음 | ② ACCEPTED 0/3 · ⑤ 0줄 | 기각 |

## 💬 Review Points

1. **업무 지표** `commerce_payment_unresolved{status=PENDING|UNKNOWN}` — gauge, `/actuator/prometheus`를 읽을 때 DB에서 COUNT (갱신 주기 없음). label은 status 두 값뿐이고, 어느 주문인지는 로그·SQL로 찾습니다.
2. **경계 로그 ①~⑤** — 모든 줄에 `orderId`·`paymentId`·`providerOrderId`. 결과 적용 경계(`PaymentResultApplier`)에 "누가 반영했나"(`source`) 인자를 더했습니다. ④는 반영 트랜잭션 **commit 뒤**에 남깁니다 — 안에서 남기면 commit이 실패해도 "반영했다"는 줄이 남기 때문입니다. DB만 보면 가설 2와 3이 같아서(UNKNOWN · 거래 키 NULL · PG SUCCESS) ②⑤로 가릅니다.
3. **분산 추적 (선택)** — Brave → OpenTelemetry bridge + OTLP, 수집은 Tempo(Grafana로 봄).
   - 이은 구간: 확정 요청 → 발송 풀(`ContextPropagatingTaskDecorator`) → PG 호출(RestClient 관측) · Kafka 발행 → streamer 소비(template·listener 관측)
   - Outbox에 `trace_parent`를 남겨 relay가 그 문맥을 부모로 발행 → **reconcile 요청 → 발행 → 소비가 한 trace**
   - 끊긴 채 둔 구간: PG → callback, batch (새 trace, `orderId`·`snapshotId`로 연결)
4. **복구는 create가 아니라 조회** — reconcile은 PG에 거래를 조회해 반영할 뿐 결제를 다시 만들지 않습니다. run.sh가 복구 중 create 수 20 유지 · 중복 create 0 · 재고 80(79면 이중 차감)을 검사합니다. 같은 3건 reconcile 한 번 더 + 보관 callback 전송 뒤에도 Outbox·구매 활동·선점이 그대로입니다.
5. **판단·한계**
   - 브랜드·상품은 SQL로 넣었습니다 — 관리자 API는 ADMIN 인증이 있어 진짜 프로세스에서 부를 수 없습니다. 결제·주문 상태는 직접 쓰지 않았습니다.
   - reconcile API는 **고객용**(본인 주문)입니다. 운영자 전용 경로가 없다는 것을 runbook의 후속 개선·남은 위험에 적었습니다.
   - run.sh가 성공하기 전에 4번 멈췄고, 원인과 고친 것을 `plan.md` "실행 결과"에 남겼습니다 (Redis 목록 설정 · shedlock DDL · trace를 너무 일찍 읽음 · 지난 jar를 고름).
   - 전체 회귀에서 테스트 JVM 메모리 부족(OOM)이 났습니다. 업무 지표 gauge 람다가 서비스를 강한 참조로 잡아, 전역 registry를 거쳐 닫힌 테스트 Spring 문맥이 회수되지 않았습니다 → 서비스를 weak reference로 넘기게 고쳤습니다 (운영 동작은 같음).
6. **관측 화면과 AI 진단 (과제 밖 추가, plan.md D-13)** — 체크리스트 근거는 위 1~5이고, 이것은 "같은 증거를 사람·AI가 화면·질의로 본다"는 실험입니다.
   - 지표 → Prometheus · trace → Tempo · 로그 → **Alloy가 로그 파일을 읽어 Loki** (앱 코드 변경 없음) · 화면 → Grafana 대시보드 `w10-incident`
   - Tempo metrics-generator로 서비스 지도(service graph)와 구간별 span 지표 — APM의 서비스 지도·구간 지연을 대신합니다
   - Loki label은 `app`·`level`만. `orderId`·`traceId`는 줄 안 글자·structured metadata로 찾습니다 (지표 label과 같은 이유)
   - Grafana MCP(읽기 전용) — AI가 같은 저장소를 PromQL·LogQL·TraceQL로 조회해 가설을 판정하고, 사람이 run.sh 판정과 대조합니다. 저장소에는 `docs/week10/example.mcp.json` 틀만
   - **결과**: AI도 주문 12·15·18과 가설 1 기각 · 2 지지 · 3 기각을 냈습니다. 다만 가설 1의 근거가 pg-sim(장애 주입기) 로그였습니다 — 운영이면 볼 수 없는 "정답"이라, 그 증거를 빼면 미확정이어야 합니다. run.sh는 PG 조회 API로 기각했습니다 (`ai-diagnosis/compare.md`)
   - 앱이 연 채로 쓰는 로그는 macOS Docker 파일 공유가 바로 전하지 않아 `log-relay.py`로 사본을 거쳐 Alloy가 읽습니다

## ✅ Checklist

**관측과 진단**
- [x] 실제 health·업무 지표·식별자별 경계 기록으로 증상을 설명한다 — `2-fault/health.json`(UP) · `prometheus.txt`(UNKNOWN 3.0) · `lost-orders-logs.txt`
- [x] 최소 두 가설의 예상과 실제를 비교하고 부족한 증거도 표시한다 — `2-fault/hypotheses.tsv` (증거가 빠지면 "미확정")
- [x] 비밀값·개인정보를 남기지 않고 metric label의 무한 증가를 피한다 — 카드 번호 grep 0건 검사 · label은 status 2개

**동일 업무 복구**
- [x] 같은 DB·PG 거래 상태를 유지해 baseline→fault→recovery를 이어 간다 — 단계마다 MySQL 컨테이너 ID · pg-sim PID@시작 시각 검사
- [x] 장애 집합은 7·UNKNOWN 3·PG 성공 10이며 같은 집합을 10·0·10으로 복구했다
- [x] create 추가 0, 예약·재고·포인트·쿠폰 불변 조건과 중복 적용 방지를 확인했다
- [x] 복구 주문의 실제 Outbox·Kafka·구매 활동·주간 랭킹 API를 연결했다

**증거와 종료**
- [x] fault 원문을 보존하고 runbook에 중단·재진입 조건을 남겼다 — `fault.sha256` 끝에서 재검사
- [x] 관련 누적 회귀·lint·ArchUnit 결과와 미검증 위험을 구분했다 — 아래 "회귀"(707개 통과 · Checkstyle 0 · ArchitectureTest) · "미검증 위험"
- [x] 자신이 시작한 테스트 자원만 정리했으며 운영 완료를 주장하지 않는다 — run.sh가 `$RUN-` 이름의 컨테이너·기록한 PID만 지운다

### 기능별 검증 범위

| 기능 | 기존 대표 테스트 | 이번 run.sh |
|---|---|---|
| 상품·브랜드·좋아요·관리자 | `ProductAdminApiE2ETest` · `BrandDeleteFlowE2ETest` · `LikeConcurrencyTest` | 브랜드·상품은 SQL, 주문 API가 상품 재검증 |
| 포인트·주문·PG | `PaymentResultApplierIntegrationTest` · `S04ResponseLostScenarioTest` · `PointChargeConcurrencyTest` | 응답 유실 → reconcile, create 0 |
| 이벤트·Outbox | `OutboxInterruptionIntegrationTest` · `ConsumedOrderConfirmationTest` | 진짜 relay·Kafka 3대·구매 활동 |
| 쿠폰 | `CouponQueueFlowE2ETest` · `CouponIssueServiceIntegrationTest` | 쓰지 않음 (줄 수 불변만) |
| 랭킹 | `WeeklyScoreRestartTest` · `RankingApiE2ETest` | batch jar → 새 snapshot → 랭킹 API 20 |
| 이번 사고 | `PaymentUnresolvedMetricsIntegrationTest` · `ObservationTraceContextAdapterTest` | run.sh 검사 154개 |

### 회귀

`./gradlew --rerun-tasks --continue :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check` — BUILD SUCCESSFUL (9분 12초), 커밋 `bb82abe7` 기준. 수는 `build/test-results/test/TEST-*.xml`에서 셌습니다.

| 앱 | 클래스 | 테스트 | 실패 | 오류 | skip | Checkstyle |
|---|---|---|---|---|---|---|
| commerce-api | 190 | 610 | 0 | 0 | 0 | main 0 · test 0 |
| commerce-streamer | 17 | 48 | 0 | 0 | 0 | main 0 · test 0 |
| commerce-batch | 9 | 49 | 0 | 0 | 0 | main 0 · test 0 |

- ArchitectureTest 3개 통과 (commerce-api 610개에 포함)
- 첫 전체 실행은 테스트 JVM 메모리 부족(OOM)으로 29개가 실패했습니다 → 업무 지표 gauge 수정(`bb82abe7`) 뒤 0건

### 미검증 위험

| 검증 안 함 | 왜 |
|---|---|
| 운영 배포 · 실제 PG · 부하 | 로컬 pg-sim으로만 일으켰다 |
| 포인트·쿠폰을 섞은 주문의 복구 | 이번 주문은 카드만 |
| 서버 여러 대의 업무 지표 | 한 대만 띄웠다 (값은 합이 아니라 최댓값으로 본다고만 정함) |
| 복구 중 Kafka·DB 장애 | 이번 사고 범위 밖 |
| 월요일 00:00 UTC를 넘는 복구 | run.sh가 그 근처 실행을 거부해 피했을 뿐 |
| 운영자 reconcile 경로 | 없음 — 고객 API이거나 스케줄러를 기다려야 한다 |

## 📎 References

- 설계·결정: `docs/week10/plan.md` (D-1 ~ D-12, 실행 결과, 변경 이력)
- runbook: `docs/runbook/week10.md`
- 증거: `docs/week10/results/w10-20261125-192654/` (`summary.txt` · `checks.tsv` · `5-monitoring/` · `ai-diagnosis/`)
- 다시 돌리기: `bash docs/week10/run.sh` (`KEEP=1`이면 끝난 뒤 대시보드 http://127.0.0.1:33000/d/w10-incident · `PAUSE_AFTER_FAULT=1`이면 fault에서 멈춤 → `docs/week10/ai-diagnose.sh`)
