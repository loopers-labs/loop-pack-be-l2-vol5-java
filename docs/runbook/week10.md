# 10주차 결제 알림 runbook

- 독자: 온콜 개발자. 코드를 몰라도 이 문서와 `scripts/ops/*.sh`만으로 따라 하게 쓴다.
- 알림마다 **Meaning(뜻) / Impact(영향) / Diagnosis(진단) / Mitigation(조치)** 순서다. 제목은 알림 이름 그대로다(알림의 `runbook_url` 앵커).
- 규칙: [docker/prometheus/rules/payment.yml](https://github.com/loopers-labs/loop-pack-be-l2-vol5-java/blob/L2J4Dot/docker/prometheus/rules/payment.yml). 대시보드: Grafana `commerce-payment-incident`(http://localhost:3000/d/commerce-payment-incident). 설계: [docs/week10/commerce-incident.md](https://github.com/loopers-labs/loop-pack-be-l2-vol5-java/blob/L2J4Dot/docs/week10/commerce-incident.md).
- 링크는 원본 저장소(`L2J4Dot` 브랜치) 웹 주소다. PR 머지 전에는 열리지 않을 수 있다. 그때는 저장소의 같은 파일을 연다.
- 수치는 모두 [임시]다. 현장에서 규칙·timeout·설정 값을 바꾸지 않는다.

## 공통 금지

- 결제·환불 요청을 다시 보내지 않는다(재전송·재결제 금지). 결과를 모를 때 다시 보내면 중복 결제·중복 환불이 된다.
- DB 상태를 직접 바꾸지 않는다(DB 강제 변경 금지). 기록은 관리자 API로만 한다.
- 비밀값·개인정보를 출력하지 않는다. 관리자 계정·토큰·API 키를 명령줄 인자·채팅·티켓에 붙이지 않는다. `env`·`docker compose config` 출력을 공유하지 않는다.
- LLM(AI)에 내부 식별자를 보내지 않는다. 주문 번호·PG 주문 번호(`LP-ORD-…`)·거래 키·사용자 ID·카드·로그 원문은 AI에 붙이지 않는다.

## 관리자 도구

local·dev 전용. 셸에 `COMMERCE_ADMIN_USERNAME`·`COMMERCE_ADMIN_PASSWORD`를 넣는다(출력되지 않음). 주소는 `COMMERCE_API_URL`(기본 `http://localhost:8080`). 변경 요청의 CSRF 토큰은 래퍼가 붙인다.

| 할 일 | 명령 |
| --- | --- |
| 결과 모름 결제 목록(5분 이상) | `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?status=UNKNOWN&minAgeSeconds=300'` |
| 사람 확인 결제 목록 | `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?status=NEEDS_REVIEW'` |
| 고른 주문 일괄 조회 확정(1~50건) | `scripts/ops/admin-curl.sh POST /api-admin/v1/payments/reconcile-batch '{"orderIds":[101,102]}'` |
| 사람 담당 환불 대상 | `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?refundOwner=PERSON'` |
| 돈의 상태별 환불 대상 | `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?refundMoneyStatus=REFUND_REQUESTED'` |
| 환불 사람 기록 | `scripts/ops/admin-curl.sh POST /api-admin/v1/payments/refund-cases/101/person-resolution '{"result":"REFUNDED"}'` |
| 사람 담당 환불 대상 지금 처리 | `scripts/ops/admin-curl.sh POST /api-admin/v1/payments/refund-cases/101/process-now` |
| 결제 지표 4종(local Prometheus) | `scripts/ops/rehearsal.sh metrics` |
| 울리는 알림(Prometheus·Alertmanager) | `scripts/ops/rehearsal.sh alerts` |
| 한 주문의 경계 로그(최근 1시간) | `scripts/ops/rehearsal.sh loki 101` |

- `status` 필터 값: `SEND_STARTED`·`PENDING`·`UNKNOWN`·`NEEDS_REVIEW`(여러 개 가능). 항목의 `lookupAttempts`(자동 조회 횟수)·`nextLookupAt`·`latestAnomaly`(가장 최근 이상)를 본다.
- 일괄 확정은 **PG 조회만** 한다. 결제를 다시 만들지 않는다. 같은 목록을 다시 보내도 `ALREADY_CLOSED`로 끝난다.
  - `outcome`: `RESOLVED` 확정됨 / `STOPPED` 멈춤(`reason` 참고) / `ALREADY_CLOSED` 이미 끝남 / `NOT_APPLICABLE` 대상 아님 / `SKIPPED` 연속 실패 3건으로 중단.
  - HTTP 503 = PG 조회 회로가 열림. 하나도 처리하지 않았다. 몇 분 뒤 같은 목록으로 다시 보낸다.
- 목록의 `orderId`는 사람이 고정한다. 알림·AI 요약의 숫자로 대상을 정하지 않는다.

## PaymentUnresolvedPiling

결과 모름 결제 쌓임 · **critical** · 패널 "미확인 결제 상태별", "시작 대비 결과 모름 유입 비율(30분)"

**Meaning.** PG에 결제를 보냈지만 응답을 못 받은(결과 모름, UNKNOWN) 결제가 새로 계속 생긴다. 30분 유입 비율 > 0.2%, 결과 모름 20건 이상이 10분 지속됐다. 자동 재조회가 따라잡지 못한다.

**Impact.** 고객 주문이 결제 대기로 멈추고 재고가 묶인다. 고객은 confirm에서 202 `PAYMENT_PENDING`을 받는다. 2분 안에 못 풀면 만료로 끝나고 환불 추적으로 넘어간다(PaymentAbandonedPiling).

**Diagnosis.**
1. 패널에서 언제부터 늘었는지 본다. `scripts/ops/rehearsal.sh metrics`로 숫자를 확인한다.
2. Grafana Explore(Loki): `{app="commerce-api", level="WARN"} |= "result=Unknown"`. `reason`이 `HttpTimeoutException`이면 PG 응답 지연·유실이다.
3. 대상을 고정한다: `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?status=UNKNOWN&minAgeSeconds=300'`. `lookupAttempts`가 0에 머물면 자동 재조회가 돌지 않는 것이다.
4. PaymentApiLatencyP99High가 함께 울리는지 본다(PG 지연 근거).

**Mitigation.**
- PG가 응답하면: 3의 `orderId`로 `scripts/ops/admin-curl.sh POST /api-admin/v1/payments/reconcile-batch '{"orderIds":[101,102]}'`. `RESOLVED`가 대부분이면 끝.
- 503이거나 `SKIPPED`·`STOPPED(LOOKUP_FAILED)`가 많으면 PG가 아직 안 된다. 기다린다. 남은 건은 2분 만료와 환불 추적이 이어받는다.
- 해소: 결과 모름 20건 미만 또는 유입 비율 0.2% 이하. 비율은 30분 창이라 해소 뒤에도 한동안 높게 보인다. Slack resolved는 최대 5분 늦다. Prometheus·대시보드로 먼저 확인한다.
- 금지: 공통 금지. 특히 confirm·결제를 다시 보내지 않는다.

## PaymentAbandonedPiling

만료 종결 쌓임 · **critical** · 패널 "만료 종결 증가(10분)", "종결 결과별 증가(10분)"

**Meaning.** 결과를 확인하지 못하고 2분 만료로 끝낸 결제가 10분에 5건 이상, 10분 지속됐다. 돈이 움직였을 수 있는 주문을 포기하고 있다.

**Impact.** 고객 주문은 결제 만료(재고 반환)다. PG에서 실제로 돈이 나갔을 수 있다. 만료된 주문마다 환불 확인 대상(돈의 상태 CHECKING)이 생기고 시스템이 PG를 조회해 돈이 나갔으면 자동 환불을 한 번 요청한다.

**Diagnosis.**
1. PaymentUnresolvedPiling이 함께 울리면 원인은 PG 응답 유실이다. 그 절부터 본다.
2. Loki: `{app="commerce-api", level="WARN"}`에서 조회 판정 줄의 `kind`·`reason`을 본다. 조회 실패가 계속이면 PG 조회 장애다.
3. 환불 추적 대상: `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?refundMoneyStatus=CHECKING'`.

**Mitigation.**
- 아직 결제 대기인 건은 PaymentUnresolvedPiling 순서로 일괄 확정해 만료 전에 끝낸다.
- 이미 만료된 주문은 손대지 않는다. 환불 추적이 조회·환불을 맡는다. 재결제·환불 재요청 금지.
- PG가 2일[임시] 동안 결론을 못 주면 담당이 사람으로 넘어온다(PaymentRefundNeedsPerson).
- 해소: 10분 만료 증가가 5건 아래.
- 주의: 이 경로는 리허설에서 아직 확인하지 않았다(부록 A).

## PaymentNeedsReview

사람 확인 결제 남음 · warning · 패널 "미확인 결제 상태별"

**Meaning.** 결제 대기 주문 중 PG 상태가 사람 확인(NEEDS_REVIEW)인 건이 15분 넘게 남았다. 자동 재조회가 거래 불일치·복수 거래·조회 12회 소진으로 멈춘 상태다. 자동으로 풀리지 않는다.

**Impact.** 주문이 결제 대기로 남고 재고가 묶인다. 보통은 2분 만료가 끝내므로, 15분 남았다면 만료가 돌지 않거나 시작 시각이 없는 옛 기록이다.

**Diagnosis.**
1. `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?status=NEEDS_REVIEW'`. 항목의 `latestAnomaly.kind`를 본다.
2. 한 주문의 경계 로그: `scripts/ops/rehearsal.sh loki 101`.

**Mitigation.**
- `LOOKUP_LIMIT_EXHAUSTED`(조회 소진): PG가 회복됐으면 일괄 확정으로 조회한다. 사람 확인 건도 같은 경로로 처리된다.
- `TRANSACTION_MISMATCH`·`DUPLICATE_TRANSACTIONS` 등: 일괄 확정은 `STOPPED`로 멈춘다(정상). PG 콘솔에서 이 주문의 실제 거래를 확인하고 결제 담당 리드에게 넘긴다.
- 해소: 사람 확인 결제 0건.
- 금지: 공통 금지. 상태를 DB에서 바꾸지 않는다.

## PaymentDeclineSurge

결제 거절 급증 · warning · 패널 "종결 결과별 증가(10분)"

**Meaning.** 종결 중 거절(failed) 비율이 30%를 넘고 10분 10건 이상, 5분 지속됐다. PG가 거래를 만들고 FAILED로 확정한 건만 센다(카드 거절 등).

**Impact.** 고객 결제가 거절되어 주문이 결제 실패로 끝난다. 돈은 나가지 않았다. 우리 시스템 오류가 아니라 카드사·PG 판정 이상을 뜻한다.

**Diagnosis.**
1. 패널에서 failed만 튀는지, 전체 종결이 줄었는지 본다.
2. Loki: `{app="commerce-api", level="INFO"} |= "result=FAILED"`로 시작 시각·`providerStatus`를 본다.
3. PaymentPgUnreachable(미발송)과 함께 울리면 연결 문제부터 본다.

**Mitigation.**
- 우리 쪽 조치는 없다. PG·카드사 공지와 상태를 확인하고 PG사에 문의한다.
- 해소: 비율 30% 이하 또는 10분 10건 미만.
- 금지: 거절 주문을 대신 다시 결제하지 않는다.

## PaymentPgUnreachable

PG 미발송 종결 · warning · 패널 "종결 결과별 증가(10분)"

**Meaning.** PG에 요청이 닿지 않고 끝난 결제(not_sent)가 10분에 5건 이상, 5분 지속됐다. PG가 효과 없음으로 거절한 건도 여기에 센다.

**Impact.** 고객 주문이 결제 실패로 끝난다. 돈은 움직이지 않아 환불할 것이 없다. 새 결제가 계속 실패한다.

**Diagnosis.**
1. Loki: `{app="commerce-api", level="WARN"} |= "result=NotSent"`의 `reason`(연결 거부·이름 해석 실패 등). `{app="commerce-api"} |= "result=RejectedNoEffect"`도 본다.
2. PG 상태 페이지·네트워크·PG 주소 설정을 확인한다.

**Mitigation.**
- PG·네트워크 담당에 알린다. 회복되면 새 결제는 정상으로 돌아온다.
- 해소: 10분 미발송 5건 미만.
- 금지: 실패한 주문의 결제를 다시 보내지 않는다.

## PaymentApiLatencyP99High

결제 확정 API 느림 · warning · 패널 "결제 확정 API 응답 시간 p50·p90·p99(5분)"

**Meaning.** 결제 확정·본인 결과 확인·관리자 결과 확인 API의 p99가 1s를 넘고(5분 100건 이상) 10분 지속됐다. 관리자 일괄 확정은 대상이 아니다.

**Impact.** 고객 결제 화면이 느리다. PG read timeout(3s) 근처면 응답 유실이 늘어 결과 모름으로 이어진다.

**Diagnosis.**
1. 패널에서 p99만 오르는지 본다. p99가 3s 근처면 PG 지연·응답 유실이다. PaymentUnresolvedPiling·유입 비율 패널을 함께 본다.
2. p50도 함께 오르면 PG가 아니라 앱·DB 전체가 느린 것이다.

**Mitigation.**
- PG 지연이면 PG 상태를 확인하고 PaymentUnresolvedPiling이 울리는지 지켜본다.
- 앱·DB 전체 느림이면 서비스 담당에 넘긴다.
- 해소: p99 1s 이하 또는 5분 100건 미만.
- 금지: timeout·임계값을 현장에서 바꾸지 않는다.

## 환불 확인 대상 읽는 법

결제 만료 뒤 늦게 성공이 확인된 돈을 추적한다. 두 축으로 본다.

| 돈의 상태(`refundMoneyStatus`) | 뜻 | 끝? |
| --- | --- | --- |
| `CHECKING` 확인 중 | 돈이 나갔는지 아직 모름 | 아니오 |
| `CHARGED` 청구 확인 | PG가 돈이 나갔다고 함. 환불 요청 전 | 아니오 |
| `REFUND_REQUESTED` 환불 요청함 | 환불을 한 번 요청함. 결과 대기 | 아니오 |
| `REFUND_UNKNOWN` 환불 결과 모름 | 요청 응답을 잃음. 조회로만 확인 | 아니오 |
| `REFUNDED` 환불됨 / `NO_CHARGE` 청구 없음 | 끝 | 끝 |

- **담당**: 자동(AUTO) 또는 사람(PERSON). 항목에 담당 사유(`owner_reason`), 담당 종료 시각(null = 열림), 환불 주체(SYSTEM·EXTERNAL·PERSON)가 나온다.
- 담당이 사람이어도 시스템은 돈이 끝날 때까지 PG를 **조회만** 계속한다(간격 상한 30m[임시]). 자동 회차는 사람 담당 건에 취소(환불 요청)를 보내지 않는다. 사람이 **지금 처리**를 누를 때만 CHARGED에서 한 번 보낸다(PaymentRefundNeedsPerson).
- 사람 담당은 자동으로 돌아가지 않는다(AUTO로 되돌리기 없음). 돈이 끝나 담당이 닫힐 때까지 사람 담당이다.
- 항목의 마지막 확인: `lastCheckAt`(시각), `lastCheckOutcome`(결과, 아래 표), `lastCheckCause`(예외 클래스 이름만, 없으면 null). 자동 회차와 지금 처리가 둘 다 갱신한다. 대시보드 "환불 추적 막힌 원인"은 같은 결과를 소문자(`pg_unreachable` 등)로 센다.
- 자동 추적 기한(2일[임시])을 넘긴 회차에는 담당이 먼저 사람으로 바뀐다. 그 직후 조회가 성공을 보여도 취소는 나가지 않는다.
- PG가 취소됨을 주면 환불됨, 확인 중에 실패를 주면 청구 없음으로 시스템이 기록하고 사람 담당을 자동으로 닫는다. 사람이 PG 콘솔에서 환불한 건도 다음 조회에서 이렇게 닫힌다.
- 청구 확인 이후인데 PG가 실패를 주면 사실이 엇갈린 것이다. 시스템은 그대로 둔다. 사람이 PG 콘솔로 판단한다.

**사람 기록**(PG로 확인되지 않는 결론만, PG 호출 없음): `scripts/ops/admin-curl.sh POST /api-admin/v1/payments/refund-cases/{orderId}/person-resolution '{"result":"REFUNDED"}'`

| 결과 | 받는 경우(사람 담당 중일 때만) | 409 |
| --- | --- | --- |
| `REFUNDED` | 돈 CHECKING·CHARGED·REFUND_REQUESTED·REFUND_UNKNOWN | 담당 AUTO, 담당 이미 닫힘, 돈 이미 끝남 |
| `NO_CHARGE` | 돈 CHECKING 또는 CHARGED(사람 담당) | 위 + REFUND_REQUESTED·REFUND_UNKNOWN(우리가 취소를 보냈을 수 있는 돈) |

- 409면 대상은 그대로다. 없는 주문 404, 본문 오류 400.

## PaymentRefundNeedsPerson

사람 담당 환불 대상 남음 · warning · 패널 "환불 확인 대상 돈의 상태별·사람 담당 중"

**Meaning.** 담당이 사람으로 바뀐 환불 대상이 15분 넘게 남았다. 돈의 상태는 그대로 기록돼 있다.

**Impact.** 고객 환불 약속(만료 뒤 4일[임시]) 안에 처리해야 한다. 이관 시점에 1일 이상 남아 있어 아침 확인으로 충분하다.

**Diagnosis.**
1. 대상 고정: `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?refundOwner=PERSON'`. 이 목록의 주문만 다룬다.
2. 담당 사유로 원인을 읽는다.

| 사유 | 뜻 |
| --- | --- |
| `HANDOFF_DEADLINE` | 2일 자동 추적으로 결론이 안 났다(PG 조회 장애 등) |
| `TRANSACTION_MISMATCH`·`MULTIPLE_TRANSACTIONS` | PG 거래가 주문·금액과 안 맞거나 여러 건이다 |
| `REFUND_REJECTED` | 우리 환불 요청을 PG가 효과 없음으로 거절했다(돈 CHARGED) |
| `RESULT_REVERSED` | 청구 없음·결제 실패로 본 뒤 늦은 성공이 왔다(돈 CHARGED) |

3. 지금 왜 막혔는지는 항목의 `lastCheckOutcome`·`lastCheckCause`로 읽고 다음 행동을 고른다. 전체 분포는 패널 "환불 추적 막힌 원인".

| `lastCheckOutcome` | 뜻 | 다음 행동 |
| --- | --- | --- |
| `APPLY_FAILED` | PG 답은 받았는데 **우리 반영이 실패**(DB·버그). 돈의 상태는 그대로 | 경계 로그에서 원인을 본다: `scripts/ops/rehearsal.sh loki 101`의 `causeType`(= `lastCheckCause`). 원인을 고친 뒤 지금 처리. 고치기 전에 누르면 같은 실패가 되풀이된다(PaymentRefundApplyFailed) |
| `PG_UNREACHABLE` | PG에 닿지 않음(타임아웃·연결·조회 회로 열림) | PG 상태를 확인하고 회복 뒤 지금 처리 |
| `PG_ERROR` | PG가 오류로 답함 | PG 상태를 확인하고 회복 뒤 지금 처리 |
| `MISMATCH` | PG 거래가 주문·금액과 안 맞거나 여러 건 | PG 콘솔로 실제 거래를 확인하고 사람 기록. 지금 처리로는 풀리지 않는다 |
| `PENDING`·`EMPTY` | PG가 아직 결론을 안 줌·거래 없음 | 기다리거나(다음 조회 30m 상한) 지금 처리 |
| `CANCEL_UNKNOWN` | 환불 요청 응답을 잃음 | 조회만 한다. 지금 처리(조회만 한다)는 괜찮다. 환불을 다시 보내지 않는다 |
| `CANCEL_REJECTED` | PG가 우리 환불 요청을 거절 | PG 콘솔에서 거절 이유·취소 여부를 확인한다 |
| `OK` | 마지막 확인은 정상 처리됨 | 돈이 끝났으면 담당이 이미 닫혔다. CHARGED로 남았으면 사람 담당이라 자동 환불을 안 한 것이다. 지금 처리로 환불을 요청한다 |

**Mitigation.**
1. **지금 처리**(그 자리에서 이 건 하나를 조회·반영하고 결과를 돌려준다):
   `scripts/ops/admin-curl.sh POST /api-admin/v1/payments/refund-cases/101/process-now`
   - 돈 CHARGED면 환불 요청을 **한 번** 보낸다. REFUND_REQUESTED·REFUND_UNKNOWN이면 조회만 한다(다시 보내지 않는다). 두 번 눌러도 환불 요청은 한 번이다.
   - 조회 회로가 열려 있으면 PG를 부르지 않고 `PG_UNREACHABLE`로 답한다. 상태는 그대로다. 몇 분 뒤 다시 누른다.
2. 결과 읽기: 응답의 돈의 상태·담당·`lastCheckOutcome`·`lastCheckCause`.
   - 돈이 `REFUNDED`·`NO_CHARGE`면 끝. 담당이 자동으로 닫힌다(닫은 사람 = 누른 관리자).
   - 돈이 `REFUND_REQUESTED`면 환불 요청이 나갔다. 다음 조회가 결과를 기록한다.
   - 그 밖이면 사람 담당 그대로다. `lastCheckOutcome`으로 Diagnosis 3의 표를 다시 본다.
   - 409 = 담당 AUTO, 담당 이미 닫힘, 돈 이미 끝남. 대상은 그대로다. 없는 주문 404.
3. `MISMATCH`·`CANCEL_REJECTED`처럼 PG 콘솔 판단이 필요하면: PG 콘솔에서 실제 승인·취소를 확인한다. 콘솔에서 수동 환불했으면 다음 조회(30m 상한)를 기다린다. 시스템이 환불됨으로 닫는다.
4. PG 조회가 안 되는데 콘솔·정산으로 결론을 확인했을 때만 사람 기록을 남긴다(환불 확인 대상 읽는 법의 표).
- 사람 담당은 자동으로 돌아가지 않는다. 되돌리기 기능은 없다. 지금 처리나 사람 기록으로 끝낸다.
- 해소: 사람 담당 중 0건.
- 금지: 공통 금지. 환불 요청은 지금 처리로만 보낸다. 같은 돈을 PG 콘솔 수동 환불과 지금 처리로 둘 다 환불하지 않는다(중복 환불 위험). 둘 중 하나만 한다.

## PaymentRefundApplyFailed

환불 추적 반영 실패 · warning · 패널 "환불 추적 막힌 원인"

**Meaning.** 환불 추적(자동 회차·지금 처리)에서 PG 조회 결과는 받았는데 우리 반영이 예외로 실패한 건(`apply_failed`)이 최근 15분[임시]에 새로 생겼고 5분 지속됐다. PG 문제가 아니라 우리 쪽(DB·버그) 문제다.

**Impact.** 그 건의 돈의 상태가 바뀌지 않는다. 고칠 때까지 같은 건에서 되풀이된다. 자동 담당 건은 2일 뒤 사람 담당으로 넘어오고, 그동안 고객 환불이 늦어진다.

**Diagnosis.**
1. 패널 "환불 추적 막힌 원인"에서 `apply_failed`가 언제부터 늘었는지 본다.
2. 대상: 환불 대상 목록(`?refundOwner=PERSON`, `?refundMoneyStatus=CHECKING` 등)에서 `lastCheckOutcome`이 `APPLY_FAILED`인 항목. `lastCheckCause`는 예외 클래스 이름이다.
3. 한 주문의 경계 로그: `scripts/ops/rehearsal.sh loki 101`. Grafana Explore: `{app="commerce-api", level="ERROR"} | orderId="101"`. `causeType`으로 DB 연결·잠금·제약 위반·코드 오류를 가른다.

**Mitigation.**
- DB 장애면 DB 담당에, 코드 오류면 서비스 담당에 넘긴다. 고치기 전에는 지금 처리를 눌러도 같은 실패다.
- 고친 뒤: 자동 담당 건은 다음 회차가 다시 조회한다. 사람 담당 건은 지금 처리(PaymentRefundNeedsPerson Mitigation 1).
- 해소: 15분 창에 새 `apply_failed`가 없다(마지막 실패 뒤 약 15분).
- 금지: 공통 금지. 특히 DB에서 돈의 상태를 직접 고치지 않는다.

## PaymentRefundRequestStuck

환불 요청 정체 · warning · 패널 "환불 요청 정체 경과"

**Meaning.** 환불 요청함(REFUND_REQUESTED) 중 가장 오래된 건이 요청 뒤 600초[임시]를 넘은 채 5분 지속됐다. 정상이면 몇 분 안에 환불됨 또는 환불 결과 모름으로 넘어간다.

**Impact.** 고객 환불이 늦어진다. PG 취소 응답·조회가 막혔을 수 있다.

**Diagnosis.**
1. 대상 고정: `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?refundMoneyStatus=REFUND_REQUESTED'`.
2. PG 장애·조회 회로 상태를 본다(PaymentApiLatencyP99High·PaymentUnresolvedPiling 동시 여부, Loki WARN).

**Mitigation.**
- PG가 회복되면 자동 조회가 이어서 푼다. 2일을 넘기면 담당이 사람으로 넘어온다(PaymentRefundNeedsPerson).
- 급하면 PG 콘솔에서 취소 여부만 확인한다.
- 해소: 가장 오래된 요청이 600초 아래이거나 요청함 0건.
- 금지: 환불 요청을 다시 보내지 않는다(주문당 한 번).

## PaymentRefundUnknown

환불 결과 모름 남음 · warning · 패널 "환불 확인 대상 돈의 상태별·사람 담당 중"

**Meaning.** 환불을 요청했지만 응답을 잃어 결과를 모르는 대상(REFUND_UNKNOWN)이 30분 넘게 남았다. 시스템은 다시 보내지 않고 조회로 푼다. 30분 넘게 못 풀면 PG 취소 조회가 막혔을 수 있다.

**Impact.** 고객 환불 완료를 확인하지 못한다. 실제로는 환불됐을 수도 있다.

**Diagnosis.**
1. 대상 고정: `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?refundMoneyStatus=REFUND_UNKNOWN'`.
2. PG 상태·조회 장애를 본다(Loki WARN).

**Mitigation.**
- PG가 회복되면 자동 조회가 이어서 푼다. 2일을 넘기면 담당이 사람으로 넘어온다.
- 급하면 PG 콘솔에서 취소 여부를 확인한다.
- 해소: 환불 결과 모름 0건.
- 금지: 환불 요청을 다시 보내지 않는다(중복 환불 위험).

## PaymentRefundPiling

사람 담당 환불 쌓임 · **critical** · 패널 "환불 확인 대상 돈의 상태별·사람 담당 중"

**Meaning.** 사람 담당 중 환불 대상이 10건[임시] 이상이고 최근 30분에도 이관이 계속된 채 10분 지속됐다. 대형 장애 뒤 자동 환불이 결론을 못 내고 사람 몫이 쌓인다.

**Impact.** 하루 안에 사람이 감당할 양을 넘을 수 있다. 고객 환불 약속(4일)을 놓칠 위험이 있다.

**Diagnosis.**
1. 대상 고정: `scripts/ops/admin-curl.sh GET '/api-admin/v1/payments/unresolved?refundOwner=PERSON'`. 이 목록으로 사람을 나눈다.
2. 담당 사유 분포를 본다. `HANDOFF_DEADLINE`이 대부분이면 PG 조회 장애가 원인이다. 가이드 메시지의 "사람 담당 이관 증가(30분)"도 본다.
3. 가이드 메시지·패널의 "환불 추적 막힌 원인"으로 PG 쪽(`pg_unreachable`·`pg_error`)인지 우리 반영(`apply_failed`, PaymentRefundApplyFailed)인지 가른다.

**Mitigation.**
- PG 장애가 원인이면 PG 회복을 먼저 챙긴다. 회복되면 시스템 조회가 사실을 기록하고 담당을 자동으로 닫는다.
- 남는 건은 PaymentRefundNeedsPerson 조치 순서로 나눠 처리한다.
- 해소: 새 이관이 멈추거나 사람 담당 중이 10건 아래. 남은 건은 PaymentRefundNeedsPerson으로 계속 보인다.
- 금지: 공통 금지.

## GuideServerDown

가이드 서버 응답 없음 · warning · 패널 "가이드 서버 가동"

**Meaning.** critical 알림에 근거를 붙이는 가이드 서버(`docker/guide/`)가 5분 넘게 응답하지 않는다.

**Impact.** critical 알림 원본은 Alertmanager가 Slack 자리로 따로 보내므로 끊기지 않는다. 근거 메시지(`[가이드]`)만 빠진다.

**Diagnosis.**
1. `docker compose -f docker/monitoring-compose.yml ps guide`
2. `docker compose -f docker/monitoring-compose.yml logs guide`(오류 종류만 본다).

**Mitigation.**
- `docker compose -f docker/monitoring-compose.yml restart guide`. 반복되면 모니터링 담당에 넘긴다.
- 그동안 critical은 원본 알림과 이 runbook으로 대응한다.
- 금지: 환경 변수(`ANTHROPIC_API_KEY` 등)를 출력·공유하지 않는다.

## critical 가이드 메시지 읽는 법

- critical은 Slack 자리에 두 번 온다. ① Alertmanager 원본 ② 가이드 서버가 근거를 붙인 메시지(`[가이드]`로 시작).
- 근거: 미확인 상태별 수, 결과 모름 유입 비율(30분), 만료 종결 증가(10분), 환불 대상 돈의 상태별 수, 사람 담당 중 건수, 사람 담당 이관 증가(30분), 환불 요청 정체 경과(분), 환불 추적 막힌 원인(결과별 30분 증가), 최근 30분 경계 로그 WARN·ERROR 종류별 줄 수와 대표 줄(최대 3줄, 카드·비밀값 가림).
- `[가이드 실패: …]`로 시작하면 근거 수집이 실패했거나 제한 시간(10s[임시])을 넘었다. 원본 알림으로 대응한다.
- "AI 요약"은 집계와 로그 종류만 보고 쓴 것이다. 대상은 관리자 목록으로 고정한다(대표 줄은 최대 3줄이다).

## 주문 번호로 경계 로그 찾기

- 앱 로그는 저장소 루트 `logs/commerce-api.json`에 쌓이고 Alloy가 Loki(`127.0.0.1:3100`)로 보낸다. 로그가 들어오는지는 `logs/` 폴더가 아니라 이 **파일**로 확인한다(폴더는 모니터링 기동 때 미리 생긴다).
- label은 `app`·`level`뿐이다. `orderId`·`providerOrderId`·`attemptKey`·`transactionKey`는 structured metadata다.
- 대시보드 "주문 번호로 경계 로그" 패널: 변수 `주문 번호(orderId)`에 번호를 넣는다.
- Grafana Explore(Loki): 한 주문 `{app="commerce-api"} | orderId="101"`, PG 주문 번호 `{app="commerce-api"} | providerOrderId="LP-ORD-0000000101"`, 결과 모름 `{app="commerce-api", level="WARN"} |= "result=Unknown"`.
- 명령줄: `scripts/ops/rehearsal.sh loki 101`.
- 로그는 그 주문이 왜 그 상태인지 볼 때 쓴다. 대상 목록은 관리자 API로 얻는다.

## AI에게 이렇게 물어라 (Grafana MCP)

- 연결: `scripts/ops/mcp-grafana.sh`(공식 `mcp-grafana` v2.3.0, 처음 실행 때 받아 SHA-256 확인). 토큰은 Grafana 서비스 계정 **Viewer** 토큰을 환경 변수 `GRAFANA_SERVICE_ACCOUNT_TOKEN`으로만 넣는다. 쓰기 도구·익명 사용 통계는 꺼져 있다.
  - Claude Code: `claude mcp add grafana --env GRAFANA_SERVICE_ACCOUNT_TOKEN=<토큰> -- "$PWD/scripts/ops/mcp-grafana.sh"`
- 켠 도구: 지표·대시보드·알림 규칙·검색·데이터 소스·링크. **Loki(로그) 도구는 켜지 않는다.** 로그 원문에 내부 식별자가 있기 때문이다.
- AI에게는 지표·대시보드·알림으로 **큰 틀**만 묻는다. 주문 번호는 질문에 넣지 않는다.
  - "지난 1시간 `commerce_payment_unresolved`를 status별로, 결과 모름 유입 비율(30분 창)을 5분 간격으로 보여 줘. 언제부터 늘었어?"
  - "지금 울리는 결제 알림과 규칙 식, for 시간을 보여 줘. 어느 패널과 runbook 앵커부터 보면 돼?"
  - "응답 유실·PG 실패·결제 만료 중 지난 30분 지표로 가장 그럴듯한 것은? 확인할 패널을 짚어 줘."
- 그다음 사람이: 관리자 목록으로 대상 고정 → 위 로그 찾기로 한 주문 확인 → 조치.

## 부록 A. 리허설 막힌 지점 대조

리허설 기록: [docs/week10/rehearsal.md](https://github.com/loopers-labs/loop-pack-be-l2-vol5-java/blob/L2J4Dot/docs/week10/rehearsal.md) "막힌 지점".

| B | 막힌 것 | 지금 runbook 어디 | 풀림 |
| --- | --- | --- | --- |
| B1 | 기본 `bootRun` 로그가 `apps/commerce-api/logs/`에 쌓여 Loki에 안 들어감 | "주문 번호로 경계 로그 찾기" 첫 줄(저장소 루트 `logs/commerce-api.json`) | 풀림. 원인은 빌드 설정에서 고쳤다(override 불필요). 수정 뒤 Loki 수집까지는 리허설 재실행으로 확인할 일 |
| B2 | `logs/` 폴더가 앱 기동 전부터 있어 로그 생성 근거로 착각 | "주문 번호로 경계 로그 찾기" 첫 줄(폴더가 아니라 파일로 확인) | 풀림(문서 안내) |
| B3 | 주문에 필요한 사용자를 만들 API가 없음 | 해당 없음(온콜 대응이 아니라 리허설 준비). local 시드가 사용자 1명을 만든다 | 풀림(시드). runbook 대상 아님 |
| B4 | runbook 링크가 상대 경로라 Grafana·Slack에서 안 열림 | 머리말 링크 안내. 알림·대시보드 링크는 GitHub 웹 주소이고 `check-alerting.sh`가 앵커를 검사한다 | 부분. 머지 전에는 열리지 않을 수 있다 |
| B5 | 자동 재조회를 켜면 "재조회 실패" 사고가 재현되지 않음 | 해당 없음(재현 도구). `scripts/ops/test-pg.sh lookup-behavior ERROR_500`으로 조회 실패를 켜고 `NORMAL`로 되돌린다 | 풀림(도구). 이 도구로 리허설을 다시 돌리지는 않았다 |
