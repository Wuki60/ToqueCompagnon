import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Toque Compagnon : l'appli du téléphone qui suit le robot sur le Wi-Fi de la maison.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Robolectric hors ligne : le jar Android 14 est téléchargé par Gradle, pas pendant les tests.
val robolectricVersion = "4.12.2"
val robolectricAndroid: Configuration by configurations.creating { isTransitive = false }
// Robolectric tire androidx.test (tests seulement) : passé en fichiers pour que la vérification
// AndroidX de l'Android Gradle Plugin (le projet est en android.useAndroidX=false) ne s'applique pas.
val robolectricTest: Configuration =
    configurations.detachedConfiguration(dependencies.create("org.robolectric:robolectric:$robolectricVersion"))
val robolectricDeps = layout.buildDirectory.dir("robolectric-deps")
val robolectricJars = tasks.register<Sync>("robolectricJars") {
    from(robolectricAndroid)
    into(robolectricDeps)
}

android {
    namespace = "app.toque.companion"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.toque.companion"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "0.5.1"
    }

    signingConfigs {
        // Clé publique exprès, la même que Toque (app/signature/toque.p12 du dépôt de Toque) : toutes les
        // compilations (PC, contributeurs) portent la même signature, donc chaque mise à jour, téléchargée
        // depuis l'appli ou à la main, s'installe par-dessus la précédente.
        create("toque") {
            storeFile = file("signature/toque.p12")
            storeType = "PKCS12"
            storePassword = "toque-public"
            keyAlias = "toque"
            keyPassword = "toque-public"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("toque")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("toque")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Captures d'écran rendues par Robolectric (graphismes natifs), voir ScreenshotTest.
            test.systemProperty("robolectric.offline", "true")
            test.systemProperty("robolectric.enabledSdks", "34")
            test.systemProperty("robolectric.dependency.dir", robolectricDeps.get().asFile.absolutePath)
            test.systemProperty("toque.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
            test.dependsOn(robolectricJars)
            test.maxHeapSize = "2g"
            test.testLogging { events("passed", "skipped", "failed") }
            // LanguageFilesTest lit les fichiers de langue et les textes du code de core : sans eux parmi
            // les entrées, Gradle dirait la tâche « à jour » quand eux seuls ont changé.
            test.inputs.dir("src/main/assets/lang").withPathSensitivity(PathSensitivity.RELATIVE)
            test.inputs.dir("../core/src/main/kotlin").withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

dependencies {
    implementation(project(":core"))
    // Lecture des QR codes d'appairage (la seule dépendance d'exécution du compagnon).
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation(files(robolectricTest))
    robolectricAndroid("org.robolectric:android-all-instrumented:14-robolectric-10818077-i6")
}
