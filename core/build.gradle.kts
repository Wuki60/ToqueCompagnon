import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Logique pure (protocole, sécurité, pilotage, recettes, recherche) : copie du module core de Toque,
// voir core/README.md. Ses tests tournent dans le dépôt de Toque.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

// Démo du compagnon (serveur JVM sur base simulée) : source set à part, jamais dans l'APK.
val demo: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}
configurations[demo.implementationConfigurationName].extendsFrom(configurations["implementation"])
configurations[demo.runtimeOnlyConfigurationName].extendsFrom(configurations["runtimeOnly"])

tasks.register<JavaExec>("companionDemo") {
    group = "application"
    description = "Serveur de démonstration de Toque Compagnon (-PdemoArgs=\"--port 8088 --fast 5\")"
    classpath = demo.runtimeClasspath
    mainClass.set("app.toque.core.companion.demo.DemoMainKt")
    standardInput = System.`in`
    workingDir = projectDir
    val extra = (project.findProperty("demoArgs") as String?)?.trim().orEmpty()
    if (extra.isNotEmpty()) args(extra.split(Regex("\\s+")))
}
