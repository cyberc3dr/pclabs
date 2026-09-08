plugins {
    java
    application
    alias(libs.plugins.shadow)
}

tasks.register<JavaExec>("runTask1") {
    group = "application"
    mainClass.set("ru.cyberc3dr.pc.Task1")
    classpath = sourceSets["main"].runtimeClasspath
}

group = "ru.cyberc3dr"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {

}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.withType<Jar> {
    destinationDirectory = file("$rootDir/build")
    archiveVersion = ""
}

sourceSets.main {
    java.srcDir("src")
    resources.srcDir("resources")
}