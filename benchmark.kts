import dev.tipbot.spike.TipAmount

val iterations = 100_000
val samples = listOf("1", "1.5", "0.01", "0.123456789", "2.9", "1 TON", "1ton")

// Warmup
for (i in 1..10_000) {
    for (s in samples) {
        TipAmount.parse(s)
    }
}

val start = System.nanoTime()
for (i in 1..iterations) {
    for (s in samples) {
        TipAmount.parse(s)
    }
}
val elapsed = System.nanoTime() - start
println("Total time for ${iterations * samples.size} parses: ${elapsed / 1_000_000} ms")
println("Per parse: ${elapsed.toDouble() / (iterations * samples.size)} ns")
