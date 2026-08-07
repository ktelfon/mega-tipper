# Bolt's Journal - Critical Learnings Only

This journal is for recording unique performance bottlenecks, rejected optimizations, or surprising app-specific patterns discovered in this codebase.

## 2026-08-04 - GZIP and Streaming Parser Optimization
**Learning:** Standard JDK HTTP client (`HttpClient`) does not automatically request or decompress GZIP compression unless explicitly configured, which wastes bandwidth on high-frequency polling. Parsing large JSON strings with `readTree(String)` also forces redundant string object materialization and garbage collection pressure on the JVM.
**Action:** Always add the `Accept-Encoding: gzip` header, check `Content-Encoding: gzip` (case-insensitive) on the response, wrap in `GZIPInputStream` where appropriate, and feed the stream directly into the Jackson `ObjectMapper` to parse from bytes without allocating intermediate Strings.
