package com.loopers.architecture;

import com.loopers.order.app.PaymentLookupAssessment;
import com.loopers.order.in.PaymentLookupScheduler;
import com.loopers.payment.app.PaymentGatewayRegistry;
import com.loopers.payment.app.PaymentLookupPolicy;
import com.loopers.payment.app.port.PaymentAnomalyRepository;
import com.loopers.payment.app.port.PaymentCreateResult;
import com.loopers.payment.app.port.PaymentGateway;
import com.loopers.payment.app.port.PaymentLookupScheduleRepository;
import com.loopers.payment.app.port.PaymentTransactionsLookup;
import com.loopers.payment.domain.LookupBackoff;
import com.loopers.payment.domain.PaymentRecord;
import com.loopers.payment.in.PaymentCallbackController;
import com.loopers.payment.out.CircuitBreakingPaymentGateway;
import com.loopers.payment.out.PaymentAnomalyJpaEntity;
import com.loopers.payment.out.PaymentAnomalyPersistenceAdapter;
import com.loopers.payment.out.PaymentCircuitBreakers;
import com.loopers.payment.out.PaymentLookupScheduleJpaEntity;
import com.loopers.payment.out.TestPgPaymentGateway;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

// 패키지는 업무 먼저 com.loopers.{context}.{in,app,domain,out}이다. (*)에는 common도 들어가 common.in·common.domain이 같은 규칙을 받는다.
// 규칙은 패키지 이름 패턴으로 계층을 가리므로, 이름이 틀린 클래스는 조용히 빠진다. 그래서 규칙 6이 검사 대상이 실제로 있는지 따로 본다.
// com.loopers.domain.BaseEntity(modules/jpa)는 패턴 com.loopers.(*).domain..과 맞지 않아 따로 제외할 필요가 없다.
class ArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.loopers");

    private static final String IN = "com.loopers.(*).in..";
    private static final String APP = "com.loopers.(*).app..";
    private static final String APP_PORT = "com.loopers.(*).app.port..";
    private static final String DOMAIN = "com.loopers.(*).domain..";
    private static final String OUT = "com.loopers.(*).out..";

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

    // domain은 순수 Java로 둔다. common.error는 여러 계층이 함께 쓰는 규칙 위반 신호라 허용하고,
    // support.error는 starter의 HTTP 응답용 CoreException이라 domain에서 쓰지 않는다.
    @Test
    @DisplayName("domain은 Spring·JPA·HTTP·servlet·support.error에 의존하지 않는다")
    void domainRemainsIndependent() {
        noClasses().that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "javax.persistence..",
                "jakarta.servlet..", "java.net.http..", "com.loopers.support.error..")
            .check(CLASSES);
    }

    // HTTP 클라이언트·JSON·서킷 브레이커는 out의 구현 세부다. app·domain에 새면 외부 연동 방식이 업무 코드로 번진다.
    @Test
    @DisplayName("app과 domain은 HTTP 클라이언트·네트워크·JSON·Resilience4j 타입에 의존하지 않는다")
    void coreLayersDoNotUseHttpClients() {
        noClasses().that().resideInAnyPackage(APP, DOMAIN)
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
            "user", List.of("app", "out"),
            "point", List.of("in", "app", "domain", "out"),
            "product", List.of("in", "app", "domain", "out"),
            "payment", List.of("in", "app", "domain", "out"),
            "order", List.of("in", "app", "domain", "out"));

        layers.forEach((context, contextLayers) -> contextLayers.forEach(layer -> {
            String layerPackage = "com.loopers." + context + "." + layer + "..";
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
}
