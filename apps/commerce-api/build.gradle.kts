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
    systemProperty("test-pg.dockerfile", fixtureDockerfile.absolutePath)
    systemProperty("test-pg.jar", fixtureJar.absolutePath)
    systemProperty("payment.safety-migration", safetyMigration.absolutePath)
    systemProperty("payment.conflict-migration", conflictMigration.absolutePath)
    systemProperty("order.confirmed-at-migration", confirmedAtMigration.absolutePath)
    inputs.files(fixtureDockerfile, fixtureJar, safetyMigration, conflictMigration, confirmedAtMigration)
}
