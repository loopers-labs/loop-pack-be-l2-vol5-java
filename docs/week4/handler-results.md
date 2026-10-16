# Handler 검증 결과 — SQL 01~14

## 측정의 범위와 읽는 법

아래 표는 **실제로 저장된 측정 원본의 값만** 정리했다. 단위는 일반 SELECT 실행 후 누적 카운터 − 실행 전 누적 카운터다. EXPLAIN ANALYZE는 카운터 구간 밖에서 실행했다. 기존 측정은 재사용하고 빠져 있던 IN 강제 경로·JOIN 관련 경로만 이번에 보완했다.

원본 링크는 [측정 묶음](measurements/README.md)으로 연결한다. 링크에 표시한 기존 파일명을 JSON의 `results`에서 찾으면 해당 원본을 그대로 볼 수 있다.

실험 seed는 상품 10,000개·브랜드 100개로 브랜드 7의 활성 상품은 99개다. 같은 가격순 20개 쿼리에서 관찰한 결과는 **next 99 → 19**다.

- key: 키 기반 읽기 요청. 첫 항목 탐색뿐 아니라 조인·임시 집계의 키 요청도 포함될 수 있다.
- next: 인덱스의 다음 항목 읽기 요청. 전체 구간을 끝까지 읽을 때 끝 확인도 포함될 수 있다.
- first: 첫 항목부터 읽기 시작하는 요청.
- rnd_next: 테이블/임시 테이블의 다음 행 읽기 요청.
- 모든 표의 last·prev·rnd 차분은 0이었다. 이 문서에서 key·next·first·rnd_next를 함께 보여준다.

카운터는 디스크 읽기·페이지 방문·Root/Branch/Leaf 탐색 횟수가 아니다. InnoDB 내부의 보조 인덱스→기본키 컬럼 확보 작업은 별도 수치로 나타나지 않는다. 따라서 커버링은 Using index·Covering 노드로 확인하고 정렬은 Sort·Using filesort로 확인한다. 시간은 보조 결과이며 별도 implementation-log에 남긴다.

## 기존 원본으로 확인한 각 단계

| SQL | 비교 경로 | key Δ | next Δ | first Δ | rnd_next Δ | 계획·결과 | 측정 원본 |
| --- | --- | ---: | ---: | ---: | ---: | --- | --- |
| 01 | 전체 미삭제 상품 | 1 | 0 | 1 | 10,001 | 전체 스캔 10,000 → 반환 9,699 | [brand-filter-reset.json](measurements/index-results.json) |
| 01 | 브랜드 7만 | 1 | 99 | 0 | 0 | 기존 인덱스 99 접근 → 99 반환 | [brand-filter-reset.json](measurements/index-results.json) |
| 02 | 정렬 없음 | 1 | 99 | 0 | 0 | 99 접근 → 99 반환 | [price-sort-reset.json](measurements/index-results.json) |
| 02 | ORDER BY만 | 1 | 99 | 0 | 0 | 99 접근 → Sort → 99 반환 | [price-sort-reset.json](measurements/index-results.json) |
| 03 | 기존 브랜드 인덱스 + LIMIT | 1 | 99 | 0 | 0 | 99 접근 → Sort → 20 반환 | [fixed-page-brand-index.json](measurements/index-results.json) |
| 04 | 가격까지만 강제 | 1 | 99 | 0 | 0 | 99 접근 → Sort → 20 반환 | [price-tie-index.json](measurements/index-results.json) |
| 04 | 자동 선택(기존 브랜드) | 1 | 99 | 0 | 0 | 99 접근 → Sort → 20 반환 | [price-only-optimizer-choice.json](measurements/index-results.json) |
| 04 | 가격 후보 강제 재확인 | 1 | 99 | 0 | 0 | 99 접근 → Sort → 20 반환 | [price-only-optimizer-choice.json](measurements/index-results.json) |
| 04 | 기존 브랜드 강제 재확인 | 1 | 99 | 0 | 0 | 99 접근 → Sort → 20 반환 | [price-only-optimizer-choice.json](measurements/index-results.json) |
| 05 | 전체 정렬 인덱스 | 1 | 19 | 0 | 0 | 20 접근 → 20 반환·Sort 없음 | [fixed-page-price-index.json](measurements/index-results.json) |
| 06 | id DESC 미명시 | 1 | 99 | 0 | 0 | 99 접근 → Sort → 20 반환 | [price-without-id-index.json](measurements/index-results.json) |
| 06 | id DESC 명시 | 1 | 19 | 0 | 0 | 20 접근 → 20 반환·Sort 없음 | [price-without-id-index.json](measurements/index-results.json) |
| 07 | 05 + 이름 포함 | 1 | 19 | 0 | 0 | 20 접근·일반 Index lookup | [covering-name.json](measurements/index-results.json) |
| 07 | 05 + 이름 제외 | 1 | 19 | 0 | 0 | 20 접근·Covering index lookup | [covering-name.json](measurements/index-results.json) |
| 07 | 이름 인덱스 비교의 05 | 1 | 19 | 0 | 0 | 20 접근·일반 Index lookup | [covering-name-index.json](measurements/index-results.json) |
| 07 | 이름까지 인덱스에 추가 | 1 | 19 | 0 | 0 | 20 접근·Covering index lookup | [covering-name-index.json](measurements/index-results.json) |
| 08 | 브랜드 상품: 짧은 인덱스 | 1 | 99 | 0 | 0 | 99 접근·정렬 없음 | [index-prefix.json](measurements/index-results.json) |
| 08 | 브랜드 상품: 긴 인덱스 | 1 | 99 | 0 | 0 | 99 접근·정렬 없음 | [index-prefix.json](measurements/index-results.json) |
| 08 | COUNT: 짧은 인덱스 | 2 | 99 | 0 | 0 | 99 집계·결과 99·브랜드 확인 포함 | [index-prefix.json](measurements/index-results.json) |
| 08 | COUNT: 긴 인덱스 | 2 | 99 | 0 | 0 | 99 집계·결과 99·브랜드 확인 포함 | [index-prefix.json](measurements/index-results.json) |
| 08 | 삭제 대상 ID: 짧은 | 1 | 99 | 0 | 0 | 99 접근·정렬 없음 | [index-prefix.json](measurements/index-results.json) |
| 08 | 삭제 대상 ID: 긴 | 1 | 99 | 0 | 0 | 99 접근·Sort 추가 | [index-prefix.json](measurements/index-results.json) |
| 09 | 가격 우선 | 1 | 1,599 | 1 | 0 | 1,600 접근 → Filter 20·Sort 없음 | [price-first-index.json](measurements/index-results.json) |
| 09 | 브랜드 우선 | 1 | 19 | 0 | 0 | 20 접근·Sort 없음 | [price-first-index.json](measurements/index-results.json) |
| 10 | 단일 브랜드 | 1 | 19 | 0 | 0 | 20 접근·Sort 없음 | [index-conditions.json](measurements/index-results.json) |
| 10 | 두 브랜드 IN 자동 | 2 | 198 | 0 | 0 | 198 접근 → Sort → 20 반환 | [index-conditions.json](measurements/index-results.json) |
| 11 | 원래 분포·삭제 열 미포함 | 1 | 19 | 0 | 0 | 20 접근·복제 테이블 | [index-conditions.json](measurements/index-results.json) |
| 11 | 원래 분포·삭제 열 포함 | 1 | 19 | 0 | 0 | 20 접근·복제 테이블 | [index-conditions.json](measurements/index-results.json) |
| 11 | 저가 삭제 집중·미포함 | 1 | 90 | 0 | 0 | 91 접근 → Filter 20·복제 테이블 | [index-conditions.json](measurements/index-results.json) |
| 11 | 저가 삭제 집중·포함 | 1 | 19 | 0 | 0 | 20 접근·복제 테이블 | [index-conditions.json](measurements/index-results.json) |
| 12 | OFFSET 60 | 1 | 79 | 0 | 0 | 80 접근 → 앞 60개 버림 → 20 | [index-conditions.json](measurements/index-results.json) |
| 12 | 커서 | 3 | 19 | 0 | 0 | OR range 20 접근·Sort 없음 | [index-conditions.json](measurements/index-results.json) |
| 13 | 좋아요 product_id 인덱스 전 | 249 | 62,756 | 1 | 19 | 62,756 스캔 → 248 필터 → 18그룹 | [final-like-index.json](measurements/index-results.json) |
| 13 | 좋아요 product_id 인덱스 후 | 20 | 248 | 0 | 0 | 248 범위 접근 → 18그룹 | [final-like-index.json](measurements/index-results.json) |

09는 가격 우선 **1,600행 읽음 → 추가 정렬 없음 → 20행 반환**, 브랜드 우선 **20행 읽음 → 추가 정렬 없음 → 20행 반환**이었다. 10의 두 브랜드 조회는 자동 선택과 05 강제 선택 모두 **198행 읽음 → 추가 정렬 있음 → 20행 반환**이었다. Handler 수치 자체를 상품 행 수로 바꾸어 설명한 것이 아니라, 실제 읽기 행은 연결된 EXPLAIN ANALYZE에서 확인했다.

같은 쿼리의 05에서는 실제 접근 99→20·Sort 제거와 next 99→19가 대응했다. 04/06에서는 동점 순서가 맞지 않아 next 99가 남았고, 09에서는 Sort가 없어도 next 1,599였다. 이것이 시간 차이만으로 판단하지 않은 근거다.

07은 카운터가 같아도 Covering으로 바뀌었다. 08도 ID 정렬이 추가돼도 read 카운터는 같았다. Handler가 모든 비용을 직접 보여주지는 않는다는 점도 실제 결과로 남긴다.

## 이번에 보완 측정한 항목

실행기 scripts/measure-week4-handler-supplement.py, 원본 [handler-supplement.json](measurements/index-results.json). 각 블록을 독립적인 연결에서 3회 실행했고 세 번 모두 카운터 차분과 반환 행이 같았다. IN은 기존 기대값, JOIN의 상품·브랜드·좋아요는 HTTP 실험의 독립 seed 기대값과 대조했다. 실제 상품·좋아요 수·ID 등의 합계, 인덱스 목록의 전후 일치도 확인했다.

| 비교 블록 | key Δ | next Δ | first Δ | rnd_next Δ | 범위 |
| --- | ---: | ---: | ---: | ---: | --- |
| 10 IN·05 강제 | 2 | 198 | 0 | 0 | 1 SQL·198행→Sort→20 |
| 14 별도 집계의 상품 페이지＋좋아요 | 22 | 267 | 0 | 0 | 실제 API에서 수집한 2 SELECT |
| 14 JOIN 후보 | 272 | 267 | 0 | 42 | 상품·브랜드·좋아요의 1 SELECT |
| 14 별도 집계 업무 SELECT 전체 | 26 | 366 | 0 | 0 | 실제 API에서 수집한 5 SELECT |
| 14 JOIN 업무 SELECT 전체 | 275 | 366 | 0 | 42 | 실제 API에서 수집한 3 SELECT |

14는 **실제 API에서 수집한 업무 SELECT를 같은 읽기 전용 트랜잭션에서 재실행한 SQL 단위 측정**이다. HTTP 연결의 Handler를 직접 포착한 기록은 아니다. 세션 설정·드라이버 내부 SQL과 앱 처리 비용은 포함하지 않는다.

JOIN은 next가 같아도 key·rnd_next가 늘었다. 임시 집계·파생 테이블 읽기 작업도 카운터에 포함되므로 key 272를 상품 272개로 해석하지 않는다. 계획에서는 상품 20개·좋아요 248개·LEFT JOIN 250행·임시 집계 20행이었다. SQL/HTTP 시간과 쿼리 왕복 횟수 비교는 별도 근거로 다룬다. JOIN 채택은 이번에 확정하지 않았다.

## 확장의 자동 COUNT를 제외한 이유

VS Code 확장 측정에서 key 138→140·next 692→810이 나왔다. 실제 로그에서 LIMIT 20 SELECT 뒤 LIMIT 없는 COUNT 자동 실행을 확인했고, 재실행에서 key 2·next 118을 재현했다. [원본 · handler-extension-probe.json](measurements/index-results.json). 상품 페이지 단독의 key 1·next 19와 COUNT의 key 1·next 99가 합산된 결과였다.

이 확장 값을 페이지 단독의 근거로 쓰지 않는다. 각 표는 자동 COUNT 없는 클라이언트의 같은 연결 전후 차이다. 직접 확인용 05/07 handler-only.sql에도 절차를 남겼다.

## 확인하지 않은 항목

물리 디스크 I/O·실제 트리 높이·페이지 방문·MVCC 내부 재조회 횟수, 실제 상품 쓰기의 Handler, 과거 모든 HTTP 요청의 세션 카운터는 측정하지 않았다. 시간표나 실행 계획 rows에서 Handler를 추산해 측정했다고 기록하지 않는다. 최초 HTTP 기준선·과거 커서 실험은 각각의 원본 기록으로 유지하며 이 문서는 현재 01~14 비교 흐름을 대상으로 한다.


## 10만 건 추가 검증

[원본 · 100k-read-write-skew.json](measurements/index-results.json)의 같은 세션 일반 SELECT 전후 차분이다. 자동 COUNT는 없고 EXPLAIN ANALYZE는 측정 밖이다. 복제 비교의 상품 날짜 출력 정규화·좋아요 ORDER BY는 implementation-log 13절에 명시했다.

| 경로 | first | key | next | rnd_next |
| --- | ---: | ---: | ---: | ---: |
| 상품 기존 인덱스만 | 0 | 1 | 990 | 0 |
| 상품 가격 인덱스 추가 | 0 | 1 | 19 | 0 |
| 좋아요 기존 유니크만 | 1 | 94 | 628,186 | 19 |
| 좋아요 product_id 추가 | 0 | 20 | 93 | 0 |
| 원본 일반 1페이지 좋아요 | 0 | 20 | 93 | 0 |
| 원본 핫스팟 동일 1페이지 좋아요 | 0 | 20 | 50,093 | 0 |

last·prev·rnd는 위 경로에서 모두 0이다. key를 상품 수나 물리 페이지 수로 해석하지 않는다. 실제 API의 COUNT 990행은 별도 실행 계획으로 확인했으며 이 표는 HTTP 연결 전체의 Handler 측정이 아니다.
