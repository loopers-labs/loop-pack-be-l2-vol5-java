# 브랜드 운영 SQL TDD 실행 기록

2026-09-18 기존 Hibernate 자동 생성 검증에 더해, 확정된 Brand 매핑을 **수동 생성 SQL**로 제공했다. 금액·나머지 HTTP·잠금 세부 정책에 의존하지 않는 전체 운영 스키마의 일부다. [적용 안내](commerce-schema-operations.md)를 함께 작성했다.

## 파일과 책임

| 파일 | 역할 |
| --- | --- |
| [001-brand.sql](../../apps/commerce-api/src/main/resources/db/schema/001-brand.sql) | 기존 Brand와 일치하는 최초 테이블 생성. 기존 행 삭제·새 이름 유일 제약 없음 |
| [BrandSchemaIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/brand/BrandSchemaIntegrationTest.java) | 별도 MySQL에 실제 SQL 적용, JPA validate·저장·조회와 DB 제약·시각 검증 |
| [commerce-api 빌드 설정](../../apps/commerce-api/build.gradle.kts) | 해당 테스트가 직접 사용하는 MySQL Testcontainers의 테스트 의존 선언 |

실행 애플리케이션의 Java 코드·계층 의존·프로파일 설정은 변경하지 않았다. DDL 테스트는 일반 Spring 테스트의 자동 생성 DB와 다른 컨테이너를 사용하며 `Brand`와 기존 `BrandNameConverter`만 매핑한다. 이를 전체 모델의 스키마 검증으로 확대하지 않는다.

## 실제 Red → Green → Refactor

| 순서 | 관찰 결과 | 반영 |
| --- | --- | --- |
| 실행 준비 | 주석만 있는 초기 SQL이 Spring SQL 파서의 빈 스크립트 오류로 종료 | 유효한 SELECT 1 골격으로 준비 오류를 분리. 이 오류를 업무 규칙 Red로 계산하지 않음 |
| BRAND-SCHEMA-01 Red | SQL이 테이블을 생성하지 않아 `Schema-validation: missing table [brand]`. 1개 중 1개 실패 | 실제 브랜드 CREATE TABLE SQL 추가 |
| BRAND-SCHEMA-01 Green | Hibernate 자동 생성 없이 Brand 매핑 검증·INSERT·다른 세션 재조회 성공 | 1개 통과 |
| 경계 확장 | Unicode·실제 메타데이터·UTC 시각·NULL/길이 제약·동일 이름·재적용 보존 검증 추가 | SQL 추가 변경 없이 11개 통과. 이미 통과한 규칙의 인위적 Red를 만들지 않음 |
| Refactor | 반복 SQL 적용·JPA 저장·직접 INSERT 준비를 보조 메서드로 모음 | 기존 기대값을 유지하고 전체 check 실행 |

## 검증 사례

| ID | 실제 검증 | 실행 수 |
| --- | --- | --- |
| BRAND-SCHEMA-01 | 수동 SQL 후 JPA validate, 양수 자동 증가 ID·정리된 이름·감사 시각·미삭제 상태 저장/복원 | 1 |
| BRAND-SCHEMA-02 | 한글·이모지 100 코드 포인트 각각 저장/재조회와 DB CHAR_LENGTH | 2 |
| BRAND-SCHEMA-03 | information_schema의 InnoDB·이름 길이/문자 collation·세 시각의 정밀도 6·기본 키 | 1 |
| BRAND-SCHEMA-04 | JPA 저장 시각과 DB UTC 값의 차이 1마이크로초 이내, 서로 다른 고정 UTC 생성/수정/삭제 시각의 정확한 복원 | 1 |
| BRAND-SCHEMA-05 | 이름·생성 시각·수정 시각 각각 null인 직접 INSERT 거절·저장 0행 | 3 |
| BRAND-SCHEMA-06 | 이모지 101개 직접 INSERT 거절·저장 0행. strict SQL mode 조건의 검증 | 1 |
| BRAND-SCHEMA-07 | 같은 이름 두 행에 별도 자동 증가 ID 부여 | 1 |
| BRAND-SCHEMA-08 | 생성 SQL 재적용 실패, 기존 모든 행·컬럼 동일 | 1 |

Hibernate validate만으로 길이·NULL 여부·문자셋 등의 모든 제약을 확인했다고 주장하지 않는다. 실제 메타데이터·저장 결과를 함께 검사하고, SQL mode를 포함한 적용 조건을 운영 안내에 기록했다.

## 전체 검사 결과

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.infrastructure.brand.BrandSchemaIntegrationTest' --console=plain -q
./gradlew :apps:commerce-api:check --console=plain
```

2026-09-18 12:43 KST 리팩터링 후 전체 **167개 테스트 통과**를 XML로 확인했다. 기존 156개와 신규 스키마 검증 11개이며 실패·오류·건너뜀은 0개다. Checkstyle main/test 위반 각각 0개, 기존 ArchUnit 규칙 통과다. 기존 테스트·기대값·검사 규칙은 삭제·완화하지 않았다.

브랜드 테이블 생성만 완료했다. 전체 6개 테이블과 FK·좋아요 유일 제약·조회 인덱스·사용자 초기 데이터·동시성 검증은 남아 있다. 실제 HTTP API 완료 수도 **1/25개**로 유지하며 [전체 체크리스트](commerce-completion-checklist.md)에 부분 증거로 기록한다.
