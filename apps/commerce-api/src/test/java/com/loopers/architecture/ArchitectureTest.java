package com.loopers.architecture;

import com.loopers.order.app.PaymentLookupAssessment;
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
import com.loopers.payment.out.PaymentLookupScheduler;
import com.loopers.payment.out.TestPgPaymentGateway;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.loopers");

    @Test
    @DisplayName("도메인은 외부 계층과 Spring·JPA·HTTP에 의존하지 않는다")
    void domainRemainsIndependent() {
        var domain = CLASSES.that(resideInAPackage("..domain.."))
            .that(describe("신규 도메인", type -> !isLegacyExample(type.getPackageName())
                && !type.getName().equals("com.loopers.domain.BaseEntity")));
        noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..application..", "..infrastructure..",
                "org.springframework..", "jakarta.persistence..", "javax.persistence..",
                "jakarta.servlet..", "java.net.http..", "..support.error..")
            .check(domain);
    }

    @Test
    @DisplayName("애플리케이션은 HTTP 계층과 저장 구현에 의존하지 않는다")
    void applicationUsesPorts() {
        var application = CLASSES.that(resideInAPackage("..application.."))
            .that(describe("Example 제외", type -> !isLegacyExample(type.getPackageName())));

        noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..infrastructure..")
            .check(application);
    }

    @Test
    @DisplayName("HTTP 계층은 저장 어댑터를 직접 참조하지 않는다")
    void interfacesDoNotAccessInfrastructure() {
        var interfaces = CLASSES.that(resideInAPackage("..interfaces.."))
            .that(describe("Example 제외", type -> !isLegacyExample(type.getPackageName())));

        noClasses().should().dependOnClassesThat()
            .resideInAPackage("..infrastructure..")
            .check(interfaces);
    }

    @Test
    @DisplayName("애플리케이션과 도메인은 HTTP 클라이언트·네트워크·JSON 타입에 의존하지 않는다")
    void coreLayersDoNotUseHttpClients() {
        var coreLayers = CLASSES.that(resideInAnyPackage("..application..", "..domain.."))
            .that(describe("Example·BaseEntity 제외", type -> !isLegacyExample(type.getPackageName())
                && !type.getName().equals("com.loopers.domain.BaseEntity")));

        noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("java.net..", "org.springframework.web..", "org.springframework.http..",
                "com.fasterxml.jackson..", "io.github.resilience4j..")
            .check(coreLayers);
    }

    @Test
    @DisplayName("PG 포트·결과·레지스트리와 어댑터가 계층 검사 대상에 포함된다")
    void paymentClassesAreChecked() {
        var application = CLASSES.that(resideInAPackage("..application.."));
        var infrastructure = CLASSES.that(resideInAPackage("..infrastructure.."));

        assertThat(application.contain(PaymentGateway.class)).isTrue();
        assertThat(application.contain(PaymentLookupAssessment.class)).isTrue();
        assertThat(application.contain(PaymentCreateResult.Unknown.class)).isTrue();
        assertThat(application.contain(PaymentGatewayRegistry.class)).isTrue();
        assertThat(infrastructure.contain(TestPgPaymentGateway.class)).isTrue();
        assertThat(application.contain(PaymentAnomalyRepository.class)).isTrue();
        assertThat(application.contain(PaymentTransactionsLookup.class)).isTrue();
        assertThat(infrastructure.contain(PaymentAnomalyJpaEntity.class)).isTrue();
        assertThat(infrastructure.contain(PaymentAnomalyPersistenceAdapter.class)).isTrue();
        assertThat(CLASSES.contain(PaymentCallbackController.class)).isTrue();
        assertThat(application.contain(PaymentLookupPolicy.class)).isTrue();
        assertThat(application.contain(PaymentLookupScheduleRepository.class)).isTrue();
        assertThat(CLASSES.contain(LookupBackoff.class)).isTrue();
        assertThat(CLASSES.contain(PaymentRecord.TransactionMatch.class)).isTrue();
        assertThat(infrastructure.contain(PaymentLookupScheduleJpaEntity.class)).isTrue();
        assertThat(infrastructure.contain(PaymentLookupScheduler.class)).isTrue();
        assertThat(infrastructure.contain(CircuitBreakingPaymentGateway.class)).isTrue();
        assertThat(infrastructure.contain(PaymentCircuitBreakers.class)).isTrue();
        assertThat(application.contain(PaymentTransactionsLookup.NotCalled.class)).isTrue();
    }

    private static boolean isLegacyExample(String packageName) {
        return packageName.contains(".example.") || packageName.endsWith(".example");
    }
}
