# Catalog constraints

작성일: 2026-10-12

기존 도메인 규칙을 DB에서도 보호한다. FK나 실험용 가격·재고 상한은 추가하지 않는다.

| 불변식 | 적용 제약 |
| --- | --- |
| INV-BRAND-01 | 이름 NOT NULL, VARCHAR(50), Java UTF-16 길이·trim 후 저장 상태 CHECK |
| INV-BRAND-02 | 활성 이름의 binary 생성 컬럼과 UNIQUE |
| INV-PRODUCT-06 | 이름 NOT NULL, VARCHAR(100), 같은 이름 유효성 CHECK |
| INV-PRODUCT-07 | 브랜드 ID + 활성 이름 binary 키 UNIQUE |
| INV-PRODUCT-08 | 가격 1~1,000,000,000 CHECK |
| INV-PRODUCT-09 | 브랜드 ID NOT NULL만 추가. 존재·삭제 상태는 서비스가 확인 |
| INV-STOCK-14 | 수량 0 이상 CHECK |
| INV-LIKE-24 | 기존 사용자–상품 UNIQUE 유지, 두 ID NOT NULL |

이름은 `OCTET_LENGTH(CONVERT(name USING utf16))`로 Java 코드 단위 길이를 검사한다. 양끝 문자가 U+0020 이하이면 정규화되지 않은 저장값으로 거절한다. 생성 컬럼 `active_name_key`는 미삭제 행의 이름을 binary로 변환하며, 삭제 행에서는 NULL이다. 대소문자·악센트·유니코드 정규화가 다른 문자열을 임의로 합치지 않는다.

## 파일과 실행

- `02-check-catalog-data.sql`: 기존 위반 데이터 조회.
- `03-add-catalog-column-constraints.sql`: 기본 컬럼·CHECK 제약.
- `04-add-active-name-uniqueness.sql`: 생성 컬럼·이름 UNIQUE.
- `apply-catalog-constraints.sh`: Python 적용 도구를 실행하는 진입점.
- `apply_catalog_constraints.py`: 모든 기존 정의와 데이터부터 확인하고 빠진 제약만 적용한다.

기존 개발 DB에 적용하는 명령:

```bash
MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER=application MYSQL_PWD=application \
  docs/week4/migrations/apply-catalog-constraints.sh loopers
```

앱의 쓰기를 중지한 상태에서 적용한다. 위반 데이터가 있으면 자동 삭제·수정하지 않고 중단한다. 기존에 같은 이름의 제약이 있어도 정의가 다르면 중단한다. DDL은 문장별로 commit되므로 실패 시 전체 rollback을 가정하지 않으며, 원인을 해결한 뒤 같은 명령으로 남은 변경을 적용한다.

새 DB는 엔티티 매핑이 생성 컬럼·UNIQUE까지 생성한다. 같은 스크립트로 재검증하면 변경 건수가 0이다. 개별 SQL을 무조건 다시 실행하지 않고 위 실행 도구를 사용한다.

## 실제 확인 결과

- 기본 DB `127.0.0.1:3306/loopers`: 사전 위반 데이터 0건, 13개 변경 적용. 데이터 삭제 없음.
- 같은 DB에 재실행: 변경 0건, 정의 검증 통과.
- 새 실험 DB `127.0.0.1:13307/perf_uniform_10k_v1`: Hibernate 생성 후 변경 0건, 같은 정의 검증 통과.
- 격리 DB에서 직접 SQL로 확인한 29건 통과: 가격·재고 경계, NULL, 빈 이름·탭·길이, 보충 유니코드 길이, 이름 중복, 대소문자·악센트·정규화 차이, 삭제 후 재사용, 다른 브랜드의 같은 상품명, 잘못된 UPDATE.
- 카탈로그 테이블의 FK 0개 유지. 기존 주문 테이블의 FK는 변경하지 않음.
- `:apps:commerce-api:check`: 테스트 367개, 실패·오류·skip 0개, Checkstyle·ArchUnit 통과.
- DB에서 발생한 브랜드·상품명 중복을 도메인 오류로 변환하고 HTTP 409를 반환하는 생성·수정 경로 확인. 다른 제약 위반을 이름 중복 오류로 변환하지 않는 것도 확인.

## 조회 캐시 revision

`05-add-read-cache-revision.sql`은 조회 캐시용 revision 테이블을 만들고 catalog revision을 갱신한다. 캐시 활성화·재활성화 전에 쓰기를 중지한 상태에서 별도로 적용한다. 기존 데이터·인덱스는 삭제하지 않는다. [캐시 ADR](../ADR.md)의 적용 조건을 따른다.
