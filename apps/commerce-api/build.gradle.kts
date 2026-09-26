plugins {
    checkstyle
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

// DomainRulesTest 가 읽는 문서다. 입력으로 걸지 않으면 문서만 고쳤을 때
// test 가 UP-TO-DATE 로 건너뛰어 어긋남이 드러나지 않는다.
tasks.test {
    inputs.files(
        rootProject.file("docs/week2/domain-rules.yaml"),
        rootProject.file("docs/week2/requirements.md"),
    )
        .withPropertyName("domainRuleDocuments")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // admin boundary
    implementation("org.springframework.boot:spring-boot-starter-security")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))

    // architecture test
    testImplementation("com.tngtech.archunit:archunit:1.5.0")

    // admin HTTP test
    testImplementation("org.springframework.security:spring-security-test")

    // OpenAPI contract test
    testImplementation("com.atlassian.oai:swagger-request-validator-mockmvc:${project.properties["openApiRequestValidatorVersion"]}")
}
