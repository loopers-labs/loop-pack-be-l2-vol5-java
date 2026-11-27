# W10 기능별 검증 범위와 남은 위험

> 이번에 확인한 것은 **로컬 통제 사고 1건의 진단·복구와 관련 회귀 통과**다. 운영 배포·실계정·부하·모든 장애 유형은 검증하지 않았다.

## 실행한 검사 (2026-11-27, 커밋 `5198652`, `--rerun-tasks`로 새로 실행)

```bash
./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check :apps:commerce-api:e2eTest --rerun-tasks
```

| 대상 | 결과 |
|---|---|
| commerce-api `check`(테스트·Checkstyle·ArchUnit) | 1034건, 실패 0, skip 7(환경변수가 있을 때만 도는 성능 벤치마크 `ProductCacheLatencyBenchmarkTest`·`ProductQueryLatencyBenchmarkTest`) |
| commerce-api `e2eTest` | 2건 통과: `CheckoutIncidentE2ETest`(새 사고 실행 `20261127T084508Z-def0fe`), `OutboxDeliveryE2ETest` |
| commerce-streamer `check` | 19건, 실패 0 |
| commerce-batch `check`(Checkstyle·ArchUnit 포함) | 26건, 실패 0 (`RankingJobRestartTest`·`WeeklyRankingFlowTest` 포함) |

과제 예시의 `*CouponIssueConcurrencyTest`는 이 저장소에 없다. 쿠폰 총량·중복·수용 실패는 아래 표의 테스트가 담당한다.

## 기능별 확인한 계약과 남은 위험

| 기능 | 이번에 확인한 증거 | 확인하지 않은 위험 |
|---|---|---|
| 상품·브랜드·좋아요·관리자 | 기존 회귀(`Product*`·`Brand*`·`Like*`·`Admin*`, 캐시 장애·무효화·쿼리 수, 좋아요·상품 생성 동시성) 통과 | 이번 변경과 무관해 새 시나리오 없음. 캐시 성능 벤치마크는 skip |
| 포인트·주문·PG | 기존 결제 회귀(`PaymentIntegrationTest`의 0건·복수·불일치·PENDING·재시작, `Payment*Concurrency`, `PaymentProviderContractTest`) 통과. 사고 e2e에서 create 추가 0·중복 0·재고 이중 차감 없음·포인트 불변·타인 reconcile 404. 경계 로그(`PaymentBoundaryLogTest`), 미종결 gauge(`UnresolvedPayment*`), PG trace 전파(`PgTracePropagationTest`) | 실제 PG·카드·네트워크 timeout(이번 유실은 연결 끊김 IO). 자동 reconcile 없음(사람이 호출). 동시 다발 UNKNOWN·대량 주문 부하 |
| 이벤트·Outbox | Outbox 기록·relay·중단 후 재발행·소비 중복 방지(`Outbox*`, `OutboxDeliveryE2ETest`, streamer `PurchaseActivityIntegrationTest`). Outbox trace 복원(`OutboxTracePropagationTest`). 사고 e2e에서 실제 scheduler relay → Kafka → streamer로 20건·주문당 활동 1행 | broker 장기 장애 중 사고 복구, BLOCKED 격리 건의 운영 처리. 운영 DDL(`trace_parent` 컬럼) 적용은 수동 SQL |
| 쿠폰 | 기존 회귀(`CouponIssueFacadeTest`·`CouponAdmissionTest`·`CouponRedisFailureTest`·`CouponIssueWriterTest`·`CouponApiE2ETest`) 통과. 사고 e2e에서 PG 복구 전후 본인 쿠폰 목록 불변 | 쿠폰은 결제 할인에 결합되지 않아 결제 사고의 원인 후보가 아님. 쿠폰 부하(W8) 재측정 안 함 |
| 랭킹 | batch 재시작·늦은 revision·SUPERSEDED·공개 rollback(`RankingJobRestartTest`·`WeeklyRankingFlowTest`) 통과. 사고 e2e에서 실제 batch 프로세스로 새 snapshot `weekly-20261123-r1` → 공개 API 점수 20·1위 | 주 경계를 넘는 확정(고정 시계로 같은 주만 확인). 복구 뒤 이미 공개된 주에 대한 revision 2 흐름은 W9 테스트로만 확인 |
| 이번 사고 | 같은 DB·PG 상태로 10·0·10 → 7·3·10 → 10·0·10, health UP·Prometheus UNKNOWN 0→3→0, 가설 판정(지연 기각·유실 지지·반영 실패 기각), fault 원문 hash 보존 | 내부 반영 실패(가설 3) 자체는 사고로 재현하지 않음(경계 로그는 `PaymentBoundaryLogTest`가 실제 rollback으로 확인). 10건 통제 실험이라 SLO 근거 아님 |
| 관측(로그·지표·추적·알람) | local JSON 파일에 MDC·key-value·traceId 기록(수동 실행), Tempo trace 저장·Prometheus 수집·Grafana 규칙 3개 로드(수동 실행), trace가 PG·Outbox·Kafka·streamer로 이어짐(e2e) | Slack 실제 발송·알람 발화, streamer 지표 수집, 비동기 로그 폐기 건수, 운영 환경 로그 수집 |

## 정리한 자원

- 수동 확인 때 띄운 임시 MySQL(`w10-logcheck-mysql`), 모니터링 스택(`w10-moncheck`), 로컬 api 프로세스만 내렸다. 사용자의 기존 인프라 컨테이너는 건드리지 않았다.
- 테스트의 streamer·batch 프로세스는 테스트가 종료했고, Testcontainers 컨테이너는 Testcontainers가 정리한다.
