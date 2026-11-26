# 사고 연결 E2E 증거: 최종 실행 (w10s7 QA 반영)

- runId는 `20261126T142753Z-ca7bfc01`이다. 맥북에어 로컬에서 2026-11-26 14:27 UTC에 실행했다.
  - 접미사 `ca7bfc01`은 무작위 8자리다. 커밋 해시가 아니다(`IncidentEvidence`).
- 실행 당시 코드는 HEAD 153bb97(동시 기록 재현 Red)에 QA 반영 Green 작업 트리를 더한 상태다.
  - 작업 트리: `APPLY_FAILED` 기록 upsert 원자화, 기록 시점 트랜잭션 없음 단언, H2 `missingEvidence` 두 문장.
- `CheckoutIncidentE2ETest`를 한 번 실행하면 단계마다 JSON을 남긴다. 그 JSON을 `build/incident-evidence/<runId>/`에서 그대로 복사했다.
  - 내용은 손대지 않았다(`SHA256SUMS`).
- 명령은 `./gradlew :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check --rerun-tasks`이다.
  - api `check`가 `e2eTest`·Checkstyle·ArchUnit을 포함한다.
  - 결과는 `BUILD SUCCESSFUL in 6m 1s`.

| 모듈 | 작업 | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- | --- |
| commerce-api | test | 1159 | 0 | 0 | 0 |
| commerce-api | e2eTest | 4 | 0 | 0 | 0 |
| commerce-streamer | test | 47 | 0 | 0 | 0 |
| commerce-batch | test | 54 | 0 | 0 | 0 |

## 파일

| 파일 | 단계 |
| --- | --- |
| baseline.json | 기준 집합(사용자 1) 10건 확정 |
| fault.json | 장애 집합(사용자 2) 10건 중 3건 응답 유실. `assets`: fault 시점 포인트·쿠폰·재고 |
| diagnosis.json | 가설 H1~H3 판정. 가설마다 `missingEvidence`. H2에는 `crossCheck` |
| recovery.json / repeat.json / followup.json | 본인 reconcile 복구 / 반복·타인 거절 / 구매 활동·주간 랭킹. `assets`가 `assetsAtFault`와 같음 |
| summary.json | 집합별·단계별 확정·UNKNOWN·PG 성공·create 수 |

## 이번 실행에서 달라진 것

- H2 `missingEvidence`가 2문장에서 4문장이 됐다. 더한 두 문장은 다음과 같다.
  - "logChannelAlive는 JVM 안 로그 캡처 기준이다. Alloy→Loki 경로는 증명하지 않는다(운영은 가이드 교차 확인·CommerceLogIngestionSilent)"
  - "반영 중 프로세스가 죽으면 DB에도 로그에도 남지 않는다"
- 그 밖에는 직전 한 벌 `20261126T140219Z-4ab238b7`과 같다.
  - 판정: H1 채택 [11, 12, 13], H2·H3 기각.
  - H2 `crossCheck`: 유실 주문 3건 모두 `applyFailedAnomalies` 0, `paymentStartInfo` 1, `createUnknownWarn` 1, `logChannelAlive` true.
  - `summary.json`(runId 제외)과 단계별 `assets`·`assetsAtFault`가 같다.
  - 장애 집합: FAULT 7·3·10 → RECOVERY·REPEAT·FOLLOWUP 10·0·10, create 증가 0.
  - 기준 집합: 모든 단계 10·0·10.
- `fault.json` sha256은 `dbf144b6…cd03378`이다. 복구 뒤에도 같음을 테스트가 단언한다.
  - runId와 시각이 들어 있어 실행마다 값이 다르다.

## 검사

- `shasum -a 256 -c SHA256SUMS`: 7개 모두 OK.
- 비밀값·카드·개인정보·관리자 계정 검사에서 노출은 0건이다.
  - 대상: 카드번호(하이픈 형식·숫자만), `cardNo`, password·secret·token·Authorization·Basic, 관리자·DB 비밀번호 변수, 이메일, 휴대폰 번호.
  - 휴대폰 번호 패턴과 16자리 숫자가 걸렸지만 모두 Prometheus 값의 소수부였다.

## 옛 한 벌과의 관계

- `20261126T140219Z-4ab238b7`(7단계 H2 교차 확인)과 `20261126T120742Z-f9dbdfde`(6단계)는 기록으로 그대로 둔다.
- `20261125T082202Z-068161dd`(11-25 실행)와 리허설 캡처 `20261126T113318Z-6d788078`·`20261126T121317Z-965d8579`도 그대로 둔다.
- `SHA256SUMS`는 JSON 7개만 다룬다. 이 README는 제외한다.
