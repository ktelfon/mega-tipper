plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    // YAML for the operator's wallet file. Chosen over JSON because that file is hand-edited
    // and needs comments explaining which group each chat id belongs to.
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.2")
    // ton4j's address module only - handles base64url + CRC16 for TON addresses.
    // Deliberately not hand-rolling checksum code in the path that decides where money goes.
    implementation("io.github.neodix42:address:1.0.0")

    // Storage. The DDL is deliberately portable, so moving from SQLite to Postgres is a
    // connection-string change rather than a rewrite - which matters because many cloud
    // hosts have an ephemeral filesystem that would wipe a SQLite file on redeploy.
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("org.postgresql:postgresql:42.7.7")

    // Official Telegram bot library. Long polling needs no public URL, so the bot runs the
    // same on a laptop as in the cloud; webhooks are a step 6 concern.
    implementation("org.telegram:telegrambots-client:9.0.0")
    implementation("org.telegram:telegrambots-longpolling:9.0.0")

    // Declared rather than relied on transitively via telegrambots. Without an SLF4J binding
    // on the classpath every log call becomes a silent no-op, so an unrelated dependency bump
    // could turn the bot's logging off without anything failing to compile or to test.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    // `./gradlew run` drives the chain-watching spike.
    mainClass.set("dev.tipbot.spike.TonVerifySpikeKt")
}

// `./gradlew runBot` starts the Telegram bot.
tasks.register<JavaExec>("runBot") {
    group = "application"
    description = "Runs the Telegram bot (needs TELEGRAM_BOT_TOKEN in .env or the environment)"
    mainClass.set("dev.tipbot.spike.TipBotKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
