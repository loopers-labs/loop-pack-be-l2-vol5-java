# 사고 연결 E2E 증거 — 최종 실행 (C1~C3 반영)

- runId: `20261126T120742Z-f9dbdfde`. 맥북에어 로컬, 2026-11-26 12:07 UTC. runId 접미사(`f9dbdfde`)는 무작위 8자리이고 커밋 해시가 아니다(`IncidentEvidence`).
- 코드: 실행 당시 HEAD `3d08c71f` + 쿠폰 발급 시각 한 줄(실행 뒤 `1c1889d5`로 커밋, 단언 불변).
- 무엇: `CheckoutIncidentE2ETest` 한 번의 실행이 단계마다 남긴 JSON을 `build/incident-evidence/<runId>/`에서 그대로 복사했다. 내용은 손대지 않았다(`SHA256SUMS`).
- 명령: `./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check --rerun-tasks`
  (api `check`가 `e2eTest`를 포함). `BUILD SUCCESSFUL in 6m 29s`.

| 모듈 | 작업 | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- | --- |
| commerce-api | test | 1143 | 0 | 0 | 0 |
| commerce-api | e2eTest | 4 | 0 | 0 | 0 |
| commerce-streamer | test | 47 | 0 | 0 | 0 |
| commerce-batch | test | 54 | 0 | 0 | 0 |

## 파일

| 파일 | 단계 |
| --- | --- |
| baseline.json | 기준 집합(사용자 1) 10건 확정 |
| fault.json | 장애 집합(사용자 2) 10건 중 3건 응답 유실. `assets`: fault 시점 포인트·쿠폰·재고 |
| diagnosis.json | 가설 H1~H3 판정. 가설마다 `missingEvidence`(보지 못한 증거) |
| recovery.json / repeat.json / followup.json | 본인 reconcile 복구 / 반복·타인 거절 / 구매 활동·주간 랭킹. `assets`가 `assetsAtFault`와 같음 |
| summary.json | 집합별·단계별 확정·UNKNOWN·PG 성공·create 수 |

## summary 숫자 (확정 · UNKNOWN · PG 성공 · create)

| 집합 | BASELINE | FAULT | RECOVERY | REPEAT | FOLLOWUP |
| --- | --- | --- | --- | --- | --- |
| 장애 집합(사용자 2, 10건) | — | 7 · 3 · 10 · 10 | 10 · 0 · 10 · 10 | 10 · 0 · 10 · 10 | 10 · 0 · 10 · 10 |
| 기준 집합(사용자 1, 10건) | 10 · 0 · 10 · 10 | 10 · 0 · 10 · 10 | 10 · 0 · 10 · 10 | 10 · 0 · 10 · 10 | 10 · 0 · 10 · 10 |

- 장애 집합의 FAULT 뒤 create 증가: RECOVERY 0, REPEAT 0, FOLLOWUP 0.
- fault 시점 자산(복구·반복·후속에서 같음): 포인트 사용자 1 5,000 · 사용자 2 10,000, 사용자 2 쿠폰 1장(3,000), 가용 재고 10, 잡힌 수량 20.
- 주문 API는 포인트·쿠폰을 쓰지 않는다(포인트 미차감, 쿠폰은 발급만). 이 비교는 "0이 아닌 잔액·쿠폰을 가진 채 사고를 겪고 복구가 다시 차감·반환하지 않는다"까지만 증명한다.

## 검사

- `shasum -a 256 -c SHA256SUMS`: 7개 OK.
- 비밀값·카드·개인정보·관리자 계정 검사 0건: 카드번호(하이픈·숫자), `cardNo`, password·secret·token·Authorization·Basic, 관리자 계정 이름·환경 변수, DB 비밀번호 변수, 이메일, 휴대폰 번호. 16자리 숫자 23건은 모두 Prometheus 값의 소수부였다.

## 옛 한 벌과의 관계

- `20261125T082202Z-068161dd`: 873d848 실행(11-25). missingEvidence·summary.json·0이 아닌 포인트·쿠폰이 없다. 기록으로 그대로 둔다.
- `20261126T113318Z-6d788078`: 중단된 회귀·리허설 사전 실행. 그대로 둔다.
- 설계 문서 8절·12-1은 이 폴더를 제출용 한 벌로 가리킨다.
- `SHA256SUMS`는 JSON 7개만 다룬다(이 README는 제외).
