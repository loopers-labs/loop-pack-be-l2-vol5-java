# 상품 목록 인덱스 전후 재현 절차

[요구사항](../requirement.md) · [계획](../plan.md) · [SQL seed 결정](../trade_off/03-profile-sql-seed.md) · [검증 SQL](verify-data.sql) · [인덱스 적용 SQL](add-product-list-indexes.sql)

이 문서는 실행 절차다. 명령의 출력이나 예상 건수를 실제 측정 결과로 간주하지 않는다.
기존 상품 인덱스 4개와 추가 후 6개 상태에서 같은 데이터·SQL의 실행 계획을 순차 비교한다.
JMeter 본 측정은 사용자가 계속 직접 실행하며, 아래 SQL 실행을 JMeter 측정과 병행하지 않는다.
현재 실행은 [seed 사전 계획 검토](seed-explain-review.md) 설명 뒤 사용자 승인을 받아 적재·검증을 완료했다.
[실행 결과](../result.md)와 [데이터 검증 원문](seed-verification.txt)을 참고한다.
**현재 상품 100만 개가 있는 DB에는 1~2절의 생성·초기화·seed를 다시 실행하지 않는다.**
현재 DB는 새 인덱스 2개 적용과 전후 계획 수집을 마쳤다. 실제 근거는 [전후 분석](index-review.md)을 따른다.
재수집은 [전용 스크립트](capture-execution-plans.ps1)에 `-Phase before|after`와 별도 `-OutputDirectory`를 지정할 수 있다.
이미 존재하는 결과 폴더는 덮어쓰지 않는다. 아래 before 단계는 기존 4개 상태를 새로 재현할 때의 순서다.

## 1. 실행 환경과 새 DB 확인

저장소 루트의 PowerShell에서 실행한다. Java 21과 실행 중인 Docker MySQL이 필요하다.
아래 계정은 저장소 `docker/infra-compose.yml`의 로컬 기본 설정이다. 기존 DB나 볼륨을 삭제하지 않는다.

```powershell
git branch --show-current
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10' # 설치된 Java 21 경로에 맞춘다.
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}'

$mysqlContainer = 'docker-mysql-1'
$benchmarkDatabase = 'week4_r01_index_benchmark'
$benchmarkUrl = 'jdbc:mysql://localhost:3306/week4_r01_index_benchmark?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$captureDirectory = Join-Path (Get-Location) ('build/week4-baseline/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $captureDirectory -ErrorAction Stop | Out-Null
```

현재 브랜치가 `volume-4/r01-product-list-index`인지 확인한다. 컨테이너 이름은 `docker ps`의 실제 값과 맞춘다.
`$captureDirectory`는 이번 재현 실행의 출력 폴더다. 확정된 기존 기준선 자료의 경로를 뜻하지 않는다.

```powershell
function Invoke-BenchmarkAdminSql {
    param([Parameter(Mandatory)][string] $Statement)
    docker exec -e MYSQL_PWD=root $mysqlContainer mysql --user=root --batch --raw --skip-column-names -e $Statement
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 관리자 명령 실패' }
}

$schemaExists = Invoke-BenchmarkAdminSql "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'week4_r01_index_benchmark'"
if ([int]$schemaExists -eq 0) {
    Invoke-BenchmarkAdminSql 'CREATE DATABASE week4_r01_index_benchmark CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci'
}

$tableCount = Invoke-BenchmarkAdminSql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'week4_r01_index_benchmark'"
if ([int]$tableCount -ne 0) {
    Invoke-BenchmarkAdminSql 'SHOW TABLES FROM week4_r01_index_benchmark'
    throw '기존 실험 스키마가 있으므로 최초 DDL을 중단합니다. 기존 내용을 먼저 확인하세요.'
}

Invoke-BenchmarkAdminSql "GRANT ALL PRIVILEGES ON week4_r01_index_benchmark.* TO 'application'@'%'"

function Invoke-BenchmarkSql {
    param([Parameter(Mandatory)][string] $Statement)
    docker exec -e MYSQL_PWD=application $mysqlContainer mysql --user=application --database=$benchmarkDatabase --default-character-set=utf8mb4 --init-command="SET time_zone = '+00:00'" --batch --raw -e $Statement
    if ($LASTEXITCODE -ne 0) { throw 'MySQL 실험 DB 명령 실패' }
}

Invoke-BenchmarkSql 'SELECT DATABASE(), VERSION(), @@session.time_zone; SHOW TABLES'
```

DB가 이미 준비돼 있다면 이 최초 생성 절차를 반복하지 않는다. 기존 자료와 건수를 확인하고 검증 단계부터 진행한다.
`ddl-auto=create`는 Runner의 DB 검사보다 먼저 실행되므로 반드시 빈 전용 스키마를 확인한 뒤 한 번만 사용한다.
`loopers` 등 기존 DB에 DDL·DROP·TRUNCATE를 실행하지 않는다.

현재 로컬 MySQL 버퍼 풀은 원래 값인 128MiB다. 전후 비교 시 같은 값인지 확인한다.
DB 전역 설정 변경은 이 실행 절차에 포함하지 않는다.

```powershell
Invoke-BenchmarkAdminSql 'SELECT @@innodb_buffer_pool_size'
```

실제 값이 134217728인지 확인한 뒤 측정한다. seed는 PK 오름차순 INSERT이며,
전체 적재·검증을 한 트랜잭션으로 처리하고 제한은 3,600초다. 실패 시 롤백이 끝날 때까지 다음 적재를 시작하지 않는다.

## 2. 스키마 한 번 생성 후 ApplicationRunner로 적재

현재 소스로 실행 JAR를 만든다. 새 빌드가 성공한 뒤 가장 최근의 commerce-api 실행 JAR를 선택한다.

```powershell
.\gradlew.bat :apps:commerce-api:bootJar
if ($LASTEXITCODE -ne 0) { throw 'commerce-api JAR 빌드 실패' }

$benchmarkJar = Get-ChildItem -LiteralPath 'apps/commerce-api/build/libs' -Filter '*.jar' |
    Where-Object { $_.Name -notlike '*-plain.jar' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $benchmarkJar) { throw 'commerce-api 실행 JAR가 없습니다.' }

$seedArguments = @(
    '--spring.profiles.active=benchmark-seed'
    "--datasource.mysql-jpa.main.jdbc-url=$benchmarkUrl"
    '--datasource.mysql-jpa.main.username=application'
    '--datasource.mysql-jpa.main.password=application'
)

# 앞 단계에서 빈 스키마를 확인한 직후에만 실행한다. seed는 실행하지 않는다.
& java '-Dfile.encoding=UTF-8' -jar $benchmarkJar.FullName @seedArguments --benchmark.seed.enabled=false --spring.jpa.hibernate.ddl-auto=create
if ($LASTEXITCODE -ne 0) { throw '최초 스키마 생성 실패' }

Invoke-BenchmarkSql 'SHOW TABLES; SHOW INDEX FROM products; SELECT COUNT(*) AS brands FROM brands; SELECT COUNT(*) AS users FROM users; SELECT COUNT(*) AS wallets FROM wallets; SELECT COUNT(*) AS products FROM products; SELECT COUNT(*) AS product_likes FROM product_likes'

# 아직 seed를 실행하지 않는다. 아래의 새 빈 DB 전용 6→4 인덱스 준비를 먼저 수행한다.
```

`benchmark-seed`만 활성화한다. `local`이나 `test`를 함께 활성화하지 않는다.
첫 실행은 현재 전체 엔티티의 스키마만 생성하고 종료한다. 5개 적재 대상 테이블은 모두 비어 있어야 한다.
새 인덱스 선언을 포함한 엔티티는 상품 보조 인덱스 6개를 생성하지만,
`JdbcProductSeedLoader`는 변경 전 4개만 허용하는 검사를 유지한다.
따라서 **새로 만든 빈 전용 스키마에 한해서** 아래 과정을 스키마 생성과 seed 사이에 실행한다.
현재 100만 DB에서 인덱스를 삭제해 기준선을 되돌리는 용도로 사용하지 않는다.

```powershell
# 1절의 빈 스키마 확인 → 위 최초 ddl-auto=create 직후에만 실행한다.
$seedTargetRows = Invoke-BenchmarkAdminSql 'SELECT (SELECT COUNT(*) FROM week4_r01_index_benchmark.brands) + (SELECT COUNT(*) FROM week4_r01_index_benchmark.users) + (SELECT COUNT(*) FROM week4_r01_index_benchmark.wallets) + (SELECT COUNT(*) FROM week4_r01_index_benchmark.products) + (SELECT COUNT(*) FROM week4_r01_index_benchmark.product_likes)'
if ([long]$seedTargetRows -ne 0) {
    throw '데이터가 있는 DB에서는 기준선 준비용 인덱스 제거를 금지합니다.'
}
Invoke-BenchmarkSql 'ALTER TABLE products DROP INDEX idx_products_deleted_created, DROP INDEX idx_products_deleted_price'
Invoke-BenchmarkSql 'SHOW INDEX FROM products'

# PRIMARY + 기존 보조 인덱스 4개를 확인한 뒤에만 seed를 실행한다.
& java '-Dfile.encoding=UTF-8' -jar $benchmarkJar.FullName @seedArguments --benchmark.seed.enabled=true --spring.jpa.hibernate.ddl-auto=none
if ($LASTEXITCODE -ne 0) { throw 'seed 적재 또는 검증 실패' }
```

이 seed 실행은 `ApplicationRunner` → `JdbcProductSeedLoader` → SQL 리소스 순서로 적재·검증하고 종료한다.
웹 서버·좋아요 집계·Redis health는 전용 프로필에서 꺼져 있다.
DB 이름·빈 테이블·기존 4개 상품 인덱스 검사를 통과해야 하며, INSERT와 검증 실패는 같은 트랜잭션에서 rollback된다.

정상 적재가 끝난 뒤 동일한 두 번째 명령을 한 번 더 실행한다.
이 재실행은 기존 데이터가 있다는 이유로 실패해야 하며 비정상 종료 코드와 오류 내용을 함께 보존한다.
성공으로 바꾸려고 데이터를 지우거나 `ddl-auto=create`를 다시 실행하지 않는다. 재실행 전후 검증 SQL 결과가 같아야 한다.

## 3. 데이터·관계·기존 인덱스 검증

```powershell
$verificationSql = Get-Content -LiteralPath 'docs/week4/r01-product-list-index/baseline/verify-data.sql' -Raw -Encoding UTF8
Invoke-BenchmarkSql $verificationSql | Out-File -LiteralPath (Join-Path $captureDirectory 'data-verification.txt') -Encoding utf8
```

검증 SQL의 주석에 기재된 예상값과 실제 출력을 대조한다. 상품 1,000,000개, 활성 950,400개, 삭제 49,600개,
실제 좋아요 2,597,980개와 브랜드 7의 활성 상품 960개를 포함한다.
모든 중복·고아 관계·집계 불일치·삭제 브랜드의 활성 상품·분포 공식 불일치는 0건이어야 한다.
상품 인덱스는 PRIMARY 외에 다음 4개만 있어야 한다.

- `idx_products_deleted_brand_created`
- `idx_products_deleted_brand_price`
- `idx_products_deleted_like`
- `idx_products_deleted_brand_like`

예상값이 다르면 HTTP·실행 계획 수집으로 넘어가지 않는다. `verify-data.sql`의 시각 비교를 위해 MySQL 세션은 UTC로 고정한다.
JDBC URL도 연결·세션 시각을 UTC로 맞춰 SQL 리터럴과 `Instant`의 기준을 일치시킨다.

## 4. 조회 앱에서 실제 HTTP·SQL 수집

현재 API와 [이미 수집한 실제 SQL](http-before/captured-queries.sql)이 준비돼 있다면 이 절의 재기동·HTTP 캡처를 반복하지 않는다.
이번 인덱스 적용과 계획 비교에서는 기존 API를 그대로 유지한다. 아래는 처음 재현할 때 SQL을 확보하는 예시다.

별도 PowerShell 터미널에서 저장소 루트로 이동하고 위 `$benchmarkUrl`, `$captureDirectory`, `$benchmarkJar` 값을 같은 값으로 준비한다.
seed 프로필 없이 `local`로 API를 띄우되, DB·DDL·집계·COUNT TTL은 명시적으로 덮어쓴다.

```powershell
$apiArguments = @(
    '--spring.profiles.active=local'
    '--server.port=18080'
    "--datasource.mysql-jpa.main.jdbc-url=$benchmarkUrl"
    '--datasource.mysql-jpa.main.username=application'
    '--datasource.mysql-jpa.main.password=application'
    '--spring.jpa.hibernate.ddl-auto=none'
    '--spring.jpa.show-sql=false'
    '--scheduler.like-count.enabled=false'
    '--query.product-count-cache.ttl=0s'
    '--management.health.redis.enabled=false'
    '--logging.level.org.hibernate.SQL=DEBUG'
    '--logging.level.org.hibernate.orm.jdbc.bind=TRACE'
)
& java '-Dfile.encoding=UTF-8' -jar $benchmarkJar.FullName @apiArguments 2>&1 |
    Tee-Object -FilePath (Join-Path $captureDirectory 'api-sql.log')
```

애플리케이션 시작이 완료되면 원래 PowerShell 터미널에서 다음 세 요청을 순차 호출한다.
상품·브랜드 조회는 사용자 헤더를 요구하지 않는다.

```powershell
$requests = @(
    @{ Label = 'latest'; Query = 'sort=latest&page=0&size=20' }
    @{ Label = 'price-asc'; Query = 'sort=price_asc&page=0&size=20' }
    @{ Label = 'brand7-price-asc'; Query = 'brandId=7&sort=price_asc&page=0&size=20' }
)
foreach ($request in $requests) {
    $startedAt = (Get-Date).ToString('o')
    $response = Invoke-WebRequest -Uri ('http://localhost:18080/api/v1/products?' + $request.Query) -UseBasicParsing
    $response.Content | Out-File -LiteralPath (Join-Path $captureDirectory ($request.Label + '-response.json')) -Encoding utf8
    "$($request.Label) start=$startedAt end=$((Get-Date).ToString('o')) status=$($response.StatusCode)" |
        Add-Content -LiteralPath (Join-Path $captureDirectory 'http-requests.txt') -Encoding utf8
}
```

HTTP 200과 응답의 정렬된 ID·가격·좋아요 수·페이지 정보를 확인한다. 각 요청의 로그 구간에서 목록과 COUNT SQL,
각 `?`의 바인딩 순서·타입·값, 실제 SQL 수를 기록한다. 앱 기동 시의 fixture 확인 SQL은 요청당 SQL 수에서 제외한다.
현재 코드상 세 요청 모두 목록과 COUNT를 실행하는 구조지만, 실제 수는 로그로 확인해 기록한다.
목록의 전체 Projection·브랜드 JOIN·삭제 조건·동점 `id DESC`를 유지하고, COUNT는 실제 캡처된 SQL을 그대로 사용한다.
COUNT에 임의 JOIN을 추가하거나 목록 Projection을 ID만 남긴 SQL로 바꾸지 않는다.

## 5. before 계획 수집 — 별도 통계 갱신 없음

세 요청의 실제 SQL·바인딩과 응답은 기존 자료를 사용한다. 이번 전후 비교에서 `ANALYZE TABLE`은 실행하지 않는다.
쿼리별 준비 실행 1회 → `EXPLAIN` 1회 → `EXPLAIN ANALYZE` 1회를 순차 실행하고,
다른 부하와 겹치지 않게 before 전체를 마친 뒤에만 인덱스를 적용한다.

```powershell
Invoke-BenchmarkSql 'SELECT DATABASE(), VERSION(), @@session.time_zone; SHOW INDEX FROM products' |
    Out-File -LiteralPath (Join-Path $captureDirectory 'before-database-and-indexes.txt') -Encoding utf8
```

다음 예시는 기존 [실제 바인딩 치환 SQL](http-before/captured-queries.sql)의 목록·COUNT 6개를 순서대로 읽는다.
새로 캡처한 자료를 사용한다면 실제 SQL의 바인딩 타입·순서·값을 보존하고,
같은 SQL 집합을 before와 after에 재사용한다.
별도 쿼리 힌트·강제 인덱스·정렬 삭제를 추가하지 않는다.

```powershell
$queryLabels = @('latest-list', 'latest-count', 'price-asc-list', 'price-asc-count', 'brand7-price-asc-list', 'brand7-price-asc-count')
$capturedSql = Get-Content -LiteralPath 'docs/week4/r01-product-list-index/baseline/http-before/captured-queries.sql' -Raw -Encoding UTF8
$statements = @([regex]::Matches($capturedSql, '(?im)^select[^\r\n]*;[ \t]*\r?$') |
    ForEach-Object { $_.Value.Trim().TrimEnd(';') })
if ($statements.Count -ne $queryLabels.Count) { throw '실제 캡처된 SELECT SQL 6개가 필요합니다.' }
$capturedQueries = @()
for ($queryIndex = 0; $queryIndex -lt $queryLabels.Count; $queryIndex++) {
    $capturedQueries += [pscustomobject]@{ Label = $queryLabels[$queryIndex]; Statement = $statements[$queryIndex] }
}

function Save-BenchmarkQueryPlans {
    param([Parameter(Mandatory)][ValidateSet('before', 'after')][string] $Phase)
    $phaseDirectory = Join-Path $captureDirectory $Phase
    New-Item -ItemType Directory -Path $phaseDirectory -ErrorAction Stop | Out-Null
    foreach ($query in $capturedQueries) {
        $query.Statement | Out-File -LiteralPath (Join-Path $phaseDirectory ($query.Label + '.sql')) -Encoding utf8
        Invoke-BenchmarkSql $query.Statement |
            Out-File -LiteralPath (Join-Path $phaseDirectory ($query.Label + '-warm-up.txt')) -Encoding utf8
        Invoke-BenchmarkSql ('EXPLAIN ' + $query.Statement) |
            Out-File -LiteralPath (Join-Path $phaseDirectory ($query.Label + '-explain.txt')) -Encoding utf8
        Invoke-BenchmarkSql ('EXPLAIN ANALYZE ' + $query.Statement) |
            Out-File -LiteralPath (Join-Path $phaseDirectory ($query.Label + '-analyze.txt')) -Encoding utf8
    }
}

Save-BenchmarkQueryPlans -Phase before
```

각 SQL을 준비 실행 1회한 뒤 `EXPLAIN`, `EXPLAIN ANALYZE`를 각각 1회 수집한다. `EXPLAIN ANALYZE`도 SQL을 실제 실행한다.
전체 최신순과 가격순의 COUNT가 같더라도 어느 HTTP 요청에서 캡처한 것인지 구분한다.
다음 항목을 원문과 함께 분석한다.

- `EXPLAIN`: 선택 인덱스 `key`, 접근 방식 `type`, 예상 `rows`, `filtered`, `Extra`의 정렬·임시 테이블 정보.
- `EXPLAIN ANALYZE`: 실제 접근 순서·인덱스, 각 단계의 실제 rows·loops·시간, 정렬과 LIMIT의 위치.
- 환경: Git 커밋, Java·MySQL 버전, 컨테이너 자원, 앱 인자·풀 설정, 데이터 검증 결과, 실행 순서와 준비 실행 횟수.

전체 최신순·가격순이 기존 인덱스로 정렬을 해결하는지, 후보 행을 얼마나 읽는지, 브랜드별 가격순과 COUNT의 비용이 어떤지 확인한다.
인덱스가 반드시 선택되거나 20행만 읽는다고 미리 결론내리지 않는다.
단일 계획 실행 시간은 JMeter의 응답 지연·처리량·분위수 결과로 해석하지 않는다.
현재 자료의 SQL을 유지하며 before와 after에 동일한 `$capturedQueries`를 사용한다.
기존 API를 종료·재시작하거나, 계획을 바꾸기 위해 강제 인덱스·쿼리 수정·통계 갱신을 추가하지 않는다.
이 절차의 출력 폴더·예시 파일명은 기존 측정 자료의 존재나 검증 완료를 의미하지 않는다.

## 6. 단일 ALTER로 인덱스 추가 후 after 계획 수집

before 수집이 완료되고 기존 보조 인덱스 4개·데이터·환경을 확인한 뒤
[적용 SQL](add-product-list-indexes.sql)의 **단일 `ALTER TABLE`** 을 실행한다.
이 SQL은 전체 최신순·가격순 인덱스를 함께 ADD하고 `ALGORITHM=INPLACE, LOCK=NONE`을 명시한다.
실패하면 임의로 COPY나 다른 잠금 방식으로 바꾸지 않고 오류를 보존해 중단한다.

```powershell
$indexSql = Get-Content -LiteralPath 'docs/week4/r01-product-list-index/baseline/add-product-list-indexes.sql' -Raw -Encoding UTF8
Invoke-BenchmarkSql $indexSql |
    Out-File -LiteralPath (Join-Path $captureDirectory 'add-product-list-indexes.txt') -Encoding utf8
Invoke-BenchmarkSql 'SELECT DATABASE(), VERSION(), @@session.time_zone; SHOW INDEX FROM products' |
    Out-File -LiteralPath (Join-Path $captureDirectory 'after-database-and-indexes.txt') -Encoding utf8
Save-BenchmarkQueryPlans -Phase after
```

기존 4개에 `idx_products_deleted_created`, `idx_products_deleted_price`가 추가된 상태를 확인하고,
상품·좋아요 건수와 128MiB 버퍼 풀·주요 설정 11개가 유지되는지 전후 대조한다.
after도 쿼리별 준비 실행 1회 → `EXPLAIN` → `EXPLAIN ANALYZE`를 before와 같은 순서로 수집한다.
별도 `ANALYZE TABLE`은 실행하지 않지만, 인덱스 생성 자체가 캐시·통계 상태에 영향을 줄 수 있다는 한계는 기록한다.
`LOCK=NONE`을 전체 작업 동안 잠금·대기가 전혀 없다는 뜻으로 설명하지 않는다.

JMeter 본 측정은 [직접 실행 안내](../jmeter-guide.md)와 [GUI 안내](../jmeter-ui-guide.md)에 따라 사용자가 실행한다.
기존 timeout 부분 기준선은 보존하고 after 자료와 구분한다. SQL 계획 한 번의 시간이나 인덱스 생성 성공만으로 성능 개선을 확정하지 않는다.
