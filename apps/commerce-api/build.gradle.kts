import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    checkstyle
}

// E2E 가 실제 streamer 실행물을 프로세스로 띄우므로 그 bootJar 를 먼저 만듦 (7주차 설계 10장)
evaluationDependsOn(":apps:commerce-streamer")
val streamerBootJar = project(":apps:commerce-streamer").tasks.named<BootJar>("bootJar")

tasks.test {
    dependsOn(streamerBootJar)
    systemProperty("commerce-streamer.boot-jar", streamerBootJar.get().archiveFile.get().asFile.absolutePath)
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

    // retry (낙관적 잠금 충돌 재시도, 3주차 설계 4.4)
    implementation("org.springframework.retry:spring-retry")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // PG 조회의 재조회 · 회로 차단 (5주차 설계 5장, D-64). 버전은 Spring Cloud BOM 이 관리함
    implementation("io.github.resilience4j:resilience4j-circuitbreaker")
    implementation("io.github.resilience4j:resilience4j-retry")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))

    // security
    testImplementation("org.springframework.security:spring-security-test")

    // architecture
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
}
