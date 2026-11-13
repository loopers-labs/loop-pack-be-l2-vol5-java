# 쿠폰 구현 현황과 검증 근거

C01~C05 원본 개발 이력과 C06 작업 트리를 기준으로 한다. C06 고객·관리자 HTTP 및 실제 JVM 두 개의 대상 검증을 통과했다.
전체 검사에서 실패한 두 모듈은 Docker API 환경을 맞춘 재실행으로 해결했다. 최신 XML은 UP-TO-DATE 재사용을 포함해
635건, 실패·오류·skip 0이다. **단일 clean 전체 실행 635건을 의미하지 않으며 C06 기능 커밋을 준비한 상태다.**

| 문서 | 내용 |
|---|---|
| [운영 및 API 안내](operations.md) | 데이터 보존 실행, 로컬 준비, 인스턴스 개방·복구, 집계 대조·재집계 |
| [기술 글 초안](technical-article.md) | 총량과 처리 자리, DB 발급, 종료 불명, Redis 재집계 경합 |
| [PR 게시용 본문](pr-description.md) | 변경 목적·결과·검증·리뷰 포인트·한계 |
| [검증 매핑과 미확인 항목](../verification-plan.md) | 실제 테스트와 아직 입증하지 않은 조건 |

## 구현 범위

| 단계 | 커밋 | 구현 | 해당 단계에서 확인한 검증 |
|---|---|---|---|
| C01 | `e228835` | 캠페인·사전 슬롯 준비, 활성 전환, JPA·DDL | MySQL 준비 5건과 명시 DDL 1건 성공 |
| C02 | `b17c3e6` | DB 발급, 중복 결과, 전체 롤백 | MySQL 통합·DDL 14건과 아키텍처 8건 성공 |
| C03 | `c601745` | 본인 목록·기존 결과 조회 | 조회 통합 3건과 아키텍처 8건 성공 |
| C04 | `b401f40` | 인스턴스별 수용·종료 관찰·수동 복구 | 최신 재검증 20건 성공, 이전 미변경 37건의 성공 근거 유지 |
| C05 | `62df5ee` | Redis 누적 집계·대조·재집계 | 신규 11건과 아키텍처 8건, 총 19건 성공 |
| C06 | 기능 커밋 준비 | 고객·관리자 API, 독립 프로세스 검증·문서 | HTTP 9건·독립 프로세스 1건 성공, 전체 검사 실패 모듈 환경 재실행 확인 완료 |

C04의 57건은 한 번에 실행한 수가 아니다. 단계별 아키텍처 검사도 중복되므로 이 표를 합산해 고유 테스트 총수로 표현하지 않는다.
C01~C05는 각 단계의 Checkstyle·리뷰·QA를 마쳤다. C06은 세 관점 리뷰와 부분 재리뷰·Checkstyle·대상 검증을 마쳤으며 최종 QA는 전체 검사 결과와 구분한다.
표의 SHA는 원본 개발 이력이다. 제출용 브랜치에서 커밋을 합치면 SHA가 달라질 수 있으므로 원본 이력을 보존해 대조한다.

## C01 근거 통합

기존 `c01-result.md`의 고유한 완료 근거를 이 절과 [검증 매핑](../verification-plan.md#실행-근거)에 통합했다.

| 검증 | 확인한 결과 |
|---|---|
| `PrepareCouponCampaignIntegrationTest` | 실제 MySQL 5건, 실패·오류·skip 0. XML 시작 2026-11-13 14:51:15 KST |
| `CouponCampaignSchemaIntegrationTest` | 실제 명시 DDL 1건, 실패·오류·skip 0. XML 시작 15:04:38 KST |
| 구조·스타일·diff | 기존 ArchUnit 8건 근거, 기존 Checkstyle와 신규 스키마 파일 검사 위반 0, 공백 검사 통과 |

준비 5건은 정상 준비·활성화와 UTC/혜택 보존, 실제 부분 저장 실패 전체 rollback, 기발급 재고 보존, 부분 준비 거절,
외부 REPEATABLE_READ snapshot 격리를 검증했다. 스키마 1건은 새 MySQL 8 컨테이너에 실제 012 SQL을 적용하고
두 Entity의 validate·저장/commit/clear/재조회 및 실제 FK 참조열·복합 유일 키·인덱스·CHECK를 확인했다.
독립 MySQLContainer 사용에 필요한 `org.testcontainers:mysql` 직접 테스트 의존성도 포함됐다.

Docker pipe/소켓 접근이 제한된 실행 세션 대신 사용자가 호스트 PowerShell에서 두 명령을 실행했고 메인이 XML을 확인했다.
초기의 직접 컴파일·정적 검사는 DB 성공을 대신하지 않았으며 후속 실제 DB 6건으로 검증을 마쳤다.
세 관점의 독립 리뷰와 지적 부분 재리뷰, 읽기 전용 최종 QA 후 C01을 커밋했다. QA가 실제 테스트를 직접 재실행한 것은 아니다.

## C06 대상 검증

| 항목 | 근거 |
|---|---|
| 고객·관리자 HTTP | `CouponApiE2ETest` 실제 HTTP 6건 성공 |
| 쿠폰 일시 오류 응답 | `CouponErrorMappingTest` 응답 경계 3건 성공 |
| 독립 앱 프로세스 두 개 | `CouponMultiProcessE2ETest` 실제 OS JVM 2개 시험 1건 성공 |
| 마지막 재실행 | 17:02 KST XML: 프로세스 1건·아키텍처 8건, 실패·오류·skip 0 |
| 프로세스 식별 | A PID 11128/port 50598, B PID 30896/port 64779. 해당 실행의 값이며 고정 설정 아님 |
| Checkstyle | main/test 위반 0 확인 |
| 최종 전체 검사 | 최초 전체 검사에서 실패한 batch·test-pg를 환경 재실행으로 해결, 최신 XML 635건 모두 실패·오류·skip 0 |

최초 독립 프로세스 시험의 관리 포트 8081 충돌은 시험 자식에만 `management.server.port=-1`을 적용해 해결했다.
쿠폰 주 서버는 임의 포트 0으로 시작한다. 다른 서비스나 운영 monitoring 설정을 종료·변경하지 않았다.

## 전체 검사 결과의 범위

최초 `./gradlew.bat check --continue`의 commerce-batch 3건·test-pg 15건 실패는 Docker API 400이었다.
성공 모듈과 달리 두 모듈에는 로컬 API 1.44 설정이 없었다. 사용자 PowerShell에만
`JAVA_TOOL_OPTIONS=-Dapi.version=1.44`를 적용하고 daemon을 중지한 뒤 두 모듈 테스트를 `--continue`로 재실행해 성공했다.
기존 환경 변수는 finally에서 복원했으며 프로덕션 설정은 변경하지 않았다.

| 모듈 | 최신 XML 건수 | 실행·재사용 근거 |
|---|---:|---|
| commerce-api | 584 | 2026-11-13 17:10 KST 실행 |
| commerce-batch | 3 | 17:24 KST 환경 재실행 |
| test-pg | 25 | 17:25 KST 환경 재실행 |
| commerce-streamer | 22 | 2026-11-04 XML, 이번 검사에서 UP-TO-DATE |
| kafka | 1 | 2026-11-04 XML, 이번 검사에서 UP-TO-DATE |
| 합계 | 635 | 실패·오류·skip 모두 0 |

이 결과는 전체 요구 검사의 실패 모듈을 보완하고 기존 UP-TO-DATE 근거를 포함해 확인한 합계다.
단일 clean 실행이나 모든 635건의 같은 시점 신규 실행으로 표현하지 않는다.

## 현재 보장과 한계

DB가 원본이며 쿠폰·슬롯·issuedCount는 같은 TX다. 사용자·슬롯별 유일 제약, 본인 목록과 저장 혜택을 유지한다.
처리 자리는 인스턴스별 Semaphore이고 기본 닫힘이다. TX와 실행 종료가 확인된 handle만 한 번 반환하며 종료 불명은 보류한다.
Redis는 누적 집계이며 장애가 발급 성공을 취소하지 않는다. commit 뒤 process 종료 누락은 DB 재집계로 복구한다.

실제 인증·공개 배포, 전역 합계 C, 영속 전달·EOS는 보장하지 않는다. 운영 `application` 계정의 직접 전역 PROCESS 권한은 미확인이다.
실제 네트워크 commit 응답 유실과 완료 신호/예외 주입 시험을 구분한다.
원문 quota=5/사용자 8명, 마감 후 지연 commit, 최대값 변경 후 실제 재시작 등 미확인 조건은
[미확인 항목](../verification-plan.md#미확인-항목)에 남겼다. 전체 check 통과와도 같은 의미가 아니다.

## 초기 준비의 보존 기록

당시 시작은 `volume-6-7/r03-delivery-recovery`의 `872e0fb`였고 `origin/corinB`의
`270fb60776fb046a193c72c03b7621a4c9fe3261`에서 `volume-8/main`을 준비했다. 준비 문서 커밋은 `66e0871`이다.
이는 과거 기준점이며 현재 브랜치·HEAD를 지정하는 지시가 아니다.
사용자 JMeter 파일 `docs/week4/r01-product-list-index/jmeter/product-list-ui.jmx`의 준비 당시 SHA-256은
`F51DC3363E79FB6EEB15846ACA55C9028CB58F207C8F89CC973E2CE3B3FC89C0`였다.
이 기록을 현재 파일 내용의 동일성 확인으로 대신하지 않으며 JMeter 수정은 이번 문서 정리 범위 밖이다.
