dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // API-specific producer; do not import the streamer's shared batch consumer configuration.
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.apache.httpcomponents.client5:httpclient5")
    // Explicitly align core with circuitbreaker; the older Spring Cloud BOM otherwise manages core 2.2.0.
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")
    implementation("io.github.resilience4j:resilience4j-core:2.3.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
}

plugins {
    checkstyle
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

// 기동 직후 측정의 자식 JVM에는 운영 의존성과 격리한 관측 클래스만 전달한다.
tasks.test {
    systemProperty("w4.startup.main-classpath", sourceSets.main.get().runtimeClasspath.asPath)
    useJUnitPlatform {
        excludeTags("ttl-measurement")
    }
}

// Test-only process orchestration: keep the two apps off each other's runtime classpath.
val streamerMain = project(":apps:commerce-streamer").extensions.getByType<SourceSetContainer>().named("main")
tasks.test {
    dependsOn(streamerMain.map { it.runtimeClasspath })
    inputs.files(streamerMain.map { it.runtimeClasspath })
        .withPropertyName("w6StreamerRuntimeClasspath").withNormalizer(ClasspathNormalizer::class.java)
    inputs.file(rootProject.file("apps/commerce-streamer/src/main/resources/db/schema/001-purchase-activity.sql"))
        .withPropertyName("w6StreamerSchema")
    doFirst {
        systemProperty("w6.streamer.main-classpath", streamerMain.get().runtimeClasspath.asPath)
        systemProperty("w6.streamer.schema", rootProject.file(
            "apps/commerce-streamer/src/main/resources/db/schema/001-purchase-activity.sql").absolutePath)
    }
}

// 실제 TTL 여러 주기를 기다리는 장시간 실험은 일반 회귀와 분리해 명시적으로 실행한다.
// 기존 테스트는 제외하지 않으며, 신규 측정 클래스만 이 태그를 사용한다.
tasks.register<Test>("ttlMeasurement") {
    group = "verification"
    description = "Measure TTL 5/30/60 seconds and Redis key memory using real application JVMs"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    maxParallelForks = 1
    useJUnitPlatform {
        includeTags("ttl-measurement")
    }
    systemProperty("w4.startup.main-classpath", sourceSets.main.get().runtimeClasspath.asPath)
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
    shouldRunAfter(tasks.test)
}
