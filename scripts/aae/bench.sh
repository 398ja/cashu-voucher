#!/usr/bin/env bash
# Frozen AAE harness runner (aae/nov27). Prints per-benchmark us/op and primary_metric (geomean).
set -euo pipefail
cd "$(dirname "$0")/../../cashu-voucher-domain"
mvn -q -o test-compile -DskipTests >/dev/null 2>&1 || mvn -q test-compile -DskipTests
[ -f target/aae.cp ] || mvn -q dependency:build-classpath -Dmdep.outputFile=target/aae.cp -Dmdep.includeScope=test >/dev/null
CP="target/classes:target/test-classes:$(cat target/aae.cp)"
LB="$(cd ../scripts/aae && pwd)/logback-bench.xml"
java -Dlogback.configurationFile="$LB" -cp "$CP" org.openjdk.jmh.Main VoucherAaeBenchmark -jvmArgsAppend "-Dlogback.configurationFile=$LB" -rf csv -rff target/aae.csv "$@" > target/aae-jmh.log 2>&1
python3 - <<'PY'
import csv, math
rows=list(csv.DictReader(open('target/aae.csv')))
vals=[]
for r in rows:
    v=float(r['Score']); vals.append(v)
    print(f"bench:{r['Benchmark'].split('.')[-1]}\t{v:.3f}\t+-{float(r['Score Error (99.9%)'] or 0):.3f}")
print(f"primary_metric: {math.exp(sum(map(math.log,vals))/len(vals)):.4f}")
PY
