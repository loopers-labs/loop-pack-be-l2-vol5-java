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
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")
    implementation("org.apache.httpcomponents.client5:httpclient5")

    // 관리자 경계. 운영 인증 수단이 아니라 /api-admin/** 을 고객 경로와 가르는 장치다(설계 9장).
    implementation("org.springframework.boot:spring-boot-starter-security")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))

    // architecture
    testImplementation("com.tngtech.archunit:archunit-junit5:${project.properties["archUnitVersion"]}")

    // security test
    testImplementation("org.springframework.security:spring-security-test")

    // resilience
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:${project.properties["resilience4jVersion"]}")
    implementation("io.github.resilience4j:resilience4j-bulkhead:${project.properties["resilience4jVersion"]}")
    implementation("io.github.resilience4j:resilience4j-micrometer:${project.properties["resilience4jVersion"]}")
}

// W5 8.5 장애 실험용: PG fixture 를 테스트 밖에서 띄우는 실행 jar (테스트 클래스 + Jackson)
tasks.register<Jar>("pgFixtureJar") {
    group = "verification"
    archiveFileName.set("pg-fixture.jar")
    dependsOn(tasks.named("testClasses"))
    from(sourceSets["test"].output)
    from({
        configurations["testRuntimeClasspath"]
            .filter { it.name.startsWith("jackson-") }
            .map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest { attributes["Main-Class"] = "com.loopers.support.pg.PgFixtureServer" }
}
