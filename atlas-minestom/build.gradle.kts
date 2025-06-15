import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

dependencies {
    implementation(project(":atlas-common"))
}

tasks.named<ShadowJar>("shadowJar") {
    dependsOn(":atlas-common:shadowJar")
}