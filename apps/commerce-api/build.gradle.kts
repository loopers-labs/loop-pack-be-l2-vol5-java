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
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:mysql")
}

// PG fixture는 별도 보관하며 -PpgFixtureRoot로 루트를 지정할 수 있다.
// 순수 단위 테스트는 fixture를 사용하지 않고, HTTP·이관 통합 테스트는 실제 파일을 요구한다.
tasks.test {
    val fixtureRoot = rootProject.file(providers.gradleProperty("pgFixtureRoot").getOrElse("."))
    val fixtureDockerfile = fixtureRoot.resolve("apps/test-pg/Dockerfile")
    val fixtureJar = fixtureRoot.resolve("apps/test-pg/build/libs/test-pg.jar")
    val safetyMigration = fixtureRoot.resolve("docker/mysql/pg-payment-safety-migration.sql")
    val conflictMigration = fixtureRoot.resolve("docker/mysql/pg-payment-conflict-migration.sql")
    systemProperty("test-pg.dockerfile", fixtureDockerfile.absolutePath)
    systemProperty("test-pg.jar", fixtureJar.absolutePath)
    systemProperty("payment.safety-migration", safetyMigration.absolutePath)
    systemProperty("payment.conflict-migration", conflictMigration.absolutePath)
    inputs.files(fixtureDockerfile, fixtureJar, safetyMigration, conflictMigration)
}
