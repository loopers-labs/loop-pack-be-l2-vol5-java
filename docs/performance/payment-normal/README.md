# 정상 PG 결제 측정

조회 지연·회로 실험과 구분해 정상 PG의 결제 접수와 최종 결과 관측을 측정합니다. 첫 조건은 새 결제 10건/초, 준비 VU 50개, 워밍업 30초와 별도 본 측정 5분입니다.

[첫 측정 기록](normal-report.md): 시작된 2,997건은 두 시간 목표와 정합성을 충족했지만, 누락 3건으로 부하 도착률 검사는 실패했습니다. Circuit Breaker는 비활성 상태였습니다.

[재측정 기록](normal-report-v2.md): 유입량·시간 목표를 유지하고 VU를 100개로 늘린 실행에서 3,001건 모두 확정, 누락 0건으로 통과했습니다. 첫 실행의 원본도 보존했습니다.

[VU 50개 재측정 기록](normal-report-v3.md): 처음과 같은 부하 조건으로 다시 실행해 3,000건 모두 확정, 경고와 누락 0건을 확인했습니다. 본 측정 최대 사용 VU는 33개였습니다. 현재 애플리케이션을 다시 빌드해 실행했으며, 실행기와 k6 스크립트는 첫 측정과 동일합니다. 원시 이벤트, PG 카운터, DB 전후 값을 대조한 검증 결과도 보존했습니다.

[최종 코드 재측정 기록](normal-report-v4.md): 조회 회복 정책과 취소 경계, 복수 거래 보완을 반영한 현재 코드로 같은 조건을 다시 실행했습니다. 3,001건 모두 확정, 경고와 누락 0건이며 22개 검증 항목을 통과했습니다. confirm p99는 49ms, 최종 성공 관측 p99는 3,199ms였습니다.

## 범위

한 iteration은 새 DRAFT 주문 생성, 같은 주문의 confirm 한 번, 주문 GET을 통한 최종 상태 관측입니다. PG 생성 요청은 재전송하지 않고, 결과 확인을 위해 reconcile을 호출하지 않습니다. 정상 callback이 결과를 반영하며 주문 GET은 저장된 상태만 읽습니다.

실행 환경은 별도로 준비합니다. 기존 데이터를 덮어쓰지 않는 전용 DB·사용자·상품·충분한 재고가 필요합니다. test-pg는 AUTO/SUCCESS, 처리 대기 3초, 추가 조회 지연 0ms로 둡니다. Commerce의 조회 Circuit Breaker는 끄고 실제 적용한 HTTP timeout·주기 조회 설정도 실행 기록에 남깁니다.

## 실행

전용 실행기는 임시 MySQL·Redis 컨테이너와 별도 Commerce·test-pg JVM을 준비합니다. 기존 로컬 데이터는 사용하지 않습니다. Docker, Python 3, Java 21, k6와 로컬 포트 18180·18181·18182가 필요합니다. 실행 종료 시 이번 실행기가 만든 프로세스와 컨테이너만 정리합니다.

```bash
./gradlew :apps:commerce-api:bootJar :apps:test-pg:bootJar
python3 docs/performance/payment-normal/run-normal.py --rate 10 --vus 50 --duration 5m --warmup 30s
```

실행 순서는 단건 smoke → 워밍업 → 본 측정입니다. 결과는 실행 시각별 `results/normal-<UTC 시각>/`에 저장합니다. 각 단계의 목표 판정이 실패하면 뒤 단계로 넘어가지 않습니다. 전용 fixture는 사용자 50명, 가격 1,000원인 상품 하나, 가용 재고 100,000개, 사용자별 포인트 100,000으로 구성합니다. 합성 카드만 사용합니다.

환경을 직접 준비했다면 k6만 실행할 수도 있습니다. URL은 로컬 주소만 허용하며, 사용자·상품 ID는 준비한 fixture의 값을 명시해야 합니다.

```bash
PG_BENCH_BASE_URL=http://127.0.0.1:18180 \
PG_BENCH_USER_IDS=1 \
PG_BENCH_PRODUCT_ID=1 \
PG_BENCH_OUTPUT=/tmp \
k6 run docs/performance/payment-normal/k6/normal-payment.k6.js
```

부하 실행은 `PG_BENCH_MODE=load`와 `PG_BENCH_RATE`, `PG_BENCH_VUS`, `PG_BENCH_DURATION`을 명시합니다. RATE는 초당 시작할 결제 흐름 수입니다. 각 흐름에 주문 생성·confirm·여러 번의 GET이 포함되므로 전체 HTTP RPS와 다릅니다. 부족한 VU로 시작하지 못한 iteration은 `dropped_iterations`로 확인합니다.

| 설정 | 의미 | 기본값 |
| --- | --- | --- |
| `PG_BENCH_MODE` | 한 건 확인 `smoke` 또는 도착률 부하 `load` | `smoke` |
| `PG_BENCH_PHASE` | 결과 파일과 원시 기록의 단계 이름 | mode 값 |
| `PG_BENCH_CONFIRM_MS` | 정확한 접수 응답의 시간 목표 | `1000` |
| `PG_BENCH_FINAL_MS` | confirm 시작부터 최종 성공 관측까지의 시간 목표 | `5000` |
| `PG_BENCH_OBSERVE_MS` | 한 시도의 관측 제한. 결제 취소·실패 처리가 아님 | `10000` |
| `PG_BENCH_POLL_MS` | 저장된 주문 상태를 읽는 간격 | `250` |
| `PG_BENCH_OUTPUT` | summary JSON을 저장할 기존 디렉터리 | `/tmp` |

이번 실험의 판정 기준은 접수 1초·최종 성공 관측 5초를 각각 99% 이상 충족하는 것입니다. 시간 목표는 환경 변수로 지정할 수 있습니다. 워밍업은 별도 phase로 실행하고 결과를 본 측정과 합산하지 않습니다. 운영 SLO의 집계 기간과 피크 트래픽은 별도 결정 사항입니다.

## 기록

단계별 summary JSON과 stdout의 `payment_normal_attempt` 기록을 보관합니다. 각 iteration이 만든 주문 ID를 남기며 카드 입력은 출력하지 않습니다. 전용 실행기는 실제 PG create·lookup·callback 횟수, DB의 주문·시도·재고·포인트 전후 값, 5초 간격의 자원 표본도 함께 수집합니다. k6만 직접 실행한다면 이 정보는 별도로 수집해야 합니다.

실행별 `results/` 원본은 저장소에 올리지 않고 로컬에 보관합니다. 보고서에 적은 원본 경로는 로컬 원본의 위치입니다.

접수 시간은 confirm 호출 전후의 경과 시간이며 연결 수립과 대기도 포함합니다. 최종 관측 시간은 confirm 시작부터 주문 GET으로 CONFIRMED를 확인할 때까지이므로 관측 간격의 오차가 포함됩니다. 완료 표본의 지연과 미완료 수를 함께 보고, 최종 목표 비율의 분모에는 시작한 모든 흐름을 포함합니다. 주문 생성 오류도 흐름 오류와 목표 미달로 집계합니다.

이 스크립트는 정상 SUCCESS 전용입니다. 이후 PG 실패 시나리오의 판정 기준은 해당 실험에서 별도로 정합니다. 짧은 로컬 실행은 장기간 운영 SLO 달성의 근거가 아닙니다.
