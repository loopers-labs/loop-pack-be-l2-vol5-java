# JVM GC·세마포어 비교 실험

## 목적

CPU 이미지 변환 작업에서 세마포어 permit 수에 따라 발생하는 JVM GC 횟수와 정지 시간이 어떻게 달라지는지 비교합니다. Serial GC와 G1GC 결과도 같은 부하 조건에서 비교합니다.

## 실험 환경 및 조건

| 항목 | 설정 |
| --- | --- |
| 앱 서버 | `54.180.231.58`, EC2 `t3.micro` (2 vCPU) |
| JVM | Java 21 |
| Redis | VPC 사설 IP, 실험 전용 Redis DB 15 |
| 워커 수 | 10개 |
| 부하 | CPU 작업 100 RPS, 10초, 총 1,000건 |
| 작업 | 2048×2048 JPEG 생성 후 512×512 축소·JPEG 인코딩 1회 |
| permit 비교 | 3개, 100개 |

permit 100은 워커 10개보다 커서 동시 실행 수를 제한하지 않습니다. 다만 Redis Lua permit 확인·반납 경로는 계속 사용하므로 세마포어 구현 자체를 제거한 조건은 아닙니다.

## 후속 비교 실험 고정 부하

이후 permit 용량 비교는 **10 RPS를 10초간 유지해 총 100건을 접수**하는 조건으로 진행합니다. permit 용량 외에는 앱·워커·작업 유형과 JVM 설정을 고정하고, 각 실행은 앱 종료 → `sync; echo 3 > /proc/sys/vm/drop_caches` → 새 JVM·빈 Redis DB 시작 순서로 준비합니다. 앱 기동 후 첫 부하 전 `MemAvailable`과 힙 사용량을 기록합니다. 캐시 정리가 가용 메모리를 정확히 같게 만들지는 않으며, 차이가 크면 결과를 직접 비교하지 않습니다. OOM이 발생하면 JVM을 즉시 종료하고 OOM 로그·heap dump·접수/완료/잔류 작업 수를 기록합니다.

GC 로깅은 JVM unified logging(`-Xlog:gc*`)으로 켰습니다. GC 횟수와 누적 시간은 첫 작업 시작부터 마지막 작업 완료까지 발생한 `Pause` 이벤트를 합산했습니다. 따라서 이 보고서의 누적 시간은 애플리케이션 정지 시간이며, 특히 G1GC의 concurrent GC 작업 시간과 CPU 비용 전체를 뜻하지는 않습니다.

## 기존 Serial GC 결과

두 조건 모두 Java 21이 `Serial` collector를 선택한 상태에서 측정했습니다. JVM 로그에 보고된 힙 크기는 약 220MiB였습니다.

| permit 용량 | GC 횟수 | 누적 pause | 평균 pause | 최장 pause | 작업 구간 | pause 비율 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 3 | 1,926회 | 26.553초 | 13.79ms | 224.383ms | 149.542초 | 17.8% |
| 100 | 2,787회 | 139.266초 | 49.97ms | 348.572ms | 267.805초 | 52.0% |

permit 100 조건에서 GC pause 횟수와 누적 시간이 더 컸습니다. 이미지 작업이 큰 버퍼를 반복 할당하고, 최대 10개 작업이 겹치면서 GC 부담이 증가한 것으로 추정합니다. Serial GC의 pause 동안 작업 스레드가 멈춘다는 점은 높은 GC 정지 시간과 긴 작업 완료 시간에 부합하지만, 이 측정만으로 인과관계 전체를 확정하지는 않습니다.

## G1GC 결과

앱 JVM은 `-XX:+UseG1GC`로 전환했고 시작 로그에서 `Using G1`을 확인했습니다.

| GC / permit | GC pause 횟수 | 누적 pause | 평균 pause | 최장 pause | 작업 구간 | 시스템 CPU 평균 | 프로세스 CPU 평균 | 완료 결과 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| G1GC / 3 | 2,475회 | 11.955초 | 4.83ms | 84.490ms | 166.080초 | 97.86% | 98.65% | 1,000/1,000 |
| G1GC / 100, 1차 | 2,828회* | 21.631초* | 7.65ms* | 120.227ms* | 165.762초* | 미측정 | 미측정 | 994/1,000 |
| G1GC / 100, 재측정 | 2,983회* | 18.745초* | 6.28ms* | 84.801ms* | 164.719초* | 96.90%* | 97.21%* | 995/1,000 |
| G1GC / 100, OOM 재현 | 측정 중단 | 측정 중단 | 측정 중단 | 측정 중단 | JVM 시작 후 121.859초에 종료 | 유효 평균 없음 | 유효 평균 없음 | 822 접수, JVM 종료 |
| G1GC / 100, 10 RPS | 측정 중단 | 측정 중단 | 측정 중단 | 측정 중단 | JVM 시작 후 60.092초에 종료 | 유효 평균 없음 | 유효 평균 없음 | 34 접수, JVM 종료 |

`*` permit 100 값은 미완료 실행에서 첫 작업 시작부터 마지막 완료 작업까지의 측정값입니다. 재측정 CPU 평균은 그 구간에 기록한 166개 1초 표본으로 계산했습니다. 원본 표본은 [CPU 표본 CSV](data/g1-permit100-rerun-cpu-samples.csv)에 보존했습니다. 5건이 남은 뒤의 idle 표본은 평균에서 제외했습니다.

기록된 1,000건 완료 실행 중 전체 작업 구간이 가장 짧았던 것은 **Serial GC·permit 3의 149.542초**입니다. G1GC·permit 3은 166.080초였습니다. 이후 G1GC·permit 100은 OOM으로 종료되어 완료 속도 비교에 포함하지 않습니다. 이는 각 조건의 관측 결과이며 반복 횟수가 적어 일반적인 성능 우위로 단정할 수 없습니다.

### 미완료 작업 원인 조사

이전 G1GC permit 100 재측정에서는 1,000건을 접수했고 995건만 완료됐습니다. 대기열은 0건이고 Redis permit 5개가 남았으며 해당 작업 상태는 `PROCESSING`으로 유지됐습니다. 당시 앱 로그에는 `FAILED`나 OOM 메시지가 없었습니다.

JVM 스레드 덤프에서는 10개 executor worker 중 6개가 `ThreadPoolExecutor.getTask()`에서 새 작업을 기다리고 있었고, 4개만 100ms 대기 후 Redis를 다시 확인하는 worker 루프에 남아 있었습니다. 즉, 일부 worker 루프가 예외 등으로 끝난 뒤 permit이 회수되지 않은 상태로 보입니다. 코드가 worker 루프를 `submit()`으로 실행하고 반환된 `Future`를 확인하지 않아, 작업 중 발생한 예외가 로그에 드러나지 않았을 가능성이 있습니다. 당시에는 OOM을 확인하지 못했지만, 이후 OOM 종료 옵션을 넣은 재실행에서 원인을 확인했습니다.

첫 G1GC permit 100 실행도 994/1,000에서 같은 형태로 멈췄습니다. 그 두 실행 당시에는 정확한 예외 원인을 확정하지 못했으나, 아래 재현 실행에서 OOM을 확인했습니다. G1GC permit 100의 앞선 CPU 평균은 완료된 작업 구간의 관측값이며, 1,000건 전체 완료 성능이나 permit 3과의 완전한 비교로 해석하지 않습니다.

### OOM 재현 및 종료 동작

worker 경계의 OOM 로그와 JVM `-XX:+ExitOnOutOfMemoryError` 동작을 확인하기 위해 permit 100, worker 10으로 다시 실행했습니다. 100 RPS로 10초간 1,000건을 보내려 했으나, 앱 프로세스가 실행 중 종료되어 822건이 접수되고 178건은 접수되지 않았습니다. 프로세스 로그에서 `java.lang.OutOfMemoryError: Java heap space`와 G1 로그의 `Retried waiting for GCLocker too often allocating 2097154 words`를 확인했습니다. JVM uptime 121.859초에 OOM이 발생했고, 옵션대로 heap dump를 `/home/ubuntu/java_pid23895.hprof`에 기록한 뒤 프로세스를 종료했습니다(168,139,946 bytes).

종료 시 worker 로그는 22건 시작, 12건 완료를 기록했습니다. Redis DB 13을 직접 조회한 결과 대기열 800건, 활성 permit 10개였습니다. 시작됐지만 완료되지 않은 10건의 permit은 반환되지 않고 Redis에 남았습니다. 프로세스를 자동 재시작하지 않았으며, 이 실행의 CPU 표본은 프로세스 종료 전 9개만 확보되어 평균으로 사용하지 않습니다. 일부 표본은 [OOM 재현 CPU 표본 CSV](data/g1-permit100-oom-exit-rerun-samples.csv)에 저장했습니다.

### permit 100, 10 RPS 확인

같은 작업·워커·JVM 설정에서 요청만 10 RPS로 낮춰 10초간 100건을 보내는 실험도 수행했습니다. 이 조건에서도 JVM uptime 60.092초에 `java.lang.OutOfMemoryError: Java heap space`로 종료됐고, heap dump 199,961,101 bytes를 생성했습니다. 종료 시 100건 중 34건이 접수됐으며, worker 로그 기준 20건 시작·10건 완료였습니다. Redis DB 1에는 대기열 14건과 활성 permit 10개가 남았습니다. 따라서 10 RPS에서도 OOM이 재현됐지만, 전체 100건 완료나 유효한 P99를 얻지 못했습니다. CPU 표본은 4개뿐이라 평균 비교에 사용하지 않습니다. 표본은 [10 RPS OOM 확인 CSV](data/g1-permit100-rps10-oom-check-samples.csv)에 저장했습니다.

### permit 3·6·7·8·9, 10 RPS 비교

새 JVM과 빈 Redis DB에서 worker 10을 유지하고 permit 3, 6, 9, 8, 7로 시험했습니다. 각 실행은 10 RPS로 10초간 100건을 접수하는 조건입니다. permit 3과 6은 모두 완료됐고, permit 7·8·9는 OOM으로 JVM이 종료됐습니다.

| permit | 실제 RPS | 접수/완료 | 대기/처리 중 | 완료시간 P99 | 전체 구간 | 시스템 CPU 평균 | 프로세스 CPU 평균 | 결과 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 100 | 약 10 | 34/10 | 14/10 | 산출 불가 | OOM으로 종료 | 유효 평균 없음 | 유효 평균 없음 | `Java heap space` |
| 3 | 10.10 | 100/100 | 0/0 | 7.605초 | 18.07초 | 93.31% | 98.51% | OOM 없이 완료 |
| 6 | 10.10 | 100/100 | 0/0 | 8.767초 | 19.10초 | 92.86% | 97.02% | OOM 없이 완료 |
| 9 | 10.10 | 100/53 | 38/9 | 산출 불가 | JVM 시작 후 33.077초에 OOM | 유효 평균 없음 | 유효 평균 없음 | `Java heap space` |
| 8 | 약 10 | 46/15 | 23/8 | 산출 불가 | JVM 시작 후 26.425초에 OOM | 유효 평균 없음 | 유효 평균 없음 | `Java heap space` |
| 7 | 10.10 | 100/56 | 37/7 | 산출 불가 | JVM 시작 후 24.462초에 OOM | 유효 평균 없음 | 유효 평균 없음 | `Java heap space` |
| 6 재실행 | 10.10 | 41/12 | 23/6 | 산출 불가 | JVM 시작 후 25.478초에 OOM | 유효 평균 없음 | 유효 평균 없음 | `Java heap space` |

permit 3과 6은 10초 P99 목표를 만족했습니다. permit 7·8·9는 OOM으로 종료되어 P99를 계산하지 못했고, 이 지점에서 추가 용량 시험을 중단했습니다. permit 3과 6의 단일 실행만 비교하면 permit 3의 P99가 약 1.16초 짧았지만, 반복 실험이 아니므로 일반적인 성능 우위로 단정하지 않습니다. CPU 표본은 [permit 3 CSV](data/g1-permit3-rps10-samples.csv), [permit 6 CSV](data/g1-permit6-rps10-samples.csv), [permit 7 CSV](data/g1-permit7-rps10-samples.csv), [permit 8 CSV](data/g1-permit8-rps10-samples.csv), [permit 9 CSV](data/g1-permit9-rps10-samples.csv)에 저장했습니다. permit 9는 heap dump `/home/ubuntu/java_pid25565.hprof`(207,140,923 bytes), permit 8은 `/home/ubuntu/java_pid25862.hprof`(199,833,029 bytes), permit 7은 `/home/ubuntu/java_pid26158.hprof`(209,806,575 bytes)를 생성했습니다. 종료 시 DB 4에는 대기 38건·permit 9개, DB 5에는 대기 23건·permit 8개, DB 6에는 대기 37건·permit 7개가 남았습니다.
permit 3과 6 최초 실행은 10초 P99 목표를 만족했지만, permit 6 재실행은 OOM으로 끝났습니다. permit 7·8·9도 OOM으로 P99를 계산하지 못했습니다. 초기 permit 6의 결과는 재현되지 않았으므로 용량별 안정성을 단정하지 않습니다. CPU 표본은 [permit 3 CSV](data/g1-permit3-rps10-samples.csv), [permit 6 CSV](data/g1-permit6-rps10-samples.csv), [permit 7 CSV](data/g1-permit7-rps10-samples.csv), [permit 8 CSV](data/g1-permit8-rps10-samples.csv), [permit 9 CSV](data/g1-permit9-rps10-samples.csv)에 저장했습니다. permit 9는 heap dump `/home/ubuntu/java_pid25565.hprof`(207,140,923 bytes), permit 8은 `/home/ubuntu/java_pid25862.hprof`(199,833,029 bytes), permit 7은 `/home/ubuntu/java_pid26158.hprof`(209,806,575 bytes)를 생성했습니다. 종료 시 DB 4에는 대기 38건·permit 9개, DB 5에는 대기 23건·permit 8개, DB 6에는 대기 37건·permit 7개가 남았습니다.

### 캐시 정리 후 permit 3·4·5·6 재비교

이전 실행 사이 시작 메모리가 달랐다는 점을 반영해 매번 앱을 종료하고 페이지 캐시를 정리한 뒤 새 JVM과 빈 Redis DB에서 재측정했습니다. 아래 시작 메모리는 앱 기동 후 부하 요청 전 첫 표본입니다.

| permit | 시작 MemAvailable | 시작 힙 사용량 / 최대 힙 | 접수/완료 | 완료시간 P99 | 전체 구간 | CPU 평균(시스템/프로세스) | 결과 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 3 | 292.0 MiB | 28.9 / 228.0 MiB | 100/100 | 8.117초 | 18.07초 | 92.88% / 92.64% | 목표 통과 |
| 4 | 321.5 MiB | 31.0 / 228.0 MiB | 100/100 | 8.521초 | 19.08초 | 93.24% / 93.22% | 목표 통과 |
| 5 | 309.8 MiB | 36.4 / 228.0 MiB | 100/100 | 10.263초 | 20.07초 | 93.85% / 93.92% | P99 10초 목표 미달 |
| 6 | 302.4 MiB | 24.6 / 228.0 MiB | 41/12 | 산출 불가 | JVM 시작 후 25.478초에 OOM | 유효 평균 없음 | `Java heap space` |

캐시 정리 후 시작 MemAvailable 범위는 292.0–321.5 MiB였습니다. permit 4는 목표를 만족했고 permit 5는 0.263초 초과했습니다. permit 6은 재실행에서 OOM이 발생해 P99가 없습니다. 이 결과만으로 적정 permit을 확정하기에는 반복 횟수가 부족합니다. 표본은 [permit 3](data/g1-cleanstart-permit3-rps10.csv), [permit 4](data/g1-cleanstart-permit4-rps10.csv), [permit 5](data/g1-cleanstart-permit5-rps10.csv), [permit 6](data/g1-cleanstart-permit6-rps10.csv) CSV에 저장했습니다. permit 6 재실행의 heap dump는 `/home/ubuntu/java_pid27313.hprof`(202,864,013 bytes)이며, Redis DB 9에는 대기 23건·활성 permit 6개가 남았습니다.

### permit 3·4 반복 측정

재현성을 보기 위해 permit 3과 4를 같은 조건으로 한 번 더 측정했습니다. 각 반복 전에도 앱 종료, 페이지 캐시 정리, 새 JVM 시작을 거쳤습니다.

| permit | 1차 P99 | 2차 P99 | 2회 평균 | 시작 MemAvailable (1차/2차) | 결과 |
| ---: | ---: | ---: | ---: | ---: | --- |
| 3 | 8.117초 | 8.653초 | 8.385초 | 292.0 / 322.5 MiB | 두 번 모두 100/100 완료 |
| 4 | 8.521초 | 8.163초 | 8.342초 | 321.5 / 313.0 MiB | 두 번 모두 100/100 완료 |

두 permit 모두 10초 목표를 통과했습니다. 2회 평균 차이는 0.043초로 작아 성능상 우열은 뚜렷하지 않습니다. 동시 처리 여유를 한 단계 더 확보하면서 관측 P99가 비슷한 permit 4를 실험 설정으로 선택하는 것은 합리적이지만, 이 결론은 현재 부하와 서버 사양에 한정됩니다. 반복 CPU 표본은 [permit 3 CSV](data/g1-repeat-permit3-rps10.csv)와 [permit 4 CSV](data/g1-repeat-permit4-rps10.csv)에 저장했습니다.

GC pause 측면에서는 G1GC permit 3이 Serial GC permit 3보다 누적 pause가 작았습니다(26.553초 → 11.955초). G1GC permit 100은 실행이 미완료되어 GC 방식이나 permit 용량의 우열을 결론 내릴 수 없습니다.

앞선 미완료 Redis 상태는 DB 14에 보존했습니다. OOM 재현 상태는 DB 13(대기 800건·permit 10개), DB 1(대기 14건·permit 10개), DB 4(대기 38건·permit 9개), DB 5(대기 23건·permit 8개), DB 6(대기 37건·permit 7개), DB 7(대기 47건·permit 6개), DB 9(대기 23건·permit 6개)에 남아 있습니다. 100 RPS queue-cap 실험에 사용한 DB 12는 대기열과 permit을 모두 비운 뒤 기본 설정 검증에 재사용했습니다. 앱 서버는 기본 설정 permit 4·worker 10·queue 30으로 실행 중입니다.

## 해석 시 주의

- permit 100 worker 루프의 숨겨진 예외 원인을 계측하고, 정상 완료 가능한 상태에서 재실행해야 합니다.
- 비교를 위해 각 GC 조건에서 워커 수, 작업 수, 요청 유입률, 인스턴스와 힙 설정을 동일하게 유지합니다.
- 세마포어 용량 100은 permit 제한만 사실상 비활성화하며, Redis permit Lua 실행 비용은 포함합니다.
- 시스템 CPU와 Java 프로세스 CPU를 따로 비교합니다. G1GC는 concurrent GC도 수행할 수 있으므로 pause 시간만으로 GC의 전체 CPU 비용을 판단하지 않습니다.
- 각 조건을 반복하고 실행 순서를 바꿔 측정 편향을 확인합니다.
