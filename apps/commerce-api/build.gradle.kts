plugins {
    checkstyle
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

tasks.named("check") {
    dependsOn(":modules:jpa:check")
}

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
    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker")
    implementation("io.github.resilience4j:resilience4j-retry")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // assignment: admin access and architecture checks
    implementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:mysql")
    testImplementation("com.tngtech.archunit:archunit:1.5.0")

    // test-fixtures
    testImplementation("com.navercorp.fixturemonkey:fixture-monkey:1.2.3")
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))
}

val week6IntegrationSupportJar by tasks.registering(Jar::class) {
    enabled = true
    archiveClassifier.set("week6-integration-support")
    dependsOn(tasks.named("classes"), tasks.named("testClasses"))
    from(sourceSets.main.get().output.classesDirs) {
        exclude("com/loopers/CommerceApiApplication.class")
    }
    from(sourceSets.test.get().output.classesDirs) {
        exclude("**/*Test*.class")
        include("com/loopers/support/payment/LocalPgServer*.class")
        include("com/loopers/support/payment/AdjustableClock*.class")
        include("com/loopers/application/**/fixture/**")
        include("com/loopers/infrastructure/**/fixture/**")
        include("com/loopers/config/FixtureMonkeyConfig.class")
    }
}

configurations.create("week6IntegrationSupport") {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.testRuntimeClasspath.get())
    outgoing.artifact(week6IntegrationSupportJar)
}

// 명시적으로 실행할 때만 로컬 DB에 실습 데이터를 저장한다.
tasks.register<JavaExec>("seedLocalProducts") {
    group = "application"
    description = "Fixture Monkey로 로컬 loopers DB에 상품 10,000개를 저장합니다"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.loopers.seed.LocalProductSeed")
    workingDir(projectDir)
    args(layout.buildDirectory.file("local-product-seed-result.json").get().asFile.absolutePath)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
}

// 격리된 13306/loopers_offset_bench 전용. 기존 seedLocalProducts 작업과 분리한다.
tasks.register<JavaExec>("seedOffsetBenchmark") {
    group = "application"
    description = "전용 빈 DB에 균등 분포 상품 100만 건과 좋아요 200만 건을 생성합니다"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.loopers.seed.benchmark.OffsetBenchmarkSeed")
    workingDir(projectDir)
    maxHeapSize = "512m"
    if (providers.gradleProperty("benchmarkSchemaOnly").isPresent) {
        args("--schema-only")
    }
}

// 기존 상품100만 건은 유지하고, 인기20개 상품의 실제 좋아요 관계만 추가한다.
tasks.register<JavaExec>("seedPopularProductLikes") {
    group = "application"
    description = "전용 DB의 인기 상품20개에 사용자당 실제 좋아요 관계를 추가합니다"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.loopers.seed.benchmark.OffsetBenchmarkSeed")
    workingDir(projectDir)
    maxHeapSize = "512m"
    args("--popular-likes=" + providers.gradleProperty("popularLikeUsers").orElse("150000").get())
}
