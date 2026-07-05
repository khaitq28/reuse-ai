#!/bin/bash
# =============================================================================
# Trading System Startup Script
# =============================================================================
# Every JVM flag here is a potential interview question. Know WHY each one exists.
# =============================================================================

JAR="target/electronic-trading-demo-1.0.0.jar"
LOG_DIR="logs"
mkdir -p "$LOG_DIR"

java \

  # ── HEAP: fix size so JVM never resizes (resize = GC pause) ──────────────
  -Xms4g \
  -Xmx4g \

  # ── Pre-fault all heap pages at startup (avoids OS page-fault latency
  #    during trading hours when a new memory page is first touched) ─────────
  -XX:+AlwaysPreTouch \

  # ── GC: ZGC for sub-millisecond pauses (Java 15+) ────────────────────────
  # Alternative: -XX:+UseG1GC -XX:MaxGCPauseMillis=5  (for Java 11+)
  -XX:+UseZGC \

  # ── Block explicit System.gc() calls from libraries
  #    (some libraries call System.gc() — catastrophic during market hours) ──
  -XX:+DisableExplicitGC \

  # ── JIT: compile to Tier 4 faster (shorter warmup window) ────────────────
  -XX:+TieredCompilation \
  -XX:CompileThreshold=1000 \

  # ── Enable @Contended padding outside java.* packages ────────────────────
  #    Required for false-sharing prevention on custom hot fields ────────────
  -XX:-RestrictContended \

  # ── GC Logging: always enable in production for post-mortem analysis ─────
  -Xlog:gc*:file=${LOG_DIR}/gc.log:time,uptime,level:filecount=5,filesize=20m \

  # ── Heap dump on OOM: capture state at the moment of failure ─────────────
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=${LOG_DIR}/heapdump-$(date +%Y%m%d-%H%M%S).hprof \

  # ── JFR: always-on low-overhead flight recording (< 1% overhead) ─────────
  #    Open with JDK Mission Control after a latency incident ────────────────
  -XX:StartFlightRecording=filename=${LOG_DIR}/trading.jfr,maxsize=256m,maxage=1h \

  # ── Stack size per thread (default ~512KB, 1m for deeper call stacks) ─────
  -Xss1m \

  # ── Metaspace: cap it to catch class-loader leaks early ──────────────────
  -XX:MaxMetaspaceSize=256m \

  # ── Spring Boot active profile ────────────────────────────────────────────
  -Dspring.profiles.active=prod \

  # ── Jar ───────────────────────────────────────────────────────────────────
  -jar "$JAR"
