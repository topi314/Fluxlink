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
