# W10 사고 실험 증거

`CheckoutIncidentE2ETest`가 실행마다 `apps/commerce-api/build/incident-evidence/{runId}/`에 남긴 기록 중, 제출용으로 고른 것이다.
파일 내용의 참·거짓은 테스트 assertion(같은 주문 id·상태·효과 수)이 확인한다. 파일 존재나 hash는 통과의 근거가 아니다.

## 대표 실행: `20261126T142204Z-8b3d35aa`

- 브랜치 `volume-10`의 최종 코드로, 전체 회귀(`:apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check --rerun-tasks`) 중 실행된 회차다.
- 위변조 확인: 폴더 안에서 `sha256sum -c hashes.txt` (이 폴더는 `.gitattributes`에서 줄바꿈 변환을 끈다).

| 파일 | 내용 |
|---|---|
| `baseline.json` · `.health.json` · `.prometheus.txt` | 정상 집합 10건의 주문·시도·예약·Outbox·PG 거래·create 수, 실제 health·Prometheus 응답 원문 |
| `fault.json` · `.health.json` · `.prometheus.txt` | 장애 집합 10건(7 확정 · 3 UNKNOWN), 같은 시점의 health·Prometheus 원문 |
| `fault.boundary-log.jsonl` | 20개 주문의 경계 로그(`payment.attempt.created`, `pg.create.result`, `payment.apply`, `payment.apply.tx`, `pg.lookup.result`) |
| `diagnosis-hypotheses.json` | 조치 전에 적은 가설 3개와 예상 관찰 |
| `diagnosis.json` | 읽기 전용 권위 조회 결과, 가설별 판정, 부족한 증거 |
| `recovery.json` · `.health.json` · `.prometheus.txt` | 같은 장애 집합 10건의 복구 뒤 상태, create 전후 수, 포인트·쿠폰 전후 값 |
| `follow-up.json` · `ranking-api.json` | relay 발행 수, consumer offset, 주문별 구매 활동·Outbox 시도 수, 새 snapshot 입력 수, 랭킹 API 응답 |
| `hashes.txt` | 각 파일을 쓴 순간의 sha256 |

| 단계 | 내부 성공 | UNKNOWN | PG SUCCESS | 누적 거래 · create | 재고 | unresolved | health |
|---|---|---|---|---|---|---|---|
| BASELINE (정상 10) | 10 | 0 | 10 | 10 · 10 | 40 | 0 | UP |
| FAULT (장애 10) | 7 | 3 | 10 | 20 · 20 | 30 | 3 | UP |
| RECOVERY (같은 장애 10) | 10 | 0 | 10 | 20 · 20 | 30 | 0 | UP |

후속: relay 20건 발행 → consumer offset 20 → 주문마다 구매 활동 1행 → `weekly-20270201-r1` 입력 20행 → 랭킹 API 상품 점수 20 · 1위.

## 실패한 실행: `failed-runs/`

테스트를 처음 맞추는 동안 실패한 실행도 지우지 않고 실패 기록(`failures/*.txt`)만 옮겨 두었다.

| runId | 실패 단계 | 원인 | 조치 |
|---|---|---|---|
| `20261126T135123Z-64e3935e` | baseline | `/actuator/prometheus` 404. Spring Boot 테스트는 메트릭 exporter를 기본으로 끈다 | 이 테스트에만 `@AutoConfigureObservability(tracing = false)` |
| `20261126T135409Z-580bdd95` | recovery | `confirmed_at`을 JVM 시간대(Asia/Seoul)로 읽어 9시간 차이. Hibernate는 `jdbc.time_zone=UTC`로 저장한다 | 테스트의 JDBC 읽기를 UTC로 해석 |
| `20261126T135633Z-9f2ef693` | follow-up | snapshotId 형식 검증(`weekly-yyyyMMdd-rN`) 위반 | `weekly-20270201-r1` 사용 |

세 실패 모두 제품 코드가 아니라 테스트 구성 문제였다. 이와 별도로, 경계 로그를 넣으면서 `append` 반환값을 읽게 한 변경이 W6 재현 E2E를 500으로 깨뜨린 회귀는 커밋 `fix: 결제 적용 로그가 Outbox 저장 반환값에 기대지 않게 한다`로 고쳤다.
