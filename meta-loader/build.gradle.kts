import java.util.Locale

plugins {
    alias(libs.plugins.agp.app)
}

android {
    defaultConfig {
        multiDexEnabled = false

        externalNativeBuild {
            cmake {
                cppFlags("-fno-threadsafe-statics")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles("proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
        }
    }

    namespace = "org.lsposed.lspatch.metaloader"
}

androidComponents.onVariants { variant ->
    val variantCapped = variant.name.replaceFirstChar { it.uppercase() }
    val variantLowered = variant.name.lowercase()

    task<Copy>("copyDex$variantCapped") {
        dependsOn("assemble$variantCapped")
        val dexOutPath = if (variant.buildType == "release")
            "$buildDir/intermediates/dex/$variantLowered/minify${variantCapped}WithR8" else
            "$buildDir/intermediates/dex/$variantLowered/mergeDex$variantCapped"
        from(dexOutPath)
        rename("classes.dex", "metaloader.dex")
        into("${rootProject.projectDir}/out/assets/${variant.name}/lspatch")
    }

    task<Copy>("copySo$variantCapped") {
        dependsOn("assemble$variantCapped")
        from(
            fileTree(
                "dir" to "$buildDir/intermediates/stripped_native_libs/${variant.name}/out/lib",
                "include" to listOf("**/libmeta_loader.so")
            )
        )
        into("${rootProject.projectDir}/out/assets/${variant.name}/lspatch/so")
    }

    task("copy$variantCapped") {
        dependsOn("copyDex$variantCapped")
        dependsOn("copySo$variantCapped")

        doLast {
            println("Loader dex has been copied to ${rootProject.projectDir}${File.separator}out")
        }
    }
}

dependencies {
    compileOnly(projects.hiddenapi.stubs)
    implementation(projects.share.java)
    implementation(libs.hiddenapibypass)
}
