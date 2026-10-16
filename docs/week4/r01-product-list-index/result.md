# R01 진행 결과: 실패 기준선과 인덱스 2개 적용

[요구사항](requirement.md) · [계획](plan.md) · [재현 절차](baseline/README.md) · [seed 실행 계획 해석](baseline/seed-explain-review.md)

2026-10-16 사용자 승인 조건으로 **상품 100만 개와 실제 좋아요 관계 적재·검증을 완료**했다.
현재까지 확인한 실행 계획은 seed INSERT의 사전 `EXPLAIN`·`FORMAT=TREE`다.
세 상품 목록 HTTP의 실제 SQL·바인딩·응답을 수집하고 JMeter 각 1건의 사전 확인을 통과했다.
세 조회의 목록·COUNT 실행 계획을 전후 수집하고, 기존 4개 유지와 새 인덱스 2개 적용을 완료했다.
첫 CLI 본 측정은 최신순 10명에서 timeout 10건으로 중단된 부분 기준선이다.
이후 사용자가 위임한 변경 후 CLI 12개를 모두 완료했다. 본 측정 합계 65,261건·오류 0건이며 상세 비교는 8절을 따른다.

## 1. 승인된 적재 실행

| 항목 | 실제 실행 내용 |
|---|---|
| DB | `week4_r01_index_benchmark`, Docker MySQL 8.0.46 |
| 실행 코드·JAR | `98da9d2`, `commerce-api-98da9d2.jar`, Java 21.0.10 |
| 실행기 | `benchmark-seed` 단독 프로필의 `ApplicationRunner` |
| 실행 조건 | `benchmark.seed.enabled=true`, `ddl-auto=none` |
| 트랜잭션 | 제한 3,600초, 전체 적재·관계 및 기대값 검증을 한 TX로 수행 |
| 적재 순서 | 상품·좋아요의 대량 INSERT 3개에 각각 삽입 PK 오름차순 지정 |
| 버퍼 풀 | 실행 전후 `134217728` bytes, 128MiB |
| 시작 | 2026-10-16 13:07:08.708 KST |
| 종료 | 2026-10-16 13:10:19.795 KST |
| 프로세스 전체 경과 | 191.086초 |
| 종료 코드 | 0 |

원문에서 [실행 요약](baseline/seed-run.json), [완료 로그 발췌](baseline/seed-completion.txt),
[설정 전후 대조](baseline/seed-settings.txt)를 저장소 문서에 보존했다.

로그에서 `ProductSeedResult`의 모든 값이 기대값과 일치한 완료 메시지, JPA·Hikari 종료를 확인했다.
실행 전후 대조한 주요 DB 설정값 11개는 일치하며 이번 승인 실행에서는 전역 설정을 변경하지 않았다.
종료 후 남은 InnoDB 트랜잭션은 0개였다.

191.086초는 JVM·Spring 기동, 전체 적재·검증·커밋과 종료를 포함하는 한 번의 프로세스 경과 시간이다.
개별 INSERT의 실행 시간이나 HTTP 지연·처리량을 측정한 값으로 해석하지 않는다.

## 2. 실제 데이터와 인덱스 검증

적재 완료 뒤 [검증 SQL](baseline/verify-data.sql)을 읽기 전용으로 실행했다.
[실제 검증 출력](baseline/seed-verification.txt)에서 아래 값과 모든 불일치 0건을 확인했다.

| 항목 | 실제 결과 |
|---|---:|
| 브랜드 / 삭제 브랜드 | 1,000 / 10 |
| 사용자 / 지갑 / 전체 잔액 | 1,000 / 1,000 / 0 |
| 상품 전체 / 활성 / 삭제 | 1,000,000 / 950,400 / 49,600 |
| 전체 상품 가격 합계 | 50,950,000,000 |
| 실제 좋아요 관계 / 저장된 좋아요 합계 | 2,597,980 / 2,597,980 |
| 좋아요 0개 상품 / 좋아요 100개 상품 | 166,338 / 1,000 |
| 재고 0개 상품 | 100,000 |
| 브랜드 7 전체 / 활성 상품 | 1,000 / 960 |
| 브랜드 7 활성 상품 가격 합계 | 49,004,000 |
| 생성 시각 가짓수 | 500 |

브랜드별 상품 1,000개 조건, 실제 좋아요 중복, 상품·좋아요·지갑의 고아 참조, 저장 좋아요 집계,
삭제 브랜드의 활성 상품, 상품·좋아요 분포 공식 검사는 모두 불일치 0건이었다.
검증 출력의 세션·전역 시간대는 `SYSTEM`이며, 별도 실행 후 설정 확인에서 MySQL 시스템 시간대가 UTC임을 확인했다.

`SHOW INDEX FROM products`는 PRIMARY와 다음 기존 보조 인덱스 4개를 반환했다. 새 전체 최신순·가격순 인덱스는 적용하지 않았다.

- `idx_products_deleted_brand_created`: `deleted, brand_id, created_at DESC, id DESC`
- `idx_products_deleted_brand_price`: `deleted, brand_id, price ASC, id DESC`
- `idx_products_deleted_like`: `deleted, like_count DESC, id DESC`
- `idx_products_deleted_brand_like`: `deleted, brand_id, like_count DESC, id DESC`

## 3. 사전 실행 계획과 검사 범위

적재 전에 [빈 테이블·기존 인덱스·128MiB 사전 상태](baseline/seed-preflight.txt)를 확인하고,
[INSERT 6개에 대한 EXPLAIN SQL](baseline/seed-explain-before.sql)과 [계획 원문](baseline/seed-explain-before.txt)을 보존했다.
사전 검토에서는 INSERT·통계 갱신·`EXPLAIN ANALYZE`를 실행하지 않았다.

상품은 숫자 CTE 1,000개와 브랜드를 조합하고, 좋아요는 상품 인덱스를 읽어 관계 후보를 만든다.
대량 INSERT 3개의 정렬은 PK순 삽입을 위한 비용이며, 각 좋아요 INSERT 사이의 전체 PK 순서까지 연속인 것은 아니다.
이전 적재·롤백의 통계가 남은 상태여서 사전 계획의 예상 rows를 실제 건수로 사용하지 않는다.
이 원문에는 실제 rows·loops·단계별 실행 시간이 없으므로 상품 목록의 조회 개선 근거로 해석하지 않는다.

현재 실행 코드와 같은 소스의 `:apps:commerce-api:check`는 앞서 통과했다.
테스트 XML 합계는 **284건, 실패 0, 오류 0, skip 0**이며 Checkstyle·ArchUnit을 포함하는 모듈 검사 결과다.
이는 seed의 프로세스 경과 시간과 별도의 사전 코드 검증이며, 실제 100만 상품 적재 시간에 포함하지 않는다.
소량 통합 테스트는 SQL 실패·검증 실패 시 전체 rollback, 잘못된 대상 DB·기존 데이터·추가 인덱스 거절을 다룬다.
적재 후 같은 실행기를 호출했을 때 INSERT 전에 `brands`의 기존 데이터를 감지해 종료 코드 1로 거절했다.
[차단 후 검증](baseline/seed-existing-data-guard.txt)에서 5개 테이블의 건수·가격 합계·좋아요 합계와 128MiB 버퍼 풀을 유지했다.

## 4. 이전 시도와 남은 작업

첫 600초 제한 실행은 검증 단계에서 시간 초과로 실패했고 전체 rollback 뒤 5개 대상 테이블이 비어 있음을 확인했다.
이후 승인되지 않은 버퍼 풀 1GiB 변경·재실행은 사용자 지적으로 중단했다.
버퍼 풀을 원래 128MiB로 되돌리고 백그라운드 rollback 종료·테이블 0건을 확인한 뒤,
INSERT 계획을 설명하고 새로 명시적인 승인을 받아 이번 적재를 실행했다.
실패·중단 시도는 이번 성공 실행과 분리하며, 실행 시간의 개선 배수나 버퍼 풀 변경의 성능 효과를 주장하지 않는다.

이 seed 완료 시점에는 조회 앱·실행 계획·인덱스 적용이 남아 있었다. 이후 실제 진행 결과는 5~7절에 구분해 기록했다.
별도 통계 갱신은 하지 않고 7절 승인 절차에 따라 전후 실행 계획을 수집했다.

## 5. JMeter 직접 실행 준비와 확인 범위

사용자는 [직접 실행 가이드](jmeter-guide.md)의 명령으로 본 측정을 실행한다.
최종 선택은 **3종 조회 × 동시 1명/10명 × 2회 반복**, 각 run의 워밍업 20초와 본 측정 60초다.
기존 3회 반복에서 시간을 줄여 본 측정은 12개, 설정 시간 16분·실제 예상 약 17~20분으로 준비했다.
각 실행의 ramp-up 5초는 실행 시간 안에 포함한다.

| 파일 | 역할 |
|---|---|
| [JMX](jmeter/product-list.jmx) | 시나리오별 단일 HTTP 요청, 안전한 기본 1명·1회, full/light 검증 |
| [기대 응답](jmeter/expected-responses.json) | seed 공식을 독립 계산한 3개 시나리오의 전체 응답 |
| [시작](jmeter/start-benchmark-api.ps1)·[종료](jmeter/stop-benchmark-api.ps1) | 전용 DB·ddl-auto=none·집계 false·COUNT TTL 0s, 기록한 API 프로세스만 관리 |
| [실행 래퍼](jmeter/run-jmeter.ps1) | 사용자 실행, 실행 조건 검사·워밍업/측정 분리·JTL/HTML·manifest 보존 |
| [결과 저장 속성](jmeter/results.properties) | CSV JTL과 오류 메시지 저장, 전체 응답 저장 비활성화 |
| [배포판 정보](jmeter/distribution.json) | 루트의 JMeter 5.6.3과 공식 ZIP·SHA-512 기록 |

실제로 실행한 확인은 다음과 같다.

- SQL 로그를 켠 앱에 세 GET을 각 한 번 호출했다. [HTTP 원문](baseline/http-before/README.md)에서 모두 HTTP 200,
  목록 1개·COUNT 1개로 요청당 2 SQL을 확인했다. COUNT TTL은 0s다.
- 응답 60개 항목의 모든 필드·정렬과 페이지를 독립 기대값과 대조해 일치했다.
- 이 SQL 수집 앱만 종료하고 SQL·바인딩 로그 OFF인 앱으로 다시 기동했다.
- 같은 실행 래퍼의 Smoke 모드로 세 시나리오를 1명·1회, 총 3요청 실행했다.
  모두 full 검증·JTL 실패 0건·HTML 생성을 통과했다. [요약·원본](baseline/jmeter-smoke/README.md)을 보존했다.
- 사전 확인 전후 데이터·기존 인덱스 4개·버퍼 풀 128MiB와 주요 설정 11개가 같았다.
- PowerShell 스크립트·XML·JSON은 정적으로 검사했다. 프로세스 생성 시각은 JSON 날짜 변환을 고려해 UTC ticks로 대조한다.

**이 준비 완료 시점에는 본 측정이 0회였으며 mock 테스트도 실행하지 않았다.** 시간 기반 루프와 전체 조합은 코드 검토만 수행했다.
단일 사전 표본으로 지연 분포·처리량·개선 배수를 보고하지 않는다.
사용자가 실행 여부를 확인한 이후에는 추가 JMeter 실행 없이 파일과 안내만 마무리했다.
자동화 스크립트는 실패 표본·0표본·도구 오류에서 원본을 남기고 중단한다.

### Windows PowerShell 5.1 한글 경로 보완

사용자 실행에서 `LiteralPath`가 null이라는 오류가 발생했다. Windows PowerShell 5.1의 기본 읽기 인코딩이
한국어 ANSI여서, PowerShell 7이 BOM 없는 UTF-8로 기록한 경로 파일의 `바탕 화면`이 깨지는 것을 재현했다.
metadata JSON을 기본 인코딩으로 읽는 부분도 같은 영향을 받았다. 이 시도는 API 사전 검사 전에 실패했다.

가이드의 경로 파일 읽기와 실행·종료 스크립트의 JSON 읽기에 `-Encoding UTF8`을 명시했다.
세 PowerShell 스크립트는 UTF-8 BOM으로 저장해 5.1에서도 한글 코드 문자열을 보존하고,
잘못 읽은 경로는 파일 존재 검사에서 원인을 안내하도록 보완했다.
PowerShell 5.1.26100과 7.6.5에서 수정한 경로·metadata·API 프로세스 사전 검사 부분을 분리해 통과를 확인했다.
이 검증에서는 Java·JMeter·HTTP·DB 작업을 실행하지 않았다.

## 6. 사용자 첫 CLI 본 측정과 GUI 전환

2026-10-16 13:57~14:01 KST 사용자가 직접 CLI 본 측정을 실행했다.
최신순 1명은 6건·실패 0건으로 끝났고, 최신순 10명은 30건 중 10건(33.33%)이 실패했다.
실패는 모두 `SocketTimeoutException: Read timed out`이며 30,007~30,017ms에 발생했다.
응답 timeout 30,000ms에 도달한 것이며 HTTP 5xx로 분류하지 않는다.
목록 SQL·COUNT 중 어느 단계가 주원인인지는 실행 계획 등 추가 근거가 필요하다.
기존 `(deleted, brand_id, created_at DESC, id DESC)`는 브랜드를 지정하지 않은 전체 최신순 정렬을 직접 제공하지 못한다.
목록 읽기·정렬, TTL 0s의 COUNT, 동시 요청 경합을 병목 후보로 기록하고 실제 실행 계획으로 확인한다.
local 설정상 커넥션 풀 최대 40·획득 제한 3초이므로 이번 30초 timeout을 풀 부족으로 단정하지 않는다.

JMeter는 HTML 두 개를 생성한 뒤 종료 코드 0으로 끝났지만, 래퍼는 실패 표본을 감지해 전체 실행을 중단했다.
가격순·브랜드별·2회차는 미실행이다. 실패 표본도 부분 기준선으로 남기며 전체 12개 완료로 기록하지 않는다.
[원본·요약](baseline/jmeter-before-first/README.md)을 보존했다. 데이터·기존 인덱스 4개·버퍼 풀 128MiB·설정 11개는 전후 동일했다.

사용자의 실시간 UI 요청에 따라 [GUI 안내](jmeter-ui-guide.md), 전용 JMX와 열기 스크립트를 추가한다.
GUI는 한 조합의 20초 워밍업·60초 본 측정을 순서대로 실행하고 본 측정만 Aggregate Report에 표시한다.
서버·DB·인덱스·30초 timeout을 유지하고 CLI 결과와 GUI 결과를 구분한다. GUI를 여는 동작은 측정을 자동 시작하지 않는다.
GUI 계획의 XML 구조와 실행기의 구문을 정적으로 확인했다. Windows PowerShell 5.1에서 GUI 실행기를 호출해
2026-10-16 14:08 KST 창을 열었다. 로딩 로그·대기 상태를 확인하고 측정 Start는 누르지 않았다.
실패한 CLI 실행은 예외로 종료하므로 호출한 PowerShell의 `$result`에 manifest를 반환하지 않는다.
사용자가 `$result.OutputDirectory`로 보고서를 열다가 null 오류를 겪어, 가이드를 실제 HTML이 있는 실행 폴더 검색 방식으로 바꿨다.
기존 최신순 1명·10명 HTML 파일이 존재함을 확인하고 1명 보고서를 기본 브라우저로 열었다. 새 측정은 실행하지 않았다.

## 7. 인덱스 적용과 전후 실행 계획

2026-10-16 14:26:53~14:27:02 KST 전용 DB에 [저장한 단일 ALTER](baseline/add-product-list-indexes.sql)를 실행했다.
`ALGORITHM=INPLACE, LOCK=NONE`으로 성공했고 MySQL은 8.26초·Warnings 0, 호출 벽시계는 8.62초였다.
기존 4개 정의를 유지하고 `idx_products_deleted_created`, `idx_products_deleted_price`를 추가했다.

| 같은 목록 SQL의 단일 관측 | 변경 전 | 변경 후 |
|---|---:|---:|
| 전체 최신순 상품 입력 | 950,400행 + 정렬 | 새 최신순 인덱스의 20행, 별도 정렬 없음 |
| 최신순 루트 실제 시간 | 13,275ms | 0.0976ms |
| 전체 가격순 상품 입력 | 950,400행 + 정렬 | 새 가격순 인덱스의 20행, 별도 정렬 없음 |
| 가격순 루트 실제 시간 | 11,241ms | 0.232ms |

위 시간은 준비 실행 뒤 한 번 수집한 `EXPLAIN ANALYZE`이며 HTTP 지연·JMeter 결과나 개선 배수로 해석하지 않는다.
전체 COUNT는 여전히 기존 인덱스에서 950,400행을 읽었다. 상세 key·예상/실제 rows·loops·시간과 원문은
[전후 분석](baseline/index-review.md), [변경 전](baseline/explain-before/capture.json), [변경 후](baseline/explain-after/capture.json)에 있다.
수집기는 [캡처한 SQL 6개](baseline/http-before/captured-queries.sql)를 쿼리별 준비 실행 1회 → EXPLAIN 1회 → ANALYZE 1회 순서로 실행했다.
ANALYZE TABLE·강제 인덱스 힌트·쿼리 축소는 사용하지 않았다.

[적용 후 검증](baseline/verification-after.json)에서 상품 1,000,000개·활성 950,400개·좋아요 2,597,980개·가격 합계 50,950,000,000을 유지했다.
버퍼 풀 128MiB 포함 주요 설정 11개, API PID 34084와 기존 `commerce-api-98da9d2.jar`, 사용자 GUI JMX 해시를 유지했다.
같은 앱에 세 GET을 각 한 번 호출해 HTTP 200과 응답의 전체 필드·정렬·페이지 일치를 확인했다.
[변경 후 HTTP 원문](baseline/http-after/README.md)은 성능 부하 표본으로 사용하지 않는다.

엔티티에도 새 2개를 선언했다. seed 실행기의 before 4개 엄격 검사는 유지하고, Testcontainers 전용 테스트에서만
새 2개를 제거·복구해 기준선 fixture를 준비한다. 기존 데이터·rollback·알 수 없는 인덱스 거절 기대를 유지하며 6개 상태의 seed 거절을 추가했다.
인덱스 적용 완료 시점에는 현재 100만 DB의 seed·스키마 생성·API 재시작과 변경 후 JMeter를 실행하지 않았다. 이후 위임한 부하 결과는 8절을 따른다.

같은 변경으로 `:apps:commerce-api:check`는 4분 49초에 성공했다. JUnit XML 합계는 **285건, 실패·오류·skip 모두 0**이다.
seed 로더 11건·Runner 3건·상품 QueryDao 3건·상품 API 실제 nested suite 3건과 ArchUnit 8건을 포함한다.
Checkstyle main/test 오류는 0건이다. 이름만 있는 tests=0 부모 suite를 실제 검사 건수로 세지 않았다.
집계와 원문 결과 파일명은 [검사 기록](baseline/validation-check.json)에 보존했다. 문서 링크·동일 SQL 6쌍과 공백 검사도 확인했다.

## 8. 위임받은 변경 후 CLI JMeter 결과

사용자의 `너가 그냥 CLI로 해줘` 요청으로 Codex가 2026-10-16 **14:38:37~14:57:46 KST**, 약 **19분 9초** 실행했다.
GUI 측정과 프로세스가 없는 상태에서 기존 API·JAR·설정을 사용했고, 세 조회 × 1명/10명 × 2회로 진행했다.
각 run은 워밍업 20초·본 측정 60초·ramp-up 5초이며 응답 timeout 30초와 light 검증을 유지했다.

**본 측정 12개, 워밍업 12개, HTML 12개가 모두 완료됐으며 본 측정 65,261건의 실패는 0건이다.**
JTL과 HTML의 표본 수·평균·최솟값/최댓값·오류율·처리량을 대조했다. 분위수는 HTML 원본의 p50·p95·p99를 보존했다.
처리량은 예정된 60초가 아니라 실제 첫 표본 시작~마지막 표본 종료 구간을 기준으로 한다.

| 대응 가능한 최신순 1회차 | 변경 전 표본→후 표본 | 평균 ms 전→후 | 오류율 전→후 |
|---|---:|---:|---:|
| 동시 1명 | 6 → 439 | 10,845.667 → 132.533 | 0% → 0% |
| 동시 10명 | 30 → 2,417 | 25,443.867 → 237.728 | 33.333% → 0% |

변경 전 10명의 평균에는 30초 read timeout 10건이 들어 있고, 서버의 실제 완료 시간 평균이 아니다.
변경 전 표본이 적고 price·brand7·2회차 대응 자료가 없어 해당 조건의 개선율이나 서비스 전체 개선 배수를 제시하지 않는다.
실행 시각·셸 버전(before 5.1/after 7.6.5)과 캐시·순서 영향도 남는다. 반복별 통계를 따로 보존하고 평균·분위수를 합쳐 평균내지 않았다.

[전체 12개 결과와 해석](baseline/jmeter-after/README.md), [필터링한 요약](baseline/jmeter-after/summary.json),
[대응 비교 원문](baseline/jmeter-after/comparison-before-latest.json)에 근거를 남겼다.
원본 JTL·HTML·로그·manifest는 `build/benchmark/jmeter/after-measure-20261016-143837-2931151-1019c396/`에 유지한다.
Git에는 지표 JSON·해시·상대 경로를 보존하고 큰 원본과 환경 변수·API 전체 명령은 복사하지 않았다.

부하 전후 상품 1,000,000개·좋아요 2,597,980개·인덱스 6개·버퍼 풀 128MiB·주요 설정 11개가 동일했고 사후 검사 오류가 없다.
동일 API JAR·JMeter 5.6.3·Java 21.0.10·MySQL 8.0.46과 CLI 입력 해시도 기존 before 자료와 대조했다.
사용자 GUI JMX 변경은 수정·커밋하지 않았다. 부하 측정 뒤 새 코드 변경이 없어 기존 285건 회귀 검사를 다시 실행하지 않았다.
