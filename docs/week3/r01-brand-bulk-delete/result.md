# R01 구현 결과

[요구사항](requirement.md) · [트레이드오프](trade_off/total_trade_off.md) · [구현 계획](plan.md) · [전체 요구사항](../total_requirement.md)

## 1. 구현 결과

`plan.md`의 커밋 1~5를 그대로 따라 구현했다. 트레이드오프에서 합의한 설계와 실제 구현이 일치한다.

- **도메인**: `Brand`가 `List<Product> products` 필드를 갖고, 삭제 전용 조회로만 채워지는 `Brand.restoreForDeletion(...)`을 신설했다.
  `Brand.delete()`가 자신을 삭제 처리한 뒤 `products` 중 미삭제 상품만 `Product.delete()`로 함께 삭제한다.
  `products == null`은 `IllegalArgumentException`으로 구조적으로 거부해 "조회하지 않은 상태를 빈 컬렉션으로 취급"하는 실수를 막았다.
- **저장소**: `BrandJpaEntity`에 조회 전용 단방향 `@OneToMany(brand_id, NO_CONSTRAINT)`를 추가하고,
  `BrandJpaRepository.findForDeletion`이 `LEFT JOIN FETCH ... ORDER BY p.id`로 브랜드와 소속 상품 전체를 한 번에 읽는다.
  `BrandRepositoryImpl.save(Brand)`가 기존 브랜드 저장 시 `brand.getProducts()`를 순회하며 상품마다 `saveAndFlush`로 즉시 반영한 뒤
  마지막에 브랜드 자신을 `saveAndFlush`한다.
- **애플리케이션**: `BrandService.execute(BrandCommand.Delete)`가 "활성 상품 존재 시 거절"(`ActiveProductChecker`,
  `BRAND_HAS_ACTIVE_PRODUCTS`)을 제거하고 `findForDeletion → delete → save` 흐름으로 교체했다. 기존 `@Transactional` 경계를 그대로 사용한다.
- **소비 경로**: 상품 목록/상세/좋아요목록/좋아요등록/주문생성/주문확정/상품수정·재고변경은 모두 기존부터 `products.deleted` 플래그
  하나만 보고 있었기 때문에, 새 코드 없이 회귀 테스트로 동작을 재확인하는 것으로 충분했다(예외: 좋아요 취소는 원래부터 상태 검사가 없어 그대로 동작).

### 실제 호출 경로 (완료 조건 2)

```
DELETE /api-admin/v1/brands/{brandId}
  → AdminBrandController.delete(brandId)              [interfaces]
  → DeleteBrandUseCase(proxy) = BrandService           [application, @Transactional 진입점]
      → BrandRepository.findForDeletion(brandId)       [domain 계약 → BrandRepositoryImpl]
          → BrandJpaRepository.findForDeletion         [LEFT JOIN FETCH, SELECT 1회]
          → BrandEntityMapper.toDomainForDeletion       [ProductEntityMapper로 상품 각각 변환]
      → Brand.delete()                                  [순수 도메인, 메모리 상태만 변경]
      → BrandRepository.save(brand)                     [BrandRepositoryImpl]
          → 상품별: ProductJpaRepository.findById → ProductEntityMapper.apply → saveAndFlush (UPDATE, 상품 수만큼)
          → BrandJpaRepository.saveAndFlush(brandEntity) (UPDATE, 1회)
  → 예외 발생 시 BrandService.execute 메서드 경계(Spring 프록시)에서 RuntimeException으로 트랜잭션 rollback-only 마킹
  → 컨트롤러까지 예외 전파 → ApiControllerAdvice/ApiErrorMapper가 HTTP 응답으로 변환
```

`@Transactional`은 `BrandService.execute` 메서드에만 있고 `Brand.delete()`/`BrandRepositoryImpl` 내부에는 별도 트랜잭션 경계가 없어
자기호출(self-invocation) 프록시 우회 문제가 없다 — 모든 저장 호출이 프록시를 통과한 서비스 메서드 하나의 트랜잭션 안에서 일어난다.

## 2. 실제 검증 결과

- 커밋마다 관련 테스트 + Checkstyle을 실행했고, 마지막에 `./gradlew :apps:commerce-api:check` 전체 게이트(빌드+테스트+Checkstyle+ArchUnit)를 실행해 **BUILD SUCCESSFUL**을 확인했다.
- 신규/보강 테스트: 도메인 6건, 저장소 통합 3건, 브랜드 API 4건 변경/추가, 롤백 통합 1건, 상품/좋아요/주문 관련 회귀·보강 5건.
- 롤백 테스트(`DeleteBrandRollbackIntegrationTest`)는 `@MockitoSpyBean`으로 `ProductJpaRepository`를 감싸 상품A의 `saveAndFlush`(실제 UPDATE)가
  성공한 뒤 상품B의 `saveAndFlush`에서 예외를 주입했다. `deleteBrandUseCase.execute(...)`를 직접 호출해 `@Transactional` 프록시 경계를
  실제로 통과시켰고, 검증은 트랜잭션이 끝난 뒤 별도 `findById` 조회로 확인했다(같은 관리 객체 재확인 아님).
- ArchUnit(`LayerArchitectureTest`, `DomainPurityArchitectureTest`)은 `Brand`가 `Product`를 참조하는 것과
  `infrastructure.mall.brand`가 `infrastructure.mall.product`를 참조하는 것 모두 위반 없이 통과했다.

## 3. 계획과의 차이

- **저장 순서 결정 방식**: `plan.md`에는 명시돼 있지 않았지만, 저장 순서를 예측 가능하게 하려고 `findForDeletion` JPQL에
  `ORDER BY p.id`를 추가했다. 정렬이 없으면 롤백 테스트의 "상품A 먼저 성공, 상품B에서 실패" 순서를 보장할 수 없었다.
- **예상 밖 스키마 회귀**: `@OneToMany` 추가로 테스트 프로필(`ddl-auto: create`)이 `products.brand_id`에 없던 물리적 FK 제약을 새로
  생성해 기존 `ProductRepositoryIntegrationTest`(임의 brandId 사용)가 깨졌다. `@JoinColumn(foreignKey = @ForeignKey(NO_CONSTRAINT))`로
  기존과 동일하게 물리적 FK 없는 상태를 유지해 해결했다. 이 프로젝트가 `products.brand_id`에 원래부터 DB 레벨 참조 무결성을
  두지 않는다는 전제를 트레이드오프 문서 작성 시점에는 명시적으로 검토하지 않았던 부분이다.
- 그 외 트레이드오프 문서의 결정(도메인 소유, 삭제 전용 조회, 브랜드 단위 저장, 기존 관리 엔티티 반영, 조회용 단방향 매핑)은 계획대로 구현됐다.

## 4. 남은 문제

- 브랜드에 연결된 상품 수가 많을 때의 성능(상품별 순차 `saveAndFlush`)은 검증하지 않았다 — 트레이드오프 문서에서 이미 "구체적 필요가 생기면 재검토"로 범위 밖으로 뒀다.
- `BrandRepositoryImpl.save()`에서 `productJpaRepository.findById`가 1차 캐시로 추가 SELECT 없이 처리되는지는 로그 레벨로 직접 확인하지 않았다(코드 구조상 같은 영속성 컨텍스트이므로 이론적으로는 추가 SELECT가 없어야 한다).
- 브랜드 삭제와 상품 등록의 동시 실행 등 경쟁 제어는 요구사항에서 명시적으로 제외됐으며 R01 범위에 포함하지 않았다.

## 5. 회고

- 요구사항·트레이드오프 문서가 이미 핵심 설계 결정(도메인 소유, 조회 전략, 저장 단위, 매핑 방식)을 구체적으로 확정해둔 덕분에
  구현 단계에서 새로 논의가 필요했던 지점은 롤백 테스트의 실패 주입 방식 하나뿐이었다.
- `ddl-auto: create`에서 JPA 연관관계 추가가 암묵적으로 DB 제약을 바꿀 수 있다는 점은 트레이드오프 단계에서 미리 점검했더라면
  더 빨리 발견했을 문제다. 이후 유사한 연관관계 추가 시 스키마 영향(특히 FK 제약 생성 여부)을 먼저 확인하는 편이 안전하다.
