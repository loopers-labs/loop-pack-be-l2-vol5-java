# 10주차 결제 알림 runbook

알림의 `runbook_url`이 가리키는 자리다. 규칙은 `docker/prometheus/rules/payment.yml`, 대시보드는 Grafana `commerce-payment-incident`다.
각 절의 대응 본문은 범위 밖이다(다음 단계에서 쓴다).
알림·대시보드의 링크는 원본 저장소 웹 주소(`loopers-labs/loop-pack-be-l2-vol5-java`, 브랜치 `L2J4Dot`)를 가리킨다. PR이 머지되기 전에는 열리지 않을 수 있으니 그때는 저장소의 이 파일을 직접 연다.

## 관리자 도구

- local·dev에서만 쓴다. 계정은 환경 변수 `COMMERCE_ADMIN_USERNAME`·`COMMERCE_ADMIN_PASSWORD`로 넣는다. 변경 요청의 CSRF 토큰은 래퍼가 알아서 붙인다(설계 문서 9-4).
- 미확인 목록: `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?status=UNKNOWN&minAgeSeconds=300'`
- 환불 확인 대상 목록(5단계): 사람 담당 중 `refundOwner=PERSON`, 돈의 상태별 `refundMoneyStatus=REFUND_REQUESTED`(또는 `REFUND_UNKNOWN`·`CHARGED` 등).
  항목에 돈의 상태·담당·담당 사유·환불 주체가 나온다.
- 환불 대상 사람 기록(PG로 확인되지 않는 결론만): `scripts/ops/admin-curl.sh POST /api-admin/v1/payments/refund-cases/101/person-resolution '{"result":"REFUNDED"}'`
  (`REFUNDED` 또는 `NO_CHARGE`). 사람 담당 중일 때만 받는다(아니면 409). PG 호출 없이 사실만 기록한다.
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

## 환불 확인 대상 읽는 법

- 두 축으로 본다. **돈의 상태**: 확인 중(CHECKING)·청구 확인(CHARGED)·환불 요청함(REFUND_REQUESTED)·환불 결과 모름(REFUND_UNKNOWN)·환불됨(REFUNDED)·청구 없음(NO_CHARGE). **담당**: 자동(AUTO)·사람(PERSON).
- 담당이 사람이어도 시스템은 돈의 상태가 끝날 때까지 PG를 **조회만** 계속한다(간격 30m[임시]). 사람 담당 건에는 시스템이 취소(환불 요청)를 보내지 않는다.
- PG가 취소됨(CANCELED)을 주면 환불됨, 확인 중(CHECKING)에 실패(FAILED)를 주면 청구 없음으로 시스템이 기록하고 사람 담당을 자동으로 닫는다. 이때 사람이 따로 기록할 필요가 없다.
- 청구 확인(CHARGED)인데 PG가 실패(FAILED)를 주면 사실이 엇갈린 것이라 시스템은 돈의 상태와 사람 담당을 그대로 둔다. 사람이 PG 콘솔로 판단한다.
- 사람이 기록하는 것은 PG로 확인되지 않는 결론뿐이다(예: PG 조회가 안 되는데 PG 콘솔·정산으로 환불·청구 없음을 확인함).
- 금지(모든 환불 알림 공통): 결제·환불 요청을 다시 보내지 않는다(중복 결제·중복 환불). DB 상태를 직접 바꾸지 않는다. 기록은 관리자 API로만 한다.

## PaymentRefundNeedsPerson

- severity: warning
- 뜻: 담당이 사람으로 바뀐 환불 대상(사람 담당 중)이 15분 넘게 남아 있다. 사유는 자동 추적 기한 2일[임시] 초과(handoff_deadline), 거래 불일치·복수 거래, 환불 거절, 결과 뒤집힘이다. 돈의 상태는 그대로 남아 있다.
- 기한: 고객 환불 약속은 만료 뒤 4일[임시]이다. 넘어온 시점부터 1일 이상 남아 있으니 아침 확인으로 처리한다.
- 해소: 사람이 기록하거나 PG 사실로 담당이 자동 종료되어 사람 담당 중 건수가 0이 되면 풀린다.
- 대상 고정: `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?refundOwner=PERSON'`. 이 목록의 주문만 다룬다.
- 처리:
  1. 항목의 돈의 상태·담당 사유를 본다. 시스템이 계속 조회 중이므로 PG에서 취소·실패가 확인되면 저절로 닫힌다.
  2. PG 콘솔 등에서 실제 승인·취소 상태를 확인한다. 청구 확인(CHARGED)인데 환불이 안 됐으면 PG 콘솔에서 수동으로 환불한다(시스템은 사람 담당 건에 취소를 보내지 않는다). PG 조회로 취소가 보이면 시스템이 환불됨으로 닫는다.
  3. PG로 확인되지 않는 결론만 기록한다: `scripts/ops/admin-curl.sh POST /api-admin/v1/payments/refund-cases/{orderId}/person-resolution '{"result":"REFUNDED"}'` 또는 `'{"result":"NO_CHARGE"}'`. 담당이 아니거나 돈의 상태와 모순이면 409다.
- 금지: 위 "환불 확인 대상 읽는 법"의 공통 금지.
- 확인: 대시보드 "환불 확인 대상 돈의 상태별·사람 담당 중" 패널.

## PaymentRefundRequestStuck

- severity: warning
- 뜻: 환불 요청함(REFUND_REQUESTED) 중 가장 오래된 건이 요청 뒤 600초[임시]를 넘은 채 5분 지속됐다. 정상이면 몇 분 안에 환불됨 또는 환불 결과 모름으로 넘어간다. PG 취소 응답·조회가 막혔을 수 있다.
- 대상 고정: `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?refundMoneyStatus=REFUND_REQUESTED'`.
- 처리: PG 상태(장애·조회 회로)를 먼저 본다. 회복되면 자동 조회가 이어서 푼다. 자동 추적 기한을 넘기면 담당이 사람으로 바뀐다(PaymentRefundNeedsPerson). 급하면 PG 콘솔에서 취소 여부만 확인한다.
- 금지: 환불 요청을 다시 보내지 않는다(요청은 주문당 한 번, 결과를 모를 때 재전송은 중복 환불 위험). DB 상태를 직접 바꾸지 않는다.
- 확인: 대시보드 "환불 요청 정체 경과" 패널.

## PaymentRefundUnknown

- severity: warning
- 뜻: PG에 환불(취소)을 요청했지만 응답을 잃어 결과를 모르는 대상(돈의 상태 REFUND_UNKNOWN)이 30분 넘게 남아 있다. 시스템은 다시 보내지 않고 다음 회차에 조회로 푼다. 30분 넘게 못 풀었으면 PG 취소 조회가 막혔을 수 있다.
- 대상 고정: `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?refundMoneyStatus=REFUND_UNKNOWN'`.
- 처리: PG 상태(장애·조회 회로)를 먼저 본다. 회복되면 자동 조회가 이어서 푼다. 자동 추적 기한을 넘기면 담당이 사람으로 바뀐다(PaymentRefundNeedsPerson). 급하면 PG 콘솔에서 취소 여부를 확인한다.
- 금지: 환불 요청을 다시 보내지 않는다(결과를 모를 때 재전송은 중복 환불 위험). DB 상태를 직접 바꾸지 않는다.
- 확인: 대시보드 "환불 확인 대상 돈의 상태별·사람 담당 중" 패널.

## PaymentRefundPiling

- severity: critical
- 뜻: 사람 담당 중인 환불 대상이 10건[임시] 이상이고 담당 이관이 최근 30분 안에도 계속된 채 10분 지속됐다. 대형 장애 뒤 자동 환불이 결론을 내지 못하고 사람 몫이 쌓이고 있다.
- 새 이관이 멈추거나 사람 담당 중 건수가 10 아래로 내려가면 풀린다. 남은 건은 PaymentRefundNeedsPerson(warning)으로 계속 남는다.
- 대상 고정: `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?refundOwner=PERSON'`. 이 목록을 기준으로 사람을 나눠 처리한다.
- 처리: PG 장애·환불 조회 실패가 원인인지 먼저 본다(대시보드, 경계 로그). PG가 회복되면 시스템 조회가 사실을 기록하고 담당을 자동으로 닫는다. 그래도 남는 건은 PaymentRefundNeedsPerson 처리 순서대로 한다.
- 금지: 위 "환불 확인 대상 읽는 법"의 공통 금지.
- 확인: 대시보드 "환불 확인 대상 돈의 상태별·사람 담당 중" 패널.

## GuideServerDown

- severity: warning
- 뜻: critical 가이드 서버(`docker/guide/`)가 5분 넘게 응답하지 않는다. critical 알림은 Alertmanager가 Slack 자리로 원본을 따로 보내므로 끊기지 않는다. 근거 메시지만 빠진다.
- 확인: `docker compose -f docker/monitoring-compose.yml ps guide`, `docker compose -f docker/monitoring-compose.yml logs guide`. 대시보드 "가이드 서버 가동" 패널.
- 본문은 범위 밖(다음 단계).

## critical 가이드 메시지 읽는 법

- critical은 Slack 자리에 두 번 온다. ① Alertmanager 원본 ② 가이드 서버가 근거를 붙인 메시지(`[가이드]`로 시작).
- 가이드 메시지의 근거는 결정적으로 모은다: 미확인 상태별 수, 결과 모름 유입 비율(30분), 만료 종결 증가(10분), 환불 확인 대상 돈의 상태별 수, 사람 담당 중 건수, 사람 담당 이관 증가(30분), 환불 요청 정체 경과(분), 최근 30분 경계 로그 WARN·ERROR의 종류별 줄 수와 대표 줄(최대 3줄, 카드·비밀값 가림).
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
- 켠 도구 범주: 지표(prometheus)·대시보드·알림 규칙·검색·데이터 소스·링크 생성. **Loki(로그) 도구는 켜지 않는다.** 로그 원문에는 주문 번호 같은 내부 식별자가 있어 AI로 보내지 않는다. 도구는 범주 단위로만 켜져 `query_loki_patterns`만 따로 켤 수도 없다.
- 원칙: AI에게는 **지표·대시보드·알림으로 큰 틀**을 묻는다("무엇이 일어나는 것 같다, 어디부터 보라"). 주문 번호로 로그 찾기는 **사람이** Grafana Explore(Loki)와 관리자 목록에서 한다.
- 예시 1 (추이): "지난 1시간 `commerce_payment_unresolved`를 status별로, 그리고 결과 모름 유입 비율(`commerce_payment_unknown_entered_total` / `commerce_payment_started_total`, 30분 창)을 5분 간격으로 보여 줘. 언제부터 늘었어?"
- 예시 2 (울린 알림): "지금 울리는 결제 알림과 그 규칙 식, for 시간을 보여 줘. 어느 대시보드 패널과 runbook 앵커부터 보면 돼?"
- 예시 3 (가설 비교): "응답 유실(create 결과 Unknown), PG 실패, 결제 만료 세 가설 중 지난 30분 지표(유입 비율·결과별 종결 수·확정 API p99)로 가장 그럴듯한 것은? 확인할 대시보드 패널을 짚어 줘."
- 그다음 사람이: 관리자 목록에서 주문 번호를 얻고 → 위 "주문 번호로 경계 로그 찾기"로 한 주문을 확인하고 → 일괄 확정을 한다.
