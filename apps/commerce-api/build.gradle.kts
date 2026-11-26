plugins {
    checkstyle
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

tasks.register<JavaExec>("seedProductQuery") {
    group = "application"
    description = "loopers DB에 상품 조회 seed를 적재하고 유지한다."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.loopers.fixture.ProductQuerySeedRunner")
}

// Resilience4j의 직접, 전이 의존성 버전을 함께 맞춘다.
dependencyManagement {
    imports {
        mavenBom("io.github.resilience4j:resilience4j-bom:${project.properties["resilience4jVersion"]}")
    }
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
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // 직접 decorator에 사용하는 조회 정책 모듈만 추가하고 버전은 위 BOM으로 관리한다.
    implementation("io.github.resilience4j:resilience4j-circuitbreaker")
    implementation("io.github.resilience4j:resilience4j-retry")
    implementation("io.github.resilience4j:resilience4j-ratelimiter")
    implementation("io.github.resilience4j:resilience4j-timelimiter")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:mysql")
}

// PG 계약 테스트는 로컬 테스트 PG와 같은 이미지를 Testcontainers로 띄운다.
// 테스트 PG JAR와 이관 SQL이 바뀌면 테스트를 다시 실행하도록 입력으로 등록한다.
tasks.test {
    dependsOn(":apps:test-pg:bootJar")
    val fixtureDockerfile = rootProject.file("apps/test-pg/Dockerfile")
    val fixtureJar = rootProject.file("apps/test-pg/build/libs/test-pg.jar")
    val safetyMigration = rootProject.file("docker/mysql/pg-payment-safety-migration.sql")
    val conflictMigration = rootProject.file("docker/mysql/pg-payment-conflict-migration.sql")
    val confirmedAtMigration = rootProject.file("docker/mysql/order-confirmed-at-migration.sql")
    val couponMigration = rootProject.file("docker/mysql/coupon-migration.sql")
    systemProperty("test-pg.dockerfile", fixtureDockerfile.absolutePath)
    systemProperty("test-pg.jar", fixtureJar.absolutePath)
    systemProperty("payment.safety-migration", safetyMigration.absolutePath)
    systemProperty("payment.conflict-migration", conflictMigration.absolutePath)
    systemProperty("order.confirmed-at-migration", confirmedAtMigration.absolutePath)
    systemProperty("coupon.migration", couponMigration.absolutePath)
    inputs.files(fixtureDockerfile, fixtureJar, safetyMigration, conflictMigration, confirmedAtMigration)
    inputs.file(couponMigration)
    // 결제 응답 유실 사고 연결 테스트는 다른 앱 JAR를 실행하므로 incidentTest에서만 돌린다.
    useJUnitPlatform { excludeTags("incident") }
}

// 결제 응답 유실 사고를 결제부터 주간 랭킹까지 한 테스트로 잇는다. streamer·batch는 실제 bootJar를 자식 프로세스로 띄운다.
// 다른 앱 JAR에 대한 빌드 의존과 입력 등록은 이 task에만 둔다. 일반 test는 이 앱들의 빌드와 무관하다.
val incidentApps = listOf(":apps:commerce-streamer", ":apps:commerce-batch")
val incidentTest by tasks.registering(Test::class) {
    description = "결제 응답 유실 사고를 결제부터 주간 랭킹까지 잇는 연결 테스트"
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("incident") }
    maxParallelForks = 1
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("spring.profiles.active", "test")
    jvmArgs("-Xshare:off")
    shouldRunAfter(tasks.test)

    dependsOn(":apps:test-pg:bootJar")
    incidentApps.forEach { dependsOn("$it:bootJar") }
    val fixtureDockerfile = rootProject.file("apps/test-pg/Dockerfile")
    val fixtureJar = rootProject.file("apps/test-pg/build/libs/test-pg.jar")
    val initSql = rootProject.file("docker/mysql/init.sql")
    systemProperty("test-pg.dockerfile", fixtureDockerfile.absolutePath)
    systemProperty("test-pg.jar", fixtureJar.absolutePath)
    systemProperty("incident.init-sql", initSql.absolutePath)
    systemProperty("incident.evidence-dir", layout.buildDirectory.dir("incident-evidence").get().asFile.absolutePath)
    inputs.files(fixtureDockerfile, fixtureJar, initSql)
    // streamer·batch JAR 이름에는 커밋 해시가 붙으므로, 다른 프로젝트 설정이 끝난 실행 시점에 bootJar 출력을 읽는다.
    incidentApps.forEach { path ->
        inputs.files(provider { project(path).tasks.getByName("bootJar").outputs.files })
    }
    doFirst {
        systemProperty("incident.streamer-jar",
            project(":apps:commerce-streamer").tasks.getByName("bootJar").outputs.files.singleFile.absolutePath)
        systemProperty("incident.batch-jar",
            project(":apps:commerce-batch").tasks.getByName("bootJar").outputs.files.singleFile.absolutePath)
    }
    // 태그로 고른 테스트가 0건이어도 Gradle은 실패하지 않는다. 0건은 통과가 아니므로 직접 실패시킨다.
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ descriptor, result ->
        if (descriptor.parent == null && result.testCount == 0L) {
            throw GradleException("incidentTest가 실행한 테스트가 0건입니다.")
        }
    }))
}

tasks.check {
    dependsOn(incidentTest)
}
