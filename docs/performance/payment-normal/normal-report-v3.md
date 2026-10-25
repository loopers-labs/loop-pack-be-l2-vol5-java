# 정상 PG 재측정, VU 50개

첫 실행의 VU 부족 경고가 다시 발생하는지 확인하기 위해 준비 VU를 50개로 유지하고 정상 결제를 재측정했습니다. 본 측정 3,000건 모두 확정됐고, `Insufficient VUs` 경고와 시작 누락은 없었습니다.

## 실행 조건

| 항목 | 적용값 |
| --- | --- |
| 실행일 | 2026-10-22, 본 측정 첫 confirm부터 마지막 완료 관측까지 약 23:26:59~23:32:02 KST |
| 유입량 | 새로운 결제 흐름 10건/초, 본 측정 5분 |
| 부하 발생기 | 준비 VU 50개, `constant-arrival-rate`, 사용자 계정 50개 |
| 단계 | 단건 smoke, 워밍업 30초, 별도 본 측정 5분 |
| PG | 접수 후 약 3초 뒤 자동 성공 처리, 조회 추가 지연 0ms |
| PG HTTP | 연결 timeout 1초, 응답 timeout 3초 |
| 회복 정책 | 조회 Circuit Breaker 비활성, 생성과 조회에 자동 retry 없음 |
| 결과 확인 | callback이 PG 조회 결과를 반영하고, k6가 주문 GET으로 저장 상태를 관측 |
| 관측 주기 | 주문 GET 간격 250ms, 흐름별 관측 한도 10초 |
| 판정 기준 | 정확한 접수 1초 이내와 최종 성공 관측 5초 이내, 각각 99% 이상 |
| fixture | 사용자별 포인트 100,000, 가격 1,000원인 상품 하나, 초기 가용 재고 100,000개 |
| JVM | Commerce와 PG 각각 활성 프로세서 수 2개, `Xms128m`, `Xmx512m`, OS CPU 할당량 제한 없음 |
| DB와 캐시 | 별도 MySQL 8.0 컨테이너 2 CPU, 1 GiB와 Redis 7.0 컨테이너 1 CPU, 256 MiB |

실행기, k6 스크립트, test-pg JAR, 초기 SQL은 첫 실행과 해시가 같습니다. Commerce JAR는 timeout 취소 예외 처리를 보완한 현재 소스로 다시 빌드했으므로 첫 실행과 해시가 다릅니다. 이번 결과는 현재 코드의 정상 기준선으로 기록합니다. 실행 시점의 작업 트리와 JAR 해시는 원본 환경 기록에 남겼습니다.

```bash
./gradlew :apps:commerce-api:bootJar :apps:test-pg:bootJar
python3 docs/performance/payment-normal/run-normal.py --rate 10 --vus 50 --duration 5m --warmup 30s
```

기존 로컬 서비스와 데이터를 사용하지 않고 전용 JVM, MySQL, Redis를 실행했습니다. 종료 후 이번 실행에서 만든 프로세스와 컨테이너를 정리했습니다.

## 워밍업과 본 측정

| 항목 | 워밍업 30초 | 본 측정 5분 |
| --- | --- | --- |
| 시작과 확정 | 301건 | 3,000건 |
| 시작 누락 | 0건 | 0건 |
| VU 부족 경고 | 없음 | 없음 |
| 최대 사용 VU | 38개 | 33개 |
| k6 종료 코드 | 0 | 0 |

단건 smoke 1건과 워밍업 301건은 본 측정 통계에 합산하지 않았습니다. 유입 구간은 300초이며, 마지막 흐름의 완료 대기와 상태 수집을 포함한 실행기의 측정 단계 경과 시간은 약 304.97초입니다.

| 지표 | 본 측정 결과 |
| --- | --- |
| 정확한 접수 1초 이내 | 3,000 / 3,000건, 100% |
| 최종 성공 관측 5초 이내 | 3,000 / 3,000건, 100% |
| 흐름 오류와 미완료 | 각각 0건 |
| confirm p50 / p95 / p99 / 최대 | 17 / 61 / 123.01 / 472ms |
| 최종 성공 관측 p50 / p95 / p99 / 최대 | 3,099 / 3,250 / 3,336.01 / 3,630ms |
| Commerce HTTP 요청 | 44,946회 |
| 저장된 주문 GET | 38,946회 |

## 호출과 DB 검증

| 검증 대상 | 본 측정 결과 |
| --- | --- |
| PG create / lookup / callback 시도 / 거래 | 각각 3,000회 또는 건 |
| 주문별 PG 효과 | 모든 주문이 create, lookup, callback, 거래 각각 1회 또는 건 |
| CONFIRMED 주문 증가 | 3,000건 |
| SUCCEEDED 시도 증가 | 3,000건 |
| 가용 재고 | 99,698 → 96,698, 정확히 3,000개 감소 |
| 미완료 예약 | 전후 0개 |
| 미완료 주문과 시도, 충돌 | 종료 시 각각 0건 |
| 포인트 | 50행, 합계 5,000,000 유지 |

원시 이벤트 3,000개의 주문 ID, 접수와 최종 시간 목표 충족 여부, p99를 summary와 대조했습니다. PG 주문별 카운터, DB 전후 값, 실행 소스 해시를 포함한 22개 검증 항목이 모두 통과했습니다. 두 모듈의 `bootJar` 빌드와 k6의 모든 threshold도 통과했습니다.

5초 간격 자원 표본 60개에서 DB 활성 연결은 최대 7개였고, 연결 대기와 연결 timeout 누적 카운터는 모두 0이었습니다. Commerce live thread는 87~89개, 표본상 heap 사용량 최대는 약 156.62 MiB였습니다.

## 이전 실행과 비교

| 본 측정 | 첫 실행, VU 50 | 이전 재실행, VU 100 | 이번 재측정, VU 50 |
| --- | --- | --- | --- |
| 시작과 확정 | 2,997건 | 3,001건 | 3,000건 |
| 시작 누락 | 3건 | 0건 | 0건 |
| 최대 사용 VU | 50개 | 36개 | 33개 |
| confirm p99 | 355.52ms | 133ms | 123.01ms |
| 최종 성공 관측 p99 | 4,106.36ms | 3,401ms | 3,336.01ms |

이번에는 VU를 늘리지 않고 초당 10건을 5분간 누락 없이 시작했습니다. 첫 실행의 경고는 재현되지 않았습니다. 실행 시점과 Commerce JAR가 다르므로 이 결과만으로 첫 실행의 지연을 기동 순서나 VU 수 때문이라고 확정하지 않습니다.

## 원본

- 환경과 실행 소스 해시(`results/normal-20261022T142556Z/environment.json`)
- 본 측정 summary(`results/normal-20261022T142556Z/measurement-summary.json`)
- 흐름별 원시 로그(`results/normal-20261022T142556Z/measurement-events.log`)
- DB 전후 값과 주문별 PG 카운터(`results/normal-20261022T142556Z/measurement-state.json`)
- 검증 결과(`results/normal-20261022T142556Z/measurement-analysis.json`)
- 검증 스크립트(`results/normal-20261022T142556Z/verify-measurement.py`)
- 자원 표본(`results/normal-20261022T142556Z/measurement-resources.jsonl`)

원본 검증은 저장소 루트에서 다음 명령으로 다시 실행할 수 있습니다. 소스와 JAR 해시도 검사하므로 이후 소스나 빌드 산출물이 변경되면 해시 항목에서 차이가 보고됩니다.

```bash
python3 docs/performance/payment-normal/results/normal-20261022T142556Z/verify-measurement.py docs/performance/payment-normal/results/normal-20261022T142556Z
```
