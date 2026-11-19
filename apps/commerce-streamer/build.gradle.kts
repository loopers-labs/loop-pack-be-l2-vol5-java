import java.util.UUID
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.springframework.boot.gradle.tasks.bundling.BootJar

sourceSets.test {
    resources.srcDir(rootProject.file("docs/week6-7/r01-order-events-inbox/fixtures"))
}

val rankingSchemas = rootProject.files(
    "docker/payment-lab/001-pg-schema.sql", "docker/payment-lab/002-pg-transactions.sql",
    "docker/payment-lab/014-weekly-ranking-snapshots.sql", "docker/payment-lab/015-weekly-ranking-scores.sql",
    "docker/payment-lab/016-weekly-ranking-publication.sql", "docker/payment-lab/017-weekly-ranking-plans.sql",
    "docker/payment-lab/018-weekly-ranking-partial-scores.sql", "docker/week9/001-batch-metadata-mysql.sql",
)

tasks.processTestResources {
    from(rootProject.file("docker/payment-lab/009-purchase-activity.sql"))
    from(rootProject.file("docker/payment-lab/010-order-event-failure.sql"))
}

// Pin the three executable JARs and all schema scripts for this test invocation, including later child JVM restarts.
val rankingFixture = layout.buildDirectory.dir("tmp/ranking-runtime-" + UUID.randomUUID())
val cleanRankingFixture = tasks.register<Delete>("cleanRankingTestRuntime") {
    delete(rankingFixture)
}

tasks.test {
    exclude("fixture/ranking/**") // These classes are loaded only by the isolated Batch JVM.
    if (providers.gradleProperty("rankingBenchmark").isPresent) {
        useJUnitPlatform { includeTags("ranking-benchmark") }
        binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/ranking-benchmark/binary"))
        reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/ranking-benchmark"))
        reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/ranking-benchmark"))
    } else {
        useJUnitPlatform { excludeTags("ranking-benchmark") }
    }
    val apps = mapOf("commerce-batch" to "ranking.worker.test-jar", "commerce-api" to "ranking.flow.api-jar",
        "test-pg" to "ranking.flow.pg-jar")
    val archives = apps.mapValues { (app, _) ->
        val bootJar = project(":apps:$app").tasks.named<BootJar>("bootJar")
        dependsOn(bootJar)
        bootJar.flatMap { it.archiveFile }
    }
    inputs.files(archives.values)
    inputs.files(rankingSchemas)
    finalizedBy(cleanRankingFixture)
    doFirst {
        val fixture = rankingFixture.get().asFile
        fixture.mkdirs()
        for ((app, property) in apps) {
            val copy = archives.getValue(app).get().asFile.copyTo(fixture.resolve("$app.jar"))
            systemProperty(property, copy.absolutePath)
        }
        for (schema in rankingSchemas) {
            schema.copyTo(fixture.resolve(schema.name))
        }
        systemProperty("ranking.flow.schema-directory", fixture.absolutePath)
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
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // querydsl
    annotationProcessor("io.github.openfeign.querydsl:querydsl-apt:${project.properties["queryDslVersion"]}:jpa")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // Batch supplies these SPI types in the isolated worker JVM.
    testCompileOnly("org.springframework.batch:spring-batch-core")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
    testImplementation(testFixtures(project(":modules:kafka")))
    testImplementation("org.testcontainers:mysql")
    testImplementation("com.tngtech.archunit:archunit-junit5:${project.properties["archUnitVersion"]}")
}

apply(plugin = "checkstyle")

configure<CheckstyleExtension> {
    toolVersion = project.properties["checkstyleVersion"].toString()
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}
