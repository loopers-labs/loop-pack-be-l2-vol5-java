# 10주차 결제 알림 runbook

알림의 `runbook_url`이 가리키는 자리다. 규칙은 `docker/prometheus/rules/payment.yml`, 대시보드는 Grafana `commerce-payment-incident`다.
각 절의 대응 본문은 범위 밖이다(다음 단계에서 쓴다).

## 관리자 도구

- local·dev에서만 쓴다. 계정은 환경 변수 `COMMERCE_ADMIN_USERNAME`·`COMMERCE_ADMIN_PASSWORD`로 넣는다. 변경 요청의 CSRF 토큰은 래퍼가 알아서 붙인다(설계 문서 9-4).
- 미확인 목록: `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?status=UNKNOWN&minAgeSeconds=300'`
- 일괄 확정(목록에서 고른 orderId만, 1~50건): `scripts/ops/admin-curl.sh POST /api-admin/v1/payments/reconcile-batch '{"orderIds":[101,102]}'`

## PaymentUnresolvedPiling

- severity: critical
- 뜻: 결과 모름 결제가 새로 계속 생기고(유입 비율 > 0.2%) 20건 이상 쌓인 채 10분 지속됐다. 자동 재조회가 따라잡지 못한다.
- 본문은 범위 밖(다음 단계).

## PaymentAbandonedPiling

- severity: critical
- 뜻: 결과를 확인하지 못하고 만료로 종결한 결제가 10분에 5건 이상 10분 지속됐다. 돈이 움직였을 수 있는 주문을 포기하고 있다.
- 본문은 범위 밖(다음 단계).

## PaymentNeedsReview

- severity: warning
- 뜻: 사람 확인(needs_review)이 필요한 결제가 15분 넘게 남아 있다. 자동으로 풀리지 않는다.
- 본문은 범위 밖(다음 단계).

## PaymentDeclineSurge

- severity: warning
- 뜻: 종결 중 거절(failed) 비율이 30%를 넘고 10분 10건 이상이다. 카드사·PG 판정 이상을 의심한다.
- 본문은 범위 밖(다음 단계).

## PaymentPgUnreachable

- severity: warning
- 뜻: PG에 요청을 보내지 못한 종결(not_sent)이 10분 5건 이상이다. PG 연결 문제를 의심한다.
- 본문은 범위 밖(다음 단계).

## PaymentApiLatencyP99High

- severity: warning
- 뜻: 결제 확정·본인 결과 확인·관리자 결과 확인 API의 응답 시간 p99가 1s를 넘고(5분 100건 이상) 10분 지속됐다. 관리자 일괄 확정은 대상이 아니다.
- p99가 PG read timeout(3s) 근처면 응답 유실·PG 지연을 먼저 의심한다(PaymentUnresolvedPiling·결과 모름 유입 비율 패널을 함께 본다).
- 확인: 대시보드 "결제 확정 API 응답 시간 p50·p90·p99(5분)" 패널.
- 본문은 범위 밖(다음 단계).

## GuideServerDown

- severity: warning
- 뜻: critical 가이드 서버(`docker/guide/`)가 5분 넘게 응답하지 않는다. critical 알림은 Alertmanager가 Slack 자리로 원본을 따로 보내므로 끊기지 않는다. 근거 메시지만 빠진다.
- 확인: `docker compose -f docker/monitoring-compose.yml ps guide`, `docker compose -f docker/monitoring-compose.yml logs guide`. 대시보드 "가이드 서버 가동" 패널.
- 본문은 범위 밖(다음 단계).

## critical 가이드 메시지 읽는 법

- critical은 Slack 자리에 두 번 온다. ① Alertmanager 원본 ② 가이드 서버가 근거를 붙인 메시지(`[가이드]`로 시작).
- 가이드 메시지의 근거는 결정적으로 모은다: 미확인 상태별 수, 결과 모름 유입 비율(30분), 만료 종결 증가(10분), 최근 30분 경계 로그 WARN·ERROR의 종류별 줄 수와 대표 줄(최대 3줄, 카드·비밀값 가림).
- `[가이드 실패: …]`로 시작하면 근거 수집이 실패했거나 제한 시간([임시] 10s)을 넘은 것이다. 뒤에 붙은 원본 알림으로 대응한다.
- `ANTHROPIC_API_KEY`가 있으면 "AI 요약"이 붙는다. AI에는 집계와 로그 종류(식별자·숫자 ID를 모두 가린 문구)만 보낸다. 주문 번호·PG 주문 번호·시도 키·거래 키·사용자 ID·카드·비밀값은 보내지 않는다. 남아 있으면 부르지 않는다. AI 요약은 "무엇이 일어나는 것 같다, 어디부터 보라"까지다. 개별 주문 찾기와 조치는 사람이 아래 순서로 한다.

## 주문 번호로 경계 로그 찾기

- 로그는 local 프로필 앱이 저장소 `logs/*.json`에 쓰고, Alloy가 읽어 Loki(`127.0.0.1:3100`)로 보낸다. label은 `app`·`level`뿐이다. `orderId`·`providerOrderId`·`attemptKey`·`transactionKey`는 structured metadata다.
- Grafana 대시보드 "주문 번호로 경계 로그" 패널: 위 변수 `주문 번호(orderId)`에 번호를 넣는다.
- Grafana Explore(Loki):
  - 한 주문: `{app="commerce-api"} | orderId="101"`
  - PG 주문 번호로: `{app="commerce-api"} | providerOrderId="LP-ORD-0000000101"`
  - 결과 모름 create: `{app="commerce-api", level="WARN"} |= "result=Unknown"`
- 명령줄: `curl -sG http://127.0.0.1:3100/loki/api/v1/query_range --data-urlencode 'query={app="commerce-api"} | orderId="101"' --data-urlencode 'since=1h'`
- 목록은 관리자 API(위 "관리자 도구")로 얻는다. 로그는 그 주문이 왜 그 상태인지 확인할 때 본다.

## AI에게 이렇게 물어라 (Grafana MCP)

- 연결: `scripts/ops/mcp-grafana.sh`(공식 `mcp-grafana` v2.3.0 단일 바이너리, 처음 실행 때 받아 SHA-256 확인). 토큰은 Grafana 서비스 계정 **Viewer** 토큰을 환경 변수 `GRAFANA_SERVICE_ACCOUNT_TOKEN`으로만 넣는다. 쓰기 도구와 익명 사용 통계는 끈다.
  - Claude Code 예: `claude mcp add grafana --env GRAFANA_SERVICE_ACCOUNT_TOKEN=<토큰> -- "$PWD/scripts/ops/mcp-grafana.sh"`
- 원칙: AI에게는 주문 번호 대신 **패턴과 집계**를 묻는다. 개별 주문은 사람이 Loki·관리자 목록에서 찾는다.
- 예시 1 (추이): "지난 1시간 `commerce_payment_unresolved`를 status별로, 그리고 결과 모름 유입 비율(`commerce_payment_unknown_entered_total` / `commerce_payment_started_total`, 30분 창)을 5분 간격으로 보여 줘. 언제부터 늘었어?"
- 예시 2 (종류별 패턴): "Loki에서 지난 30분 `{app=\"commerce-api\", level=~\"WARN|ERROR\"} |= \"OrderApplicationService\"`의 로그 패턴(query_loki_patterns)을 종류별 건수로 묶어 줘. 주문 번호는 빼고 문구 종류만."
- 예시 3 (가설 비교): "응답 유실(create `result=Unknown`, `HttpTimeoutException`), 내부 반영 실패(`결제 결과 반영 실패`·`PG 호출 후 결과 저장에 실패`), PG 실패(`결제 실패로 종결`) 세 가설 중 지난 30분 경계 로그 건수로 가장 많은 것은? 대시보드 어느 패널부터 보면 돼?"
- 그다음 사람이: 관리자 목록에서 주문 번호를 얻고 → 위 "주문 번호로 경계 로그 찾기"로 한 주문을 확인하고 → 일괄 확정을 한다.
