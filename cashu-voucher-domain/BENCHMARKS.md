# Voucher Domain Performance Benchmarks

This module includes JMH (Java Microbenchmark Harness) performance benchmarks for critical voucher operations.

## Overview

The benchmarks measure the performance of:

1. **VoucherSignatureBenchmark** - ED25519 signature generation and verification
2. **VoucherSerializationBenchmark** - CBOR serialization/deserialization
3. **VoucherOperationsBenchmark** - Voucher creation, validation, and business logic

## Prerequisites

- Java 21+
- Maven 3.9+
- 16+ GB RAM recommended for accurate benchmarking
- Quiet system (minimal background processes)

## Building Benchmarks

Build the benchmarks JAR:

```bash
cd cashu-voucher-domain
mvn clean package -DskipTests
```

This creates `target/benchmarks.jar` - a self-contained executable JAR with all dependencies.

## Running Benchmarks

### Run All Benchmarks

```bash
java -jar target/benchmarks.jar
```

This runs all benchmarks with default settings (warmup + measurement iterations).

### Run Specific Benchmark Class

```bash
# Signature benchmarks only
java -jar target/benchmarks.jar VoucherSignatureBenchmark

# Serialization benchmarks only
java -jar target/benchmarks.jar VoucherSerializationBenchmark

# Operations benchmarks only
java -jar target/benchmarks.jar VoucherOperationsBenchmark
```

### Run Single Benchmark Method

```bash
# Signature generation only
java -jar target/benchmarks.jar VoucherSignatureBenchmark.signVoucher

# Signature verification only
java -jar target/benchmarks.jar VoucherSignatureBenchmark.verifyVoucher

# CBOR serialization only
java -jar target/benchmarks.jar VoucherSerializationBenchmark.serializeVoucherSecret
```

## Benchmark Options

### Custom Iterations

```bash
# 5 warmup iterations, 10 measurement iterations
java -jar target/benchmarks.jar -wi 5 -i 10 VoucherSignatureBenchmark
```

### Custom Fork Count

```bash
# Run with 3 JVM forks (more reliable, takes longer)
java -jar target/benchmarks.jar -f 3 VoucherSignatureBenchmark
```

### Output Formats

```bash
# JSON output
java -jar target/benchmarks.jar -rf json -rff benchmark-results.json

# CSV output
java -jar target/benchmarks.jar -rf csv -rff benchmark-results.csv

# Human-readable text
java -jar target/benchmarks.jar -rf text -rff benchmark-results.txt
```

### Profiling

```bash
# GC profiling
java -jar target/benchmarks.jar -prof gc VoucherSignatureBenchmark

# Stack profiling (requires async-profiler)
java -jar target/benchmarks.jar -prof async VoucherSignatureBenchmark

# List all available profilers
java -jar target/benchmarks.jar -lprof
```

## Benchmark Results

### Expected Performance (Reference Hardware)

Hardware: AMD Ryzen 7 / Intel i7 (8 cores, 3.5 GHz), 16 GB RAM, Ubuntu Linux

| Operation | Average Time | Throughput |
|-----------|-------------|------------|
| **Signatures** | | |
| Sign voucher | ~10-20 µs | ~50k-100k ops/sec |
| Verify signature | ~30-50 µs | ~20k-33k ops/sec |
| Sign + Verify | ~40-70 µs | ~14k-25k ops/sec |
| **Serialization** | | |
| Serialize VoucherSecret | ~5-10 µs | ~100k-200k ops/sec |
| Deserialize VoucherSecret | ~5-10 µs | ~100k-200k ops/sec |
| Serialize SignedVoucher | ~5-10 µs | ~100k-200k ops/sec |
| Deserialize SignedVoucher | ~5-10 µs | ~100k-200k ops/sec |
| **Operations** | | |
| Create VoucherSecret | ~1-3 µs | ~330k-1M ops/sec |
| Validate expiry | ~0.1-0.5 µs | ~2M-10M ops/sec |
| Full validation | ~30-50 µs | ~20k-33k ops/sec |
| Complete workflow | ~50-80 µs | ~12k-20k ops/sec |

### Interpreting Results

- **µs** = microseconds (1 µs = 0.001 ms)
- **Warmup** = JVM warm-up iterations (JIT compilation, class loading)
- **Measurement** = Actual benchmark iterations
- **Score** = Average time per operation (lower is better)
- **Error** = 99.9% confidence interval

## Performance Tuning

### JVM Options

For more accurate benchmarking, use these JVM options:

```bash
java -XX:+UseG1GC \
     -Xms2G \
     -Xmx2G \
     -XX:+AlwaysPreTouch \
     -jar target/benchmarks.jar
```

### System Preparation

1. **Close unnecessary applications** - Minimize background processes
2. **Disable frequency scaling** - Set CPU governor to `performance`
3. **Disable turbo boost** - For consistent results
4. **Run multiple times** - Average results across 3-5 runs

```bash
# Linux: Set CPU governor to performance
sudo cpupower frequency-set -g performance
```

## Benchmark Architecture

### VoucherSignatureBenchmark

Tests ED25519 cryptographic operations:

- `signVoucher()` - Signature generation
- `verifyVoucher()` - Signature verification
- `signAndVerifyVoucher()` - Complete workflow
- `createAndSignVoucher()` - Creation + signing

**Why it matters**: Signature operations are the most CPU-intensive part of voucher processing.

### VoucherSerializationBenchmark

Tests CBOR serialization performance:

- `serializeVoucherSecret()` - Secret → CBOR bytes
- `deserializeVoucherSecret()` - CBOR bytes → Secret
- `serializeSignedVoucher()` - Signed voucher → CBOR bytes
- `deserializeSignedVoucher()` - CBOR bytes → Signed voucher
- `roundTripVoucherSecret()` - Serialize + deserialize secret
- `roundTripSignedVoucher()` - Serialize + deserialize voucher

**Why it matters**: Serialization affects token encoding/decoding and Nostr storage.

### VoucherOperationsBenchmark

Tests domain logic and validation:

- `createVoucherSecret()` - VoucherSecret creation
- `createVoucherSecretNoExpiry()` - Without expiry
- `validateNonExpiredVoucher()` - Expiry check (valid)
- `validateExpiredVoucher()` - Expiry check (expired)
- `validateSignature()` - Signature verification
- `validateComplete()` - Full validation (expiry + signature)
- `createSignAndValidate()` - Complete lifecycle
- `validateIssuerId()` - Issuer ID match
- `getVoucherFaceValue()` - Getter performance

**Why it matters**: These operations are frequently used in merchant verification and wallet operations.

## Performance Goals

### Throughput Targets

- **Merchant verification**: 1000+ vouchers/second (1 ms per voucher)
- **Wallet operations**: 500+ vouchers/second (2 ms per voucher)
- **Nostr publishing**: 100+ vouchers/second (10 ms per voucher)

### Latency Targets

- **P50**: < 50 µs for signature operations
- **P99**: < 100 µs for signature operations
- **P99.9**: < 200 µs for signature operations

## Continuous Integration

### Maven Integration

Run benchmarks as part of CI/CD:

```bash
# Quick benchmarks (1 warmup, 1 measurement)
mvn test -Pbenchmarks -Djmh.wi=1 -Djmh.i=1

# Full benchmarks (3 warmup, 5 measurement)
mvn test -Pbenchmarks -Djmh.wi=3 -Djmh.i=5
```

### Benchmark Regression Detection

Track benchmark results over time:

```bash
# Baseline
java -jar target/benchmarks.jar -rf json -rff baseline.json

# After changes
java -jar target/benchmarks.jar -rf json -rff current.json

# Compare (requires jmh-result-compare tool)
jmh-result-compare baseline.json current.json
```

## Troubleshooting

### Benchmark Takes Too Long

Reduce iterations:

```bash
java -jar target/benchmarks.jar -wi 1 -i 1
```

### Inconsistent Results

1. Close background applications
2. Use multiple forks: `-f 5`
3. Increase measurement time: `-i 10`
4. Check CPU frequency scaling
5. Ensure system is not thermal throttling

### OutOfMemoryError

Increase heap size:

```bash
java -Xmx4G -jar target/benchmarks.jar
```

### JMH Not Found

Rebuild benchmarks JAR:

```bash
mvn clean package -DskipTests
```

## References

- **JMH**: https://github.com/openjdk/jmh
- **JMH Tutorial**: https://jenkov.com/tutorials/java-performance/jmh.html
- **ED25519**: https://en.wikipedia.org/wiki/EdDSA#Ed25519
- **CBOR**: https://cbor.io/

## Contributing

When adding new benchmarks:

1. Extend existing benchmark classes or create new ones
2. Follow naming convention: `benchmark<Operation>()`
3. Add JavaDoc explaining what is being measured
4. Update this README with expected performance
5. Use `@Blackhole` to prevent dead code elimination
6. Use `@State(Scope.Benchmark)` for setup
7. Use `@Setup(Level.Trial)` for one-time initialization

---

**Last Updated**: 2025-11-06
**JMH Version**: 1.37
**Java Version**: 21+
