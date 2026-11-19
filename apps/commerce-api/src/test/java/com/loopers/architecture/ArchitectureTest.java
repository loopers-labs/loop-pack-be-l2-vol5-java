package com.loopers.architecture;

import com.loopers.domains.order.app.PaymentLookupAssessment;
import com.loopers.domains.order.app.port.OrderConfirmedOutbox;
import com.loopers.domains.order.in.PaymentLookupScheduler;
import com.loopers.domains.order.out.OrderConfirmedOutboxJpaEntity;
import com.loopers.domains.order.out.OrderConfirmedOutboxJpaRepository;
import com.loopers.domains.order.out.OrderConfirmedOutboxPersistenceAdapter;
import com.loopers.domains.payment.app.PaymentGatewayRegistry;
import com.loopers.domains.payment.app.PaymentLookupPolicy;
import com.loopers.domains.payment.app.port.PaymentAnomalyRepository;
import com.loopers.domains.payment.app.port.PaymentCreateResult;
import com.loopers.domains.payment.app.port.PaymentGateway;
import com.loopers.domains.payment.app.port.PaymentLookupScheduleRepository;
import com.loopers.domains.payment.app.port.PaymentTransactionsLookup;
import com.loopers.domains.payment.domain.LookupBackoff;
import com.loopers.domains.payment.domain.PaymentRecord;
import com.loopers.domains.payment.in.PaymentCallbackController;
import com.loopers.domains.payment.out.CircuitBreakingPaymentGateway;
import com.loopers.domains.payment.out.PaymentAnomalyJpaEntity;
import com.loopers.domains.payment.out.PaymentAnomalyPersistenceAdapter;
import com.loopers.domains.payment.out.PaymentCircuitBreakers;
import com.loopers.domains.payment.out.PaymentLookupScheduleJpaEntity;
import com.loopers.domains.payment.out.TestPgPaymentGateway;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

// 업무 패키지는 com.loopers.domains.{context}.{in,app,domain,out,config}이다. common은 이 패턴에 들지 않아 규칙 1~5를 받지 않고,
// 규칙 8과 common 규칙으로 따로 검사한다.
// 규칙은 패키지 이름 패턴으로 계층을 가리므로, 이름이 틀린 클래스는 조용히 빠진다. 그래서 규칙 6이 검사 대상이 실제로 있는지 따로 본다.
// com.loopers.domain.BaseEntity(modules/jpa)는 패턴 com.loopers.domains.(*).domain..과 맞지 않아 따로 제외할 필요가 없다.
class ArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.loopers");

    private static final String IN = "com.loopers.domains.(*).in..";
    private static final String APP = "com.loopers.domains.(*).app..";
    private static final String APP_PORT = "com.loopers.domains.(*).app.port..";
    private static final String DOMAIN = "com.loopers.domains.(*).domain..";
    private static final String OUT = "com.loopers.domains.(*).out..";
    private static final String CONFIG = "com.loopers.domains.(*).config..";
    private static final String DOMAINS = "com.loopers.domains..";
    private static final String COMMON = "com.loopers.common..";
    private static final String COMMON_DOMAIN = "com.loopers.common.domain..";
    private static final String COMMON_ERROR = "com.loopers.common.error..";
    private static final String COMMON_IN = "com.loopers.common.in..";
    private static final String COMMON_CONFIG = "com.loopers.common.config..";

    // 규칙 1: 업무 규칙이 입출력·저장 방식에 끌려 바뀌지 않게 domain은 바깥 계층을 모른다.
    @Test
    @DisplayName("domain은 app·in·out에 의존하지 않는다")
    void domainDoesNotKnowOuterLayers() {
        noClasses().that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(APP, IN, OUT)
            .check(CLASSES);
    }

    // 규칙 2: 입력 계층은 유즈케이스(app)만 부른다. domain·저장 구현을 직접 쓰면 app의 트랜잭션·규칙을 우회한다.
    @Test
    @DisplayName("in은 domain·out에 의존하지 않고 app만 쓴다")
    void inUsesAppOnly() {
        noClasses().that().resideInAPackage(IN)
            .should().dependOnClassesThat().resideInAnyPackage(DOMAIN, OUT)
            .check(CLASSES);
    }

    // 규칙 3: app은 포트로만 바깥과 연결된다. 입력 방식·저장 구현이 바뀌어도 app은 그대로다.
    @Test
    @DisplayName("app은 in·out에 의존하지 않는다")
    void appDoesNotKnowAdapters() {
        noClasses().that().resideInAPackage(APP)
            .should().dependOnClassesThat().resideInAnyPackage(IN, OUT)
            .check(CLASSES);
    }

    // 규칙 4: 출력 어댑터가 입력 어댑터를 부르면 바깥끼리 엉켜 app을 거치지 않는 흐름이 생긴다.
    @Test
    @DisplayName("out은 in에 의존하지 않는다")
    void outDoesNotKnowIn() {
        noClasses().that().resideInAPackage(OUT)
            .should().dependOnClassesThat().resideInAPackage(IN)
            .check(CLASSES);
    }

    // 규칙 5: 저장·외부 연결 구현은 포트(인터페이스와 그 인자·결과)만 안다. 서비스를 바꿔도 구현이 같이 깨지지 않게 경계를 한 곳에 모은다.
    @Test
    @DisplayName("out은 app 중 app.port만 쓴다")
    void outUsesAppPortOnly() {
        noClasses().that().resideInAPackage(OUT)
            .should().dependOnClassesThat(resideInAPackage(APP).and(not(resideInAPackage(APP_PORT))))
            .check(CLASSES);
    }

    // domain(업무 domain과 common.domain)은 순수 Java로 둔다. common.error는 여러 계층이 함께 쓰는 규칙 위반 신호라 허용하고,
    // common.in.error는 HTTP 응답용 CoreException(옛 starter support.error)이라 domain에서 쓰지 않는다.
    @Test
    @DisplayName("domain과 common.domain은 Spring·JPA·HTTP·servlet·common.in.error에 의존하지 않는다")
    void domainRemainsIndependent() {
        noClasses().that().resideInAnyPackage(DOMAIN, COMMON_DOMAIN)
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "javax.persistence..",
                "jakarta.servlet..", "java.net.http..", "com.loopers.common.in.error..")
            .check(CLASSES);
    }

    // HTTP 클라이언트·JSON·서킷 브레이커는 out의 구현 세부다. app·domain과 공통 값·예외(common.domain·common.error)에 새면 외부 연동 방식이 업무 코드로 번진다.
    @Test
    @DisplayName("app·domain·common.domain·common.error는 HTTP 클라이언트·네트워크·JSON·Resilience4j 타입에 의존하지 않는다")
    void coreLayersDoNotUseHttpClients() {
        noClasses().that().resideInAnyPackage(APP, DOMAIN, COMMON_DOMAIN, COMMON_ERROR)
            .should().dependOnClassesThat()
            .resideInAnyPackage("java.net..", "org.springframework.web..", "org.springframework.http..",
                "com.fasterxml.jackson..", "io.github.resilience4j..")
            .check(CLASSES);
    }

    // 규칙 6: 패키지 이름이 틀리면 규칙 1~5가 그 클래스를 검사하지 않고 통과한다. 컨텍스트마다 있는 계층이 실제로 비어 있지 않은지 본다.
    @Test
    @DisplayName("컨텍스트마다 있는 계층의 클래스가 계층 검사 대상에 실제로 들어간다")
    void everyContextLayerIsChecked() {
        Map<String, List<String>> layers = Map.of(
            "brand", List.of("in", "app", "domain", "out"),
            "like", List.of("app", "domain", "out"),
            "user", List.of("in", "app", "out"),
            "point", List.of("in", "app", "domain", "out"),
            "product", List.of("in", "app", "domain", "out"),
            "payment", List.of("in", "app", "domain", "out"),
            "order", List.of("in", "app", "domain", "out"),
            "coupon", List.of("in", "app", "domain", "out"));

        layers.forEach((context, contextLayers) -> contextLayers.forEach(layer -> {
            String layerPackage = "com.loopers.domains." + context + "." + layer + "..";
            String rulePattern = switch (layer) {
                case "in" -> IN;
                case "app" -> APP;
                case "domain" -> DOMAIN;
                default -> OUT;
            };
            JavaClasses found = CLASSES.that(resideInAPackage(layerPackage));
            assertThat(found).as(layerPackage + " 클래스").isNotEmpty();
            // 규칙 1~5가 쓰는 패턴 상수의 오타를 막는다. 패턴이 틀리면 이 계층 클래스가 규칙에서 빠져 크기가 달라진다.
            assertThat(found.that(resideInAPackage(rulePattern))).as(layerPackage + " → " + rulePattern).hasSameSizeAs(found);
        }));
        for (String context : List.of("payment", "order")) {
            String configPackage = "com.loopers.domains." + context + ".config..";
            JavaClasses found = CLASSES.that(resideInAPackage(configPackage));
            assertThat(found).as(configPackage + " 클래스").isNotEmpty();
            assertThat(found.that(resideInAPackage(CONFIG))).as(configPackage + " → " + CONFIG).hasSameSizeAs(found);
        }
        for (String commonPackage : List.of(COMMON_DOMAIN, COMMON_ERROR, COMMON_IN, "com.loopers.common.in.error..", COMMON_CONFIG)) {
            JavaClasses found = CLASSES.that(resideInAPackage(commonPackage));
            assertThat(found).as(commonPackage + " 클래스").isNotEmpty();
            assertThat(found.that(resideInAPackage(COMMON))).as(commonPackage + " → " + COMMON).hasSameSizeAs(found);
        }
    }

    // 규칙 7(a): 업무 config는 그 업무의 배치도다. 다른 업무의 클래스를 엮으면 업무 사이 결합이 조립 지점에 숨어 경계 정리 때 보이지 않는다.
    // 업무 목록을 따로 두지 않고 패키지 이름에서 업무를 읽어, 새 업무의 config도 자동으로 검사한다.
    @Test
    @DisplayName("업무 config는 자기 업무 밖의 domains 패키지에 의존하지 않는다")
    void configKnowsOwnContextOnly() {
        classes().that().resideInAPackage(CONFIG)
            .should(dependOnOwnContextOnly())
            .check(CLASSES);
    }

    // 규칙 7(b): 조립은 바깥에서 한다. in·app·domain·out이 config를 알면 조립 방식이 바뀔 때 업무 코드가 따라 바뀐다.
    @Test
    @DisplayName("in·app·domain·out은 업무 config와 common.config에 의존하지 않는다")
    void layersDoNotKnowConfig() {
        noClasses().that().resideInAnyPackage(IN, APP, DOMAIN, OUT)
            .should().dependOnClassesThat().resideInAnyPackage(CONFIG, COMMON_CONFIG)
            .check(CLASSES);
    }

    // 규칙 8: common은 여러 업무가 함께 쓰는 바닥이다. common이 특정 업무를 알면 업무를 추가·삭제할 때 common이 따라 바뀐다.
    @Test
    @DisplayName("common은 domains에 의존하지 않는다")
    void commonDoesNotKnowDomains() {
        noClasses().that().resideInAPackage(COMMON)
            .should().dependOnClassesThat().resideInAPackage(DOMAINS)
            .check(CLASSES);
    }

    // 공통 값·예외는 HTTP 공통과 조립보다 아래에 있다. common.domain·common.error가 common.in·common.config를 알면 domain이 그것을 통해 HTTP·조립을 알게 된다.
    @Test
    @DisplayName("common.domain과 common.error는 common.in과 common.config에 의존하지 않는다")
    void commonCoreDoesNotKnowCommonAdapters() {
        noClasses().that().resideInAnyPackage(COMMON_DOMAIN, COMMON_ERROR)
            .should().dependOnClassesThat().resideInAnyPackage(COMMON_IN, COMMON_CONFIG)
            .check(CLASSES);
    }

    @Test
    @DisplayName("PG 포트·결과·레지스트리와 어댑터가 계층 검사 대상에 포함된다")
    void paymentClassesAreChecked() {
        JavaClasses in = CLASSES.that(resideInAPackage(IN));
        JavaClasses app = CLASSES.that(resideInAPackage(APP));
        JavaClasses domain = CLASSES.that(resideInAPackage(DOMAIN));
        JavaClasses out = CLASSES.that(resideInAPackage(OUT));

        assertThat(app.contain(PaymentGateway.class)).isTrue();
        assertThat(app.contain(PaymentLookupAssessment.class)).isTrue();
        assertThat(app.contain(PaymentCreateResult.Unknown.class)).isTrue();
        assertThat(app.contain(PaymentGatewayRegistry.class)).isTrue();
        assertThat(out.contain(TestPgPaymentGateway.class)).isTrue();
        assertThat(app.contain(PaymentAnomalyRepository.class)).isTrue();
        assertThat(app.contain(PaymentTransactionsLookup.class)).isTrue();
        assertThat(out.contain(PaymentAnomalyJpaEntity.class)).isTrue();
        assertThat(out.contain(PaymentAnomalyPersistenceAdapter.class)).isTrue();
        assertThat(in.contain(PaymentCallbackController.class)).isTrue();
        assertThat(app.contain(PaymentLookupPolicy.class)).isTrue();
        assertThat(app.contain(PaymentLookupScheduleRepository.class)).isTrue();
        assertThat(domain.contain(LookupBackoff.class)).isTrue();
        assertThat(domain.contain(PaymentRecord.TransactionMatch.class)).isTrue();
        assertThat(out.contain(PaymentLookupScheduleJpaEntity.class)).isTrue();
        assertThat(in.contain(PaymentLookupScheduler.class)).isTrue();
        assertThat(out.contain(CircuitBreakingPaymentGateway.class)).isTrue();
        assertThat(out.contain(PaymentCircuitBreakers.class)).isTrue();
        assertThat(app.contain(PaymentTransactionsLookup.NotCalled.class)).isTrue();
    }

    @Test
    @DisplayName("주문 확정 Outbox 포트는 domains.order.app.port에, 어댑터·엔티티·JPA 리포지토리는 domains.order.out에 있어 계층 검사 대상에 포함된다")
    void orderOutboxClassesAreChecked() {
        JavaClasses appPort = CLASSES.that(resideInAPackage(APP_PORT));
        JavaClasses out = CLASSES.that(resideInAPackage(OUT));

        assertThat(appPort.contain(OrderConfirmedOutbox.class)).isTrue();
        assertThat(out.contain(OrderConfirmedOutboxPersistenceAdapter.class)).isTrue();
        assertThat(out.contain(OrderConfirmedOutboxJpaEntity.class)).isTrue();
        assertThat(out.contain(OrderConfirmedOutboxJpaRepository.class)).isTrue();
    }

    private static ArchCondition<JavaClass> dependOnOwnContextOnly() {
        return new ArchCondition<JavaClass>("자기 업무 밖의 domains 패키지에 의존하지 않는다") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String owner = contextOf(item.getPackageName());
                item.getDirectDependenciesFromSelf().forEach(dependency -> {
                    String target = dependency.getTargetClass().getPackageName();
                    if (target.startsWith("com.loopers.domains.") && !contextOf(target).equals(owner)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                });
            }
        };
    }

    private static String contextOf(String packageName) {
        return packageName.split("\\.")[3];
    }
}
