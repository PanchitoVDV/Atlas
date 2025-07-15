import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

repositories {
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots")
}

dependencies {
    implementation(project(":atlas-common"))
    compileOnly("org.spigotmc:spigot-api:1.21.5-R0.1-SNAPSHOT")
    implementation("io.netty:netty-all:4.1.100.Final")
}

tasks.named<ShadowJar>("shadowJar") {
    dependsOn(":atlas-common:shadowJar")
}

tasks.named("compileJava") {
    dependsOn(":atlas-common:shadowJar")
}