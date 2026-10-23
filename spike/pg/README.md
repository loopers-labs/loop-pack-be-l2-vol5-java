# PG 관찰 실험 (Toss 결제위젯 · PortOne V1)

5주차 설계 전에 실제 PG가 승인·중복·응답 유실·금액 변조·웹훅에 어떻게 반응하는지 본다.
Spring 코드와 무관한 일회성 도구이고, 체크리스트 구현은 과제의 로컬 테스트 PG 계약으로 한다.

- 키: `apps/commerce-api/src/main/resources/application-secret.yml` (커밋되지 않음). 서버는 비밀 키를 화면·로그에 내지 않는다.
- 로그: `spike/pg/logs/events.jsonl` (커밋되지 않음). 브라우저 화면 아래에도 보인다.
- 외부 호출은 버튼 한 번에 한 번뿐이고 자동 재시도는 없다.

## 실행

```bash
python3 -I spike/pg/server.py          # http://127.0.0.1:8090
```

PortOne 웹훅까지 볼 때만 터널을 연다. 터널로는 `/webhook/*` 만 통과하고 나머지는 403이다.

```bash
ngrok http 8090                                              # 터미널 1
PUBLIC_URL=https://xxxx.ngrok-free.app python3 -I spike/pg/server.py   # 터미널 2
```

끝나면 터널과 서버를 모두 닫는다.

## 실험과 관찰할 것

| # | 실험 | 관찰할 것 |
|---|---|---|
| T1 | Toss 결제 → A. 정상 승인 | 승인 응답의 `status`(DONE?), 걸린 시간, 응답 필드(paymentKey·orderId·totalAmount) |
| T2 | B. 응답 유실 흉내 (timeout 짧게) | 우리는 TIMEOUT인데 바로 조회하면 DONE인가? timeout을 50ms·300ms·800ms로 바꾸면 "보내기 전 끊김"과 "보낸 뒤 끊김"이 갈리는가 |
| T3 | C. 같은 승인 다시 | 두 번째 응답 코드·메시지 (중복 승인을 PG가 막는가) |
| T4 | D. 금액 +1로 승인 | PG가 금액 불일치를 거절하는가 |
| T5 | E. Idempotency-Key 같게 두 번 | 두 번째가 첫 응답을 그대로 돌려주는가 (과제 PG는 이 키가 없다는 점과 비교) |
| T6 | 결제창에서 취소 | failUrl로 오는 code·message |
| P1 | PortOne prepare → 결제 | 결제창 콜백 `rsp`와 조회 결과의 status(paid?) |
| P2 | 변조 흉내 체크 후 결제 | prepare 금액과 다른 금액을 PG가 막는 시점 |
| P3 | 웹훅 (PUBLIC_URL) | 결제창 콜백과 웹훅 중 무엇이 먼저 오나, 같은 결제의 웹훅이 몇 번 오나 |

## 관찰 기록

| # | 결과 | 설계에 주는 의미 |
|---|---|---|
| T1 | 승인 1회에 666ms, `status: DONE`, `approvedAt` 채워짐. 공개 문서 키라 `mId: tgen_docs` | Toss 승인은 동기식이다: 응답이 오면 곧 최종 결과. 과제 PG의 PENDING(접수만)과 대비 |
| T2 | | |
| T3 | | |
| T4 | | |
| T5 | | |
| T6 | | |
| P1 | prepare 200(95ms) → 결제창 콜백 `success: true, status: paid, paid_amount: 1000` | 결제창 콜백은 브라우저가 전한 값이라 위조 가능 → 서버 조회로 확인해야 함 |
| P2 | | |
| P3 | 웹훅 `{imp_uid, merchant_uid, status: paid}`가 승인 시각(`paid_at`)과 같은 초에 1회 도착. 그 직후 `GET /payments/{imp_uid}`는 **404 "존재하지 않는 결제정보"** — 테스트 결제는 `include_sandbox=true`가 있어야 보였다 (같은 결제를 `find/{merchant_uid}`·목록 조회로는 paid) | **조회 404·0건 ≠ 거래 없음.** 조회 조건이 틀려도 "없음"이 돌아온다. 0건을 근거로 실패 확정·재결제하면 이미 paid인 결제를 이중으로 만든다 (과제의 "조회 0건은 영구적 증거가 아님") |
