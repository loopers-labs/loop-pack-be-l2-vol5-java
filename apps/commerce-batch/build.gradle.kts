dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // batch
    implementation("org.springframework.boot:spring-boot-starter-batch")
    testImplementation("org.springframework.batch:spring-batch-test")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}

val weeklyRankingJobJar by tasks.registering(Jar::class) {
    enabled = true
    archiveClassifier.set("weekly-ranking-job")
    dependsOn(tasks.named("classes"))
    from(sourceSets.main.get().output.classesDirs) {
        include("com/loopers/batch/job/ranking/**")
    }
}

configurations.create("weeklyRankingJob") {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations.runtimeClasspath.get())
    outgoing.artifact(weeklyRankingJobJar)
}
