# ADR-0002 · 결제 DB는 15초마다 집계하고 Prometheus는 5초마다 읽는다

채택 · 2026-11-26. Prometheus가 수집할 때마다 결제 DB를 조회하면 수집 주기를 줄이거나 수집기를 추가할 때 DB 읽기도 늘어난다. 앱이 집계한 값을 메모리에 보관해 두 주기를 독립적으로 조절한다. 주문을 세는 조건은 [ADR-0001](adr-0001-payment-observability.md)에 따른다.

## 집계와 수집

| 누가 | 언제 | 무엇을 읽거나 쓰나 |
|---|---|---|
| Commerce 집계 작업 | 인스턴스마다 이전 집계가 끝나고 **15초 후** | 결제 DB를 한 번 읽고 건수와 마지막 성공 관찰 시각을 메모리에 게시한다. |
| Prometheus | **5초마다** | 관리 포트에서 게시된 표본을 수집한다. 이 경로에서 추가되는 결제 DB 조회는 0회다. |
| Grafana | 대시보드 조회 시 | Prometheus 표본으로 건수·집계 신선도·수집 상태를 표시한다. |

복구 직후에도 다음 성공 집계와 수집까지는 이전 건수가 보일 수 있다. 같은 DB를 읽는 앱이 여러 대면 인스턴스마다 집계 쿼리가 실행된다. 건수는 인스턴스별로 읽는다. 합산하면 같은 주문을 여러 번 센다. [집계 스케줄](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/monitoring/pay/PaymentUnknownMetricsScheduler.java) · [수집 설정](control/monitor/prometheus.yaml) · [Grafana 패널](control/monitor/catalog/dashboard.json)

## 화면에 보일 값

| 상황 | 건수 `commerce_payment_unknown` | 마지막 성공 관찰 시각 |
|---|---|---|
| 첫 집계 전 | 확인 불가(`NaN`) | 확인 불가 |
| 집계 성공 | 대상이 없으면 **0**, 있으면 실제 건수 | 이번 집계의 관찰 시각으로 갱신 |
| DB 조회 실패 또는 발송한 `UNKNOWN`의 기한 누락 | 확인 불가(`NaN`) | 직전 성공 시각 유지 |
| Prometheus 수집 실패 | 이번 수집에서 앱 표본을 얻지 못함 | `up`과 최근 표본 시각을 확인 |

마지막 성공 시각은 `commerce_payment_unknown_last_success_timestamp_seconds`다. Grafana의 집계 신선도는 현재 시각에서 이 값을 빼 보여준다. 새 표본을 받았어도 마지막 성공 시각이 복구 전이면 복구 후 상태는 다음 집계에서 확인한다. [표본 게시 구현](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/monitoring/pay/PaymentUnknownMetrics.java)

## 테스트 기준

관리 포트 테스트는 여러 번 수집해도 다음 집계까지 DB 읽기가 늘어나지 않는지, DB 조회 실패 뒤 `NaN`과 이전 성공 시각이 함께 제공되는지 검사한다. 로컬 부하 실험에서는 집계가 정상인 상태에서 미확인 건수 **0 → 3 → 0**을 관찰했다. [관리 포트 HTTP 테스트](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/monitoring/pay/PaymentUnknownMetricsHttpIntegrationTest.java) · [실험 화면과 시계열](evidence/2026-11-27-catalog/README.md)
