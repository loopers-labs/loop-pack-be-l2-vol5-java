dependencies {
    implementation("com.github.ben-manes.caffeine:caffeine")
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation("io.github.resilience4j:resilience4j-retry:2.3.0")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
}

// 설정·장애 조건을 바꿔 결과를 비교하는 실험 테스트(@Tag("experiment"))는 기능 검증과 목적이 달라 평소 test·check에서 뺀다.
// 연결 풀 실험처럼 수백 ms 차이에 결과가 걸린 것도 있어, 필요할 때 experimentTest로 따로 돌린다.
tasks.test {
    useJUnitPlatform {
        excludeTags("experiment")
    }
}

tasks.register<Test>("experimentTest") {
    description = "PG 조회 정책 실험 테스트(@Tag(\"experiment\"))만 실행한다."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    maxParallelForks = 1
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
    useJUnitPlatform {
        includeTags("experiment")
    }
}
