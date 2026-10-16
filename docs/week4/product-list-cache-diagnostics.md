# W4 같은 버전의 중복 채움과 miss 시간 분해

[이전 혼합 실험](product-list-cache-mixed-load.md) · [캐시 설계](../week2/commerce-policy-decisions.md#w4-캐시-설계의-선택과-미정-질문)

이 문서는 **12:07 KST 검사 시점의 진단 기록**이다. 이후 [한 JVM의 요청 합치기](product-list-single-flight-tdd-log.md), [좁은 좋아요 순위 집계](product-like-ranking-tdd-log.md), [TTL 다중 주기·키 수·메모리 측정](product-list-cache-ttl-memory.md)을 완료했다. 아래 원시 수치·SQL 수·소스 해시는 진단 당시 증거로 보존하며, 현재 구현과 최신 검사는 후속 기록 및 [완료 체크리스트](../week2/commerce-completion-checklist.md#최신-통합-검사)를 따른다.

## 이번 작업의 범위

2026-10-16 사용자가 같은 버전의 중복 계산과 miss의 단계별 비용 측정을 요청했다. 이 진단 증분에서는 운영 코드·Redis 방식·TTL 60초·타임아웃·업무 트랜잭션·잠금을 변경하지 않았고 요청 합치기, 좋아요 카운터, 새로운 캐시 무효화 정책도 구현하지 않았다. 요청 합치기의 후속 완료와 구분한다.

기존 `ProductListCacheMixedLoadIntegrationTest`에 테스트 전용 계측과 동시 조회 장면을 추가했다. `RequestStageProbe`와 단위 테스트 9개를 추가했고 기존 `RequestSqlProbe`는 그대로 재사용한다. 기존 동작을 관찰하는 작업이며 운영 코드의 실패를 인위적으로 만들거나 성능 수치를 새 합격선으로 정하지 않는다.

## 무엇을 어떻게 측정했는가

| 항목 | 방법·의미 |
| --- | --- |
| 실제 환경·요청 | 테스트 소유 MySQL·Redis, 수동 DDL 001~008, 상품 10,000개·좋아요 46,955개. 실제 HTTP `GET /api/v1/products?sort=likes_desc&page=0&size=20` |
| 기존 혼합 조건 | 초당 조회 20회, 읽기 worker 4개·쓰기 worker 1개. 좋아요 변경 없음/초당 2회/10회. OFF→ON과 ON→OFF 두 라운드, 조건·모드당 400회 조회 |
| 별도 동시 조회 | 이전 버전 캐시를 채운 뒤 좋아요 1회 변경을 완료하고, 클라이언트 8개를 함께 출발시킨다. 이후 쓰기는 없다. 독립된 버전에서 3회 반복한다. 서버에 sleep·barrier를 넣어 miss나 DB 겹침을 강제하지 않는다. 혼합 부하 통계에 합치지 않는다. |
| 조회 버전 | 기존 `currentRevision()` 반환값과 `cache.find()` 인자를 관찰한다. 측정용 추가 DB 조회나 Redis GET을 요청 내부에 넣지 않는다. |
| 계산·저장 버전 | `readSnapshot()` 결과의 버전과 `cache.store()` 인자를 관찰한다. 중복 계산은 최초 lookup 버전이 아니라 실제 snapshot 버전으로 묶는다. |
| 메서드 시간 | 실제 읽기 트랜잭션 프록시 바깥을 감싼 테스트 프록시로 초기 버전 확인·snapshot·application 전체를 측정한다. 내부 TransactionInterceptor 존재와 계측 진입 시 TX 없음도 검사한다. cache spy는 실제 find/store 호출을 수행하며 그 구간을 측정한다. |
| SQL 시간 | 기존 요청 ID별 JDBC execute 기록을 사용한다. snapshot 안의 버전 SELECT·전체 개수 SELECT·좋아요 집계 SELECT를 분리한다. |
| 측정 안전성 | 단조 시계와 메모리 이벤트만 사용하며 측정 중 콘솔·파일 쓰기는 하지 않는다. 유한한 요청·worker 대기와 종료를 사용하고 인터럽트 시에도 계측 세션을 닫는다. 테스트 소유 컨테이너·namespace만 사용한다. |

### 시간과 중복 수치의 해석

- `db.snapshot`은 트랜잭션·연결·SQL·결과 처리 등을 포함한 메서드 전체 시간이다. SQL execute는 그 내부 일부이며 결과 fetch·ORM 매핑 전체 시간은 아니다.
- `cache.lookup`에는 Redis GET과 hit의 JSON 역직렬화·검증이, `cache.store`에는 JSON 직렬화와 Redis SET이 포함된다. 순수 Redis 서버 실행 시간이라고 부르지 않는다.
- HTTP에서 application 시간을 뺀 잔여에는 서버 HTTP 처리·응답 변환·네트워크·클라이언트 처리가 함께 들어 있다. snapshot에서 SQL execute 합을 뺀 잔여도 연결·TX·fetch·매핑 등을 따로 구분한 값은 아니다.
- 단계별 평균은 같은 요청 집합에서 합산할 수 있지만 p50/p95는 합산하지 않는다. 중첩된 SQL 시간과 snapshot 시간을 더하지 않는다. hit/miss 분포는 따로 계산한다.
- 버전별 `fills - 1`은 같은 결과의 추가 계산 횟수다. 모두 요청 합치기로 제거할 수 있다는 보장은 아니다.
- `overlappingSnapshotStarts`는 앞선 같은 버전의 snapshot 메서드가 끝나기 전에 시작한 계산 수다. `overlappingFillStarts`는 앞선 snapshot 시작부터 store 반환까지의 채움 구간과 겹친 추가 시작 수다.
- `snapshotStartsAfterCompletedStore`는 같은 버전의 앞선 store 반환 뒤에 시작한 계산 수다. 앞선 GET miss와 실제 계산 사이에 다른 요청이 채웠을 수 있다. 이 수와 채움 겹침 수는 서로 배타적이지 않아 합산하지 않는다. store 반환 시각을 Redis 내부 반영 시각으로 동일시하지 않는다.
- spy·프록시·이벤트 기록의 오버헤드가 포함된다. 이전 11:41 측정과 절대 지연을 직접 비교하여 성능 변화라고 주장하지 않는다. 운영 트래픽·최대 처리량·여러 writer 경합을 재현한 실험도 아니다.

## 정확성 검증

모든 기존 HTTP 응답·SQL 개수·최종 DB·버전 증가·마지막 별도 변경 후 새 조회 검증을 유지한다. 추가로 다음을 검사한다.

1. hit의 lookup 버전 또는 miss의 snapshot 버전과 독립 fixture의 전체 DTO 상태가 일치한다. 등록·취소를 번갈아 하는 이번 fixture에서는 기준 버전과의 홀짝으로 기대 상태를 구분한다.
2. miss에는 snapshot/store가 각각 한 번, hit에는 둘 다 없으며 저장 버전과 snapshot 버전이 같다.
3. 최초 버전 확인 → GET → snapshot → store의 단계 순서와 요청 ID별 SQL이 맞는다. 당시 OFF/hit/miss는 기존 SELECT 2/1/4회와 목록 집계 1/0/1회를 유지했다. 후속 좁은 순위 집계 적용 후 비어 있지 않은 DB-only/직접 계산 miss/hit는 3/5/1회이며, 이 진단의 수치를 현재 SQL 기대값으로 사용하지 않는다.
4. 고정된 지연이나 hit율, burst의 miss 개수를 assertion하지 않는다. 실제 관찰값을 기록한다.

## 실행 결과

아래는 전체 검사 중 **2026-10-16 12:03:25~12:05:34 KST**에 완료된 측정(`completed=true`)이다. Java 21.0.10·MySQL 8.0.46·Redis 7.4·Docker Engine 28.5.2 환경이다. 단독 실행도 11:59:53~12:02:03 KST에 완료했지만 아래 숫자와 섞지 않는다. 혼합 조회 2,400회·쓰기 480회와 별도 burst 조회 24회가 정확성 검사를 통과했다. 준비·마지막 sentinel 변경은 표본 밖이다. 혼합 실험의 Redis 오류·invalid와 전체 측정의 HTTP/SQL 오류는 0이다.

### 같은 버전의 중복 계산

아래 혼합 수치는 캐시 ON 두 라운드, 조건당 조회 400회 기준이다. 버전 수는 측정 구간에서 실제 계산한 서로 다른 snapshot 버전 수다. 미리 채운 시작 버전의 준비 작업은 제외한다.

| 좋아요 변경 | hit / miss | 계산한 버전 수 | 실제 목록 계산 | 버전당 1회보다 추가된 계산 | snapshot 구간이 겹친 추가 시작 | 채움 구간이 겹친 추가 시작 | 같은 버전 저장 완료 후 계산 시작 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 없음 | 400 / 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| 초당 2회 | 320 / 80 | 40 | 80 | 40 (계산의 50.0%) | 40 | 40 | 0 |
| 초당 10회 | 95 / 305 | 200 | 305 | 105 (계산의 34.4%) | 96 | 103 | 2 |

초당 2회에서는 버전 하나를 평균 2번, 초당 10회에서는 평균 1.525번 계산했다. 이번 자연 부하에서는 lookup 버전과 snapshot 버전이 바뀐 요청은 0건이었다. 이것이 다른 부하에서도 두 버전이 항상 같다는 뜻은 아니다.

별도 클라이언트 8명 burst는 **3회 모두 같은 버전의 8회 계산·7회 추가 계산**이 발생했다. 세 버전에서 총 24회 계산 중 추가 계산은 21회였으며 각 burst의 7개 추가 시작 모두 앞선 snapshot 구간과 겹쳤다. 응답은 모두 해당 버전의 독립 기대 DTO와 같았다. 서버 장벽으로 8번 miss를 강제한 결과는 아니지만, 클라이언트 출발을 맞춘 합성 장면이며 운영 발생률로 해석하지 않는다.

### miss 시간은 어디에 쓰였는가

단위는 ms, 같은 조건의 miss 표본만 모은 **평균**이다. p95를 더하여 분해하지 않는다.

| 순차 단계 | 초당 2회 변경: miss 80개 | 초당 10회 변경: miss 305개 |
| --- | ---: | ---: |
| 최초 DB 버전 확인: TX·연결 포함 | 3.347 | 2.285 |
| 캐시 조회 | 0.822 | 0.557 |
| DB snapshot 전체 | 68.473 | 54.447 |
| 캐시 저장: 직렬화 포함 | 0.758 | 0.760 |
| application 내 미분해 잔여 | 0.392 | 0.265 |
| application 밖 HTTP·클라이언트 등 잔여 | 2.225 | 1.656 |
| HTTP 전체 | 76.018 | 59.971 |

반올림 차이를 제외하면 위 순차 단계의 평균 합이 HTTP 전체 평균이다. DB snapshot 안의 비용은 다음과 같다. 이 표를 위 표에 다시 더하지 않는다.

| snapshot 내부 | 초당 2회 변경 | 초당 10회 변경 |
| --- | ---: | ---: |
| 버전 SELECT execute | 0.568 | 0.312 |
| 전체 개수 SELECT execute | 2.640 | 1.413 |
| 좋아요 집계·정렬 SELECT execute | 62.759 | 50.432 |
| JDBC execute 밖 연결·TX·fetch·매핑 등 잔여 | 2.507 | 2.290 |

**miss 시간의 약 90~91%가 DB snapshot에, 약 83~84%가 그 안의 목록 집계 SELECT execute에 쓰였다.** 캐시 조회와 저장을 합친 비중은 약 2.1~2.2%였다. 이 비중은 같은 표본의 시간 합으로 계산했으며, Redis 내부 실행 시간이나 CPU 사용률은 아니다.

초당 2회 조건이 10회 조건보다 개별 miss가 느린 현상도 관찰됐지만 그 차이를 DB 잠금이나 특정 경합 때문이라고 단정하지 않는다. 여러 writer 시험이나 DB 내부 대기 원인 분석은 이번에 하지 않았다.

### 같은 실행의 HTTP 비교

조건별 두 라운드 원시 요청 400개를 합쳐 nearest-rank p50/p95를 계산했다. hit/miss별 p95도 같은 방식이며 각 표본 수가 다르다.

| 변경 빈도 | HTTP p50: OFF → ON | HTTP p95: OFF → ON | ON hit의 p95 | ON miss의 p95 | SELECT: OFF → ON |
| --- | ---: | ---: | ---: | ---: | ---: |
| 없음 | 50.99 → 10.58 | 57.35 → 15.23 | 15.23 | 해당 없음 | 800 → 400 |
| 초당 2회 | 51.71 → 9.01 | 60.76 → 89.21 | 12.88 | 94.80 | 800 → 640 |
| 초당 10회 | 51.44 → 54.04 | 65.25 → 75.77 | 9.77 | 86.70 | 800 → 1,315 |

모든 혼합 ON 구간은 시작 캐시의 TTL 만료 전 끝났다. TTL 연장을 우선 개선으로 추천할 근거가 없다. OFF도 기존 버전 쓰기는 수행하므로 DB 버전 도입 전후 비교는 아니다.

## 다음 개선 제안 — 당시 미구현

이 절은 진단 당시의 제안이다. 이후 사용자 승인으로 [같은 버전 요청 합치기](product-list-single-flight-tdd-log.md)와 [첫 miss의 좁은 순위 집계](product-like-ranking-tdd-log.md)를 구현·검증했다. 아래 제안의 순서와 과거 측정값은 당시 판단 근거로 보존한다.

1. **우선: 같은 조건·같은 버전의 동시 miss 요청 합치기(single-flight)를 작은 증분으로 검토한다.** 자연 부하에서도 추가 계산 40/105회가 확인됐고 대부분 채움 구간이 겹쳤다. 같은 버전의 첫 요청이 결과를 만들고 다른 요청이 공유하면 중복 DB 집계를 줄일 후보가 된다. 계산 진입 직전 이미 채워졌는지 재확인하는 경계도 함께 설계해야 한다.
2. 새로운 버전 요청이 구버전의 계산 결과를 받지 않도록 조회 조건과 버전을 구분한다. 실제 snapshot 버전으로만 저장하고, 실패·timeout·대기자 정리·Redis 우회를 검증한다. 기존 주문/상품 DB 잠금을 조회 전체로 늘리지 않는다. 한 JVM의 합치기가 여러 서버 사이 중복까지 해결한다고 주장하지 않는다.
3. 구현 뒤에는 버전별 계산 수·DB 집계 시간·조회와 쓰기 p95를 다시 비교한다. 합쳐진 대기 요청도 최초 GET은 miss일 수 있으므로 hit율 상승만 성공 기준으로 삼지 않는다. 현재 추가 계산 모두가 제거되거나 응답 시간이 같은 비율로 줄어든다고 보장하지 않는다.
4. **그다음: 여전히 남는 첫 miss의 집계 SQL·실행계획을 검토한다.** 요청 합치기는 필요한 최초 집계를 없애지 못한다. 전체 후보의 좋아요순 정렬·페이지 의미를 유지해야 하며, 좋아요 수 저장 컬럼 등의 대안은 현재 관계 집계 정책과 추가 정합성 책임을 검토한 뒤 별도 승인한다.

진단 당시의 추천 자체를 구현 승인이나 새로운 과제 필수 조건으로 보지 않았다. 후속 구현은 별도 사용자 승인으로 진행했으며 캐시 방식·TTL·신선도 계약은 유지했다. 여러 서버 사이 중복 계산, 운영 접근·변경 분포와 최적 TTL은 여전히 별도 검토 대상이다.

## 증거와 소스 식별

[고정 원시 보고서](evidence/product-list-cache-diagnostics-2026-10-16.json)에 요청별 단계·SQL 시간·버전·전체 응답 일치 여부와 공통 단조 시각 이벤트를 보존했다. 당시 실행의 생성 보고서와 고정 복사본의 SHA-256은 모두 `3bcf07d3bb49b1f0d468da4d84f021f8de42fab9e27bcc20330035a689290323`이었다. 아래 소스 해시도 진단 시점의 식별값이며 후속 변경된 소스의 현재 해시를 뜻하지 않는다.

| 계측 소스 | SHA-256 |
| --- | --- |
| `ProductListCacheMixedLoadIntegrationTest.java` | `d63f7044d2c43d5ed0ed1733eca17b944ffeca776da6252ac97db643d9610e28` |
| `RequestStageProbe.java` | `2a06d23f0297717a0756029ca7d6b13b6a37721bf0faa8758b4724208d6ddba7` |
| `RequestStageProbeTest.java` | `cf6ddecea02aeaa09b4d6236a3f4b36301fa6630458e6f02eac226b7e59d345c` |

`apps/commerce-api/src/main`, `modules/jpa/src/main`, `modules/redis/src/main` 파일을 정렬한 SHA-256 목록의 SHA-256은 작업 전후 모두 `acb008becd52caca0e3420859622153547a3736ea558a0c4ab784a40049101ba`로 같았다. 기존 미커밋 구현을 보존했으며 이번 작업에서 운영 소스·리소스를 변경하지 않았다.

## 재실행

12:07 KST 전체 `check --rerun-tasks`가 **BUILD SUCCESSFUL·종료 코드 0**으로 완료됐다. **77개 스위트·705개 테스트 통과**, 실패·오류·skip 0, Checkstyle XML 11개·위반 0, ArchUnit 1개 통과다. 이전 696개에 새 계측 도구의 단위 테스트 9개를 추가했고 기존 혼합 통합 테스트 1개를 확장했다. 새 계측 단위 테스트는 첫 실행부터 통과했으며 사전 Red를 확인했다고 기록하지 않는다.

```shell
./gradlew :apps:commerce-api:test --tests '*RequestStageProbeTest' --tests '*RequestSqlProbeTest' :apps:commerce-api:checkstyleTest --console=plain
./gradlew :apps:commerce-api:test --tests '*ProductListCacheMixedLoadIntegrationTest' --tests '*ArchitectureTest' --console=plain
./gradlew :apps:commerce-api:check --rerun-tasks --console=plain
```

당시 생성 보고서는 `apps/commerce-api/build/reports/week4/product-list-cache-diagnostics.json`이었다. 현재 `ProductListCacheMixedLoadIntegrationTest`는 요청 합치기 비교까지 포함해 `apps/commerce-api/build/reports/week4/product-list-single-flight-comparison.json`을 생성한다. 위 명령을 현재 소스에서 실행해도 당시 진단을 그대로 재현하는 것은 아니므로 과거 값은 고정 원시 보고서로 확인한다. `completed`와 실행 시각·JUnit 결과를 함께 확인하며 이전 혼합 실험의 고정 증거는 덮어쓰지 않는다.
