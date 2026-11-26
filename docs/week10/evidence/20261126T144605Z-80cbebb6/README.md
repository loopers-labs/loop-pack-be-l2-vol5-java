# 사고 연결 E2E 증거: 최종 실행 (로컬 경로 치환)

- runId는 `20261126T144605Z-80cbebb6`이다. 맥북에어 로컬에서 2026-11-26 14:46 UTC에 실행했다.
  - 접미사 `80cbebb6`은 무작위 8자리다. 커밋 해시가 아니다(`IncidentEvidence`).
- 실행 당시 코드는 38ebb8e + 단언 테스트(Red 커밋 bb8aa0d와 같은 내용) + 로컬 경로 치환 작업 트리다.
  - 작업 트리: `IncidentEvidence`가 쓰기 전에 경로를 바꾼다. `CheckoutIncidentE2ETest`가 모든 JSON에 `/Users/`·`/home/`이 없음을 단언한다.
- 경로 치환: 홈 경로는 `~`, 저장소 루트는 `$REPO`로 바꿨다. 긴 경로(저장소 루트)부터 바꾼다.
  - 예: Prometheus `disk_free_bytes`의 `path` 라벨은 `$REPO/apps/commerce-api/.`이다.
- `CheckoutIncidentE2ETest`를 한 번 실행하면 단계마다 JSON을 남긴다. 그 JSON을 `build/incident-evidence/<runId>/`에서 그대로 복사했다.
  - 복사 뒤 내용은 손대지 않았다(`SHA256SUMS`).
- 명령은 `./gradlew :apps:commerce-api:check --rerun-tasks`이다.
  - api `check`가 `test`·`e2eTest`·Checkstyle·ArchUnit을 포함한다.
  - 결과는 `BUILD SUCCESSFUL in 5m 8s`.
- `bash scripts/ops/check-alerting.sh`: `executed=18 failed=0 errors=0 skipped=0`, `ALL CHECKS PASSED`.
- commerce-streamer·commerce-batch 검사는 이번에 돌리지 않았다.
  - 이번 apps 변경은 commerce-api `e2eTest` 소스(`IncidentEvidence`·`CheckoutIncidentE2ETest`)뿐이다.
  - 직전 회귀(38ebb8e 한 벌의 README): streamer test 47·batch test 54, 실패·skip 0.

| 모듈 | 작업 | tests | failures | errors | skipped |
| --- | --- | --- | --- | --- | --- |
| commerce-api | test | 1159 | 0 | 0 | 0 |
| commerce-api | e2eTest | 4 | 0 | 0 | 0 |

## 파일

| 파일 | 단계 |
| --- | --- |
| baseline.json | 기준 집합(사용자 1) 10건 확정 |
| fault.json | 장애 집합(사용자 2) 10건 중 3건 응답 유실. `assets`: fault 시점 포인트·쿠폰·재고 |
| diagnosis.json | 가설 H1~H3 판정. 가설마다 `missingEvidence`. H2에는 `crossCheck` |
| recovery.json / repeat.json / followup.json | 본인 reconcile 복구 / 반복·타인 거절 / 구매 활동·주간 랭킹. `assets`가 `assetsAtFault`와 같음 |
| summary.json | 집합별·단계별 확정·UNKNOWN·PG 성공·create 수 |

## 결과

- 판정: 유실 주문 11·12·13 모두 H1 채택, H2·H3 기각. `missingEvidence`는 H1·H3 2문장, H2 4문장.
- H2 `crossCheck`: 유실 주문 3건 모두 `applyFailedAnomalies` 0, `paymentStartInfo` 1, `createUnknownWarn` 1, `logChannelAlive` true.
- `summary.json`
  - 장애 집합: FAULT 7·3·10 → RECOVERY·REPEAT·FOLLOWUP 10·0·10, create 증가 0.
  - 기준 집합: 모든 단계 10·0·10.
- fault 시점 자산(포인트 5,000·10,000, 사용자 2 쿠폰 1장, 가용 재고 10, 잡힌 수량 20)이 복구·반복·후속에서 그대로다.
- `fault.json` sha256은 `09a5114a…bf6e9b9f`이다. 복구 뒤에도 같음을 테스트가 단언한다.
  - runId와 시각이 들어 있어 실행마다 값이 다르다.
- 직전 한 벌(38ebb8e의 `20261126T142753Z-ca7bfc01`)과 판정·`crossCheck`·집합 수·자산이 같다. 달라진 것은 경로 치환뿐이다.

## 검사

- `shasum -a 256 -c SHA256SUMS`: 7개 모두 OK.
- 로컬 경로·사용자명: `/Users/`·`/home/` 0건, 실제 사용자명(경로 조각·단어) 0건.
- 비밀값·카드·개인정보 검사에서 노출은 0건이다.
  - 대상: 카드번호(하이픈 형식·숫자만), `cardNo`, password·secret·token·Authorization·Basic, 관리자 계정, 이메일, 휴대폰 번호.
  - 휴대폰 번호 패턴과 16자리 숫자가 걸렸지만 모두 Prometheus 값의 소수부거나 UUID 조각이었다.

## 옛 한 벌과의 관계

- 옛 E2E 한 벌 4개에는 로컬 홈 경로가 들어 있어 트리에서 지운다. 기록은 git 히스토리에만 있다.
  - `20261125T082202Z-068161dd`(3518fff), `20261126T120742Z-f9dbdfde`(965d857), `20261126T140219Z-4ab238b7`(22e4fdc), `20261126T142753Z-ca7bfc01`(38ebb8e).
- 리허설 캡처 `20261126T113318Z-6d788078`·`20261126T121317Z-965d8579`는 그대로 둔다(로컬 경로 없음).
- `SHA256SUMS`는 JSON 7개만 다룬다. 이 README는 제외한다.
