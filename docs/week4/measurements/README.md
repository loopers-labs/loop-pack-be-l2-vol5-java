# Week 4 측정 근거

커밋할 JSON은 아래 9개다. 기존 6개에 이전 좋아요순 비교·검사 2개와 현재 인기순 비교·검사를 함께 담은 1개를 추가했다. 구현 전 좋아요순 기준선은 비교 JSON 안에 포함해 별도 파일을 늘리지 않는다. 실행 로그와 개별 중간 결과는 이 폴더의 `.gitignore`로 제외한다. 기존 로컬 파일은 보관하며, 새 측정 파일도 최종 근거로 선택하기 전에는 자동으로 커밋 대상에 넣지 않는다.

| 파일 | 용도 |
| --- | --- |
| [index-results.json](index-results.json) | 인덱스·Handler·JOIN 등 기존 비교 원본 30개 |
| [like-counter-results.json](like-counter-results.json) | 카운터 후보·초기화·적용 후 API 원본 3개 |
| [100k-price-cache-providers-personal.json](100k-price-cache-providers-personal.json) | 가격순 단계의 캐시 없음·로컬/Redis miss·hit, 가격 변경·두 앱·헤더 없는 조회 |
| [100k-price-cache-skew-personal.json](100k-price-cache-skew-personal.json) | 가격순 단계의 일반/상품 쏠림·동시 조회 |
| [price-cache-redis-read-cost-personal-build.json](price-cache-redis-read-cost-personal-build.json) | 가격순 단계 빌드의 별도 Redis GET·JSON 변환 측정 |
| [price-cache-personal-validation.json](price-cache-personal-validation.json) | 가격순 개인화 단계 310건 검사와 측정 대조 |
| [100k-likes-cache.json](100k-likes-cache.json) | 이전 ADR 0024의 좋아요마다 DEL하던 비교·기준선 |
| [likes-cache-validation.json](likes-cache-validation.json) | 이전 ADR 0024 단계의 326건 검사·표본/SQL/빌드 대조 |
| [100k-popular-cache.json](100k-popular-cache.json) | 현재 60초 인기순의 비교·실제 변경·만료·전체 검사 집계 |

## 묶음 읽는 법

기존 인덱스·카운터 파일의 JSON 값을 `results["원래 파일명"]`에 그대로 보관했다. SQL·실행 계획·표본·검증 결과를 생략하거나 다시 계산하지 않았다. `source_sha256`는 묶기 전 개별 파일의 SHA-256이다. 문서의 원본 링크는 묶음으로 이동하며 링크 이름에 원래 파일명을 남겼다. JSON에서 그 파일명을 검색하면 된다.

묶음에는 여러 단계의 기록이 있다. 가격순 성능은 해당 단계의 4개 파일과 [비교 문서](../price-cache-comparison.md), 이전 좋아요순 2개는 ADR 0024 기록이다. 현재 인기순은 `100k-popular-cache.json` 하나에 성능과 검사 집계를 함께 보관하며 [설계](../design.md#popular-cache-choice)를 기준으로 읽는다. 각 파일의 JAR hash와 실행 조건을 구분하며 다른 단계의 시간을 현재 빌드의 재측정으로 취급하지 않는다.

예를 들어 커밋된 묶음만으로 기존 한 결과를 읽을 수 있다. DB나 앱을 실행하지 않는 명령이다. 저장소 루트에서 실행한다.

```bash
python3 - <<'PY'
import json
from pathlib import Path
bundle = json.loads(Path('docs/week4/measurements/index-results.json').read_text())
print(json.dumps(bundle['results']['fixed-page-price-index.json'], ensure_ascii=False, indent=2))
PY
```

재측정 스크립트는 기존 방식대로 개별 로컬 JSON·로그를 만든다. 묶음의 기존 수치를 재실행 결과로 덮어쓰지 않는다. 새 결과를 제출 근거로 채택할 때에는 문서와 커밋 허용 목록을 함께 갱신한다. [실행 안내](../setup.md), [구현 기록](../implementation-log.md).

## 기존 파일명 찾기

| 원래 파일명 | 보관한 묶음 |
| --- | --- |
| `100k-brand-dedup-api.json` | [index-results.json](index-results.json) |
| `100k-counter-api.json` | [like-counter-results.json](like-counter-results.json) |
| `100k-counter-experiment.json` | [like-counter-results.json](like-counter-results.json) |
| `100k-final-api.json` | [index-results.json](index-results.json) |
| `100k-read-write-skew.json` | [index-results.json](index-results.json) |
| `baseline.json` | [index-results.json](index-results.json) |
| `brand-filter-reset.json` | [index-results.json](index-results.json) |
| `brand-filter.json` | [index-results.json](index-results.json) |
| `counter-backfill.json` | [like-counter-results.json](like-counter-results.json) |
| `covering-name-index.json` | [index-results.json](index-results.json) |
| `covering-name.json` | [index-results.json](index-results.json) |
| `final-after-like-index-api.json` | [index-results.json](index-results.json) |
| `final-before-like-index-api.json` | [index-results.json](index-results.json) |
| `final-like-index.json` | [index-results.json](index-results.json) |
| `fixed-page-brand-index.json` | [index-results.json](index-results.json) |
| `fixed-page-price-index.json` | [index-results.json](index-results.json) |
| `handler-extension-probe.json` | [index-results.json](index-results.json) |
| `handler-supplement.json` | [index-results.json](index-results.json) |
| `index-conditions.json` | [index-results.json](index-results.json) |
| `index-prefix.json` | [index-results.json](index-results.json) |
| `index-reset.json` | [index-results.json](index-results.json) |
| `join-api-final.json` | [index-results.json](index-results.json) |
| `like-index-api.json` | [index-results.json](index-results.json) |
| `like-index-read-write.json` | [index-results.json](index-results.json) |
| `like-join.json` | [index-results.json](index-results.json) |
| `price-first-index.json` | [index-results.json](index-results.json) |
| `price-only-optimizer-choice.json` | [index-results.json](index-results.json) |
| `price-page.json` | [index-results.json](index-results.json) |
| `price-sort-reset.json` | [index-results.json](index-results.json) |
| `price-sort.json` | [index-results.json](index-results.json) |
| `price-sql.json` | [index-results.json](index-results.json) |
| `price-tie-index.json` | [index-results.json](index-results.json) |
| `price-without-id-index.json` | [index-results.json](index-results.json) |
