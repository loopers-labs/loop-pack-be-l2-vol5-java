# 서버 재시작 직후 첫 상품 조회 측정

2026-10-16 사용자 요청으로 **앱 서버를 새로 시작한 뒤 보내는 첫 HTTP 요청**을 측정했다. Redis의 현재 버전 캐시가 남은 경우와 없는 경우를 비교한다. 운영 워밍 기능이나 새로운 캐시 정책을 구현한 기록이 아니다.

아래 측정값과 검사 수는 이 증분 당시의 기록으로 보존한다. 이후 완료한 [TTL·메모리 비교](product-list-cache-ttl-memory.md)와 [최신 통합 검사](../week2/commerce-completion-checklist.md#최신-통합-검사)는 별도로 확인한다.

**후속 선택:** 2026-10-16 사용자 승인으로 이번 과제에서는 서버 시작 워밍을 도입하지 않고 요청 시 캐시를 채우는 방식을 유지한다. 브랜드 필터 없는 좋아요순 첫 페이지(`page=0,size=20`)와 TTL 60초를 선택했다. 워밍은 미완료 필수 항목이 아니며, 선택 이유와 대안은 [정책 결정](../week2/commerce-policy-decisions.md#w4-캐시-설계의-선택과-미정-질문)을 따른다.

## 무엇을 비교했는가

| 항목 | 조건 |
| --- | --- |
| 대상 | `GET /api/v1/products?sort=likes_desc&page=0&size=20`, 브랜드 필터 없음 |
| 데이터 | 상품 10,000개, 노출 9,812개, 좋아요 46,955개. 기존 수동 DDL 001~008과 동일 fixture |
| 서버 | 매 표본 별도 JVM에서 실제 `CommerceApiApplication`과 HTTP 서버 시작. MockMvc나 서비스 직접 호출이 아님 |
| 반복 | 캐시 있음/없음 각각 8개, 총 16개 측정 JVM. 짝수/홀수 쌍마다 실행 순서를 반대로 배치 |
| 캐시 있음 | 이전 앱의 실제 조회가 저장한 같은 DB 버전의 payload를 유지. 그 앱을 종료한 뒤 새 앱에서 요청 |
| 캐시 없음 | 위와 동일하게 준비한 뒤, 이번 실험의 정확한 대상 키 하나만 제거하고 새 앱에서 요청 |
| 측정 시작 | 앱 준비 완료를 파일 신호로 확인. health check 등 사전 HTTP 요청 없음. 첫 요청의 실제 서버 순번이 1인지 검사 |
| 측정 시간 | 부모 HTTP client의 전송 시작부터 응답 본문 수신까지. 앱 실행→준비 완료 시간은 별도 기록 |
| 유지한 설정 | TTL 60초, Redis 연결/명령 제한 100ms, single-flight 500ms, 기존 정렬·삭제·트랜잭션·락 |
| 환경 | Java 21.0.10, MySQL 8.0.46, Redis 7.4 이미지. 테스트 전용 컨테이너와 namespace |
| 기동 설정 | 실제 production runtime classpath + 관찰용 테스트 클래스만 사용. `test` profile, `ddl-auto=none`, 캐시 명시 ON, heap 128~512MiB, `-Xshare:off`, 임의 HTTP/management port |

로컬/테스트 기본 캐시 OFF는 그대로다. 이 실험만 ON으로 실행하며, 앱 재시작마다 DB를 재생성하지 않도록 수동 DDL과 `ddl-auto=none`을 사용했다. **DB·Redis 프로세스와 DB 버퍼·OS 파일 캐시는 유지하고 앱 JVM만 다시 시작한 실험**이다. 전체 인프라의 첫 부팅이나 실제 운영 배포 환경을 재현했다는 뜻은 아니다.

캐시 있음 조건은 TTL이 남았는지뿐 아니라 **현재 DB 버전의 정확한 키와 payload가 그대로 있는지** 검사한다. 키가 있어도 최초 Redis 연결에 실패하면 DB 우회로 분류하며, 그런 요청을 버리고 재시도한 수치를 첫 요청으로 쓰지 않는다.

## 측정 결과

아래는 검증을 보강한 뒤 **전체 검사에서 재실행한 마지막 측정**이다. [최종 원시 JSON](evidence/product-list-cold-start-2026-10-16.json)에 모든 요청·응답·SQL·카운터·TTL·PID를 보존했다. 단위 ms, 각 조건 8개. 중앙값과 관측 범위를 기록하며 작은 표본으로 운영 p95나 SLO를 주장하지 않는다.

| 항목 | 현재 버전 캐시 있음 | 캐시 없음 |
| --- | ---: | ---: |
| 첫 HTTP 중앙값 | 279.74 | 402.82 |
| 첫 HTTP 최소~최대 | 265.05~295.04 | 377.64~414.25 |
| 첫 요청 실제 경로 | hit 8/8 | miss 후 저장 8/8 |
| 첫 요청 SELECT | 버전 조회 1회 | 최초 버전 + snapshot의 버전/count/rank/detail = 5회 |
| 같은 JVM 두 번째 HTTP 중앙값 | 8.95 | 21.34 |
| 실행→준비 완료 중앙값 | 3,265.94 | 3,257.37 |

첫 요청 중앙값 차이는 **123.08ms, 약 30.6%**다. 이는 코드 변경 전후의 개선율이 아니라 **현재 구현에서 캐시 잔존 여부를 바꾼 조건 간 차이**다. Redis 오류·손상 payload는 없었고, 두 번째 요청은 16개 모두 hit였다.

캐시 있음 조건에서 요청 직전 남은 TTL은 55.384~55.731초였다. 캐시 없음 조건은 요청 전 실제 키 부재를 확인했고 첫 응답 후 60초 TTL로 채워졌다. DB 버전은 전후 모두 0이며 독립 fixture의 전체 DTO·정렬·금액·재고·브랜드·좋아요·total·페이지가 모든 측정 응답과 일치했다.

[첫 실행 JSON](evidence/product-list-cold-start-run1-2026-10-16.json)도 덮어쓰지 않고 보존했다. 첫 실행은 각각 8개에서 캐시 있음 274.58ms, 없음 408.82ms 중앙값이며, 실제 hit/miss 분류와 SQL 수는 최종 실행과 같았다. 두 실행을 합쳐 유리한 표본을 고르거나 p95를 만들지 않는다.

두 번째 요청은 첫 요청의 진단 자료를 회수한 다음 보냈다. **충분히 예열한 정상 상태 측정이 아니며**, 캐시 유무 두 그룹의 두 번째 요청 시간이 다른 원인을 이번 결과만으로 특정하지 않는다. 앞선 [순위 집계 개선](product-like-ranking-tdd-log.md)의 반복 hit/miss 측정과도 준비 조건이 다르므로 절대 시간을 섞어 개선율을 계산하지 않는다.

## 결과를 어떻게 해석하는가

| 확인한 사실 | 해석과 한계 |
| --- | --- |
| 캐시가 남아 있으면 첫 조회에서 count/rank/detail을 실행하지 않는다. | 재시작 후에도 유효한 캐시를 재사용하는 이득이 확인됐다. 모든 캐시를 다시 채우는 기능이 이미 있다는 뜻은 아니다. |
| hit의 첫 HTTP는 중앙값 약 280ms인데 JDBC execute 합은 약 1.29~2.82ms다. | SQL 실행 외의 첫 요청 비용이 크게 남는다. JDBC 계측은 ResultSet 소비 등을 제외한다. JPA 초기 쿼리 처리·Redis 연결·JSON·HTTP 초기화 등의 정확한 비중은 별도 계측 없이는 단정할 수 없다. |
| 이미 캐시가 있어도 두 번째 요청보다 첫 요청이 느리다. | **캐시 데이터가 미리 존재하는 것과 새 앱의 처리 경로가 준비된 것은 다른 문제**다. 캐시 선채움만으로 첫 응답이 몇 ms가 된다고 보장할 수 없다. |
| 워밍 코드를 실행하지 않았다. | 워밍에 걸리는 시간·기동 지연·워밍 후 첫 HTTP는 아직 측정하지 않았다. 실제 앱 경로를 실행하는 워밍은 초기화도 일부 수행할 수 있으므로, 이번 캐시 잔존 비교와 효과가 같다고 간주하지 않는다. |

이번에는 기존 요청 시 채우기를 유지하고 워밍 미도입을 선택했다. 워밍의 추가 효과·기동 비용이 검증되지 않았으며, 잦은 변경으로 새 버전이 생길 때의 miss도 시작 시 1회 워밍만으로 해결되지 않기 때문이다. 나중에 선택을 재검토한다면 **현재 방식 / 캐시 데이터 선채움 / 앱의 조회 경로까지 미리 실행**을 구분하고 첫 요청 이득과 준비 완료 지연을 함께 비교한다. 현재 버전 확인·진행 중 삭제 경계·장애 우회 정책은 유지한다.

## 테스트 책임과 검증

| 파일·항목 | 역할·기대값 |
| --- | --- |
| [ProductListColdStartMeasurementTest](../../apps/commerce-api/src/test/java/com/loopers/application/product/ProductListColdStartMeasurementTest.java) | 전용 DB/Redis·DDL·fixture 준비, 이전 앱의 캐시 저장, 새 JVM 기동, 첫/두 번째 HTTP·전체 DTO·TTL·PID·요청 순번·SQL 구성·실제 cache counter·최종 DB 검증과 JSON 기록 |
| [ColdStartServer](../../apps/commerce-api/src/test/java/w4/measurement/ColdStartServer.java) | 실제 앱을 띄우는 테스트 실행기. 관찰 filter/JDBC wrapper로 요청·SQL을 기록. HTTP 아닌 파일 신호로 ready를 알리고, 측정 후 stdin 명령으로 증거를 회수 |
| [app build 설정](../../apps/commerce-api/build.gradle.kts) | 테스트에 main runtime classpath를 전달. 새 JVM이 다른 테스트의 Spring 설정/Testcontainers까지 스캔하지 않도록 격리 |
| 정확성 | 200·SUCCESS·독립 전체 DTO, 16개 서로 다른 PID, 첫 HTTP 순번 1, hit는 버전 1회, 정상 miss는 버전 2회+count/rank/detail 각 1회 |
| 오류 구분 | 키 존재와 실제 hit를 구분. GET 오류→DB 우회, SET 오류→DB 응답을 별도 결과로 기록. 측정 요청 재시도로 오류 은폐하지 않음 |
| 자원 정리 | 유한 ready/capture/request 대기, EXIT→종료 대기→필요 시 실험 소유 JVM 강제 종료. stdin 오류·인터럽트에도 정리 수행. 기존 데이터/볼륨 삭제 없음 |

최초 관찰 테스트는 처음부터 통과했다. 운영 기능을 고치는 TDD가 아니므로 인위적인 실패를 만들지 않았다. 이후 테스트의 프로세스 정리와 PID·SQL 구성 검증만 보강했다. 시간은 관찰 값이며 임의 ms 합격선을 추가하지 않는다.

재실행 명령:

```bash
./gradlew :apps:commerce-api:test \
  --tests '*ProductListColdStartMeasurementTest' \
  --tests '*RequestSqlProbeTest' \
  --tests '*ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest \
  --console=plain
```

측정용 이전 앱이 다음 실행을 준비하기 위해 하는 캐시 채움은 측정 구간 밖이다. 대상 JVM에는 최초 요청 전 조회를 보내지 않는다. 조건 준비 중 재시도는 따로 기록하며 첫/두 번째 측정 표본에 섞지 않는다. 테스트 관찰 비용과 로컬 HTTP·Docker 비용은 남고, 동시 쓰기·여러 서버 동시 기동·TTL 주기·운영 요청 분포는 이 첫 조회 실험의 범위가 아니다. TTL 주기·메모리의 별도 실측은 [후속 기록](product-list-cache-ttl-memory.md)에서 완료했다.

### 최종 검사

위 첫 관련 실행은 3개 스위트·10개 테스트 통과다. 이 측정 증분의 보강 후 아래 전체 검사가 **BUILD SUCCESSFUL·종료 코드 0**으로 완료됐다. 이후 증분까지 포함한 검사 수는 [최신 통합 검사](../week2/commerce-completion-checklist.md#최신-통합-검사)와 구분한다.

```bash
./gradlew :apps:commerce-api:check --console=plain
```

- 81개 스위트·734개 테스트 통과. 실패·오류·skip 0, ArchUnit 1개 통과.
- Checkstyle XML 11개·위반 0. 변경된 test 검사는 재실행했고 변경 없는 검사 태스크는 UP-TO-DATE였다.
- main 소스/리소스 153개 전체의 경로·내용 해시가 작업 전후 동일하다. 운영 워밍·TTL·트랜잭션·락·스키마는 변경하지 않았다.
- 마지막 측정의 이전 앱 1개+측정 앱 16개가 정상 종료됐고 종료 후 생존 프로세스도 없음을 확인했다. 기존 데이터·볼륨 삭제, 커밋·푸시·PR 제출은 하지 않았다.
- [검사 집계·소스와 증거 해시](evidence/product-list-cold-start-validation-2026-10-16.json), `git diff --check`, 관련 문서의 로컬 링크를 확인했다. 이전 실행 증거는 별도 파일로 보존한다.
