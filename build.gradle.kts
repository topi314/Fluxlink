import org.ajoberstar.grgit.Grgit
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        mavenLocal()
        maven("https://plugins.gradle.org/m2/")
        maven("https://repo.spring.io/plugins-release")
        maven("https://jitpack.io")
        maven("https://m2.dv8tion.net/releases")
    }

    dependencies {
        classpath("gradle.plugin.com.gorylenko.gradle-git-properties:gradle-git-properties:1.5.2")
        classpath("org.springframework.boot:spring-boot-gradle-plugin:4.0.3")
        classpath("org.sonarsource.scanner.gradle:sonarqube-gradle-plugin:2.6.2")
        classpath("com.adarshr:gradle-test-logger-plugin:4.0.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
        classpath("org.jetbrains.kotlin:kotlin-allopen:2.3.0")
    }
}

allprojects {
    group = "lavalink"
    version = versionFromTag()

    repositories {
        mavenCentral() // main maven repo
        mavenLocal()   // useful for developing
        maven("https://m2.dv8tion.net/releases")
        maven("https://maven.lavalink.dev/releases")
        maven("https://jitpack.io") // build projects directly from GitHub
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "idea")

    if (project.hasProperty("includeAnalysis")) {
        project.logger.lifecycle("applying analysis plugins")
        apply(from = "../analysis.gradle")
    }

    tasks.withType<KotlinJvmCompile> {
	    compilerOptions {
			jvmTarget = JvmTarget.JVM_11
	    }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:unchecked")
        options.compilerArgs.add("-Xlint:deprecation")
    }

    val isOssrhMissing = (findProperty("signing.gnupg.keyName") as String?).isNullOrBlank() || (findProperty("ossrhPassword") as String?).isNullOrBlank() || (findProperty("ossrhUsername") as String?).isNullOrBlank()
    val isMavenMissing = (findProperty("MAVEN_USERNAME") as String?).isNullOrBlank() || (findProperty("MAVEN_PASSWORD") as String?).isNullOrBlank()

    if (!isOssrhMissing) {
        println("Publishing to OSSRH")
        repositories {
            val snapshots = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
            val releases = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"

            maven(if (version.toString().endsWith("SNAPSHOT")) snapshots else releases) {
                credentials {
                    password = findProperty("ossrhPassword") as String
                    username = findProperty("ossrhUsername") as String
                }
            }
        }
    } else {
        println("Not capable of publishing to OSSRH because of missing GPG key or OSSRH credentials")
    }

    if (!isMavenMissing) {
        println("Publishing to Maven Repo")
        repositories {
            val snapshots = "https://maven.lavalink.dev/snapshots"
            val releases = "https://maven.lavalink.dev/releases"

            maven(if (version.toString().endsWith("SNAPSHOT")) snapshots else releases) {
                credentials {
                    password = findProperty("MAVEN_PASSWORD") as String
                    username = findProperty("MAVEN_USERNAME") as String
                }
            }
        }
    } else {
        println("Maven credentials not found, not publishing to Maven Repo")
    }
}

@SuppressWarnings("GrMethodMayBeStatic")
fun versionFromTag(): String = Grgit.open(mapOf("currentDir" to project.rootDir)).use { git ->
    val headTag = git.tag
        .list()
        .find { it.commit.id == git.head().id }

    val clean = git.status().isClean || System.getenv("CI") != null
    if (!clean) {
        println("Git state is dirty, setting version as snapshot.")
    }

    return if (headTag != null && clean) headTag.name else "${git.head().id}-SNAPSHOT"
}
