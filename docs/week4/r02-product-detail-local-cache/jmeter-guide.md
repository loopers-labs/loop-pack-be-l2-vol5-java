# R02 상품 상세 캐시 전후 측정

[요구사항](requirement.md) · [계획](plan.md) · [실제 결과](result.md)

같은 새 JAR의 Caffeine OFF/ON을 비교한다. R01 GUI JMX는 수정하거나 사용하지 않는다.
명령을 실행하기 전에는 API 기동·DB 확인·JMeter 요청이 발생하지 않는다. 기본 모드는 읽기 전용 사전 확인이다.
원본은 Git에서 제외된 `build/benchmark/r02/`에 저장한다. 실제 수행 여부와 수치는 결과 문서에서 구분한다.

## 고정 조건과 파일

| 항목 | 값 |
|---|---|
| 조회 API / 관리 포트 | `127.0.0.1:18081` / `127.0.0.1:18082` |
| 보존할 기존 포트 | 18080 / 8081. 해당 프로세스를 시작·종료하지 않는다. |
| DB | `week4_r01_index_benchmark`, 상품 100만 개·좋아요 2,597,980개 |
| 상품 인덱스 | PRIMARY와 기존 보조 인덱스 6개 |
| DB 설정 | 버퍼 풀 128MiB와 R01 최종 주요 설정 11개 유지 |
| 캐시 ON | maximum-size=1000, TTL=30s, expireAfterWrite |
| 요청 분포 | 브랜드 7의 첫 활성 상품 100개를 CSV 순서로 순환, 전체 스레드가 한 CSV를 공유 |
| warm-up / 본 측정 | 20초 / 60초, 각각 별도 JMeter 프로세스·원본 |
| 동시 사용자 / 반복 | 1명·10명 / 각 2회, 총 본 측정 8개 |
| ramp-up / timeout | 5초 / 연결 3초·응답 30초 |
| API 설정 | ddl-auto=none, seed·좋아요 집계·Redis health 끔, COUNT TTL=0s, SQL 로그 OFF |

- [product-detail.jmx](jmeter/product-detail.jmx): HTTP sampler 1개. GUI 기본은 1명·1회·전체 응답 검증이다.
- [product-ids.csv](jmeter/product-ids.csv): 활성 상품 ID 100개, 첫 ID 7·마지막 ID 103007.
- [expected-responses.json](jmeter/expected-responses.json): SQL seed 공식을 독립 계산한 상세 응답 100개. API/DB 응답을 복사하지 않았다.
- [run-benchmark.ps1](jmeter/run-benchmark.ps1): Preflight·Smoke·Measure 진입점.
- [benchmark-common.ps1](jmeter/benchmark-common.ps1): 읽기 전용 DB 확인·자기 PID 관리·Prometheus 수집.
- [results.properties](jmeter/results.properties): CSV JTL·productId·오류 메시지를 저장하고 전체 응답 본문 저장은 끈다.
- [summarize-results.ps1](jmeter/summarize-results.ps1): 완료된 원본의 JTL·HTML 통계·Prometheus·해시를 대조해 공개 요약을 만든다.

상세는 `productId/name/price/brand/likeCount/description/stock` 7개 필드와 브랜드의 `brandId/name`을 포함한다.
설명은 SQL seed와 같은 `benchmark`다. users 1/2와 잔액 0의 wallets 1/2도 확인해 local fixture의 자동 INSERT를 방지한다.

## 명령 실행 순서

저장소 루트 PowerShell에서 실행한다. 아래 JAR 이름은 실제 **이번 구현의 새 빌드 파일명**으로 교체한다.
실험 도중 JAR이나 도구를 다시 빌드·수정하지 않는다. 자동으로 최신 파일을 추측하지 않는다.

```powershell
$tool = '.\docs\week4\r02-product-detail-local-cache\jmeter\run-benchmark.ps1'
$jar = (Get-Item '.\apps\commerce-api\build\libs\commerce-api-<새빌드버전>.jar').FullName

& $tool -Mode Preflight -JarPath $jar

# SQL 로그를 켠 별도 새 API에서 OFF 2회, ON miss/hit 응답과 SQL 수를 대조한다.
& '.\docs\week4\r02-product-detail-local-cache\jmeter\capture-http-sql.ps1' -JarPath $jar
& $tool -Mode Smoke -JarPath $jar

# 앞 단계가 Finished이고 실패 요청이 0일 때 명시 실행한다.
& $tool -Mode Measure -JarPath $jar
```

- **Preflight**: 입력·JDK 21·JMeter 5.6.3·새 Caffeine JAR·빈 실험 포트와 실제 DB를 확인한다. API나 부하는 시작하지 않는다.
- **Smoke**: 새 OFF API와 새 ON API를 각각 띄우고 1명으로 상품 100개를 한 번씩 전부 확인한 뒤 종료한다. full assertion은 전체 응답을 oracle과 비교한다.
- **Measure**: 다음 순서로 새 API 4개를 순차 기동한다. 각 모드 안에서는 1명→10명으로 warm-up·본 측정을 수행한다. 본 측정마다 HTML을 만든다.

| 반복 | 새 API 실행 순서 | API 안의 순서 |
|---|---|---|
| 1 | OFF → ON | 1명 warm-up/본 측정 → 10명 warm-up/본 측정 |
| 2 | ON → OFF | 1명 warm-up/본 측정 → 10명 warm-up/본 측정 |

warm-up·본 측정의 초 수는 ramp-up을 포함하는 Thread Group 수명이다.
진행 중 응답은 timeout까지 기다릴 수 있으므로 벽시계 시간이 20/60초와 정확히 같지는 않다.
같은 JAR SHA-256과 동일한 도구를 사용한다. 모드마다 API를 새로 시작해 로컬 캐시를 초기화한다.
1명 본 측정 이후 10명 warm-up으로 넘어갈 때는 같은 모드의 API를 유지한다.

기본 설치는 JDK `C:\Program Files\Java\jdk-21.0.10`과 저장소 루트 `apache-jmeter-5.6.3`다.
다른 경로는 `-JavaHome` / `-JMeterHome`으로 지정한다.
JMeter는 `bin/ApacheJMeter.jar`를 해당 Java 절대경로로 직접 실행한다.
배포 bat의 기본 heap/GC와 Java module opens를 명시하고 실제 인자는 manifest에 보존한다.
PATH·JAVA_HOME·DB/서버 전역 설정을 바꾸지 않는다. 새 Java 프로세스는 모두 Hidden으로 기동한다.

## JMX를 직접 열거나 검토할 때

기본은 `localhost:18081`, 1명·1회, scheduler=false, full 검증이다.
API가 해당 포트에서 실행 중이어야 하며 CLI wrapper는 작업 종료 시 자신이 시작한 API를 종료한다.
GUI는 소량 응답 확인에만 사용하고 공식 본 측정 8개는 wrapper의 CLI 조건으로 실행한다.

| JMeter 속성 | GUI 기본값 | 용도 |
|---|---|---|
| host / port | localhost / 18081 | HTTP 대상 |
| threads / rampup | 1 / 0 | 동시 사용자 / 초 |
| loops | 1 | 반복 수, 무한 반복은 -1 |
| scheduler / duration | false / 0 | 시간 제한 여부 / 초 |
| connectTimeout / responseTimeout | 3000 / 30000 | ms |
| validation | full | full=상세·브랜드·envelope 전체 일치, light=200·SUCCESS·요청 ID 일치 |
| idsFile / expectedFile | JMX 옆 고정 파일 | 절대경로 재정의 가능 |

시간 기준 실행은 `loops=-1`, `scheduler=true`, `duration>0`을 함께 지정한다.
잘못된 무한 반복 조합은 HTTP 전에 스레드를 중단한다. wrapper는 CSV와 oracle의 절대경로를 넘긴다.
Groovy 스크립트는 컴파일 캐시를 사용한다.
HTTP/검증 실패 시 JMeter 테스트를 중단하고 wrapper가 실패로 기록한다. 다음 실행으로 넘어가지 않는다.

## SQL 확인용 API를 별도로 시작할 때

SQL 수집과 부하 측정은 분리한다. 공통 함수의 기본 SQL 로그는 OFF이며 `-CaptureSql`만 DEBUG/TRACE를 켠다.
아래는 root 실행자가 명시적으로 수행할 때만 API를 시작한다.

```powershell
. .\docs\week4\r02-product-detail-local-cache\jmeter\benchmark-common.ps1
$repo = (Get-Location).Path
$session = Join-Path $repo ('build\benchmark\r02\capture-' + [guid]::NewGuid().ToString('N'))
$owned = $null
try {
    $start = @{
        RepositoryRoot = $repo
        Java = 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
        Jar = $jar
        CacheMode = 'ON'
        Directory = (Join-Path $session 'api')
        CaptureSql = $true
    }
    $owned = Start-R02Api @start
    # 이 구간에서만 실행자가 필요한 GET과 로그·응답 수집을 명시 실행한다.
    # Metadata.StdoutPath / StderrPath / ProcessId / Arguments에 원본 정보가 있다.
    Read-R02Metrics -ApiMetadata $owned.Metadata -Path (Join-Path $session 'metrics.prom')
} finally {
    if ($null -ne $owned) { Stop-R02OwnedJava $owned.Metadata }
}
```

OFF·ON cold·ON hit와 기존 400/404 검증은 별도 기동에서 기록한다.
SQL 수집 API를 종료한 다음 Measure는 SQL 로그 OFF의 새 API를 시작한다.

## 결과와 해석

세션마다 새 디렉터리를 만들고 기존 파일을 덮어쓰지 않는다.

Measure가 `Finished`로 끝난 뒤 해당 `manifest.json`을 지정해 요약한다. 이 단계는 저장된 파일만 읽는다.
DB나 API에 요청하지 않으며 이미 생성한 요약을 덮어쓰지 않는다.

```powershell
$manifest = '.\build\benchmark\r02\measure-<이번 실행 디렉터리>\manifest.json'
& '.\docs\week4\r02-product-detail-local-cache\jmeter\summarize-results.ps1' -ManifestPath $manifest
```

기본 출력은 [공개 측정 요약](baseline/jmeter/summary.json)과 통계 JSON 8개다. 재실행은
`-OutputDirectory 'build/benchmark/r02-summary-<새 이름>'`처럼 새 경로를 지정한다.
전체 JTL을 검산하므로 표본 수가 많으면 후처리 시간이 추가된다.

```text
build/benchmark/r02/<mode>-<시각>-<임의ID>/
  manifest.json
  db-before.json / db-after.json
  protected-listeners-before.json / protected-listeners-after.json
  measurement-summary.json
  r1-off|r1-on|r2-on|r2-off/
    api/process.json / stdout.log / stderr.log
    u1|u10/warmup|measurement/
      process.json / stdout.log / stderr.log
      jmeter.log / samples.jtl / stage.json
      metrics-before.prom / metrics-after.prom / metrics-delta.json
      html/index.html  # measurement만
```

각 stage 전후 `http://127.0.0.1:18082/actuator/prometheus`를 읽고 원문을 보존한다.
필수 DB counter는 `product_detail_db_loads_total`이다.
ON은 `cache="product-detail"`의 `cache_gets_total{result="hit|miss"}`도 확인한다.
OFF에서 cache metric이 없으면 0으로 꾸미지 않고 부재를 기록한다.
put·eviction·size도 있으면 보존하며 put 수를 Java `put()` 횟수나 DB 조회 수로 단정하지 않는다.

- 지연·처리량·오류율은 본 측정 HTML/JTL을 모드·동시 사용자·반복별로 비교한다. warm-up은 합치지 않는다.
- hit rate는 hit 증가량 / (hit+miss 증가량)이다. 분모가 0이거나 metric이 없으면 null이다.
- DB loads/request는 DB load 증가량 / JTL 요청 수다. 원본 DAO 호출 수이며 SQL 수나 읽은 행 수로 가정하지 않는다.
- **요약 분위수는 raw JTL elapsed의 nearest-rank p50/p95/p99**다. HTML 보간 방식과 값이 다를 수 있어 분위수의 강제 일치 검사는 하지 않는다.
- HTML statistics의 표본 수·오류 수·평균·처리량은 raw JTL과 교차 검사한다. 실보고서는 nearest-rank를 일관되게 사용한다.
- JAR·JMX·CSV·oracle·wrapper 해시, 실행 순서·인자·실제 PID·생성시각·종료 상태를 보존한다.

100개 hot set이 최대 크기 1000보다 작으므로 이 실험은 반복 상세 조회와 TTL 조건의 비교다.
전체 상품 분포, 쓰기 혼합 부하, 용량 초과 시 W-TinyLFU 효과는 이 결과만으로 주장하지 않는다.
TTL은 본 측정 중 만료될 수 있으며 OFF/ON 순서를 교차해 로컬 환경의 순서 영향을 일부 줄인다.

실패·중단 때도 원본을 보존하고 생성시각·명령·JAR·PID가 일치하는 자신이 시작한 Java만 정리한다.
기존 18080/8081 프로세스와 R01 파일을 정리 대상으로 선택하지 않는다.
완료 후 DB 설정 11개·데이터·인덱스와 기존 포트의 소유자가 유지되는지 재확인한다.
