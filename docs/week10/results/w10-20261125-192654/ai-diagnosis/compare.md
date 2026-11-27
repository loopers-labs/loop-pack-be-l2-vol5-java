# AI 진단과 run.sh 판정 대조 — `w10-20261125-192654`

- AI: fault 상태에서 멈춘 동안 빈 폴더에서 실행 (`docs/week10/ai-diagnose.sh`). 내장 도구 없음, 읽기 전용 Grafana MCP만 사용. 질의 23개, 24턴, 약 2분 54초.
- run.sh: 같은 fault 상태의 증거 파일(`2-fault/`)로 판정 (`2-fault/hypotheses.tsv`).

## 결론 비교

| 항목 | AI (Grafana MCP만) | run.sh (증거 파일) | 같은가 |
|---|---|---|---|
| 결과 모르는 결제 | 3건 · 주문 12 · 15 · 18 | 3건 · 주문 12 · 15 · 18 (`ids/lost-orders.txt`) | 같음 |
| 가설 1. PG가 아직 처리 중 | 기각 — **pg-sim 로그**의 `SUCCESS · callback 보관` | 기각 — **PG 조회 API** 응답 SUCCESS 3/3 (`2-fault/pg-lookup-*.json`) | 결론은 같고 근거가 다름 |
| 가설 2. PG 성공 응답을 잃음 | 지지 — ② `READ_TIMEOUT` 3004~3014ms · Tempo PG span 3.0초 error · 지표 UNKNOWN 3 | 지지 — ② `READ_TIMEOUT` 3/3 · ⑤ 0줄 · 느린 PG span 3/3 | 같음 |
| 가설 3. 성공 조회 뒤 내부 반영 실패 | 기각 — ③ 0줄 · ⑤ 0줄 | 기각 — ② ACCEPTED 0/3 · ⑤ 0줄 | 같음 |
| create를 다시 보내도 되나 | 안 된다 (중복 결제 위험) | 복구에서 create 0 검사 | 같음 |

## 짚을 점

1. **가설 1의 근거는 장애를 주입한 쪽이 남긴 로그였다.** pg-sim은 응답 유실을 일부러 만드는 가짜 PG이고, 로그에 정답이 그대로 적혀 있다.
   - `logs/pg-sim.log` 12번째 줄: `응답 유실 목록 = ['LP-ORD-0000000012', 'LP-ORD-0000000015', 'LP-ORD-0000000018']`
   - 운영에서는 PG 내부 로그가 우리 Loki에 없다. 이 로그를 빼면 AI가 볼 수 있는 것(우리 로그·지표·trace)만으로는 가설 1이 **미확정**이어야 한다. AI는 "조회 API로 지금 상태를 직접 본 것은 아니다"라고 적었지만, 판정은 기각으로 냈다.
   - run.sh는 PG 조회 API(권위 조회)로 기각했다. 과제 §4 "주입 코드의 정답으로 원인을 확정하지 않는다"에 맞는 쪽은 run.sh다.
2. **AI가 "확인하지 못했다"고 적은 증거는 run.sh가 확인했다.**

   | AI가 못 본 것 | run.sh가 본 곳 |
   |---|---|
   | PG의 지금 상태 · 주문당 거래 수 | `2-fault/pg-lookup-*.json` (SUCCESS 1건씩) · `pg-transactions.json` |
   | DB의 결제 행 상태 | `2-fault/db.tsv` · `unresolved.tsv` |
   | 보관된 callback 3건 | `2-fault/pg-stats.json` `callback.held = 3` |

   AI가 이것들을 추측으로 채우지 않고 "못 봤다"고 적은 것은 프롬프트 규칙대로다.
3. **AI 제안 중 코드와 다른 것: "주문 ID로 조회하는 복구 작업이 있는지 확인하라".** 그런 경로는 이미 있다. 거래 키가 없으면 reconcile이 providerOrderId로 조회한다 (`logs/api.log`의 `payment.lookup.result … by=PROVIDER_ORDER_ID … source=RECONCILE_API`). 자동 스케줄러는 실험을 위해 `run.yml`에서 꺼 두었다. AI는 코드를 볼 수 없으니, 단정하지 않고 "확인하라"고 한 것은 맞는 태도다.
4. **AI가 맞게 짚은 것**
   - health는 UP이고 알림 규칙이 없다 (Q14). `UNKNOWN > 0`이 이어지면 알리자는 제안은 runbook에 없던 것이다 → runbook 8 "후속 개선"에 더했다 (5분 넘게 이어지면, 2026-11-27).
   - 3초 timeout과 5초 늦은 응답을 구분했다. timeout을 늘리는 것은 증상 완화일 뿐 근본 해결이 아니라고 적었다.
5. **실험 잡음** — 이 컴퓨터의 사용자 전역 설정(업무일지 규칙)이 AI 세션에도 들어가, 답 끝에 "업무일지를 갱신하지 못했다"는 한 줄이 붙었다. 판정과는 관계없다.

## 다음 실험 후보 (정할 것)

- pg-sim 로그를 Loki에 넣지 않고(운영처럼 PG는 외부 시스템) 같은 질문을 다시 한다. AI가 가설 1을 "미확정"으로 내는지 본다.
