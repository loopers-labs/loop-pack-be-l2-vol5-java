# Java 전체 모듈 Checkstyle 검사 범위 보완 기록

2026-09-18 과제 검토에서 `commerce-api` 밖의 Java 코드가 검사에서 빠진 문제를 확인했다. 기존 규칙을 공통 설정으로 옮기고, API의 `check`로 전체 Java 모듈의 정적 검사를 실행하도록 연결한다. 업무 동작을 추가하는 TDD 사례와 구분하여 Gradle 실행 계획·실제 규칙 위반·검사 결과로 검증한다.

## 변경 책임

| 파일 | 변경 |
| --- | --- |
| [루트 build.gradle.kts](../../build.gradle.kts) | 9개 Java leaf 모듈에 Checkstyle 공통 적용. `commerce-api:check`가 다른 모듈의 모든 Checkstyle 태스크에도 의존 |
| [commerce-api build.gradle.kts](../../apps/commerce-api/build.gradle.kts) | 중복된 모듈 전용 Checkstyle 설정 제거 |
| [공통 checkstyle.xml](../../config/checkstyle/checkstyle.xml) | 기존 `AvoidStarImport`·`UnusedImports` 규칙 유지 |
| [KafkaConfig.java](../../modules/kafka/src/main/java/com/loopers/confg/kafka/KafkaConfig.java) | `org.springframework.kafka.core.*`를 실제 사용하는 타입 5개의 명시적 import로 변경 |
| [DemoJobE2ETest.java](../../apps/commerce-batch/src/test/java/com/loopers/job/demo/DemoJobE2ETest.java) | 사용하지 않는 `RequiredArgsConstructor` import 1개 제거 |

Checkstyle `10.26.1`, `ignoreFailures=false`, `maxWarnings=0`을 유지한다. `apps`·`modules`·`supports`는 소스가 없는 모듈 컨테이너여서 적용 대상에서 제외한다. 하위 Java 모듈은 자체 `check`에서도 같은 설정을 사용한다.

## 검사 대상

| 구분 | 모듈 | source set |
| --- | --- | --- |
| 앱 | `commerce-api`, `commerce-batch`, `commerce-streamer` | `main`, `test` |
| 저장·메시징 | `jpa`, `redis`, `kafka` | `main`, `test`, `testFixtures` |
| 공통 지원 | `jackson`, `logging`, `monitoring` | `main`, `test` |

전체 **21개 Checkstyle 태스크**를 연결한다. 소스가 없는 source set은 Gradle이 `NO-SOURCE`로 처리하며, 소스가 있는 파일을 임의로 제외하지 않는다. [DatabaseCleanUp.java](../../modules/jpa/src/testFixtures/java/com/loopers/utils/DatabaseCleanUp.java)는 `:modules:jpa:checkstyleTestFixtures`에 포함한다. 태스크 컬렉션을 의존 대상으로 삼아 이후 생성되는 testFixtures 검사도 연결한다.

## 실행 증거

| 단계 | 실제 결과 |
| --- | --- |
| 변경 전 실행 계획 | `:apps:commerce-api:check --dry-run` 성공. `checkstyleMain`·`checkstyleTest` **2개만** 포함하며 `modules/jpa` testFixtures의 컴파일만 수행 |
| 공통 설정 적용 후 실행 계획 | 같은 dry-run 성공. 모든 Java leaf의 **21개 Checkstyle 태스크** 포함 |
| 확장 검사 Red | 전체 정적 검사 명령 exit 1. KafkaConfig.java 11행 `AvoidStarImport` 1건, DemoJobE2ETest.java 4행 `UnusedImports` 1건. `jpa:checkstyleTestFixtures`는 실제 실행·통과 |
| 수정 | 위 두 import만 정리. 기능 코드·테스트 본문·규칙·기대값 변경 없음 |
| 최종 Green | `2026-09-18 14:22:56 KST` 고정된 최종 소스의 `:apps:commerce-api:check` exit 0. 21개 검사 태스크 연결을 유지하며 소스가 있는 11개 Checkstyle XML의 총 위반 **0개**, 나머지는 `NO-SOURCE`. API 테스트 **506개**와 ArchUnit 통과 |

## 재실행

```shell
# API 테스트·ArchUnit과 전체 모듈 Checkstyle을 함께 실행
./gradlew :apps:commerce-api:check --console=plain

# 연결된 태스크만 확인
./gradlew :apps:commerce-api:check --dry-run --console=plain

# 모든 모듈 정적 검사만 실행
./gradlew checkstyleMain checkstyleTest checkstyleTestFixtures --continue --console=plain
```

API의 `check`에 연결한 것은 다른 모듈의 **Checkstyle** 태스크다. 다른 앱의 별도 통합 테스트까지 API 검사로 실행한다는 의미는 아니다. 최종 통합 결과는 [전체 완료 체크리스트](commerce-completion-checklist.md)에 함께 기록한다.
