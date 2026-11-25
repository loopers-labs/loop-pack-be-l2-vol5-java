# W10 검증 범위

이번 작업물(`volume-10`)에서 새로 실행한 결과만 적는다. 이전 UP-TO-DATE 기록은 쓰지 않았다. 모두 로컬 Testcontainers(MySQL·Redis·Kafka) 위에서 실행했다.
**로컬 통제 사고·관련 회귀가 성공했다는 뜻이지, 운영 배포·실계정·실제 PG·부하·모든 장애를 검증했다는 뜻이 아니다.**

## 실행한 검사

```bash
./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check :tests:commerce-e2e:check --rerun-tasks
```

| 모듈 | 테스트 | skip | 실패 | 정적 검사 |
|---|---|---|---|---|
| commerce-api | 582 | 0 | 0 | checkstyle main·test 0, ArchUnit 통과 |
| commerce-streamer | 32 | 0 | 0 | checkstyle 0, ArchUnit 통과 |
| commerce-batch | 20 | 0 | 0 | (checkstyle 미설정 모듈) |
| commerce-e2e | 12 | 0 | 0 | checkstyle 0 |

사고 실험 단독: `./gradlew :tests:commerce-e2e:test --tests '*CheckoutIncidentTest'` — 증거 [`evidence/20261125T074911Z-9bacc715`](evidence/20261125T074911Z-9bacc715/manifest.json).

## 기능별 확인한 것과 남은 위험

| 기능 | 이번에 확인한 증거 | 확인하지 않은 것·남은 위험 |
|---|---|---|
| 상품·브랜드·좋아요·관리자 | `Product*`·`Brand*`·`ProductLike*` 통합/E2E, `ProductCacheIntegrationTest`·`ProductCacheRedisOutageTest`(캐시 보호), `WeeklyRankingFlowTest`(삭제 상품 제외) | 이번 변경과 직접 관련 없음. 회귀만 확인 |
| 포인트·주문·PG | `PgClientTest`(빈 2xx 본문 포함 결과 변환), `OrderFacadeIntegrationTest`, `PaymentCallbackV1ApiE2ETest`, `PaymentRequestGuardTest`, `PaymentExpiryJobIntegrationTest`, `PaymentLookupSchedulerIntegrationTest`, `OrderConfirmConcurrencyTest`, 새 `PaymentBoundaryLogTest`(경계 로그·실제 rollback), 새 `PaymentAwaitingMetrics*Test` | 백업 조회 실패 뒤 만료 job 이 PG SUCCESS 주문을 FAILED 로 만드는 경우 복구 경로 없음 (runbook §8). read timeout 은 ERROR → FAILED (W5 정책) |
| 이벤트·Outbox | `OutboxAtomicityTest`, `OrderEventRelayTest`, `OrderConfirmedBackfillTest`, e2e `OutboxDeliveryE2ETest`·`W6MissingEventRecoveryTest`, streamer `OrderConfirmedConsumer*`·`PurchaseActivityIntegrationTest`, 사고 실험의 relay 20 발행·활동 20행·중복 0 | 다중 relay 인스턴스 소유권은 여전히 보장하지 않음 (W7 그대로) |
| 쿠폰 | `CouponIssueConcurrencyTest`(총량·사용자 중복·동시 실행), `CouponIssueLimiterTest`, `RedissonAsyncCouponIssueSemaphoreTest`, `PromotionV1ApiE2ETest`(본인 목록) | 이번 변경과 직접 관련 없음. 부하 조건은 미검증 |
| 랭킹 | batch `WeeklyRankingJobTest`·`RankingJobRestartTest`(같은 instance 재시작), e2e `WeeklyRankingFlowTest`(늦은 구매 → 새 revision, 공개 API), 사고 실험의 새 snapshot r1(입력 20, 점수 20) | 이번 사고가 복구됐다고 W9 회귀를 생략하지 않았다 |
| 이번 사고 | `FakePgServerTest`(가짜 PG 계약 8건), `CheckoutIncidentTest`: BASELINE 10/0, FAULT 7/3 (health UP, `missing`=3), 진단 3건 모두 응답 유실·상태 불변, 다른 사용자 403 + PG 조회 0, RECOVERY 같은 10개 ID 10/0, create 추가 0, 재고·포인트 불변, 반복 resolve·callback 수렴, fault 기록 hash 보존 | 운영 설정(백업 조회 6s·만료 9s)에서의 사고 흐름은 실험에서 보류해 직접 재현하지 않음. prd JSON 로그의 key-value 출력 미확인. 분산 trace 미구현(로그 상관관계만) |

## 이번에 바꾼 것

| 구분 | 파일 |
|---|---|
| 제품 코드 (commerce-api) | `PaymentAwaitingMetrics`(gauge), `AwaitingPaymentCounts`·`OrderRepository.countAwaitingPayment`(집계 쿼리), `OrderFacade`(경계 구조화 로그 3종), `application.yml`(갱신 주기 1줄) |
| 테스트 지원 | `FakePaymentGateway` 조건부 생성(기본 켜짐), e2e `E2eContexts`(기본값 덮어쓰기)·`E2eDb.orderPayment`, e2e `build.gradle.kts`(증거 경로) |
| 테스트 | `FakePgServer`/`FakePgServerTest`, `CheckoutIncidentTest`, `IncidentEvidence`, `PaymentAwaitingMetricsTest`·`PaymentAwaitingMetricsIntegrationTest`, `PaymentBoundaryLogTest` |
| 문서 | `docs/runbook/week10.md`, 이 문서, `docs/week10/evidence/` |
