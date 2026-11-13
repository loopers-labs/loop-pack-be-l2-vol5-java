# 8주차 부하 측정 — 실제 앱에서 C를 바꿔 보기

과제 범위 밖에서 한 측정이다. 테스트 안 실험(`experiment/CouponThroughputExperimentTest`, 테스트 풀 10)이 보인 경향을 실제로 띄운 앱(로컬 풀 40)에서 다시 본다.

확인하려는 것은 두 가지다.
1. **C를 늘리면 발급 처리량이 오르나** — D-17은 "처리량은 `1 ÷ 락 쥔 시간`이 정한다"고 적었다.
2. **C를 늘리면 다른 기능이 느려지나** — D-17은 "C를 키운 만큼 다른 기능의 커넥션이 준다(벌크헤드)"고 적었다.

## 무엇을 보내나

`k6/issue.js`가 두 시나리오를 같이 돌린다.

| 시나리오 | 방식 | 내용 |
|---|---|---|
| `issue` | `constant-vus` (기본 200 VU), 60초 | `POST /api/v1/coupon-campaigns/{id}/issues`, `X-USER-ID`는 매 반복 새 사용자(1,000,001부터). 429·503에도 다시 보내지 않는다 |
| `list` | `constant-arrival-rate` 초당 20건, 60초 | 다른 기능: `GET /api/v1/coupons`(내 쿠폰 목록). 캐시 없이 매번 readOnly TX로 DB를 읽는다(`CouponQuery.listMine`). 사용자는 1,000,001~1,000,500 중에서 고른다 |

- 다른 기능으로 상품 상세를 쓰지 않은 이유: 4주차부터 Redis 캐시를 거쳐 DB를 매번 읽지 않는다. 쿠폰 목록은 캐시가 없다.
- `list`는 도착 속도를 고정했다. 그래서 그쪽 응답 시간이 변하면 원인은 자기 부하가 아니라 서버 안의 자원 경쟁이다.
- 캠페인 한도는 1,000,000장(`coupon.load.quota`)이라 품절(409)이 섞이지 않는다.

### 두 가지 측정

| 측정 | 발급 VU | 결과 파일 | 보려는 것 |
|---|---|---|---|
| 본 측정 | 200 (포화) | `results/c{C}.*` | 과제가 말하는 "몰릴 때" 그대로 |
| 보조 측정 | C와 같게 | `results/vu-eq-c-c{C}.*` | 자리 거절(429)을 거의 없애고, C개의 실행이 커넥션을 얼마나 가져가는지만 본다 |

보조 측정은 본 측정 결과를 보고 추가했다. 본 측정에서는 429로 거절되는 요청도 문 앞의 기존 쿠폰 조회(D-18)로 매번 DB를 읽어, 모든 단계에서 풀이 거절 요청으로 가득 찼다. 그 상태에서는 C의 몫(D-17)이 잘 보이지 않는다.

## 준비 코드 (`coupon-load` 프로필)

`apps/commerce-api/src/main/java/com/loopers/application/coupon/CouponLoadRunPreparation.java`

- `@Profile("coupon-load")`라 이 프로필을 켤 때만 빈이 된다. 다른 실행에서는 지금처럼 서버가 닫힌 채 켜진다(D-7).
- 서버가 다 켜지면(`ApplicationReadyEvent`) `CouponCampaignSetup.create`로 캠페인을 만들고 `CouponAdmission.open()`을 부른다. HTTP 주소는 만들지 않는다(D-11, D-16의 "로컬 실행 코드").
- 로그에 `coupon-load campaignId=…`를 남기고, `run-stage.sh`가 그 줄에서 id를 읽는다. local은 매번 스키마를 새로 만들어 id는 보통 1이다.
- 테스트는 따로 두지 않았다. 하는 일이 이미 테스트된 두 빈 메서드를 순서대로 부르는 것뿐이고, 프로필 전용 통합 테스트는 Spring 컨텍스트를 하나 더 띄워 `check`를 늘린다. 실제 동작은 이 측정의 각 단계(`results/*-db.txt`: 캠페인 1개, 발급 수 = 쿠폰 수)로 확인했다.

## 실행 순서

```bash
# 1) 인프라 (kafka는 쓰지 않으므로 끈다)
docker compose -f docker/infra-compose.yml up -d && docker stop kafka-ui kafka
# 2) 모니터링 (Prometheus :9090이 host.docker.internal:8081을 5초마다 긁는다, Grafana :3000 admin/admin)
docker compose -f docker/monitoring-compose.yml up -d

# 3) 본 측정: C = 1 3 6 12 24 40, 단계마다 앱을 새로 띄운다(스키마 초기화)
docs/week8/load-test/run-all.sh
# 4) 보조 측정: 발급 VU = C
VU_EQ_C=1 docs/week8/load-test/run-all.sh

# 5) 표로 모으기
python docs/week8/load-test/summarize.py            # → results/summary.csv
python docs/week8/load-test/summarize.py vu-eq-c    # → results/vu-eq-c-summary.csv

# 6) 정리 — down 대신 stop: Prometheus 컨테이너 안의 측정 기록이 남아 나중에 Grafana로 다시 볼 수 있다
docker compose -f docker/monitoring-compose.yml stop
docker compose -f docker/infra-compose.yml stop
# 다시 켤 때
docker compose -f docker/infra-compose.yml start && docker stop kafka-ui kafka
docker compose -f docker/monitoring-compose.yml start
```

단계 하나만: `docs/week8/load-test/run-stage.sh 12` (환경 변수 `VUS`, `DURATION`, `LIST_RATE`, `PREFIX`).

앱은 이렇게 뜬다(`run-stage.sh` 안):

```bash
./gradlew :apps:commerce-api:bootRun --args="--spring.profiles.active=local,coupon-load --spring.jpa.show-sql=false --server.tomcat.mbeanregistry.enabled=true --coupon.admission.capacity=12"
```

## 파일

| 파일 | 내용 |
|---|---|
| `k6/issue.js` | 발급(포화) + 다른 기능(고정 속도) 두 시나리오. 상태별 개수(201/200/409/429/503/500/그 밖)와 응답 시간 |
| `run-stage.sh` | C 하나: 앱 새로 기동 → 1초 지표 수집 + k6 → Prometheus 구간 저장 → DB 확인 |
| `run-all.sh` | C = 1, 3, 6, 12, 24, 40을 차례로. `VU_EQ_C=1`이면 보조 측정 |
| `sample-metrics.sh` | 1초마다 `/actuator/prometheus`에서 풀 사용/대기/최대, 커넥션 획득 시간(누적 sum·count·max), Tomcat 바쁜 스레드 |
| `prom-range.py` | 단계 구간을 Prometheus `query_range`(5초 간격)로 받아 CSV로. 식은 Grafana 대시보드와 같다 |
| `summarize.py` | 단계별 k6 요약과 1초 지표를 표 하나로 |
| `grafana-dashboard.json` | Grafana에 가져올 대시보드 |
| `results/` | `{name}.json`·`.txt`(k6), `-metrics.csv`(1초), `-prom.csv`(Prometheus), `-window.txt`(측정 시작·끝 unix초), `-db.txt`(끝난 뒤 DB), `-app.log`, `summary.csv` |

## Grafana 대시보드

1. 모니터링을 띄운다: `docker compose -f docker/monitoring-compose.yml up -d`
2. http://localhost:3000 (admin / admin) → Dashboards → New → **Import** → `docs/week8/load-test/grafana-dashboard.json` 업로드 → 데이터 소스로 `Prometheus` 선택 → Import.
3. 패널: 풀 사용/대기/최대, 커넥션 획득 시간, 발급 요청 수(상태별), 다른 기능 응답 시간 p50/p95, 발급 응답 시간 p95(상태별), Tomcat 바쁜 스레드.

**캡처할 때**
- Prometheus는 지난 측정도 보관한다(기본 15일). 기록은 컨테이너 안에 있어 `stop`/`start`로는 남고 `down`하면 사라진다(볼륨이 없다). 다시 측정하지 않고 찍으려면 오른쪽 위 시간 범위를 `results/*-window.txt`의 시작·끝(unix초)으로 맞춘다. 본 측정 전체는 첫 단계 시작부터 마지막 단계 끝까지 한 화면에 들어온다. 2026-11-13 측정은 본 측정 11:51~12:02, 보조 측정 12:03~12:14(KST)다.
- 다시 측정하면서 찍는다면 `run-all.sh`를 돌리고 시간 범위를 "Last 15 minutes"로 둔다. 단계 사이 약 40초는 앱 재기동이라 비어 있다.
- 한 장으로 보여 줄 장면: (1) 풀 패널 — 본 측정에서 모든 단계 사용 40·대기 수십~160, (2) 보조 측정의 풀 패널 — C에 따라 사용 개수가 계단처럼 오르는 모습, (3) 다른 기능 p95가 C=40에서 오르는 모습.

## 결과

2026-11-13, 로컬 PC(Windows 11), Docker MySQL 8.0, DB 커넥션 풀 40(`mysql-main-pool`, 획득 timeout 3초), Tomcat 스레드 200. 단계마다 60초, 앱 재기동. 전체 값은 `results/summary.csv`, `results/vu-eq-c-summary.csv`.

### 본 측정 — 발급 VU 200

| C | 발급 201 (건/초) | 상태 (60초 합) | 발급 전체 p50 / p95 (ms) | 201만 p50 / p95 (ms) | 풀 사용 최대 / 대기 최대 | 커넥션 획득 평균 (ms) | 다른 기능 p95 (ms) |
|---|---|---|---|---|---|---|---|
| 1 | 9.8 | 201 592, 429 30,667, 연결 거절 8 | 388 / 499 | 113 / 329 | 40 / 103 | 25.7 | 402 |
| 3 | 9.7 | 201 585, 429 28,022 | 426 / 534 | 314 / 735 | 40 / 155 | 32.7 | 396 |
| 6 | 9.7 | 201 585, 429 26,001, 연결 거절 91 | 437 / 655 | 596 / 1,427 | 40 / 87 | 50.7 | 442 |
| 12 | 14.0 | 201 843, 429 28,540 | 395 / 525 | 849 / 1,344 | 40 / 132 | 27.0 | 286 |
| 24 | 14.6 | 201 885, 429 29,286 | 352 / 615 | 1,712 / 2,674 | 40 / 151 | 93.0 | 427 |
| 40 | 21.4 | 201 1,314, 429 23,601 | 381 / 1,815 | 2,192 / 2,612 | 40 / 160 | 338.9 | 724 |

- 200·409·503·500은 모든 단계에서 0이다. "연결 거절"은 k6가 시작하자마자 200개 연결을 한꺼번에 열 때 일부가 TCP 단계에서 거절된 것(`connectex … actively refused`)으로, HTTP 응답이 없다.
- 단계마다 끝난 뒤 DB에서 발급 수 = 실제 쿠폰 수, 사용자당 2장 이상 0건이었다(`results/c*-db.txt`).

### 보조 측정 — 발급 VU = C

| C | 발급 201 (건/초) | 상태 | 발급 p50 / p95 (ms) | 풀 사용 최대 / 대기 최대 | 커넥션 획득 평균 (ms) | 다른 기능 p95 / p99 (ms) |
|---|---|---|---|---|---|---|
| 1 | 21.6 | 201만 1,296 | 44 / 60 | 3 / 0 | 0.0 | 14 / 25 |
| 3 | 34.5 | 201만 2,073 | 81 / 116 | 5 / 0 | 0.0 | 14 / 21 |
| 6 | 33.4 | 201만 2,008 | 165 / 242 | 8 / 0 | 0.0 | 15 / 30 |
| 12 | 34.1 | 201만 2,054 | 337 / 439 | 14 / 0 | 0.0 | 14 / 34 |
| 24 | 34.2 | 201만 2,071 | 682 / 837 | 26 / 0 | 0.0 | 14 / 40 |
| 40 | 34.2 | 201만 2,084 | 1,139 / 1,391 | 40 / 12 | 3.6 | 34 / 126 |

### 숫자가 말하는 것

1. **C를 늘려도 발급 처리량은 C=3부터 멈춘다.** 보조 측정에서 C=1 → 3은 21.6 → 34.5/s로 올랐고, C=3~40은 33.4~34.5/s로 같았다. 같은 구간에 발급 p50은 81 → 1,139ms로 C에 비례해 늘었다(초당 34건을 C개가 나눠 기다리면 약 C ÷ 34초). 늘린 C는 처리량이 아니라 대기열이 됐다. 테스트 실험의 "처리량은 `1 ÷ 락 쥔 시간`이 정한다"(D-17)와 같은 모양이다. 다만 이 측정은 락 쥔 시간을 직접 재지 않았다.
2. **발급 실행 C개는 커넥션도 C개를 가져간다.** 보조 측정에서 풀 사용 최대는 3, 5, 8, 14, 26, 40 = C + 2였다. C=40에서 발급만으로 풀이 차고 대기(최대 12)가 생겼고, 다른 기능 p95가 14 → 34ms, p99가 40 → 126ms로 늘었다. C≤24에서는 다른 기능 p95가 14~15ms로 변하지 않았다. D-17의 "C를 키운 만큼 다른 기능의 커넥션이 준다"는 풀에 닿는 지점에서 응답 시간으로 드러났다.
3. **포화 부하(200 VU)에서는 거절된 요청이 풀을 채운다.** 본 측정은 모든 단계에서 429가 초당 약 390~510건이었고, 풀 40을 다 쓰고 대기가 87~160이었다. 신규 발급 처리량은 C=1~6에서 9.7~9.8/s로, 같은 C의 보조 측정(21.6~34.5/s)의 절반 이하였다. 다른 기능 p95는 C와 상관없이 286~442ms(보조 측정의 약 30배)였고 C=40에서 724ms였다. 429로 끝날 요청도 문 앞의 기존 쿠폰 조회(D-18)로 커넥션을 한 번씩 쓰기 때문으로 보인다(테스트 실험 `CouponPrecheckOrderExperimentTest`의 A 순서와 같은 비용).
4. **본 측정에서는 C를 키우면 신규 발급이 늘었다(9.8 → 21.4/s).** 거절 요청과 커넥션을 다투는 상황에서 자리를 받은 실행이 많을수록 그 몫이 커진 것으로 보인다. 대신 201 p95가 329ms → 2.6초, 커넥션 획득 평균이 26 → 339ms로 늘었다.
5. **429 하나마다 WARN 로그가 스택과 함께 남는다.** 본 측정의 앱 로그는 단계마다 약 200MB(약 200만 줄)였다. 저장소에는 앞 250줄과 종류별 개수만 남겼다(`results/c*-app.log` 끝). 거절이 많을 때 로그 쓰기도 비용이 된다. 그 몫을 따로 재지는 않았다.

### 한계

- 로컬 PC 한 대에서 앱, MySQL, Redis, k6, Prometheus가 CPU를 나눠 쓴다. 절대값보다 단계 사이의 경향을 본다.
- 단계마다 1회만 쟀다. JVM은 단계마다 새로 떠서 처음 몇 초가 느릴 수 있다(예열 구간을 따로 두지 않았다).
- 다른 기능은 내 쿠폰 목록 하나(초당 20건, 행 1개를 읽는 짧은 조회)다. 주문 확정처럼 TX가 긴 기능이면 영향이 더 클 수 있다.
