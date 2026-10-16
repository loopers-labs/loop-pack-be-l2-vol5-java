# R02 실제 결과

캐시 구현·변경 반영·실제 MySQL/HTTP 검증과 JMeter CLI 본 측정 8개를 완료했다.
최종 전체 테스트 333개와 본 측정 1,823,514건의 실패·오류는 0건이다.

- [요구사항](requirement.md)
- [설계 판단](trade_off/total_trade_off.md)
- [구현 계획](plan.md)

## 작업 준비

- 시작 커밋: `3786c0d1b4518a66fa2196586d998a4f09a573ea` (PR #22 병합)
- 작업 브랜치: `volume-4/r02-product-detail-local-cache`
- 사용자 GUI JMX SHA-256: `F51DC3363E79FB6EEB15846ACA55C9028CB58F207C8F89CC973E2CE3B3FC89C0`
- 기본 OFF·용량 1000·저장 후 TTL 30초이며 테스트·측정에서만 명시적으로 활성화한다.

## 구현과 동작

이번 구현의 최종 선택은 Caffeine 로컬 캐시다. 단일 JVM의 반복 상세 조회에서 별도 캐시 서버 없이
DB 호출을 줄인 실측 결과를 근거로 선택했다. 별도 캐시 서버의 설치·운영 비용을 추가하지 않았으며,
애플리케이션 메모리와 무효화 처리 비용은 남는다. Redis는 이번에 도입하지 않는다.

`ProductQueryDao`의 비TX 데코레이터가 고객 상세만 캐시한다. miss의 원본 DAO 트랜잭션 프록시는 유지한다.
이미 TX가 있으면 캐시 조회·저장을 모두 우회한다. 목록·관리자 조회·주문 판단은 기존 경로를 유지한다.
미존재·삭제·예외는 저장하지 않으며 Caffeine W-TinyLFU와 expireAfterWrite를 사용한다.

캐시 접근과 세대 변경은 같은 짧은 잠금으로 보호한다. DB 읽기는 잠금 밖에서 수행하며
무효화 세대가 달라졌으면 늦게 완료된 값을 저장하지 않는다. 상품 ID별·브랜드별·전체 무효화를
기존 쓰기 TX의 동기 AFTER_COMMIT 이벤트에 연결했다. 관계 commit 뒤와 좋아요 집계 commit 뒤를 모두 처리한다.

캐시 변경 전파는 같은 JVM의 기존 쓰기 경로가 대상이다. 여러 JVM 사이의 캐시 공유와 직접 SQL 변경 전파는 지원하지 않는다.
Redis 성능·장애·복구와 직접 비교한 실험은 없으며, 아래 측정은 Caffeine OFF/ON의 차이를 보여 준다.
동시 miss의 중복 DB 조회는 허용한다. TTL을 전역 최신성 시간 상한으로 주장하지 않는다.

## 테스트와 실제 SQL

- 초기 관련 선택 테스트 **74개**, 실패·오류·skip 0개, `4m 2s`: [관련 검사 기록](baseline/validation-related.json).
- 초기 전체 check와 bootJar **332개** 통과, `5m 24s`: [최초 검사](baseline/validation-check-initial.json).
  delta rollback 사례를 보완한 뒤 최종 `:apps:commerce-api:check` **333개**, 실패·오류·skip 0개, `4m 2s`:
  [최종 검사 기록](baseline/validation-check.json). Checkstyle main/test 오류 0개, ArchUnit 8개 통과.
  최종 검사는 JAR를 재생성하지 않았다.
- 실제 MySQL 무효화 통합 테스트 14개는 상품·브랜드·좋아요 관계/집계·재집계·주문의 commit/rollback과 다른 항목 보존을 확인했다.
  [최종 보완 검사](baseline/validation-delta-rollback.json)는 delta SQL 반영을 확인한 뒤 외부 TX를 rollback해 DB·기존 캐시 보존까지 검증했다.
- 실제 HTTP 회귀 5개는 전체 응답, SQL 1→0, 400/404, 미커밋 조회 우회·rollback 보존을 확인했다.
- 래치 기반 단위 테스트는 상품·브랜드·전체 무효화의 늦은 채움 방지를 확인했다. TTL은 가짜 시간원으로 검증했다.
- 현재 100만 DB의 [별도 HTTP·SQL 수집](baseline/http-sql/README.md)에서도 OFF 1회/매번, ON miss 1회/hit 0회와 전체 응답 일치를 확인했다.

이벤트 관련 테스트를 먼저 작성한 Red는 새 이벤트·생성자가 없어 compileTestJava 실패로 남았다.
캐시 단위 테스트의 최초 Red 시도는 Gradle이 시작되지 않아 중단했으며 유효한 Red 결과로 계산하지 않았다.
캐시와 이벤트의 Green은 이후 관련 검사와 전체 검사로 확인했다. 로그는 `build/benchmark/r02/validation/`에 보존한다.
테스트 구성 3개는 `@TestConfiguration`으로 격리해 가짜 TX manager가 실제 앱 구성에 섞이지 않게 했다.

## JMeter CLI 실제 비교

2026-10-16 15:47:53~16:10:22 KST에 기존 100만 DB를 사용해 같은 새 JAR의 OFF/ON을 측정했다.
고정 활성 상품 100개·동시 1명/10명·2회, 각 워밍업 20초·본 측정 60초·램프업 5초다.
1회차 OFF→ON, 2회차 ON→OFF로 순서를 교대했다.

| 항목 | OFF 4개 조건 | ON 4개 조건 |
|---|---:|---:|
| 평균 응답 시간 범위 | 17.262~19.462ms | 0.407~0.574ms |
| p95 범위 | 32~43ms | 1ms |
| 1명 처리량 범위 | 48.81~55.07 요청/초 | 1532.55~1717.29 요청/초 |
| 10명 처리량 범위 | 492.93~520.81 요청/초 | 13078.74~13456.25 요청/초 |
| DB DAO 호출/요청 | 1 | 0.000284~0.002210 |
| hit rate | null (캐시 조회 0회) | 99.7790~99.9716% |

전체 표본·평균·p50/p95/p99·처리량·오류율·캐시 지표는 [8개 개별 측정표](baseline/jmeter/README.md)와
[검산 요약](baseline/jmeter/summary.json)에 보존했다. 분위수는 원본 JTL의 nearest-rank이고
반복 간 평균을 내지 않았다. 0ms 분위수는 정수 ms 기록 해상도에 따른 값이다.

본 측정 1,823,514건과 워밍업 478,263건 모두 오류 0건이다. 별도 [full smoke 200건](baseline/jmeter-smoke/README.md)은
100개 전체 응답을 OFF/ON 각각 대조했다. 본 부하는 200/SUCCESS/요청 ID의 light 검증을 사용했다.
요약기는 원본 JTL 16개, HTML 통계 8개, Prometheus 원문 32개와 manifest를 대조해 `Verified`로 완료했다.
DB load는 DAO 호출 counter이며 부하 중 실제 SQL 수로 단정하지 않는다. 별도 SQL 검증의 1→0 결과와 구분한다.

구현 소스는 `5003d2c5242b408d8bced896e135627cf625e274`, 측정 도구는
`7ea15c3678c7b1e6ba5dbf79f808a234e5177a6d`로 고정했다. 이후 변경은 요약기·문서이며
동일 `commerce-api-5003d2c.jar` SHA-256 `6D30AE13AC5E82A3C3EA38C36AEB394035BDB5B7505B336890D79495D4058BBE`를 사용했다.
[실제 런타임](baseline/runtime.json)의 Caffeine은 3.1.8, Spring Boot는 3.4.4, Java는 21.0.10이다.

## 보존 확인과 한계

- 상품 1,000,000·활성 950,400·좋아요 2,597,980·저장 좋아요 합 2,597,980·가격 합 50,950,000,000이 전후 같았다.
- PRIMARY+보조 인덱스 6개 및 주요 DB 설정 11개가 같았고 버퍼 풀은 128MiB를 유지했다. 재적재·DDL·설정 변경은 없었다.
- 전용 API 18081/관리 18082의 새 프로세스 4개만 종료했다. 기존 API PID 34084(18080/8081)는 전후 실행 정보가 같았다.
- 사용자 GUI JMX의 작업 전 SHA-256을 보존했고 커밋에서 제외했다.
- 대용량 JTL·HTML·JAR·JMeter 배포본은 build/ignore 범위에 유지했다. 공개 결과는 요약·통계 원문·해시·재현 도구다.

100개 hot set이 1000개 용량보다 작으므로 W-TinyLFU의 용량 압박 효과나 순수 LRU 대비 우위를 측정한 결과는 아니다.
TTL 만료와 동시 miss의 DB 비용은 지표에 포함된다. 같은 장비의 생성기/API/MySQL, think time 없는 closed-loop,
OS/DB 캐시·JIT·2회 반복의 편차가 남는다. 전체 상품 분포·쓰기 혼합 부하나 특정 개선 배수를 보장하지 않는다.
원본 세션의 로컬 build 파일은 Git만으로 복원되지 않으며 경로·크기·해시는 공개 요약에서 찾을 수 있다.

원문의 Redis 요구와 이번 구현에서 Caffeine을 최종 선택한 차이는 [전체 요구사항](../total_requirement.md)에 구분해 기록한다.

좋아요 delta flush의 외부 TX rollback 검증은 DB 집계와 상세 캐시의 보존을 대상으로 한다.
기존 flush는 DAO 호출 중 예외에서만 drain한 delta를 복원한다. 성공 반환 후 외부 TX가 나중에 rollback되는 경우의
버퍼 복원은 기존 동작 그대로이며, 이번 결과를 버퍼 복원 보장으로 해석하지 않는다.
