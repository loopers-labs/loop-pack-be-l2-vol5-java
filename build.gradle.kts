import org.gradle.api.Project.DEFAULT_VERSION
import org.gradle.language.jvm.tasks.ProcessResources
import org.springframework.boot.gradle.tasks.bundling.BootJar
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.DosFileAttributeView

/** --- configuration functions --- */
fun getGitHash(): String {
    return runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()
    }.getOrElse { "init" }
}

/** --- project configurations --- */
plugins {
    java
    id("org.springframework.boot") apply false
    id("io.spring.dependency-management")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

allprojects {
    val projectGroup: String by project
    group = projectGroup
    version = if (version == DEFAULT_VERSION) getGitHash() else version

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "org.springframework.boot")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")

    val prepareGeneratedDirectories by tasks.registering {
        description = "Clears Windows read-only directory attributes before Gradle replaces Java outputs."
        onlyIf { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) }
        doLast {
            // OneDrive can mark generated directories read-only; File.setWritable cannot repair directories on Windows.
            // Limit repairs to Java build outputs, preserving files, other attributes and benchmark data.
            listOf("classes", "resources", "generated", "tmp").forEach { output ->
                val directory = layout.buildDirectory.dir(output).get().asFile.toPath()
                if (Files.isDirectory(directory, NOFOLLOW_LINKS)) {
                    val realDirectory = directory.toRealPath()
                    require(realDirectory.startsWith(layout.buildDirectory.get().asFile.toPath().toRealPath())) {
                        "Generated output points outside the build directory: $directory"
                    }
                    Files.walk(directory).use { paths ->
                        paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach { path ->
                            val attributes = Files.getFileAttributeView(path, DosFileAttributeView::class.java, NOFOLLOW_LINKS)
                            if (attributes.readAttributes().isReadOnly) {
                                require(path.toRealPath().startsWith(realDirectory)) {
                                    "Generated directory points outside the output directory: $path"
                                }
                                attributes.setReadOnly(false)
                            }
                        }
                    }
                }
            }
        }
    }
    tasks.withType<JavaCompile>().configureEach { dependsOn(prepareGeneratedDirectories) }
    tasks.withType<ProcessResources>().configureEach { dependsOn(prepareGeneratedDirectories) }

    dependencyManagement {
        imports {
            mavenBom("org.springframework.cloud:spring-cloud-dependencies:${project.properties["springCloudDependenciesVersion"]}")
        }
    }

    dependencies {
        // Web
        runtimeOnly("org.springframework.boot:spring-boot-starter-validation")
        // Spring
        implementation("org.springframework.boot:spring-boot-starter")
        // Serialize
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
        // Lombok
        implementation("org.projectlombok:lombok")
        annotationProcessor("org.projectlombok:lombok")
        // Test
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        // testcontainers:mysql 이 jdbc 사용함
        testRuntimeOnly("com.mysql:mysql-connector-j")
        testImplementation("org.springframework.boot:spring-boot-starter-test")
        testImplementation("com.ninja-squad:springmockk:${project.properties["springMockkVersion"]}")
        testImplementation("org.mockito:mockito-core:${project.properties["mockitoVersion"]}")
        testImplementation("org.instancio:instancio-junit:${project.properties["instancioJUnitVersion"]}")
        // Testcontainers
        testImplementation("org.springframework.boot:spring-boot-testcontainers")
        testImplementation("org.testcontainers:testcontainers")
        testImplementation("org.testcontainers:junit-jupiter")
    }

    tasks.withType(Jar::class) { enabled = true }
    tasks.withType(BootJar::class) { enabled = false }
    tasks.withType<JavaCompile> { options.encoding = "UTF-8" }

    configure(allprojects.filter { it.parent?.name.equals("apps") }) {
        tasks.withType(Jar::class) { enabled = false }
        tasks.withType(BootJar::class) { enabled = true }
    }

    tasks.test {
        maxParallelForks = 1
        useJUnitPlatform()
        systemProperty("file.encoding", "UTF-8")
        systemProperty("user.timezone", "Asia/Seoul")
        systemProperty("spring.profiles.active", "test")
        jvmArgs("-Xshare:off")
    }

    tasks.withType<JacocoReport> {
        mustRunAfter("test")
        executionData(fileTree(layout.buildDirectory.asFile).include("jacoco/*.exec"))
        reports {
            xml.required = true
            csv.required = false
            html.required = false
        }
        afterEvaluate {
            classDirectories.setFrom(
                files(
                    classDirectories.files.map {
                        fileTree(it)
                    },
                ),
            )
        }
    }
}

// module-container 는 task 를 실행하지 않도록 한다.
project("apps") { tasks.configureEach { enabled = false } }
project("modules") { tasks.configureEach { enabled = false } }
project("supports") { tasks.configureEach { enabled = false } }
