plugins {
    checkstyle
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":modules:kafka"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // PG 조회의 재시도·회로 차단. annotation 은 AOP 프록시로 동작하므로 aop 스타터가 필요하다. 스타터가 이미 있다고 가정하지 않고 버전을 명시한다.
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:${project.properties["resilience4jVersion"]}")

    // querydsl 어노테이션 프로세서(querydsl-apt 5.1.0)는 @Embeddable record 를 처리하지 못해 두지 않는다.
    // QueryDSL 쿼리가 필요해지면 record 를 지원하는 OpenFeign 포크(io.github.openfeign.querydsl)로 교체한다.

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))

    testImplementation("org.springframework.security:spring-security-test")

    // architecture test
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
}
