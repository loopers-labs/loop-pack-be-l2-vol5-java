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
