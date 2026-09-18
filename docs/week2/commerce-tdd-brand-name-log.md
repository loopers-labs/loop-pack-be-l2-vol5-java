# 브랜드 이름 TDD 실행 기록

2026-09-18 브랜드 CRUD의 첫 단계로 이름 값객체를 구현했다. 관련 계약은 P02와 [BRAND-NAME-01~04](commerce-tdd-plan.md#브랜드-이름의-tdd-계획)다. 사용자가 브랜드 이름의 100자 계산을 Unicode 코드 포인트 기준으로 확정했다. 상품명의 계산 방식은 별도 제안으로 유지한다.

## 구현 범위와 책임

| 파일 | 책임 |
| --- | --- |
| [BrandName](../../apps/commerce-api/src/main/java/com/loopers/domain/brand/BrandName.java) | 생성 시 앞뒤 공백 정리, 필수값·코드 포인트 1~100자 검증, 유효한 이름의 불변 보관 |
| [BrandNameException](../../apps/commerce-api/src/main/java/com/loopers/domain/brand/BrandNameException.java) | EMPTY_NAME·NAME_TOO_LONG 업무 실패 사유. HTTP 상태와 분리 |
| [BrandNameTest](../../apps/commerce-api/src/test/java/com/loopers/domain/brand/BrandNameTest.java) | 공백 정리·빈 값·한글과 이모지의 길이 경계 13개 실행 사례 |

기존 Layer-first의 `domain/brand`에 배치하고 모듈을 추가하지 않았다. 자기 값만으로 판단하는 규칙이므로 repository나 application 서비스가 필요하지 않다. HTTP·JPA·다른 계층에 의존하지 않으며 저장 기술과의 DIP 연결은 후속 영속성 구현의 책임이다.

## 실제 Red → Green → Refactor

| 순서·대상 | 실제 확인한 Red 또는 추가 사례 결과 | 구현·재검증 |
| --- | --- | --- |
| 1 / BRAND-NAME-01 | 최소 record 골격이 앞뒤 공백을 그대로 보관하여 1개 중 1개 실패 | 생성자에서 strip 후 1개 통과 |
| 2 / BRAND-NAME-02 | null은 NPE, 빈 문자열·공백뿐인 입력은 예외 없이 생성되어 7개 중 6개 실패 | null 및 공백 제거 후 빈 값은 EMPTY_NAME으로 거절. 7개 통과 |
| 3 / BRAND-NAME-03·04 | 한글·이모지 1개·100개는 기존 구현으로 통과. 각각 101개인 두 입력도 생성되어 13개 중 2개 실패 | codePointCount로 길이를 검사하고 초과는 NAME_TOO_LONG으로 거절. 13개 통과 |
| 4 / Refactor | 100의 의미를 MAX_CODE_POINT_LENGTH로 명시하고 반복된 예외 사유 assertion을 테스트 보조 메서드로 추출 | 동일한 기대값으로 전체 검사 실행 |

이미 통과하는 사례를 인위적으로 실패시키지 않았고 기대값을 삭제·완화하지 않았다. 이모지 100개 허용 사례는 Java String.length의 UTF-16 길이와 코드 포인트 수를 혼동하는 구현을 검출한다. 내부 공백과 대소문자는 보존한다.

## 검사 결과와 후속 범위

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.domain.brand.BrandNameTest' --console=plain -q
./gradlew :apps:commerce-api:check --console=plain
```

리팩터링 후 전체 **73개 테스트**가 통과했다. 브랜드 이름 13개·기존 회귀 60개(ArchUnit 포함)이며 실패·오류·건너뜀은 0개다. Checkstyle main/test의 위반도 0개다. 변경 파일을 검토했고 `git diff --check`, 문서 8개의 로컬 링크 82개·앵커·코드 블록 검사도 통과했다.

이 단계는 브랜드 CRUD 완료가 아니다. 이후 [브랜드 모델 TDD](commerce-tdd-brand-log.md)에서 `Brand`의 생성·이름 변경, 논리 삭제 상태·재삭제·삭제 후 이름 변경 거절과 메모리 상태 보존을 구현했다. 미삭제 상품의 존재 결과에 따른 삭제 조건 서비스도 조회 대역으로 검증했다. 이후 [영속성 TDD](commerce-tdd-brand-persistence-log.md)에서 ID·실제 MySQL 저장·조회와 변경 감지·감사 시각을 검증했다. 관리자 권한·HTTP 오류 변환·DB 잠금과 수정·삭제 유스케이스는 후속 범위이며, API-16·18 전체의 DB 상태 보존도 해당 연결 때 검증한다.
