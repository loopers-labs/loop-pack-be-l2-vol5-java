# 고객 브랜드 상세 HTTP TDD 실행 기록

2026-09-18 사용자가 승인한 P11·C01 계약을 [고객 상세 조회 application](commerce-tdd-brand-query-log.md)에 연결했다. `GET /api/v1/brands/{brandId}`는 헤더 없이 공개 조회하며 전달된 `X-USER-ID`도 무시한다. 이번 기록은 실제 MySQL과 HTTP를 사용하는 테스트의 결과다.

## 승인된 계약과 작업 범위

| 경우 | 확정 기대값 |
| --- | --- |
| 존재하는 미삭제 브랜드 | 200, `meta.result=SUCCESS`, `data.brandId/name`. 오류 코드·메시지 필드는 생략 |
| 없는 브랜드 또는 삭제된 브랜드 | 404, `BRAND_NOT_FOUND`, `브랜드를 찾을 수 없습니다.` |
| 문자·소수·0·음수·Long 범위 초과 ID | 400, `INVALID_REQUEST`, `요청 값이 올바르지 않습니다.` |
| 예상하지 못한 서버 오류 | 500, `INTERNAL_ERROR`, `일시적인 오류가 발생했습니다.` |

실패 응답은 `meta.result=FAIL`과 오류 코드·메시지를 포함하고 `data`를 생략한다. HTTP 응답은 기존 `ApiResponse`를 재사용한다. 응답 DTO와 controller·브랜드 범위 예외 처리만 추가하며 application·domain·기존 전역 Advice·ErrorType은 변경하지 않는다.

| 파일 | 책임 |
| --- | --- |
| [BrandV1Controller](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/brand/BrandV1Controller.java) | C01 경로 연결, 양수 ID 검사, application 호출·응답 구성 |
| [BrandV1Dto](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/brand/BrandV1Dto.java) | BrandInfo를 brandId/name만 가진 HTTP BrandView로 변환 |
| `BrandApiControllerAdvice` (최초 구현) → [CommerceErrors](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/commerce/CommerceErrors.java) | 최초 C01 전용 처리에서 후속 커머스 API 공통 400·404·500 및 프레임워크 오류 변환으로 통합 |
| `InvalidBrandIdException` (최초 구현) → [StrictInput](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/commerce/StrictInput.java) | 최초 0·음수 검증에서 공통 양의 Long ID 형식 검사로 통합 |
| [BrandV1ApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/brand/BrandV1ApiE2ETest.java) | 실제 HTTP·MySQL, 전체 JSON 구조·값·생략, 모든 브랜드 행 보존 검증 |

브랜드 Advice는 `assignableTypes=BrandV1Controller.class`와 `@Order(0)`을 사용한다. 다른 controller에는 새 오류 코드를 적용하지 않는다. 경로를 숫자 정규식으로 제한하지 않아 문자·소수·범위 초과도 해당 controller의 타입 변환 오류로 처리한다.

## 실제 Red → Green → Refactor

| 순서·대상 | 실제 확인한 결과 | 구현·재검증 |
| --- | --- | --- |
| 1 / BRAND-HTTP-01 | controller가 없는 상태에서 1개 중 1개 실패. 기대 200/SUCCESS, 실제 404/Not Found | controller와 BrandView를 연결해 1개 통과 |
| 2 / BRAND-HTTP-02·03 | 없는 ID와 삭제 ID가 404 대신 500/Internal Server Error를 반환해 3개 중 2개 실패 | BrandV1Controller에만 적용하는 우선순위 0의 Advice에서 BRAND_NOT_FOUND로 변환. 3개 통과 |
| 3 / BRAND-HTTP-04 | 입력 6사례를 추가해 9개 중 6개 실패. 문자·소수·범위 초과는 기존 Bad Request 코드, 0·음수는 404 | controller에서 양수를 검사하고 타입 변환 실패와 함께 브랜드 범위의 400 INVALID_REQUEST로 변환. 9개 통과 |
| 4 / BRAND-HTTP-05 | SQL로 공백 이름을 넣어 엔티티 복원 오류를 만들었을 때 기존 Internal Server Error 코드가 반환되어 10개 중 1개 실패 | 브랜드 범위의 Exception 처리에서 INTERNAL_ERROR와 승인된 일반 메시지를 반환. 10개 통과 |
| 5 / BRAND-HTTP-06 | alice·admin·미등록 값·빈 값·공백 포함 헤더의 5사례가 기존 공개 조회 구현으로 통과 | 추가 구현 없이 C01 15개 통과. 이미 통과하는 사례를 인위적으로 실패시키지 않음 |
| 6 / Refactor | 중복 오류 응답 생성과 테스트의 상태·JSON·DB 보존 assertion을 보조 메서드로 추출 | C01 15개·기존 예제 7개·ArchUnit 1개를 함께 재실행해 23개 통과. Checkstyle main/test도 통과 |

첫 Green 후보에서 HTTP 상태와 JSON 값은 맞았지만 테스트가 직접 만든 `LongNode`와 응답을 파싱한 `IntNode`가 달라 비교가 실패했다. 기대 JSON도 실제 응답과 같은 JSON 파서로 읽도록 교정했으며 상태·필드·값·null 필드 생략의 기대값은 유지했다. 이것은 업무 동작의 추가 Red가 아닌 테스트 표현 교정이다.

첫 명령은 sandbox의 Gradle 캐시 접근 제한으로 테스트 전에 종료됐다. 허용된 실행 환경에서 재실행했으며 환경 실패와 기능 Red를 구분한다.

## 검사 결과

리팩터링 후 선택한 회귀 검사 **23개가 통과**했다. C01 15개, 기존 Example E2E 3개·ContractClassification 4개, ArchUnit 1개이며 XML 합계의 실패·오류·건너뜀은 모두 0개다. Checkstyle main/test XML의 위반도 0개다. 2026-09-18 12:31 KST 실행에서 test·Checkstyle main/test가 실제 실행됐고 Gradle은 BUILD SUCCESSFUL로 종료됐다.

기존 예시의 `Bad Request`·`Not Found`, 음수 예시 ID의 404와 미매핑 URL의 404 관찰 계약을 유지했다. 새 테스트는 전체 JsonNode를 비교하여 값뿐 아니라 추가 필드와 `null` 필드 노출도 검출한다. 모든 HTTP 사례에서 브랜드 테이블의 전후 상태를 비교한다. 코드 읽기 검토와 변경 파일 6개의 공백·개행 및 로컬 링크 검사도 완료했다.

아래 결과는 C01에 관련된 선택 검사다. 다른 agent가 병행 구현한 기능을 포함한 프로젝트 전체 검사 결과는 [현재 단계와 검증 기록](commerce-tdd-plan.md#현재-단계와-이번-구현)에서 별도로 관리한다.

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.interfaces.api.brand.BrandV1ApiE2ETest' --console=plain -q
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.interfaces.api.brand.BrandV1ApiE2ETest' \
  --tests 'com.loopers.interfaces.api.ExampleV1ApiE2ETest' \
  --tests 'com.loopers.interfaces.api.ContractClassificationTest' \
  --tests 'com.loopers.architecture.ArchitectureTest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```

## 검증 범위와 남은 항목

테스트는 준비 데이터를 커밋한 뒤 실제 HTTP 요청을 보내고, 호출 전후 모든 브랜드의 ID·이름·생성·수정·삭제 시각을 DB에서 읽어 비교한다. 삭제·저장 데이터 오류는 해당 조회 경로 검증을 위한 SQL fixture로 준비하며, 삭제 유스케이스나 동시성 검증을 수행한 것으로 기록하지 않는다.

500 사례에서는 이름을 공백으로 바꾸는 SQL의 갱신 행 수가 1인지 확인한다. 실제 로그에서 BrandNameConverter의 복원 중 BrandNameException(EMPTY_NAME)이 발생했음을 확인했고, HTTP 응답에는 원인·스택 등 내부 정보를 넣지 않는다. 예상 외 처리 범위는 Exception이며 JVM Error까지 새 처리기에 포함하지 않는다.

지원하지 않는 HTTP 메서드의 405와 미매핑 URL은 controller 선택 범위 밖이다. 기존 전역 처리의 405→500 문제를 이번 기능에서 해결한 것으로 취급하지 않는다. 다른 API의 HTTP 계약·관리자 작업·행 잠금·운영 DDL도 이 구현 범위에 포함하지 않는다.
