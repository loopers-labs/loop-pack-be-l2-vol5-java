# W10 사고 실험 증거

> 결제 생성 응답 유실 사고 1회분의 원문 증거다. `CheckoutIncidentE2ETest`(커밋 `3acf6ab`)가 한 lifecycle에서 baseline → fault → 진단·보존 → recovery → 후속을 이어 실행하며 남겼다.

- 원본 실행: `apps/commerce-api/build/incident-runs/20261127T083426Z-099c30/` (2026-11-27 08:34Z)
- 실행: `./gradlew :apps:commerce-api:e2eTest --tests '*CheckoutIncidentE2ETest'`
- 무결성 확인: 이 폴더에서 `shasum -a 256 -c SHA256SUMS`. hash는 기록이 바뀌지 않았다는 확인일 뿐 내용이 참이라는 증명은 아니다. 내용은 테스트 assertion이 같은 식별자·상태·효과 수로 검증했다.

## 결과 요약

| 단계 | 대상 주문 | 내부 성공 | UNKNOWN | PG SUCCESS | 누적 거래 | create 누적 | 재고 |
|---|---|---|---|---|---|---|---|
| baseline | 1~10 | 10 | 0 | 10 | 10 | 10 | 15 |
| fault | 11~20 (유실 12·15·19) | 7 | 3 | 10 | 20 | 20 | 5 |
| recovery | 같은 11~20 | 10 | 0 | 10 | 20 | 20 (+0, 중복 0) | 5 |

- health: 세 단계 모두 `UP`. Prometheus 원문의 `commerce_payment_unresolved{status="UNKNOWN"}`은 0 → 3 → 0.
- 가설: 처리 지연 기각, **성공 응답 유실 지지**, 내부 반영 실패 기각(`diagnosis.json`).
- 후속: Outbox 20건 PUBLISHED, 구매 활동 20행(주문당 1), 주간 랭킹 `weekly-20261123-r1` 1위 점수 20(baseline 10 + 장애 집합 10).
- 추적: 복구한 3건은 create trace ≠ reconcile trace, reconcile trace가 Outbox·Kafka 헤더·streamer 로그까지 같다(`downstream.json`의 `traces`).

## 파일

| 파일 | 내용 |
|---|---|
| `run.json` | 실행 ID·시각·고정 시계·주문 집합·유실 주문·fault 파일 hash·결과 |
| `baseline.json`·`fault.json`·`recovery.json` | 관찰 시각, 주문별 시도 키·providerOrderId·거래 키·상태·PG 거래, create·중복 수, 재고 |
| `*-health.json` | 실제 management 포트 `/actuator/health` 응답 원문 |
| `*-prometheus.txt` | 실제 `/actuator/prometheus` 응답 원문 |
| `fault-logs.json` | fault 시점까지 수집한 경계 로그(event·key-value·MDC orderId·traceId) |
| `diagnosis.json` | 가설별 예상·실제·판정(관찰에서 계산, 경계 로그가 없으면 "미확정") |
| `downstream.json` | Outbox·구매 활동·trace 대조·랭킹 API 응답 |
| `SHA256SUMS` | 위 파일들의 sha256 |

## 다른 실행

- `20261127T082939Z-36eb95`(같은 날 첫 실행)도 통과했지만 가설 2의 판정 문자열을 계산하지 않던 코드였다. 제출 증거로 쓰지 않는다. 중간에 실패한 실행은 없었다.
- 범위: 로컬 통제 실험 10+10건이다. 운영 배포·실계정·부하·SLO 달성·Slack 실제 발송·알람 발화는 이 증거가 증명하지 않는다.
- health 응답은 `status`·`groups`만 보인다(구성 요소 세부 비공개 설정). health가 업무 종결을 말해 주지 않는다는 관찰에 그대로 사용했다.
