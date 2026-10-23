# R03 결제 회복 구현·검증 결과

[요구사항](requirement.md) · [계획](plan.md) · [기능 리뷰](review.md) · [재현 방법](verification.md) · [운영 확인](operations.md) · [기술 글](technical-article.md)

## 완료 범위

2026-10-23 로컬 구현과 최종 검증을 마쳤다. 검증한 기능 커밋은 `ae46776`이며 마지막 정리에서는 기능 코드·테스트·검사 설정을 변경하지 않았다. 작업 브랜치는 `volume-5/r03-payment-resilience`, PR 대상은 `volume-5/main`이다. 이후 [PR #26](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/26)으로 게시했고 사용자가 병합했다(`b70d83b`). 병합 뒤 전체 요구사항 대조와 반환 캐시 검증 보완은 [5주차 최종 점검](../final-audit.md)을 따른다. 아래는 PR03에서 실제 수행한 검사·실험 기록이다.

- PG 조회만 감싸는 공유 CircuitBreaker와 확인 불가 fallback을 연결했다. 자동 조회·수동 대사·callback이 공유하며 생성 POST는 회로 밖이다.
- 내부 업무 반영은 MySQL 1213·SQLSTATE 40001과 전체 rollback을 확인한 경우만 새 TX로 100ms 뒤 추가 1회 재시도한다.
- 자동 조회는 최초 confirm의 발송 승자만 시작한다. DB 권한 확보 후 전용 2스레드·큐 2개에 전달한다. 최대 4회, 1·3·10초±20%, 최초 전달부터 30초 예산이다.
- 회차를 먼저 commit하고 새 TX에서 실행 권한·완료 상태를 다시 확인한다. HTTP 직전 시간도 확인한다. 중단·재시작으로 회차를 되돌리거나 자동 작업을 다시 시작하지 않는다.
- 기존 조회·로그·기본 Prometheus 지표를 사용한다. 상태와 트랜잭션 설명은 [상태 문서](../states/total.md)에 정리했다.

기능 커밋마다 비즈니스·사용자 경험·기술 리뷰를 받았다. 알려진 종결/존재 충돌의 회로 집계 제외는 사용자 승인 후 수정·재리뷰했고, 미해결 중요 지적은 없다. 마지막 검증은 동작 변경이 없어 별도 기능 리뷰를 반복하지 않았다.

## 필수 전체 검사

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21.0.10'
./gradlew.bat -I build/docker-api.init.gradle :apps:test-pg:check :apps:commerce-api:check :apps:test-pg:bootJar :apps:commerce-api:bootJar --console=plain
```

| 모듈 | 실행 | 실패 | 오류 | 건너뛰기 |
|---|---:|---:|---:|---:|
| test-pg | 25 | 0 | 0 | 0 |
| commerce-api | 484 | 0 | 0 | 0 |
| 합계 | **509** | **0** | **0** | **0** |

종료 코드 0, `BUILD SUCCESSFUL in 4m 30s`였다. Checkstyle main/test·ArchUnit과 기존 slow/example 검사도 포함한다. 앞선 기능별 검사 수를 중복 합산하지 않았다. 기기 전용 init script는 Docker API 1.44 호환만 처리하며 테스트 선택·기대값·검사 규칙을 바꾸지 않는다.

## 실제 PG 장애·재시작 실험

commerce와 PG를 독립 JVM으로 실행했다. 전용 MySQL `payment-lab-verification`(호스트 13316)의 commerce/PG 스키마를 사용했고 `payment-lab`, `ddl-auto=validate`로 데이터를 보존했다. 실행 JAR 두 개 모두 기능 커밋 `ae46776` 기준이다. SQL 000~007이 적용된 기존 학습 DB를 초기화하지 않았다.

새 주문 55~68은 각각 가격 1,000원·재고 5개 상품을 2개 주문한 사례다. callback은 DROP으로 두어 자동 조회 경로를 확인했다. 표의 HTTP는 PG GET **전송 행 수**이며, 자동 회차와 별개다. 수동 대사는 자동 사용 회차를 늘리지 않는다.

| 주문 | 상황 | 자동 사용 회차 | 수동 처리 전 핵심 증거 | 최종 PG GET 수·결과 |
|---|---|---:|---|---|
| 55 | 생성 응답 유실 후 성공 | 2 | 최초 202·재고 3, 자동 조회로 성공 확인 | 2·CONFIRMED |
| 56 | 생성 응답 유실 후 실패 | 2 | 실패 확인 전 재고 3, 확정 실패 때 한 번 반환 | 2·PAYMENT_FAILED·재고 5 |
| 57 | 30초 처리 지연·조회 한도 | 4 | GET 4회 후 RETRY_EXHAUSTED·수동 확인, 같은 confirm도 재시작 없음 | 5·수동 조회 1회로 CONFIRMED |
| 58 | 회차 commit에 35초 DB 대기 | 1 | commit 뒤 예산 만료·HTTP 0 | 1·수동 조회로 CONFIRMED |
| 59 | 회차 commit에 35초 DB 대기 | 1 | commit 뒤 예산 만료·HTTP 0 | 1·수동 조회로 CONFIRMED |
| 60 | 실행기 큐 대기 중 만료 | 0 | 실행 차례에 예산 만료·HTTP 0 | 1·수동 조회로 CONFIRMED |
| 61 | 실행기 큐 대기 중 만료 | 0 | 실행 차례에 예산 만료·HTTP 0 | 1·수동 조회로 CONFIRMED |
| 62 | 2 worker·큐 2개 포화 | 0 | 접수·100ms 뒤 재접수 거절, EXECUTOR_REJECTED·HTTP 0 | 1·수동 조회로 CONFIRMED |
| 63 | 조회 지연 1.5초 | 4 | 자동 4회＋수동 1회로 느림 5/10·회로 OPEN | 6·후속 수동 조회로 CONFIRMED |
| 64 | OPEN 중 자동 조회 | 1 | CIRCUIT_BLOCKED·HTTP 0·차감 유지, 생성은 가능 | 1·빠른 수동 탐색 후 HALF_OPEN |
| 65 | OPEN 중 자동 조회 | 1 | CIRCUIT_BLOCKED·HTTP 0·차감 유지 | 1·두 번째 빠른 탐색 후 CLOSED |
| 66 | 첫 조회 중 PG 종료·재시작 | 4 | 같은 DB·거래 키·거래 1행 유지, 한도 안에서 자동 완료 | 3·CONFIRMED |
| 67 | 자동 성공 관찰 후 업무 rollback | 3 | SUCCESS 보존·주문 PENDING·기록 0·재고 3·APPLICATION_FAILED | 3·PG 정지 상태에서 추가 GET 없이 재적용 |
| 68 | 회차 commit 뒤 HTTP 전 commerce 종료 | 1 | 실행 ID·회차·최근 시각 보존, 재시작/GET/동일 confirm 뒤 HTTP 0 | 1·명시적 수동 대사로 인계·CONFIRMED |

64·65도 최종 업무 상태는 CONFIRMED다. 회로 표의 HALF_OPEN/CLOSED는 각 수동 요청 직후 공유 회로 상태다. OPEN 중 health는 200이었다. 회로가 회복돼도 중단 자동 작업은 부활하지 않았다.

66은 회차 4와 HTTP 전송 행 3이 달랐다. 연결 실패처럼 HTTP 전송 전에 끝난 실행도 회차는 소비할 수 있다. 전송 행은 응답 수신·PG 도달을 보장하는 지표가 아니며 PG 거래 DB와 함께 해석했다.

68은 JDWP 중단점을 `CircuitPaymentLookupGateway.lookup` 진입(line 21, bci 0)에 고정했다. `provider.lookup` 호출 전 stack과 별도 DB의 RUNNING·회차 1을 확인하고 해당 commerce PID만 종료했다. 같은 DB로 재기동한 뒤에도 실행 ID·회차·lastCheckedAt이 같았고 상세/목록 GET·같은 confirm은 자동 조회를 시작하지 않았다. 수동 POST에서만 새 실행 ID와 GET 1회가 생겼다.

67의 실패 장치는 해당 주문 기록 삽입만 SQLSTATE 45000으로 거절했다. 이는 영구적 업무 오류와 사실 보존을 검증한 것이며 실제 MySQL 데드락 victim 선정을 재현한 실험은 아니다. 데드락 재시도는 정확한 코드 예외 주입과 실제 rollback·새 TX 검사로 검증했다.

### 최종 효과 수

| 항목 | 실측 |
|---|---:|
| 새 주문·PG 생성 POST·PG 거래 | 각각 14 |
| APPLIED | 14 |
| CONFIRMED·주문 기록·CONSUMED | 각각 13 |
| PAYMENT_FAILED·RESTORED | 각각 1 |
| 신규 포인트 USE | 0 |
| 성공 재고 / 실패 재고 | 3 / 5 |

반복 confirm·대사 이후에도 각 주문의 PG 거래는 1건이었다. 주문 확정·재고 반환의 중복 효과는 없었다. 완료 뒤 과거 lastReason이 남을 수 있어 APPLIED와 주문 종결 상태를 먼저 확인했다.

## 보존·정리

- 과거 POINT 주문 42의 CONFIRMED·7,000원·POINT·PAID·기록 시각을 그대로 보존했고 고객 실제 GET도 200이었다.
- 사용자 JMeter와 두 원문은 수정하지 않았다. SHA-256은 아래와 같다.

| 파일 | SHA-256 |
|---|---|
| week4 사용자 product-list-ui.jmx | `F51DC3363E79FB6EEB15846ACA55C9028CB58F207C8F89CC973E2CE3B3FC89C0` |
| reference/learning.md | `11C85B44FC32280190DEDB8CE23A8997173EF5B5BA0C4B668E866A5E8A756FA1` |
| reference/implementation-quest.md | `42BCA27558F38BBE322DF78BAF62D465CEE455D6DF2828125AB463A51682060C` |

실험용 trigger 2개는 제거했고 잔여 수는 0이다. 자신이 실행한 commerce/PG/디버거를 종료하고 8080·8081·8082·5005 listener가 없음을 확인했다. 전용 실험 MySQL만 정지했으며 데이터·볼륨은 보존했다. 기존 사용자 MySQL·Redis·다른 테스트 컨테이너는 건드리지 않았다.

## 증거 위치와 학습 범위의 한계

원시 로그·임시 계측/장애 주입 helper는 기기별 무시 대상 `build/`에 두고 커밋하지 않는다. 공유 가능한 조건·절차·실측 결과는 이 문서와 [재현 방법](verification.md)에 남겼다.

- 전체 검사: `build/r03-full-check.log`, `build/r03-full-check.exit`, `build/r03-full-check-summary.json`, 각 모듈 `build/test-results/test/`.
- 실험: `build/r03-final-lab-state.json`, `build/r03-final-{api,pg}-*.log`, `build/r03-final-metrics.prom`.
- 기존 R01/R02 검증은 각 결과 문서의 증거를 따른다. 이번 14건을 과거 실험 수와 섞지 않는다.

회로·Retry 지표는 JVM 메모리이며 재시작하면 초기화된다. 자동 lease/재시작 회수·Retry-After 해석·별도 운영 API/대시보드는 추가하지 않았다. 느린 PG는 자동 창을 벗어나 수동 대사가 필요할 수 있다. DB 회차는 정확한 HTTP 수가 아니며 30초는 이미 시작한 조회와 저장을 강제 취소하는 시간도 아니다. 조회 후 뒤늦게 발견된 동시 충돌의 회로 표본을 소급 변경하지 않는다.

이 한계와 수동 처리 절차는 [운영 문서](operations.md)에 명시했다. 최종 변경은 검증 기록과 문서뿐이며 필수 전체 검사를 반복할 새 기능 변경은 없다.

## 병합 후 최종 점검 보완

2026-10-23 사용자 요청으로 실패 반환 캐시 commit·rollback 통합 테스트2건을 추가했다. 신규2건을 포함한 기존 캐시 클래스16건과 ArchUnit8건, 테스트 Checkstyle이 통과했다(관련24건, 실패·오류·skip0). 실제 반환 SQL 뒤 rollback과 저장 FAILED 근거 보존까지 확인했고 기능 수정은 없었다. 위 PR03 당시 전체509건/독립 서버14건과 중복 합산하지 않는다. 명령·상세 결과·현재 완료 범위는 [5주차 최종 점검](../final-audit.md)을 따른다.
