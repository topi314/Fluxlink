import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.apache.tools.ant.filters.ReplaceTokens
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    application
    kotlin("jvm")
    id("org.jetbrains.dokka")
    kotlin("plugin.serialization")
    alias(libs.plugins.maven.publish.base)
}

apply(plugin = "org.springframework.boot")
apply(plugin = "com.gorylenko.gradle-git-properties")
apply(plugin = "org.ajoberstar.grgit")
apply(plugin = "com.adarshr.test-logger")
apply(plugin = "kotlin")
apply(plugin = "kotlin-spring")

val archivesBaseName = "Lavalink"
group = "dev.arbjerg.lavalink"
description = "Play audio to LiveKit voice channels"

application {
    mainClass = "lavalink.server.Launcher"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

kotlin {
    sourceSets {
        all {
            languageSettings.optIn("kotlinx.serialization.ExperimentalSerializationApi")
            languageSettings.optIn("nl.adaptivity.xmlutil.ExperimentalXmlUtilApi")
        }
    }
}

configurations {
    compileOnly {
        extendsFrom(annotationProcessor.get())
    }
}

dependencies {
    implementation(projects.protocol)
    implementation(projects.pluginApi) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }

    implementation(libs.xmlutil.jdk)
    implementation(libs.xmlutil.serialization)

    implementation(libs.bundles.metrics)
    implementation(libs.spring.cloud.config)
    implementation(libs.bundles.spring) {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    }

    implementation(libs.livekit.server)
    implementation(libs.webrtc.java)
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:linux-x86_64")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:linux-aarch32")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:linux-aarch64")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:macos-aarch64")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:macos-x86_64")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.14.0:windows-x86_64")

    implementation(libs.lavaplayer)
    implementation(libs.lavaplayer.ip.rotator)

    implementation(libs.lavadsp)
    implementation(libs.kotlin.reflect)
    implementation(libs.logback)
    implementation(libs.sentry.logback)
    implementation(libs.oshi) {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }

    testImplementation(libs.spring.boot.test)
}

tasks {
    build {
        doLast {
            println("Version: $version")
        }
    }

    processResources {
        val tokens = mapOf(
            "project.version"    to project.version,
            "project.groupId"    to project.group,
            "project.artifactId" to "Lavalink-Server",
            "env.BUILD_TIME"     to System.currentTimeMillis().toString()
        )

        filter(ReplaceTokens::class, mapOf("tokens" to tokens))
        copy {
            from("application.yml.example")
            into(layout.buildDirectory.dir("resources/main"))
        }
    }

    // https://stackoverflow.com/questions/41444916/multiple-artifacts-issue-with-deploying-zip-to-nexus
    named<AbstractArchiveTask>("bootDistTar") {
        archiveClassifier = "bootTar"
    }

    named<AbstractArchiveTask>("bootDistZip") {
        archiveClassifier = "bootZip"
    }

    named<Test>("test") {
        useJUnitPlatform()
    }

    val nativesJar = create<Jar>("lavaplayerNativesJar") {
        // Only add musl natives
        from(configurations.runtimeClasspath.get().find { it.name.contains("lavaplayer-natives") }?.let { file ->
            zipTree(file).matching {
                include {
                    it.path.contains("musl")
                }
            }
        })

        archiveBaseName = "lavaplayer-natives"
        archiveClassifier = "musl"
    }


    withType<BootJar> {
        archiveFileName = "Lavalink.jar"

        if (findProperty("targetPlatform") == "musl") {
            archiveFileName = "Lavalink-musl.jar"
            exclude {
                it.name.contains("lavaplayer-natives-fork")
            }

            classpath(nativesJar.outputs)
            dependsOn(nativesJar)
        }
    }

    withType<BootRun> {
        dependsOn("compileTestJava")

        //pass in custom jvm args
        // source: https://stackoverflow.com/a/25079415
        // example: ./gradlew bootRun -PjvmArgs="--illegal-access=debug -Dwhatever=value"
        if (project.hasProperty("jvmArgs")) {
            val args = project.property("jvmArgs")
                .toString()
                .split("\\s".toPattern())

            jvmArgs?.addAll(args)
        }
    }
}

mavenPublishing {
    configure(KotlinJvm(JavadocJar.Dokka("dokkaHtml")))
    pom {
        name = "Lavalink Server"
        description = "Lavalink Server"
    }
}

publishing {
    publications {
        named<MavenPublication>("maven") {
            artifact(tasks.named("bootJar"))
        }
    }
}
