#!/bin/sh
# Run every benchmark in benchmarks/ across every available backend
# (interpreter, bytecode VM, native C, WebAssembly) and summarise the
# per-benchmark output as an ASCII table.
#
# Backends that cannot be used (missing wasmtime, missing C compiler,
# or a benchmark that fails to compile for a given backend) are marked
# "-" in the table rather than aborting the run.
#
# Usage:
#   scripts/run_benchmarks.sh [backend ...]
#
# If no backend is supplied, every detected backend runs. Valid names:
#   interpreter  bytecode  native  wasm
set -u

JAR="target/safe-lang-1.0.0-cli.jar"
BENCH_DIR="benchmarks"
RESULTS=$(mktemp)
trap 'rm -f "$RESULTS"' EXIT

# ── Build jar if missing ────────────────────────────────────────────────
if [ ! -f "$JAR" ]; then
  echo "Building $JAR ..."
  mvn -q clean package -DskipTests || { echo "Build failed" >&2; exit 1; }
fi

# ── Detect available backends ───────────────────────────────────────────
have_native=0
have_wasm=0
command -v cc  >/dev/null 2>&1 && have_native=1
command -v gcc >/dev/null 2>&1 && have_native=1
command -v wasmtime >/dev/null 2>&1 && have_wasm=1

# Decide which backends to run
if [ "$#" -gt 0 ]; then
  SELECTED="$*"
else
  SELECTED="interpreter bytecode"
  [ "$have_native" -eq 1 ] && SELECTED="$SELECTED native"
  [ "$have_wasm"   -eq 1 ] && SELECTED="$SELECTED wasm"
fi

echo "Backends: $SELECTED"
echo

# ── Helpers ─────────────────────────────────────────────────────────────
# record bench backend status write read peak rss
record() {
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" "$5" "$6" "$7" >> "$RESULTS"
}

# Parse "write: 1234ms" / "read: 567ms" out of the captured program output.
extract() {
  key="$1"
  text="$2"
  echo "$text" | awk -v k="$key" '
    $0 ~ "^"k":" { gsub(/[^0-9]/, "", $2); print $2; exit }
  '
}

# Parse "safe: arena peak = NNN bytes" or "safe: wasm heap peak = NNN bytes"
# out of the captured program output (native: stderr destructor; WASM: safe_heap_report).
extract_peak() {
  text="$1"
  echo "$text" | awk '
    /safe:.*peak = [0-9]+ bytes/ { for (i=1; i<=NF; i++) if ($i ~ /^[0-9]+$/) { print $i; exit } }
  '
}

# Parse resident-set size (OS-level "used memory") from /usr/bin/time -l
# (macOS) or -v (Linux) output. macOS prints "NN maximum resident set size"
# in bytes; Linux prints "Maximum resident set size (kbytes): NN".
extract_rss() {
  text="$1"
  echo "$text" | awk '
    /maximum resident set size/ {
      # macOS form: "NN maximum resident set size"
      if ($1 ~ /^[0-9]+$/) { print $1; exit }
    }
    /Maximum resident set size/ {
      # Linux form: "Maximum resident set size (kbytes): NN"
      for (i=NF; i>=1; i--) if ($i ~ /^[0-9]+$/) { print $i * 1024; exit }
    }
  '
}

# Wrap the command under /usr/bin/time so we can pull peak RSS. The command
# output is written to $3, the timing output to $4; callers can then read
# both back for parsing.
TIME_BIN="/usr/bin/time"
if [ ! -x "$TIME_BIN" ]; then
  TIME_BIN=""
fi
TIME_FLAG=""
if [ -n "$TIME_BIN" ]; then
  case "$(uname -s)" in
    Darwin) TIME_FLAG="-l" ;;
    Linux)  TIME_FLAG="-v" ;;
  esac
fi

fmt_mb() { awk -v b="$1" 'BEGIN { printf "%.1f", b/1048576 }'; }

# Run one command, capture output, print timing, append to results.
# When /usr/bin/time is available we wrap the command so its report lands
# in a side file; the benchmark's own stdout+stderr go to $output for
# peak/timing parsing, and the time report gets read separately for RSS.
run_bench() {
  bench="$1"; backend="$2"; shift 2
  label="$bench/$backend"
  printf '  %-30s ' "$label"
  stdout_file=$(mktemp)
  time_file=$(mktemp)
  if [ -n "$TIME_BIN" ] && [ -n "$TIME_FLAG" ]; then
    SAFE_HEAP_REPORT=1 "$TIME_BIN" $TIME_FLAG "$@" >"$stdout_file" 2>"$time_file"
    rc=$?
    output=$(cat "$stdout_file" "$time_file")
    rss_raw=$(cat "$time_file")
  else
    SAFE_HEAP_REPORT=1 "$@" >"$stdout_file" 2>&1
    rc=$?
    output=$(cat "$stdout_file")
    rss_raw=""
  fi
  rm -f "$stdout_file" "$time_file"
  if [ "$rc" -ne 0 ]; then
    echo "FAIL ($rc)"
    echo "$output" | head -3 | sed 's/^/      /'
    record "$bench" "$backend" FAIL - - - -
    return 1
  fi
  w=$(extract write "$output")
  r=$(extract read  "$output")
  p=$(extract_peak "$output")
  rss=$(extract_rss "$rss_raw")
  msg="write=${w:-—}ms read=${r:-—}ms"
  [ -n "$p" ]   && msg="$msg peak=$(fmt_mb "$p")MB"
  [ -n "$rss" ] && msg="$msg rss=$(fmt_mb "$rss")MB"
  echo "$msg"
  record "$bench" "$backend" OK "${w:-—}" "${r:-—}" "${p:-—}" "${rss:-—}"
}

# ── Precompile bytecode / native / wasm once per benchmark ──────────────
for src in "$BENCH_DIR"/*.safe; do
  name=$(basename "$src" .safe)
  echo "--- $name ---"

  for backend in $SELECTED; do
    case "$backend" in
      interpreter)
        run_bench "$name" interpreter java -jar "$JAR" run "$src"
        ;;
      bytecode)
        if java -jar "$JAR" bytecode "$src" >/dev/null 2>&1; then
          run_bench "$name" bytecode java -jar "$JAR" vm "$BENCH_DIR/$name.safeb"
        else
          printf '  %-30s skipped (bytecode compile failed)\n' "$name/bytecode"
          record "$name" bytecode SKIP - - - -
        fi
        ;;
      native)
        if [ "$have_native" -ne 1 ]; then
          printf '  %-30s skipped (no C compiler)\n' "$name/native"
          record "$name" native SKIP - - - -
          continue
        fi
        build_log=$(java -jar "$JAR" build "$src" 2>&1)
        if [ $? -ne 0 ] || [ ! -x "$BENCH_DIR/$name" ]; then
          printf '  %-30s skipped (C compile failed)\n' "$name/native"
          record "$name" native SKIP - - - -
          continue
        fi
        run_bench "$name" native "$BENCH_DIR/$name"
        ;;
      wasm)
        if [ "$have_wasm" -ne 1 ]; then
          printf '  %-30s skipped (no wasmtime)\n' "$name/wasm"
          record "$name" wasm SKIP - - - -
          continue
        fi
        wasm_out=$(java -jar "$JAR" wasm "$src" 2>&1)
        if [ $? -ne 0 ] || [ ! -f "$BENCH_DIR/$name.wasm" ]; then
          printf '  %-30s skipped (wasm compile failed)\n' "$name/wasm"
          record "$name" wasm SKIP - - - -
          continue
        fi
        # The compiler prints the exact wasmtime invocation; extract
        # everything after "Run with: " so preloads stay in sync with
        # whatever modules the compiler produced.
        cmd=$(echo "$wasm_out" | awk '/^Run with: /{sub(/^Run with: /,""); print; exit}')
        if [ -z "$cmd" ]; then
          printf '  %-30s skipped (no wasmtime command emitted)\n' "$name/wasm"
          record "$name" wasm SKIP - - - -
          continue
        fi
        # Inject --dir=/tmp so file-backed benchmarks can open their DBs,
        # and --env SAFE_HEAP_REPORT so the module's safe_heap_report logs on exit.
        cmd=$(echo "$cmd" | sed 's|^wasmtime run |wasmtime run --dir=/tmp --env SAFE_HEAP_REPORT=1 |')
        run_bench "$name" wasm sh -c "$cmd"
        ;;
      *)
        echo "Unknown backend: $backend" >&2
        exit 1
        ;;
    esac
  done
  echo
done

# ── Render ASCII results table ──────────────────────────────────────────
echo
echo "Results (milliseconds):"
echo

awk -F'\t' '
  {
    bench=$1; backend=$2; status=$3; write=$4; read_=$5; peak=$6; rss=$7;
    benches[bench]=1
    backends[backend]=1
    if (status == "OK") {
      w[bench SUBSEP backend "/write"]=write
      r[bench SUBSEP backend "/read"]=read_
      p[bench SUBSEP backend "/peak"]=peak
      rs[bench SUBSEP backend "/rss"]=rss
    } else {
      s[bench SUBSEP backend]=status
    }
  }
  END {
    # stable backend order
    n=0
    split("interpreter bytecode native wasm", order, " ")
    for (i=1; i<=4; i++) if (order[i] in backends) cols[++n]=order[i]

    cw=22  # benchmark name column
    vw=30  # value column width (now includes peak AND rss)
    printf "%-*s", cw, "benchmark"
    for (i=1; i<=n; i++) {
      printf " | %-*s", vw, cols[i] " (w/r ms, peak/rss MB)"
    }
    printf "\n"
    printf "%s", dash(cw)
    for (i=1; i<=n; i++) printf "-+-%s", dash(vw)
    printf "\n"

    for (b in benches) names[++m]=b
    for (i=1; i<=m; i++) for (j=i+1; j<=m; j++) if (names[i]>names[j]) { t=names[i]; names[i]=names[j]; names[j]=t }

    for (i=1; i<=m; i++) {
      bench=names[i]
      printf "%-*s", cw, bench
      for (k=1; k<=n; k++) {
        bk=cols[k]
        key=bench SUBSEP bk
        if (s[key] == "FAIL") {
          cell = "FAIL"
        } else if (s[key] == "SKIP") {
          cell = "-"
        } else {
          wv = w[bench SUBSEP bk "/write"]
          rv = r[bench SUBSEP bk "/read"]
          pv = p[bench SUBSEP bk "/peak"]
          rssv = rs[bench SUBSEP bk "/rss"]
          if (wv == "" && rv == "") cell = "-"
          else {
            if (rv == "" || rv == "—") wr = wv "/-"
            else if (wv == "" || wv == "—") wr = "-/" rv
            else wr = wv "/" rv
            # Append peak / rss if either is present. "-" when missing.
            pcell = (pv != "" && pv != "—") ? sprintf("%.1f", pv/1048576) : "-"
            rcell = (rssv != "" && rssv != "—") ? sprintf("%.1f", rssv/1048576) : "-"
            if (pcell == "-" && rcell == "-") cell = wr
            else cell = sprintf("%s %s/%sMB", wr, pcell, rcell)
          }
        }
        printf " | %-*s", vw, cell
      }
      printf "\n"
    }
  }
  function dash(len, s, i) { s=""; for (i=0;i<len;i++) s=s"-"; return s }
' "$RESULTS"

echo
echo "Legend: w/r = write/read in ms. peak/rss = heap high-water / RSS in MB."
echo "        peak tracks SAFE allocator growth (arena or bump heap);"
echo "        rss tracks OS-level resident memory for the whole process."
echo "        '-' = skipped or unavailable. 'FAIL' = runtime error."
