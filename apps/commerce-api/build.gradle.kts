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
