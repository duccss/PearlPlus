plugins {
    id("zenithproxy.plugin.dev") version "1.0.0-SNAPSHOT"
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

    // RabbitMQ client — shaded in for optional Hydra C2 integration.
    // Activates automatically when HYDRA_RABBIT_URL + HYDRA_AGENT_ID env vars are set.
    // Has zero runtime impact on standalone PearlPlus deployments.
    shade("com.rabbitmq:amqp-client:5.22.0")
}

tasks {
    shadowJar {
        val basePackage = "${project.group}.shadow"

        // Relocate RabbitMQ client to avoid classpath conflicts with any other AMQP
        // library that a host application or another plugin may ship.
        relocate("com.rabbitmq",    "$basePackage.rabbitmq")
        // amqp-client transitively pulls in SLF4J API — exclude since ZenithProxy provides it.
        dependencies {
            exclude(dependency("org.slf4j:slf4j-api:.*"))
        }
    }
}
