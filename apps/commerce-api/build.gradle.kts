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
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // security (관리자 경로 인가)
    implementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.security:spring-security-test")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // architecture
    testImplementation("com.tngtech.archunit:archunit:1.5.0")

    // acceptance (실제 포트로 HTTP 요청 — 버전은 Spring Boot가 관리)
    testImplementation("io.rest-assured:rest-assured")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}
