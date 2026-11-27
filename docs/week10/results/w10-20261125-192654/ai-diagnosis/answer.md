# 결제 사고 진단: "결제 확인 중"에서 멈춘 주문

**결론:** 결과를 모르는 결제는 **3건(주문 12·15·18)** 입니다. PG는 세 건 모두 거래를 만들고 SUCCESS로 기록했습니다. 그런데 commerce-api는 생성 응답을 기다리다 약 3초에서 timeout으로 끊었습니다. PG가 보관만 하고 보내지 않은 콜백도 있어, 우리는 결과를 받을 길이 없었습니다. 그래서 **가설 2(PG 성공 응답을 잃음)를 지지**합니다. 아무것도 바꾸지 않았고 조회만 했습니다.

## 1. 결과를 모르는 결제 (시각은 KST)

| 주문 | paymentId | providerOrderId | PG 거래키 (pg-sim 로그) | 내부 transactionKey | create 경과 | traceId |
|---|---|---|---|---|---|---|
| 12 | 12 | LP-ORD-0000000012 | 20261125:TR:118e6b0 | `-` | 3014 ms | 944b694ad5e292dcc0d74ea1e0b72c94 |
| 15 | 15 | LP-ORD-0000000015 | 20261125:TR:56aaa47 | `-` | 3004 ms | 8dbe4f7b1f0f4e7472c4f3c51db9bee7 |
| 18 | 18 | LP-ORD-0000000018 | 20261125:TR:3890c28 | `-` | 3006 ms | 99572e0384558f880b0a83ebf910bc1e |

지표 `commerce_payment_unresolved{status="UNKNOWN"}`는 19:28:15에 0이었고, 19:28:30부터 마지막 샘플인 19:29:30까지 3입니다. PENDING은 내내 0입니다. 지표 기록은 19:28:15부터만 있습니다.

**주문 12의 흐름** (15·18도 같은 모양이고, 0.2~0.5초씩 늦습니다)

| 시각 | 어디서 | 무엇이 |
|---|---|---|
| 19:28:21.879 | api ① | `payment.requested` |
| 19:28:22.036 | pg-sim | "응답 유실 … 생성 응답을 5초 뒤에 보낸다" |
| 19:28:23.290 | pg-sim | "SUCCESS · callback 보관" |
| 19:28:24.904 | api ② | `result=UNKNOWN cause=READ_TIMEOUT elapsedMs=3014 transactionKey=-` |
| 19:28:24.921 | api ④ | `from=SENDING to=UNKNOWN outcome=NOT_FINAL source=DISPATCH` |
| 19:28:27.055 | pg-sim | "늦은 응답을 썼다 (받는 쪽이 이미 끊었으면 버려진다)" |
| 19:29:37까지 | api | 이 세 주문의 줄 없음. 조회(③)도 콜백도 없음 |

## 2. 가설 판정

| 가설 | 예상 | 실제 (질의 번호는 아래 목록) | 판정 |
|---|---|---|---|
| 1. PG가 아직 처리 중 | PG에 조회하면 PENDING | 우리 쪽에서 세 건을 PG에 조회한 적이 없습니다. ③ 로그 0줄(Q3·Q7), GET span에도 세 거래키가 없습니다(Q12). PG 자체 로그는 세 건 모두 **SUCCESS**를 기록했습니다(Q4, 19:28:23.290 / 23.290 / 23.542). | **기각.** 근거는 PG 자체 로그입니다. 조회 API로 지금 상태를 직접 본 것은 아닙니다. |
| 2. PG 성공 응답을 잃음 | PG SUCCESS · 내부 UNKNOWN · 생성 응답 timeout | PG 로그에 SUCCESS, "callback 보관", 5초 뒤 늦은 응답이 남아 있습니다(Q4). 우리 쪽은 ② `READ_TIMEOUT` 3004~3014 ms에 `transactionKey=-`(Q2), ④ SENDING→UNKNOWN(Q3), 지표 UNKNOWN=3(Q1)입니다. Tempo의 PG `POST /api/v1/payments` span도 3.0초에 `HttpTimeoutException: Request timed out`으로 error입니다(Q10·Q11). | **지지** |
| 3. 성공 조회 뒤 내부 반영 실패 | PG SUCCESS · 반영 시도 · ⑤ rollback | 세 건에 성공 조회(③)가 없습니다(Q3·Q7). ⑤ `payment.apply.failed`는 24시간 동안 0줄입니다(Q6·Q9). 마지막 ④(SENDING→UNKNOWN)는 commit까지 됐습니다(Q3). | **기각.** 반영을 시도할 전제인 "성공 조회"가 아예 일어나지 않았습니다. |

**쓴 질의와 결과**

| # | 질의 | 결과 |
|---|---|---|
| Q1 | Prom `commerce_payment_unresolved` (now-15m, 15s 간격) | UNKNOWN 0 → 3, PENDING 0 |
| Q2 | `{app="commerce-api"} \|= "payment.create.result" != "result=SUCCESS"` (24h) | 20줄: ACCEPTED 17, UNKNOWN 3 (12·15·18, 모두 READ_TIMEOUT) |
| Q3 | `{app="commerce-api"} \|~ "payment\\.[a-z.]+ orderId=(12\|15\|18) "` | 9줄: ①②④가 3건씩. ③과 ⑤는 0 |
| Q4 | `{app="pg-sim"} \|~ "LP-ORD-00000000(12\|15\|18)"` | 10줄: "응답 유실 목록 = [12, 15, 18]", 거래키, SUCCESS · callback 보관, 늦은 응답 |
| Q5 | `{app="pg-sim"} !~ "LP-ORD-00000000(12\|15\|18)"` | `callback … → 200` 17건(1~11, 13, 14, 16, 17, 19, 20). 12·15·18은 없음 |
| Q6 | 경계별 `count_over_time` (24h) | requested 20 · create.result 20 · lookup.result 17 · apply 37 · apply.failed 없음 |
| Q7 | `{app="commerce-api"} \|= "payment.lookup.result"` | 17줄 모두 `by=TRANSACTION_KEY pgStatus=SUCCESS source=CALLBACK`. 12·15·18은 없음 |
| Q8 | apply의 from/to/outcome/source별 개수 | SENDING→PENDING 17 · PENDING→SUCCESS(CALLBACK) 17 · SENDING→UNKNOWN 3 |
| Q9 | `{app="commerce-api"} \|~ "payment\\.apply\\.failed\|ERROR\|Exception\|callback"` | Kafka 설정 출력 3줄만 나옴. ⑤와 ERROR는 0 |
| Q10 | Tempo trace 944b694a… | PG POST 3011.6 ms, status=error, HttpTimeoutException |
| Q11 | TraceQL: commerce-api → localhost:8082 중 `status = error` | trace 3개(12·15·18), 3004~3012 ms |
| Q12 | TraceQL: commerce-api → localhost:8082 중 `span.method != "POST"` | GET 17건, 모두 콜백 요청 안에서 나감. 세 거래키는 없음 |
| Q13 | `{app=~"commerce-api\|pg-sim"}`, 19:28:26 이후 | pg-sim의 늦은 응답 3줄만. commerce-api는 0줄(19:29:37까지) |
| Q14 | 알림 규칙 목록 (Grafana 관리 + prometheus 데이터소스) | 둘 다 비어 있음 |

## 3. 증거

**판정을 가른 증거**
1. **같은 거래를 양쪽에서 본 기록.** PG는 `TR:118e6b0` 등을 만들어 SUCCESS로 기록했습니다(Q4). 우리 쪽은 같은 시각에 3초 timeout으로 끊었고 `transactionKey=-`로 남았습니다(Q2·Q10). PG는 성공했는데 응답만 잃은 것입니다.
2. **세 주문에는 결과를 알려줄 신호가 하나도 오지 않았습니다.** 나머지 17건은 콜백 200 → 조회 → SUCCESS 반영까지 끝났습니다(Q5·Q7·Q8). 세 건은 콜백이 "보관"으로 멈췄고, 조회와 ⑤가 모두 0입니다. 그래서 가설 3은 성립하지 않고, 그대로 멈춰 있는 이유도 이것으로 설명됩니다.

**확인하지 못했지만 판정을 바꿀 수 있는 증거**

| 증거 | 못 본 이유 | 판정을 어떻게 바꾸나 |
|---|---|---|
| providerOrderId로 PG에 조회한 **지금 상태**, 그리고 주문당 거래가 1건인지 | PG API를 부를 도구가 없고, 이번 작업은 읽기 전용 | PENDING이나 FAILED가 나오면 가설 1이 다시 살아납니다. 거래가 2건 이상이면 이미 중복 결제입니다. |
| 내부 DB에서 결제 12·15·18 행의 실제 상태 | DB 조회 수단이 없음. 지표는 건수만 주므로, 주문과 짝짓기는 로그로만 했습니다 | 로그와 DB가 다르면 대상 주문이 바뀝니다. |
| UNKNOWN을 다시 조회하는 복구 작업이 있는지, 주기는 얼마인지 | 이를 보여주는 로그·지표가 없음. 관찰한 시간도 약 72초뿐입니다(19:28:25~19:29:37) | 나중에 복구가 돌다가 ⑤가 나면 가설 3이 다시 살아납니다. |
| PG가 보관한 콜백 3건이 나중에 전달되는지 | pg-sim 로그에 "보관" 이후 기록이 없음 | 전달되면 저절로 해소될 수 있습니다. |

## 4. 조치 제안 (실행하지 않음)

**PG에 create를 다시 보내도 되나요? → 안 됩니다.** PG는 이미 세 건 모두 거래를 만들고 SUCCESS로 기록했습니다(Q4). 다시 보내면 새 거래가 생겨 **이중 결제가 될 위험**이 있습니다. PG가 providerOrderId 기준으로 중복을 막아 주는지는 확인하지 못했습니다(미확정). UNKNOWN의 정의 자체가 "PG에 보냈을 수도 있다"이므로, 원칙은 **다시 만들지 말고 조회한 뒤 반영**하는 것입니다.

| 순서 | 조치 | 다 됐는지 확인하는 방법 |
|---|---|---|
| 1 | LP-ORD-0000000012 / 15 / 18을 PG에 **providerOrderId로 조회**합니다. SUCCESS인지, 주문당 거래가 1건인지 확인합니다. | 거래키가 118e6b0 · 56aaa47 · 3890c28로 나오는지 |
| 2 | 정상 반영 경로로 UNKNOWN → SUCCESS 처리합니다. | `payment.apply … from=UNKNOWN to=SUCCESS` 3줄, 지표 UNKNOWN 3 → 0 |
| 3 | 조회 경로를 점검합니다. 지금까지 관찰된 조회는 모두 `by=TRANSACTION_KEY`, `source=CALLBACK`입니다. 세 건은 내부 거래키가 `-`라 이 방식으로는 조회할 수 없습니다. 주문 ID로 조회하는 복구 작업이 있는지 코드와 설정을 확인합니다. | — |
| 4 | PG(pg-sim) 쪽에 콜백 3건을 왜 보관했는지 묻고, 다시 보내 달라고 요청합니다. | — |
| 5 | 고객 응대: **다시 결제하라고 안내하지 않습니다.** 문의한 고객의 주문이 12·15·18 중 무엇인지 확인합니다. | — |
| 6 | 알림을 추가합니다. health는 UP이라 이 상황을 잡지 못하고, 현재 알림 규칙도 없습니다(Q14). `commerce_payment_unresolved{status="UNKNOWN"} > 0`이 N분 이상 이어지면 알리도록 합니다. | — |
| 7 | (부차) create timeout은 약 3초인데 PG는 5초 뒤에 응답했습니다. timeout을 늘리면 증상은 줄지만, 근본 해결은 3번(주문 ID로 조회하는 복구)입니다. | — |

※ 이번 세션에는 파일 쓰기 도구가 없어서 오늘 업무일지(`worklog/daily/2026-11-25-수.html`)는 갱신하지 못했습니다.
