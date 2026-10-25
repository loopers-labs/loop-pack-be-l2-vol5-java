# 정상 PG 측정 기록

정상 test-pg와 Circuit Breaker 비활성 상태에서 결제 흐름을 측정했습니다. 시작된 2,997건의 접수·최종 성공 관측은 각각 99% 목표를 충족했습니다. 다만 `dropped_iterations=3`으로 부하 도착률 검사는 실패했습니다. 이번 결과는 정상 흐름의 첫 관측 기록이며, 누락 없이 10건/초를 유지한 비교 기준으로 확정하지 않습니다.

## 실행 조건

| 항목 | 적용값 |
| --- | --- |
| 실행일 | 2026-10-22, 본 측정 약 20:08:13~20:13:16 KST |
| 코드 | `7b97dbb6e207b4dc8140262a58fc49358a2aeb8a` |
| 작업 트리 | 새 측정 디렉터리만 미추적 상태입니다. 실행 소스·JAR 해시는 원본 환경 기록에 있습니다. |
| JVM | Java 21.0.9, JBR 21.0.9+10, macOS arm64 |
| k6 | `v1.6.1 (commit/devel, go1.26.0, darwin/arm64)` |
| 부하 | 새 결제 흐름 10건/초, 준비·최대 VU 50개, 본 측정 5분 |
| 단계 | 단건 smoke → 워밍업 30초 → 본 측정 5분, 단계별 집계 |
| fixture | 사용자 50명, 상품 하나·가격 1,000원·가용 재고 100,000개, 포인트 각 100,000 |
| Commerce·PG | 별도 네이티브 JVM, 각각 `ActiveProcessorCount=2`, `Xms128m`, `Xmx512m` |
| CPU 제한 | 호스트 논리 CPU 10개입니다. JVM의 활성 프로세서 수 설정만 사용했으며 OS CPU 할당량은 제한하지 않았습니다. |
| DB·캐시 | 임시 MySQL 8.0 컨테이너 2 CPU·1 GiB, Redis 7.0 컨테이너 1 CPU·256 MiB |
| Redis 구성 | 하나의 전용 Redis를 master·replica endpoint 양쪽에 연결했습니다. |
| PG | AUTO/SUCCESS, 처리 대기 3초, 조회 추가 지연 0ms |
| HTTP | 기존 RestClient, PG 연결 timeout 1초·응답 timeout 3초 |
| Circuit Breaker | `payment.pg.lookup-circuit-breaker.enabled=false` |
| 결과 확인 | callback이 PG를 조회해 반영하며, k6는 저장된 주문 GET으로 관측합니다. |
| 주기 조회 | 활성화, 1분 주기·마지막 변경 후 1분 경과 대상입니다. |
| retry | create·조회에 자동 retry를 추가하지 않았습니다. |

측정 명령은 다음과 같습니다. 실행기는 전용 프로세스·컨테이너를 종료하고, 기존 로컬 DB와 서비스는 유지합니다.

```bash
./gradlew :apps:commerce-api:bootJar :apps:test-pg:bootJar
python3 docs/performance/payment-normal/run-normal.py --rate 10 --vus 50 --duration 5m --warmup 30s
```

## 판정 기준

- 접수: 정확한 PG 접수·현재 상태 응답을 confirm 시작부터 1초 이내에 받는 비율이 99% 이상입니다.
- 최종 성공 관측: confirm 시작부터 PG 성공이 반영된 CONFIRMED 주문을 5초 이내에 읽는 비율이 99% 이상입니다.
- 주문 GET은 250ms 간격으로 관측하며 한 시도의 관측 한도는 10초입니다. 관측 한도는 결제 실패·취소 처리가 아닙니다.
- 시작한 모든 흐름을 비율의 분모에 포함하며, 미완료·오류를 지연 표본에서 조용히 제외하지 않습니다. 시작하지 못한 흐름은 `dropped_iterations=0`으로 별도 검사합니다.
- 업무 오류·미완료 수는 0건이어야 합니다. 외부 호출 수와 DB의 주문·시도·재고·포인트도 대조합니다.

## 본 측정

| 항목 | 결과 | 판정 |
| --- | --- | --- |
| 시작·완료 흐름 | 2,997건 / 2,997건 | 전부 최종 성공 |
| 시작하지 못한 흐름 | 3건 | 부하 도착률 검사 실패 |
| 목표 유입 구간 평균 | 2,997 / 300초 = 9.99건/초 | 목표 10건/초 일부 누락 |
| 정확한 접수 1초 이내 | 2,988 / 2,997 = 99.700% | 99% 목표 충족 |
| 최종 성공 관측 5초 이내 | 2,987 / 2,997 = 99.666% | 99% 목표 충족 |
| 접수 1초 초과 | 9건 | 최대 1,328ms |
| 최종 성공 관측 5초 초과 | 10건 | 최대 5,199ms |
| HTTP 실패·업무 흐름 오류 | 0건 / 0건 | 관측된 오류 없음 |
| 종료 시 미완료 | 0건 | 모두 CONFIRMED |
| k6 종료 코드 | 99 | `dropped_iterations` threshold 실패 |

k6의 전체 실행 시간에는 마지막 흐름의 완료 대기가 포함됩니다. 이에 따라 summary의 iteration rate는 약 9.891건/초입니다. 이를 300초 유입 구간의 9.99건/초와 혼동하지 않습니다.

| 지연 경계 | p50 | p95 | p99 | 최대 |
| --- | --- | --- | --- | --- |
| confirm 응답 | 23ms | 70ms | 355.52ms | 1,328ms |
| confirm 시작 → 최종 성공 GET 관측 | 3,111ms | 3,282.20ms | 4,106.36ms | 5,199ms |

최종 관측 지연에는 PG 처리, callback, PG 조회, 내부 반영과 주문 GET 관측 대기가 포함됩니다. DB commit 시각을 직접 측정한 값은 아닙니다. 250ms sleep에 HTTP 왕복·스케줄링이 더해지므로 관측 오차가 항상 250ms 이내인 것도 아닙니다.

## 호출과 업무 효과

본 측정만 집계한 값입니다. smoke 1건과 워밍업 301건은 포함하지 않습니다.

| 항목 | 결과 |
| --- | --- |
| k6 → Commerce 전체 HTTP | 44,790회 |
| 주문 생성 / confirm | 각 2,997회 |
| 저장된 주문 GET | 38,796회 |
| PG create / lookup / callback 시도 / 외부 거래 | 각각 2,997회·건 |
| 주문별 외부 호출·거래 | 모든 주문이 각각 create 1회·lookup 1회·callback 1회·거래 1건 |
| CONFIRMED 주문 증가 | 2,997건 |
| SUCCEEDED 시도 증가 | 2,997건 |
| 미종결 시도·충돌 | 종료 시 각각 0건 |
| 가용 재고 | 99,698 → 96,701, 정확히 2,997개 감소 |
| 미완료 예약 | 측정 전후 0개 |
| 포인트 | 50행·합계 5,000,000 유지 |

주문 GET 38,796회가 PG 조회 38,796회를 의미하지 않습니다. 실제 PG 조회는 시도당 한 번이었고, 별도의 추가 조회는 관측되지 않았습니다. 예약 소비 시 재고를 다시 차감하거나 PG 결제에서 포인트를 차감한 효과도 관측되지 않았습니다.

## 자원 표본

본 측정 중 약 5초 간격으로 60개 표본을 수집했습니다. 순간 최고점 전체를 보장하는 값은 아닙니다.

| 항목 | 관측값 |
| --- | --- |
| Commerce JVM live thread | 82~100개 |
| DB active 연결 | 표본 최대 22개, 풀 최대 40개 |
| DB 연결 대기 | 모든 표본 0개 |
| DB 연결 timeout 누적 카운터 | 전 표본 0 |
| Commerce heap 사용량 | 표본 최대 약 157.27 MiB |
| Commerce / PG RSS | 표본 최대 약 561.95 / 276.19 MiB |

자원 원본에는 Commerce CPU 지표와 두 JVM의 `ps` 표본도 있습니다. Tomcat busy thread 지표는 이번 scrape에 없었고, PG JVM thread는 수집하지 않았습니다. 이 자료만으로 지연이 발생한 위치나 CPU·락·GC 중 원인을 확정하지 않습니다.

## 해석

PG의 정상 성공 설정에서도 접수 9건과 최종 관측 10건은 개별 시간 목표를 넘었습니다. 전체 정상 응답이 모든 시도의 시간 목표 충족을 보장하지 않는다는 관측입니다. 이번 표본의 두 비율은 99% 이상이지만, 장기간 운영 SLO 달성을 입증하지는 않습니다.

실행 로그에는 `Insufficient VUs, reached 50 active VUs and cannot initialize more`가 남았습니다. 최대 실행 VU 50개에 도달한 구간이 있었으며 예정한 iteration 3개를 시작하지 못했습니다. 평균 처리 시간 약 3초만으로 계산한 초기 VU에는 일부 지연 구간을 흡수할 여유가 부족했습니다. 지연의 원인은 아직 분리하지 않았습니다.

다음 비교 전에는 같은 목표 RPS에서 VU 여유와 실행 환경을 검토하고, 누락 0건을 확인해야 합니다. 이번 실행의 VU·판정 기준은 결과를 본 뒤 바꾸지 않았습니다. Circuit Breaker 효과, 장애 격리, 다른 API의 SLO는 아직 이 실험으로 평가하지 않았습니다.

## 원본과 검증

- 실행 환경·소스 해시(`results/normal-20261022T110710Z/environment.json`)
- k6 본 측정 summary(`results/normal-20261022T110710Z/measurement-summary.json`)
- DB 전후·주문별 PG 카운터(`results/normal-20261022T110710Z/measurement-state.json`)
- 계산 결과·시간 목표 초과 표본(`results/normal-20261022T110710Z/measurement-analysis.json`)
- 흐름별 로그(`results/normal-20261022T110710Z/measurement-events.log`), 자원 표본(`results/normal-20261022T110710Z/measurement-resources.jsonl`)

2,997개 원시 흐름 기록과 summary의 시작·완료·좋은 결과 수를 대조했습니다. 주문별 PG 카운터, DB 증가량, 재고 감소량과 포인트 유지도 일치했습니다. 실행 소스·JAR의 SHA-256은 환경 기록과 일치합니다. 사용한 임시 컨테이너·JVM은 종료했습니다.

첫 단건 실행에서는 PG 거래·주문은 성공했지만 k6 내부 JavaScript 런타임 panic으로 측정 도구가 중단됐습니다. 첫 시도 원본(`results/normal-20261022T110514Z/smoke-events.log`)을 보존하고, 스크립트의 `try/finally` 반환 구조를 단순화한 뒤 재실행했습니다. 목표와 기대값은 유지했으며 첫 시도는 본 측정 표본에 포함하지 않았습니다. 재실행의 smoke·워밍업은 통과했고, 본 측정은 위의 누락 검사만 실패했습니다.

이번 변경은 측정 스크립트·문서에 한정되며 Commerce·test-pg의 Java 코드와 설정 기본값은 변경하지 않았습니다.
