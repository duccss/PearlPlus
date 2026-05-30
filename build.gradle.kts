plugins {
    id("zenithproxy.plugin.dev") version "1.0.0-SNAPSHOT"
    id("org.graalvm.buildtools.native") version "1.1.0"
}

group = properties["maven_group"] as String
version = properties["plugin_version"] as String
val mc = properties["mc"] as String
val pluginId = properties["plugin_id"] as String

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

zenithProxyPlugin {
    templateProperties = mapOf(
        "version" to project.version,
        "plugin_id" to pluginId,
        "mc_version" to mc,
        "maven_group" to group as String,
    )
}

repositories {
    maven("https://maven.2b2t.vc/releases") {
        description = "ZenithProxy Releases"
    }
    maven("https://maven.2b2t.vc/remote") {
        description = "Dependencies used by ZenithProxy"
    }
}

dependencies {
    zenithProxy("com.zenith:ZenithProxy:$mc-SNAPSHOT")

    compileOnly("org.graalvm.sdk:nativeimage:25.0.3")

    /** to include dependencies into your plugin jar **/
//    shade("com.github.ben-manes.caffeine:caffeine:3.2.0")
}

tasks {
    shadowJar {
        /**
         * relocate shaded dependencies to avoid conflicts with other plugins
         * transitive dependencies should also be relocated or removed (with exclude)
         * build and examine your plugin jar contents to check
         * https://gradleup.com/shadow/configuration/relocation/
         */
//        val basePackage = "${project.group}.shadow"
//        relocate("com.github.benmanes.caffeine", "$basePackage.caffeine")

        /**
         * remove unneeded transitive dependencies
         * https://gradleup.com/shadow/configuration/dependencies/#filtering-dependencies
         */
//        dependencies {
//            exclude(dependency(":error_prone_annotations:.*"))
//            exclude(dependency(":jspecify:.*"))
//        }
    }
    nativeCompile {
        notCompatibleWithConfigurationCache("not compatible with configuration cache")
        dependsOn(shadowJar, build)
    }
    generateResourcesConfigFile {
        notCompatibleWithConfigurationCache("not compatible with configuration cache")
        dependsOn(shadowJar)
    }
}

graalvmNative {
    binaries {
        named("main") {
            javaLauncher = javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
                vendor = JvmVendorSpec.ORACLE
                //nativeImageCapable = true
            }
            imageName = properties["plugin_name"] as String
            mainClass = "com.zenith.Proxy"
            quickBuild = true // set to true for fast builds while developing
            verbose = true
            sharedLibrary = false
            buildArgs.addAll(
                "-H:Preserve=package=dev.zenith.pearlplus.*", // required - otherwise plugin classes will be stripped
                "-O3", // highest optimization level, but slowest build times
                "-H:DeadlockWatchdogInterval=30",
                "-H:+CompactingOldGen",
                "-H:+TrackPrimitiveValues",
                "-H:+TreatAllTypeReachableConditionsAsTypeReached",
                "-H:+UsePredicates",
                "--future-defaults=all",
                "-R:MaxHeapSize=200m",
                "-march=x86-64-v3",
                "--gc=serial",
                "-J-XX:MaxRAMPercentage=90"
            )
            configurationFileDirectories.from(file("src/main/resources/META-INF/native-image"))
        }
        metadataRepository { enabled = true }
    }
}
