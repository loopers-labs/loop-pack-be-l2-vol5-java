package com.loopers.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** commerce-api 와 같은 계층 규칙. Kafka(메시지 형식·ack)는 interfaces 와 config 에만 있고 업무 계층은 모른다. */
class ArchitectureTest {

    private static JavaClasses importClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.loopers");
    }

    @Test
    void respectsLayerDependencies() {
        var classes = importClasses();

        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..interfaces..", "..application..", "..infrastructure..")
                .check(classes);

        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..interfaces..", "..infrastructure..")
                .check(classes);

        noClasses().that().resideInAPackage("..interfaces..")
                .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                .check(classes);
    }

    /** interfaces 는 domain 을 import 하지 않는다. 메시지는 application 의 Command 로 넘긴다. */
    @Test
    void interfacesDoNotDependOnDomain() {
        noClasses().that().resideInAPackage("..interfaces..")
                .should().dependOnClassesThat().resideInAPackage("..domain..")
                .check(importClasses());
    }

    /** 업무 계층(domain·application)은 Kafka 를 모른다. ack·offset 은 listener 가, 재시도·중단은 config 가 맡는다. */
    @Test
    void businessLayersDoNotDependOnKafka() {
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().resideInAnyPackage("org.apache.kafka..", "org.springframework.kafka..")
                .check(importClasses());
    }
}
