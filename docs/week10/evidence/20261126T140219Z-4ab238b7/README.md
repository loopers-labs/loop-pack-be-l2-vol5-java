# 사고 연결 E2E 증거: 최종 실행 (w10s7 H2 교차 확인 반영)

- runId는 `20261126T140219Z-4ab238b7`이다. 맥북에어 로컬에서 2026-11-26 14:02 UTC에 실행했다.
  - 접미사 `4ab238b7`은 무작위 8자리다. 커밋 해시가 아니다(`IncidentEvidence`).
- 실행 당시 코드는 HEAD 커밋(w10s7 결제 반영 실패 DB 기록 Green·E2E 교차 확인 Red)에 E2E 교차 확인 Green 작업 트리를 더한 상태다.
  - `src/main`은 HEAD와 같다.
- `CheckoutIncidentE2ETest`를 한 번 실행하면 단계마다 JSON을 남긴다. 그 JSON을 `build/incident-evidence/<runId>/`에서 그대로 복사했다.
  - 내용은 손대지 않았다(`SHA256SUMS`).
- 명령은 `./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check --rerun-tasks`이다.
  - api `check`가 `e2eTest`를 포함한다.
  - 결과는 `BUILD SUCCESSFUL in 6m 2s`.

| 모듈 | 작업 | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- | --- |
| commerce-api | test | 1157 | 0 | 0 | 0 |
| commerce-api | e2eTest | 4 | 0 | 0 | 0 |
| commerce-streamer | test | 47 | 0 | 0 | 0 |
| commerce-batch | test | 54 | 0 | 0 | 0 |

## 파일

| 파일 | 단계 |
| --- | --- |
| baseline.json | 기준 집합(사용자 1) 10건 확정 |
| fault.json | 장애 집합(사용자 2) 10건 중 3건 응답 유실. `assets`: fault 시점 포인트·쿠폰·재고 |
| diagnosis.json | 가설 H1~H3 판정. 가설마다 `missingEvidence`(보지 못한 증거). H2에는 `crossCheck`(아래) |
| recovery.json / repeat.json / followup.json | 본인 reconcile 복구 / 반복·타인 거절 / 구매 활동·주간 랭킹. `assets`가 `assetsAtFault`와 같음 |
| summary.json | 집합별·단계별 확정·UNKNOWN·PG 성공·create 수 |

## H2 교차 확인 (이번 실행에서 새로 생김)

H2는 내부 반영 실패 가설이다. 이 가설의 기각이 로그 부재 하나에만 기대지 않게 했다. 유실 주문 3건(11·12·13) 모두 값이 같다.

| 칸 | 값 | 출처 |
| --- | --- | --- |
| applyFailedAnomalies | 0 | DB. `select count(*) from payment_anomalies where order_id = ? and kind = 'APPLY_FAILED'`(kind로 세고, 해소 여부는 보지 않음) |
| paymentStartInfo | 1 | 같은 주문의 "PG 결제를 시작했습니다" INFO |
| createUnknownWarn | 1 | 같은 주문의 create Unknown WARN |
| logChannelAlive | true | 위 두 로그가 모두 잡혔다. 같은 주문의 로그 통로가 살아 있었다 |

- 판정식: H2는 `APPLY_FAILED > 0`이거나 (Accepted INFO 또는 Verified INFO)와 반영 ERROR가 함께 있을 때 채택한다.
  - 판정 요약은 이전 실행과 같다. H1 채택 [11, 12, 13], H2·H3 기각.
- 테스트는 진단 앞에서 대조군을 하나 돌린다.
  - 진단 대상이 아닌 주문 ID로 APPLY_FAILED 한 행을 넣고, 질의가 1을 세는지 확인한 뒤 지운다.
  - 그래서 0건은 질의 고장(항상 0)이 아니다.
- H2의 `missingEvidence`는 남는 부족한 증거만 적는다.
  - MySQL error log·rollback 통계는 보지 않았다.
  - 이상 기록 자체가 실패하면 기록기 ERROR만 남는다. 그 경우는 errorLines로만 보인다.
  - E2E 로그 캡처에 기록기 logger를 더했다.

## summary 숫자 (확정 · UNKNOWN · PG 성공 · create)

| 집합 | BASELINE | FAULT | RECOVERY | REPEAT | FOLLOWUP |
| --- | --- | --- | --- | --- | --- |
| 장애 집합(사용자 2, 10건) | — | 7 · 3 · 10 · 10 | 10 · 0 · 10 · 10 | 10 · 0 · 10 · 10 | 10 · 0 · 10 · 10 |
| 기준 집합(사용자 1, 10건) | 10 · 0 · 10 · 10 | 10 · 0 · 10 · 10 | 10 · 0 · 10 · 10 | 10 · 0 · 10 · 10 | 10 · 0 · 10 · 10 |

- 장애 집합은 FAULT 뒤 create 증가가 없다. RECOVERY 0, REPEAT 0, FOLLOWUP 0.
- fault 시점 자산은 복구·반복·후속에서도 같다.
  - 포인트: 사용자 1 5,000, 사용자 2 10,000.
  - 쿠폰: 사용자 2 1장(3,000).
  - 재고: 가용 10, 잡힌 수량 20.
- 이 비교가 증명하는 범위는 좁다. 주문 API는 포인트·쿠폰을 쓰지 않는다(포인트 미차감, 쿠폰은 발급만).
  - 증명하는 것: 0이 아닌 잔액·쿠폰을 가진 채 사고를 겪어도, 복구가 다시 차감하거나 반환하지 않는다.

## 검사

- `shasum -a 256 -c SHA256SUMS`: 7개 모두 OK.
- 비밀값·카드·개인정보·관리자 계정 검사에서 노출은 0건이다.
  - 대상: 카드번호(하이픈 형식·숫자만), `cardNo`, password·secret·token·Authorization·Basic, 관리자 계정 이름·환경 변수, DB 비밀번호 변수, 이메일, 휴대폰 번호.
  - 휴대폰 번호 패턴 6건과 16자리 숫자 23건이 걸렸다. 모두 Prometheus 값의 소수부였다.

## 옛 한 벌과의 관계

- `20261126T120742Z-f9dbdfde`(C1~C3 반영 최종 실행)는 기록으로 그대로 둔다. H2 `crossCheck`가 없는 것만 이번과 다르다. summary 숫자와 자산 값은 같다.
- `20261125T082202Z-068161dd`(11-25 실행), `20261126T113318Z-6d788078`·`20261126T121317Z-965d8579`(리허설 캡처)도 그대로 둔다.
- 이 폴더를 제출용 한 벌로 가리키게 하는 설계 문서·PR 갱신은 이 README 범위 밖이다.
- `SHA256SUMS`는 JSON 7개만 다룬다. 이 README는 제외한다.
