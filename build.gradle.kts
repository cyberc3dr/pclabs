plugins {
    java
    application
    alias(libs.plugins.shadow)
}

application {
    mainClass = "ru.cyberc3dr.pc.Main"
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
    manifest {
        attributes["Main-Class"] = application.mainClass
    }
}

sourceSets.main {
    java.srcDir("src")
    resources.srcDir("resources")
}