# CI/CD DevSecOps Pipeline

## Why This Problem Matters

The CI/CD DevSecOps Pipeline problem is the infrastructure backbone that enables everything else in modern software engineering. Without it, even perfectly designed systems fail in production — not because of bad code, but because of bad deployment practices, undetected vulnerabilities, leaked secrets, and manual error-prone releases.

**What interviewers are really testing:** Can you build a system that enforces quality and security automatically, without relying on human discipline? Anyone can write "run tests before deploying." The hard question is: how do you design a pipeline where security cannot be bypassed, where secrets never touch disk, where rollback is automatic, and where a broken deployment is detected in seconds rather than discovered by customers?

In the banking and financial services world (Khai's domain), this matters even more. A vulnerability in production can mean regulatory fines, customer data exposure, and reputational damage that takes years to recover from. DevSecOps shifts security responsibility left — from the security team doing a quarterly review to every developer getting instant feedback on every commit.

The problem also tests your understanding of developer experience tradeoffs: a pipeline that takes 45 minutes discourages commits, leading to large risky batches; a pipeline that runs in 8 minutes encourages frequent small commits, reducing risk. Speed and thoroughness are in direct tension, and every design decision must navigate that tension.

---

## Key Insight Before Diving In

**Security is not a gate at the end of the pipeline — it is the pipeline itself.**

The old model: write code → test it → ship it → let security team review quarterly → patch vulnerabilities reactively.

The DevSecOps model: every commit triggers automated security checks. SAST catches SQL injection in your code before it merges. SCA catches Log4Shell in your dependencies before it's deployed. Trivy catches a vulnerable base image before it reaches production. Vault injects secrets at runtime so they never exist in your Git history or Docker layers.

The second key insight is **immutability**: the artifact (Docker image tagged with Git commit SHA) built and scanned in CI is the exact same artifact deployed to production. There is no "rebuild for production." This eliminates the "it worked in UAT" class of bugs — the image is identical across environments, only configuration changes.

Third insight: **fail fast, fail loud**. Every gate must be `allow_failure: false`. A pipeline that warns about HIGH CVEs but continues to production is not a security pipeline — it is security theater. The value of the pipeline comes from the gates being real and non-negotiable.

---

## The Core Problem: Why "Deploy on Push" Is Dangerous

At scale, "just FTP the JAR to the server" creates catastrophic problems:

**Problem 1: Manual steps are skipped under pressure.** When there's an incident and someone needs to hotfix production at 2am, they skip the test suite "just this once." That hotfix introduces a regression that causes a second incident. Automation eliminates the human judgment call.

**Problem 2: Secrets sprawl.** Without a secrets management strategy, developers put database passwords in `application.properties`, commit them to Git ("I'll rotate it later"), and that credential persists in Git history forever — even after the file is deleted. One leaked credential can mean full database access.

**Problem 3: Dependency blindness.** A Spring Boot application has 150+ transitive dependencies. Without SCA, you don't know that `jackson-databind:2.9.8` has a critical deserialization vulnerability until a pentester or, worse, an attacker finds it. OWASP Dependency-Check scans every JAR against the CVE database automatically.

**Problem 4: No rollback strategy.** Manual deployments rarely have a tested rollback plan. When the deployment fails, the team scrambles to figure out how to reverse it under pressure. Helm's `--atomic` flag automatically rolls back if health checks fail — rollback becomes the default, not an emergency procedure.

**Problem 5: Environment drift.** When you build once on the developer's laptop and the binary is promoted through environments, you accumulate risk. "Works on my machine" means different JVM flags, different library versions, different OS locale settings. Docker images with pinned tags eliminate drift by making environments identical.

---

## The Full Pipeline Architecture

```
Developer pushes code
       │
 ┌─────▼────────────────────────────────────────────────────────┐
 │                       CI Pipeline                            │
 │                                                              │
 │  Stage 1: Build & Compile        ← fail fast on syntax      │
 │     └─ Maven compile, cache deps                            │
 │                                                              │
 │  Stage 2: Test (parallel)                                    │
 │     ├─ Unit tests (JaCoCo coverage)                          │
 │     └─ Integration tests (real DB, real Redis)               │
 │                                                              │
 │  Stage 3: Security (parallel, ALL allow_failure: false)      │
 │     ├─ SAST: SonarQube           ← quality gate: 80% cov    │
 │     ├─ SCA: OWASP Dependency-Check ← CVSS < 7              │
 │     └─ DAST: OWASP ZAP           ← API scan in test env     │
 │                                                              │
 │  Stage 4: Package                                            │
 │     ├─ Multi-stage Docker build  ← immutable artifact        │
 │     ├─ Push to registry (tagged with $CI_COMMIT_SHA)         │
 │     └─ Trivy container scan      ← CVE scan on final image  │
 └────────────────────────┬─────────────────────────────────────┘
                          │ on main branch only
 ┌────────────────────────▼─────────────────────────────────────┐
 │                       CD Pipeline                            │
 │                                                              │
 │  Stage 5: Deploy DEV   → automated smoke tests               │
 │  Stage 6: Deploy UAT   → full regression suite               │
 │  Stage 7: Manual gate  → team lead / release manager         │
 │  Stage 8: Deploy PROD  → rolling/blue-green                  │
 │  Stage 9: Post-deploy  → Prometheus health check, auto-      │
 │                           rollback if error rate spikes       │
 └──────────────────────────────────────────────────────────────┘
```

**Why this ordering matters:** Each stage gate prevents a class of problems from reaching the next environment. Build failure means no artifact. Test failure means no security scan. Security failure means no Docker image. Image scan failure means no deployment. If you skip a stage or run them in the wrong order, you lose the fail-fast property.

**Why parallel stages within a tier:** SAST and SCA can run in parallel because they have no dependency on each other — both analyze the compiled artifact. This cuts pipeline time significantly. But SAST must complete before the artifact is packaged into Docker (you don't want to scan a vulnerable artifact into an image).

---

## GitLab CI Configuration — Deep Explanation

```yaml
# .gitlab-ci.yml
stages:
  - build
  - test
  - security
  - package
  - deploy-dev
  - deploy-uat
  - deploy-prod

variables:
  # Maven uses project-local .m2 cache → GitLab cache restores it between runs
  # Without this, every pipeline re-downloads 150+ dependencies → +3 minutes
  MAVEN_OPTS: "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"

  # Tag image with commit SHA, NOT 'latest'
  # Why: 'latest' is mutable — two deployments with 'latest' might have different images
  # SHA is immutable — the exact same image is promoted dev→uat→prod
  IMAGE_NAME: "$CI_REGISTRY_IMAGE:$CI_COMMIT_SHA"

  # Separate tag for human readability (git tag or branch name)
  IMAGE_LABEL: "$CI_REGISTRY_IMAGE:$CI_COMMIT_REF_SLUG"

# ─────────────── STAGE 1: BUILD ───────────────
build:
  stage: build
  image: maven:3.9-eclipse-temurin-17
  script:
    - mvn clean compile -q
    # -q (quiet) reduces noise. CI logs are for errors, not successful output.
  cache:
    # Cache the Maven dependency cache between pipeline runs
    # Key includes pom.xml hash → cache invalidates when dependencies change
    key:
      files: [pom.xml]
    paths: [.m2/repository/]
  artifacts:
    # target/ directory is passed to downstream stages
    # Without artifacts, the next stage has a fresh workspace with no compiled code
    paths: [target/]
    expire_in: 1 hour   # artifacts are ephemeral, not long-term storage

# ─────────────── STAGE 2a: UNIT TESTS ───────────────
unit-tests:
  stage: test
  image: maven:3.9-eclipse-temurin-17
  script:
    - mvn test -q
    # JaCoCo generates coverage report during 'test' phase automatically
    # via the jacoco-maven-plugin configured in pom.xml
  artifacts:
    reports:
      # GitLab natively parses JUnit XML and shows test results in MR view
      junit: target/surefire-reports/*.xml
    paths:
      # Coverage HTML report for SonarQube to consume in next stage
      - target/site/jacoco/
    expire_in: 1 hour
  cache:
    key:
      files: [pom.xml]
    paths: [.m2/repository/]
    policy: pull   # only read from cache, don't update (build stage writes it)

# ─────────────── STAGE 2b: INTEGRATION TESTS ───────────────
integration-tests:
  stage: test
  # GitLab 'services' spin up Docker containers linked to the test container
  # The service hostname matches the image name (postgres, redis)
  # Your Spring Boot test config points to these hostnames
  services:
    - name: postgres:15-alpine
      alias: postgres
    - name: redis:7-alpine
      alias: redis
  variables:
    POSTGRES_DB: testdb
    POSTGRES_USER: test
    POSTGRES_PASSWORD: test
    # Spring Boot test properties (overriding application.yml)
    SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres:5432/testdb"
    SPRING_REDIS_HOST: redis
  script:
    # -Pintegration-tests activates Maven profile that includes Testcontainers
    # or runs against the GitLab service containers above
    - mvn verify -Pintegration-tests -q
  # Integration tests run in parallel with unit tests (both in 'test' stage)
  # This saves ~2 minutes vs sequential execution

# ─────────────── STAGE 3a: SAST (SonarQube) ───────────────
sonarqube:
  stage: security
  image: maven:3.9-eclipse-temurin-17
  script:
    - |
      mvn sonar:sonar \
        -Dsonar.host.url=$SONAR_HOST_URL \
        -Dsonar.login=$SONAR_TOKEN \
        -Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml \
        -Dsonar.qualitygate.wait=true
        # qualitygate.wait=true makes Maven WAIT for SonarQube to process results
        # Without this flag, the pipeline moves on before SonarQube finishes analysis
        # and the quality gate check is never enforced — common misconfiguration!
  allow_failure: false   # NEVER set this to true for security stages
  # $SONAR_TOKEN is a GitLab CI/CD masked variable — it appears as [MASKED] in logs
  # and is never stored in the repository
  needs: [unit-tests]   # needs JaCoCo report from unit-tests artifact

# ─────────────── STAGE 3b: SCA (Dependency Check) ───────────────
dependency-check:
  stage: security
  image: owasp/dependency-check:latest
  script:
    - |
      dependency-check.sh \
        --project "$CI_PROJECT_NAME" \
        --scan ./target \
        --failOnCVSS 7 \
        --format HTML \
        --format JSON \
        --out dependency-check-report
        # --failOnCVSS 7 means: fail if any dependency has CVSS score >= 7.0
        # CVSS 7.0 = HIGH severity. This blocks HIGH and CRITICAL vulnerabilities.
        # Some teams use 9.0 (CRITICAL only) to reduce false positives initially.
        # Gradually tighten the threshold as the team matures.
  artifacts:
    paths: [dependency-check-report/]
    when: always   # upload report even on failure (to see what CVEs were found)
    expire_in: 1 week
  allow_failure: false
  # NVD database is auto-updated inside the container.
  # For air-gapped environments, host a mirror of the NVD data feed.

# ─────────────── STAGE 3c: DAST (OWASP ZAP — optional) ───────────────
dast-scan:
  stage: security
  image: owasp/zap2docker-stable
  script:
    # ZAP scans a running instance of the application
    # In GitLab, you can deploy to a review environment first, then scan it
    - zap-api-scan.py -t $DEV_APP_URL/v3/api-docs -f openapi -r zap-report.html
    # -f openapi: ZAP reads your OpenAPI spec to know all endpoints to test
    # This catches SQL injection, XSS, path traversal in actual running app
  artifacts:
    paths: [zap-report.html]
    when: always
  allow_failure: true   # DAST has more false positives; adjust as needed
  only: [main]

# ─────────────── STAGE 4a: DOCKER BUILD ───────────────
docker-build:
  stage: package
  image: docker:24
  services:
    - docker:24-dind   # Docker-in-Docker: allows running Docker inside GitLab container
  before_script:
    - docker login -u $CI_REGISTRY_USER -p $CI_REGISTRY_PASSWORD $CI_REGISTRY
  script:
    - |
      docker build \
        --label "git.commit=$CI_COMMIT_SHA" \
        --label "git.branch=$CI_COMMIT_REF_NAME" \
        --label "build.date=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        -t $IMAGE_NAME \
        -t $IMAGE_LABEL \
        .
      # Labels embed provenance metadata into the image
      # 'docker inspect <image>' reveals exactly which commit produced it
      docker push $IMAGE_NAME
      docker push $IMAGE_LABEL
  # needs all security stages to pass before building the image
  needs: [sonarqube, dependency-check, unit-tests, integration-tests]

# ─────────────── STAGE 4b: CONTAINER SCAN (Trivy) ───────────────
trivy-scan:
  stage: package
  image: aquasec/trivy:latest
  script:
    - |
      trivy image \
        --exit-code 1 \
        --severity HIGH,CRITICAL \
        --ignore-unfixed \
        --format table \
        $IMAGE_NAME
        # --exit-code 1: return non-zero exit if vulnerabilities found → fails pipeline
        # --severity HIGH,CRITICAL: ignore LOW/MEDIUM (too noisy for blocking)
        # --ignore-unfixed: don't fail on vulnerabilities with no fix available
        #   (you can't fix what upstream hasn't patched yet)
  allow_failure: false
  needs: [docker-build]

# ─────────────── STAGE 5: DEPLOY DEV ───────────────
deploy-dev:
  stage: deploy-dev
  image: alpine/helm:3.13
  environment:
    name: development
    url: https://dev.mycompany.com
  before_script:
    # Authenticate to Kubernetes cluster
    # KUBECONFIG_DEV is a GitLab CI/CD file variable containing the kubeconfig
    - mkdir -p ~/.kube && echo "$KUBECONFIG_DEV" > ~/.kube/config
  script:
    - |
      helm upgrade --install $CI_PROJECT_NAME ./helm \
        --namespace dev \
        --create-namespace \
        --set image.tag=$CI_COMMIT_SHA \
        --set env=dev \
        --set replicaCount=1 \
        --atomic \
        --timeout 5m
        # --atomic: if deployment fails, automatically roll back to previous release
        # Without --atomic, a failed deployment leaves the cluster in a broken state
        # --timeout: give pods 5 minutes to become ready before declaring failure
  after_script:
    # Smoke test: verify the app is actually responding
    - sleep 30
    - kubectl rollout status deployment/$CI_PROJECT_NAME -n dev
    - curl -f https://dev.mycompany.com/actuator/health || exit 1
  only: [main]
  needs: [trivy-scan]

# ─────────────── STAGE 6: DEPLOY UAT ───────────────
deploy-uat:
  stage: deploy-uat
  image: alpine/helm:3.13
  environment:
    name: uat
    url: https://uat.mycompany.com
  before_script:
    - mkdir -p ~/.kube && echo "$KUBECONFIG_UAT" > ~/.kube/config
  script:
    - |
      helm upgrade --install $CI_PROJECT_NAME ./helm \
        --namespace uat \
        --set image.tag=$CI_COMMIT_SHA \
        --set env=uat \
        --set replicaCount=2 \
        --atomic --timeout 5m
  after_script:
    # Trigger regression test suite (Newman/Postman collection, RestAssured, etc.)
    - newman run ./tests/regression-collection.json --environment uat-env.json
  only: [main]
  needs: [deploy-dev]

# ─────────────── STAGE 7: DEPLOY PROD (manual gate) ───────────────
deploy-prod:
  stage: deploy-prod
  image: alpine/helm:3.13
  environment:
    name: production
    url: https://mycompany.com
  when: manual                # human must click "play" in GitLab UI
  before_script:
    - mkdir -p ~/.kube && echo "$KUBECONFIG_PROD" > ~/.kube/config
  script:
    - |
      helm upgrade --install $CI_PROJECT_NAME ./helm \
        --namespace prod \
        --set image.tag=$CI_COMMIT_SHA \
        --set env=prod \
        --set replicaCount=3 \
        --atomic --timeout 10m
  after_script:
    # Post-deploy validation script (checks Prometheus metrics)
    - ./scripts/post-deploy-check.sh
  only: [main]
  needs: [deploy-uat]
  # GitLab records who clicked "play" → full audit trail of who deployed what when
```

---

## Multi-Stage Dockerfile — Deep Security Rationale

The Dockerfile is not just a build script. Every decision has a security implication.

```dockerfile
# ─── Stage 1: Build Stage ───────────────────────────────────────────────────
# Use the full Maven + JDK image for compilation
# This image has Maven, JDK compiler, all dev tools — it's large (~500MB)
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /app

# Copy pom.xml first (before source code)
# WHY: Docker layer caching. pom.xml rarely changes; source code changes constantly.
# When only source changes, Docker reuses the 'mvn dependency:go-offline' layer
# → saves 2-3 minutes per build by not re-downloading 150+ dependencies
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source code last (invalidates fewer layers)
COPY src ./src

# Build the fat JAR. Skip tests here — tests already ran in CI pipeline.
# Running tests inside Docker would re-run them unnecessarily and slow the build.
RUN mvn package -DskipTests -q

# ─── Stage 2: Runtime Stage ─────────────────────────────────────────────────
# CRITICAL: Use JRE (runtime), NOT JDK (development kit)
# WHY: JDK includes javac, jmap, jstack, jconsole — tools an attacker could use
# to inspect or manipulate a compromised container. JRE is the minimum required.
# alpine variant: ~200MB vs ~500MB for full debian variant → smaller attack surface
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Security hardening: never run as root inside containers
# WHY: If an attacker achieves code execution inside the container (via RCE vulnerability),
# running as root gives them root inside the container, which on some setups
# translates to root on the host. Non-root user limits the blast radius.
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Copy ONLY the compiled artifact from the build stage
# The final image does NOT contain:
# - Maven source code
# - test files
# - pom.xml
# - JDK tools
# - any build-time dependencies
# This is the core security benefit of multi-stage builds.
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

# Kubernetes uses this for pod health checks
# wget is available on alpine; curl is not (smaller image)
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -q --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", \
  # Container-aware memory management: JVM respects cgroup memory limits
  # Without this (Java < 8u191), JVM reads host memory (e.g., 64GB)
  # and sets heap to 25% of that (16GB), causing OOM kills in a 512MB pod
  "-XX:+UseContainerSupport", \

  # Use 75% of container memory limit for heap
  # 25% reserved for Metaspace, thread stacks, off-heap NIO buffers
  "-XX:MaxRAMPercentage=75.0", \

  # Faster random number generation (important for SSL/TLS key generation)
  # /dev/random blocks; /dev/urandom does not (safe for most uses)
  "-Djava.security.egd=file:/dev/./urandom", \

  "-jar", "app.jar"]
```

**Why the final image is only ~150MB:** The builder stage (Maven + JDK + source) is ~800MB during build, but it's discarded after Stage 1. The final image contains only JRE + your application JAR. Trivy has far fewer CVEs to find in a 150MB image than a 800MB one.

---

## Secrets Management — Why Vault, Not Environment Variables

### The Wrong Way (Common Antipatterns)

```yaml
# WRONG: Secret in GitLab CI variables (shows in job logs if misconfigured)
deploy:
  script:
    - export DB_PASSWORD=supersecret123   # logged to console on error

# WRONG: Secret in Dockerfile (baked into image layers, readable with 'docker history')
ENV DB_PASSWORD=supersecret123

# WRONG: Secret in Kubernetes Deployment YAML (stored in etcd unencrypted by default)
env:
  - name: DB_PASSWORD
    value: supersecret123
```

### The Right Way: Vault Dynamic Secrets

```yaml
# Kubernetes Deployment with Vault Agent Injector
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-service
spec:
  template:
    metadata:
      annotations:
        # Vault Agent sidecar reads these annotations and injects secrets
        vault.hashicorp.com/agent-inject: "true"

        # The Vault role 'my-service' has a policy granting access
        # to specific secret paths — principle of least privilege
        vault.hashicorp.com/role: "my-service"

        # Inject database credentials from Vault KV path
        vault.hashicorp.com/agent-inject-secret-db: "secret/data/my-service/db"

        # Vault Agent renders the secret as environment variable format
        # into /vault/secrets/db file, which Spring Boot reads via spring.config.import
        vault.hashicorp.com/agent-inject-template-db: |
          {{- with secret "secret/data/my-service/db" -}}
          spring.datasource.password={{ .Data.data.password }}
          spring.datasource.username={{ .Data.data.username }}
          {{- end }}

        # Dynamic secrets: Vault generates short-lived DB credentials on startup
        # and auto-rotates them before they expire → stolen credential is useless in minutes
        vault.hashicorp.com/agent-inject-secret-db-dynamic: "database/creds/my-service-role"
```

**Why dynamic secrets are transformative:** With static secrets, if someone steals your database password (leaked in logs, compromised pipeline), it works forever until manually rotated. With Vault dynamic secrets, the credential is generated when the pod starts and has a TTL of, say, 1 hour. Vault auto-renews it while the pod is healthy. If a credential is stolen, it expires in at most 1 hour — and you can revoke it immediately. This changes the attacker's window from "infinite" to "minutes."

---

## Deployment Strategies — Choosing the Right One

### Rolling Update (Default — Low Risk, Always Available)

```yaml
# kubernetes deployment strategy
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1         # allow 1 extra pod above desired count during rollout
    maxUnavailable: 0   # NEVER take pods below desired count (zero downtime)
```

**How it works:** If you have 4 pods, rolling update starts 1 new pod (total: 5), waits until it's healthy, terminates 1 old pod (total: 4 again), repeats. Traffic is always flowing to at least 4 pods.

**When to use:** Standard releases, small changes, frequent deployments.

**Risk:** Old and new code run simultaneously. If you have a breaking API change or database schema that's incompatible with old code, rolling update causes errors during the transition window.

### Blue-Green (Full Swap — Safe for Breaking Changes)

```
Blue (v1.0):  Deployment "app-blue"   ← Ingress routes 100% traffic here
Green (v2.0): Deployment "app-green"  ← Running but receiving no traffic

Steps:
1. Deploy app-green with new image (while blue continues serving)
2. Run full smoke test suite against app-green (internally, no user traffic)
3. Patch the Ingress to route traffic to app-green:
   kubectl patch ingress app -p '{"spec":{"rules":[{"http":{"paths":[{"backend":{"service":{"name":"app-green"}}}]}}]}}'
4. Monitor for 30 minutes → Prometheus alerts, error rate, P99 latency
5. If healthy: delete app-blue
6. If unhealthy: patch Ingress back to app-blue (rollback in < 30 seconds)
```

**Why this beats rolling update for risky changes:** The new version is fully tested before a single user request hits it. Rollback is instant (Ingress patch) not gradual.

**Cost:** Requires 2x infrastructure during transition window. For large clusters, this is non-trivial.

### Canary Deployment (Gradual Traffic Shift — Risk Reduction)

```yaml
# Using Argo Rollouts for Canary (more control than native Kubernetes)
apiVersion: argoproj.io/v1alpha1
kind: Rollout
spec:
  strategy:
    canary:
      steps:
        - setWeight: 5     # 5% of traffic goes to new version
        - pause: {}        # wait for manual approval (or automated metric check)
        - setWeight: 25    # ramp to 25%
        - pause:
            duration: 10m  # auto-advance after 10 minutes if metrics are healthy
        - setWeight: 50
        - pause:
            duration: 10m
        - setWeight: 100   # full rollout
      analysis:            # automatic metric check at each pause
        templates:
          - templateName: success-rate
        args:
          - name: service-name
            value: my-service-canary
```

**The value of canary:** You expose 5% of real users (not synthetic tests) to the new version. Real user traffic reveals bugs that smoke tests miss — unexpected browser behavior, edge case inputs, third-party API interactions. If the error rate spikes at 5% traffic, you've caught it before it affects 95% of users.

---

## Quality Gates — The Thresholds and Why

```
┌──────────────────┬───────────────────────┬─────────────┬──────────────────────────────────────────┐
│ Gate             │ Tool                  │ Threshold   │ Why This Number                          │
├──────────────────┼───────────────────────┼─────────────┼──────────────────────────────────────────┤
│ Code coverage    │ JaCoCo + SonarQube    │ > 80%       │ 100% is aspirational but creates test-   │
│                  │                       │             │ for-coverage gaming. 80% forces coverage  │
│                  │                       │             │ of main paths without penalizing edge      │
│                  │                       │             │ cases that are genuinely hard to test.    │
├──────────────────┼───────────────────────┼─────────────┼──────────────────────────────────────────┤
│ Code smells      │ SonarQube             │ 0 blocker   │ Blockers/criticals represent actual bugs  │
│                  │                       │ 0 critical  │ or security risks (null deref, infinite   │
│                  │                       │             │ loop). Majors/minors are style — warn only│
├──────────────────┼───────────────────────┼─────────────┼──────────────────────────────────────────┤
│ Dependency CVEs  │ OWASP DC / Nexus IQ   │ CVSS < 7.0  │ 7.0 = HIGH. Most exploitable vulns are   │
│                  │                       │             │ HIGH+. Start at 9.0 to reduce noise,      │
│                  │                       │             │ tighten to 7.0 as team matures.           │
├──────────────────┼───────────────────────┼─────────────┼──────────────────────────────────────────┤
│ Container vulns  │ Trivy                 │ No HIGH/    │ Base images accumulate CVEs over time.    │
│                  │                       │ CRITICAL    │ Weekly image rebuild (even without code   │
│                  │                       │ (unfixed)   │ changes) picks up patched base images.    │
├──────────────────┼───────────────────────┼─────────────┼──────────────────────────────────────────┤
│ Code duplication │ SonarQube             │ < 10%       │ High duplication means copy-paste code:   │
│                  │                       │             │ fix a bug in one place, miss it in 5.     │
│                  │                       │             │ 10% allows some repetition without        │
│                  │                       │             │ premature abstraction.                    │
├──────────────────┼───────────────────────┼─────────────┼──────────────────────────────────────────┤
│ Test pass rate   │ JUnit / Surefire      │ 100%        │ 99% pass rate sounds good but means 1    │
│                  │                       │             │ known broken test ignored in production.  │
│                  │                       │             │ 100% or fix the test or delete it.        │
└──────────────────┴───────────────────────┴─────────────┴──────────────────────────────────────────┘
```

---

## Post-Deploy Health Check Script

```bash
#!/bin/bash
# scripts/post-deploy-check.sh
# Runs after every production deployment — fails fast if deployment is bad

set -euo pipefail

SERVICE=$CI_PROJECT_NAME
NAMESPACE=prod
APP_URL=${PROD_APP_URL}
ERROR_RATE_THRESHOLD=0.01  # 1% error rate maximum
LATENCY_THRESHOLD_MS=500   # P99 latency must be < 500ms
PROMETHEUS_URL=${PROMETHEUS_URL}

echo "=== Post-Deploy Health Check: $SERVICE ==="

# Step 1: Wait for Kubernetes to consider rollout complete
echo "[1/5] Checking Kubernetes rollout status..."
kubectl rollout status deployment/$SERVICE -n $NAMESPACE --timeout=120s

# Step 2: Verify readiness probe passes for all pods
echo "[2/5] Verifying all pods are ready..."
READY=$(kubectl get deployment $SERVICE -n $NAMESPACE -o jsonpath='{.status.readyReplicas}')
DESIRED=$(kubectl get deployment $SERVICE -n $NAMESPACE -o jsonpath='{.status.replicas}')
if [ "$READY" != "$DESIRED" ]; then
  echo "FAIL: Only $READY/$DESIRED pods ready"
  exit 1
fi

# Step 3: Application-level health check (Spring Boot Actuator)
echo "[3/5] Checking application health endpoint..."
sleep 30  # give the app 30 seconds to fully initialize before health check
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$APP_URL/actuator/health")
if [ "$HTTP_STATUS" != "200" ]; then
  echo "FAIL: Health endpoint returned $HTTP_STATUS"
  kubectl rollout undo deployment/$SERVICE -n $NAMESPACE  # trigger rollback
  exit 1
fi

# Step 4: Check error rate in Prometheus
# This catches runtime errors that don't show up in readiness probes
echo "[4/5] Checking error rate in Prometheus..."
ERROR_RATE=$(curl -s "$PROMETHEUS_URL/api/v1/query" \
  --data-urlencode 'query=rate(http_requests_total{status=~"5..",service="'$SERVICE'"}[2m]) / rate(http_requests_total{service="'$SERVICE'"}[2m])' \
  | jq -r '.data.result[0].value[1]' 2>/dev/null || echo "0")

if (( $(echo "$ERROR_RATE > $ERROR_RATE_THRESHOLD" | bc -l) )); then
  echo "FAIL: Error rate $ERROR_RATE exceeds threshold $ERROR_RATE_THRESHOLD"
  kubectl rollout undo deployment/$SERVICE -n $NAMESPACE
  exit 1
fi

# Step 5: Check P99 latency
echo "[5/5] Checking P99 latency..."
P99=$(curl -s "$PROMETHEUS_URL/api/v1/query" \
  --data-urlencode 'query=histogram_quantile(0.99, rate(http_request_duration_seconds_bucket{service="'$SERVICE'"}[2m])) * 1000' \
  | jq -r '.data.result[0].value[1]' 2>/dev/null || echo "0")

if (( $(echo "$P99 > $LATENCY_THRESHOLD_MS" | bc -l) )); then
  echo "FAIL: P99 latency ${P99}ms exceeds threshold ${LATENCY_THRESHOLD_MS}ms"
  kubectl rollout undo deployment/$SERVICE -n $NAMESPACE
  exit 1
fi

echo "=== All health checks passed. Deployment successful. ==="
```

**Why automate rollback:** Manual rollback under pressure is error-prone. The operator is stressed, documentation is incomplete, the rollback command differs per environment. Automating it ensures rollback is always correct and takes effect within seconds of detecting a problem — before customers notice.

---

## Helm Chart — Production-Ready Configuration

```yaml
# helm/values.yaml — annotated with security and reliability rationale

replicaCount: 3    # Always ≥ 2 for HA. 3 allows one pod failure + one rolling update
                   # simultaneously without service degradation.

image:
  repository: registry.mycompany.com/my-service
  tag: latest      # overridden by --set image.tag=$CI_COMMIT_SHA in pipeline
  pullPolicy: IfNotPresent   # don't pull if image already on node → faster startup

# Pod Disruption Budget: ensures at least 2 pods are always available
# Prevents cluster maintenance (node drain) from taking all pods offline at once
podDisruptionBudget:
  minAvailable: 2

service:
  type: ClusterIP   # ClusterIP = internal only. External access via Ingress only.
  port: 8080

ingress:
  enabled: true
  className: nginx
  annotations:
    # cert-manager automatically provisions and renews Let's Encrypt TLS certificate
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    # HSTS: force HTTPS for 1 year, include subdomains
    nginx.ingress.kubernetes.io/configuration-snippet: |
      add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
  host: my-service.mycompany.com
  tls:
    - secretName: my-service-tls
      hosts: [my-service.mycompany.com]

resources:
  requests:
    memory: 512Mi   # JVM initial heap. Scheduler uses this for placement decisions.
    cpu: 250m       # 0.25 vCPU sustained. Start conservative, adjust with profiling.
  limits:
    memory: 1Gi     # JVM max heap + overhead. App is OOM-killed if exceeded.
    cpu: 1000m      # 1 vCPU burst. CPU throttling above this (not kill).

# Liveness: is the app alive? Restart if not.
# Separate from readiness: an app can be alive but not ready (warming up caches)
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 60   # JVM startup + Spring context initialization time
  periodSeconds: 15
  failureThreshold: 3        # 3 consecutive failures → restart pod

# Readiness: is the app ready to receive traffic? Remove from load balancer if not.
# Spring Boot Actuator /readiness checks DB connection, cache connection, etc.
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  failureThreshold: 3

# Horizontal Pod Autoscaler
autoscaling:
  enabled: true
  minReplicas: 3
  maxReplicas: 20
  targetCPUUtilizationPercentage: 70   # scale up before hitting 100% (reactive scaling)
  targetMemoryUtilizationPercentage: 80

# Anti-affinity: spread pods across nodes
# Prevents a single node failure from taking all pods offline
affinity:
  podAntiAffinity:
    preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchExpressions:
              - key: app
                operator: In
                values: [my-service]
          topologyKey: kubernetes.io/hostname
```

---

## Security Hardening Checklist

```
Container Security:
  ✓ Non-root user (USER appuser in Dockerfile)
  ✓ Read-only root filesystem: securityContext.readOnlyRootFilesystem: true
  ✓ No privilege escalation: allowPrivilegeEscalation: false
  ✓ Drop ALL Linux capabilities: capabilities.drop: [ALL]
  ✓ JRE only, not JDK in runtime image
  ✓ Alpine base (minimal attack surface)
  ✓ Weekly image rebuild (pick up patched base images)

Secrets:
  ✓ No secrets in Git (even encrypted — key can be compromised)
  ✓ No secrets in Docker image layers
  ✓ No secrets in Kubernetes YAML (etcd unencrypted by default)
  ✓ Vault dynamic secrets with short TTL
  ✓ GitLab CI masked variables for pipeline-specific secrets

Network:
  ✓ Kubernetes NetworkPolicy: pods can only receive traffic from Ingress controller
  ✓ mTLS between services (Istio/Linkerd service mesh)
  ✓ TLS on Ingress with auto-renewed cert-manager certificates
  ✓ HSTS header enforced

Pipeline:
  ✓ allow_failure: false on ALL security gates
  ✓ Separate kubeconfig per environment (dev cannot deploy to prod)
  ✓ Manual approval gate before production
  ✓ Full audit trail (GitLab records who triggered what deployment)
  ✓ Image tagged with Git SHA (immutable, traceable)
```

---

## Interview Questions & Answers

### Q1: What does "shift left" mean in DevSecOps, and why does it matter?

"Shift left" means moving security testing earlier in the development lifecycle — from post-release pentesting to pre-commit scanning. The "left" refers to the position on a timeline of development stages: requirements → design → development → testing → staging → production. Security was traditionally done at the far right (post-release), meaning vulnerabilities were found after the code was already in customers' hands.

The value of shifting left is economic and operational. Studies show that fixing a vulnerability during development costs roughly $30 (a code change and PR review), while fixing it in production requires an emergency hotfix, potential customer breach notification, regulatory reporting, and reputational damage — orders of magnitude more expensive. By running SAST on every commit, developers get feedback within minutes rather than months. The psychological shift is also important: when a developer sees "SQL injection vulnerability on line 47" in their own PR, they learn from it immediately. A quarterly security report with abstract findings doesn't create the same learning.

### Q2: How do you prevent secrets from being committed to Git?

Defense in depth across multiple layers. First, pre-commit hooks using tools like `git-secrets` or `detect-secrets` scan staged files for patterns matching API keys, passwords, and private keys — the commit fails locally before it reaches Git. Second, GitLab has built-in secret detection in the CI pipeline that scans every push, including history. Third, the organization policy uses Vault as the single source of truth for secrets: developers never generate or handle production credentials directly — the application authenticates to Vault at runtime and receives a short-lived credential.

If a secret is committed despite these controls, the response is: immediately rotate the credential (assume it's compromised), then use `git filter-branch` or BFG Repo Cleaner to rewrite Git history to remove the secret. However, this is a last resort — assume anyone who cloned the repo before the rewrite has the secret. The Vault approach eliminates this class of problem entirely because the actual credential never exists in the codebase — only a Vault path reference does, which is useless without Vault authentication.

### Q3: Explain the difference between SAST, DAST, and SCA. When should each run?

**SAST (Static Application Security Testing)** analyzes source code without running it. It finds vulnerabilities in your code: SQL injection, hardcoded credentials, insecure cryptography, null pointer dereferences. Tools: SonarQube, Checkmarx, Semgrep. Run time: during the CI pipeline on every commit. Fast feedback but produces false positives and cannot find runtime-only vulnerabilities.

**DAST (Dynamic Application Security Testing)** tests a running application by sending malicious inputs and observing responses. It finds vulnerabilities that only manifest at runtime: authentication bypasses, session fixation, server-side request forgery, some XSS variants. Tools: OWASP ZAP, Burp Suite. Run time: after deployment to a test environment (UAT/staging). Slower (takes 20-30 minutes per scan) and requires a running application.

**SCA (Software Composition Analysis)** audits your third-party dependencies — your application's transitive dependency tree — against known CVE databases. It finds vulnerabilities like Log4Shell or Spring4Shell that exist in libraries you use, not code you wrote. Tools: OWASP Dependency-Check, Snyk, Nexus IQ. Run time: CI pipeline on every build. Critical because 80%+ of a typical application's code is third-party dependencies, and most breaches exploit known, patched vulnerabilities in outdated libraries.

A mature DevSecOps pipeline runs all three: SAST and SCA in CI (fast feedback), DAST in the staging environment (runtime validation), and periodic penetration testing by humans for what automation misses.

### Q4: Why use rolling update by default, and when should you switch to blue-green?

Rolling update is the default because it requires no extra infrastructure, completes gradually, and Kubernetes handles it natively. The key configuration is `maxUnavailable: 0` — this ensures zero downtime by never reducing the number of serving pods below the desired count during the update. Kubernetes starts new pods, waits for their readiness probes to pass, then terminates old pods, one at a time.

The problem with rolling update is that old and new code run simultaneously during the transition. This is safe for backward-compatible changes but dangerous when you have breaking API changes, database schema changes that the old code cannot handle, or changes to shared message formats. For those cases, blue-green is appropriate: deploy the new version completely in parallel with the old, run full smoke tests against the new version with zero user traffic, then instantly switch the load balancer to route all traffic to the new version. Rollback is equally instant — switch the load balancer back. Blue-green's weakness is cost (2x infrastructure during switchover) and complexity (need to manage two live deployments). Canary is a third option when you want to validate the new version under real user traffic before full rollout — expose 5% of traffic to the new version, measure error rates and latency in Prometheus, and gradually increase the percentage if metrics are healthy.

### Q5: How does Helm's `--atomic` flag work, and why is it critical?

`helm upgrade --atomic` enables automatic rollback if the deployment fails. Specifically, Helm waits for all pods to pass their readiness probes within the timeout window. If any pod fails to become ready (crash loop, OOM kill, readiness probe timeout), Helm immediately runs `helm rollback` to the previous release version. Without `--atomic`, a failed deployment leaves the cluster in a degraded state — some pods running the new version (broken), some still on the old version, with the operator manually figuring out what happened at 3am.

The `--timeout` parameter (e.g., `--timeout 5m`) defines how long Helm waits. Set it longer than your application's worst-case startup time: a JVM application that initializes heavy Spring context might take 45-60 seconds to be ready. If timeout is 60 seconds and startup takes 75, the deployment always "fails" and rolls back despite the app being healthy. The combination of `--atomic` and appropriate `--timeout` with proper `livenessProbe.initialDelaySeconds` creates a self-healing deployment that never leaves the cluster in an inconsistent state.

### Q6: Why tag Docker images with Git commit SHA instead of "latest"?

The `latest` tag is mutable — `docker pull my-service:latest` today might pull a different image than the same command ran yesterday. This creates traceability problems: when debugging a production incident, you cannot determine what code is running by looking at the tag. SHA tags are immutable — `my-service:abc1234def5` refers to one specific image forever.

Immutable tags enable the core DevSecOps principle of artifact promotion: the same Docker image built and scanned in CI is promoted through DEV → UAT → PROD without rebuilding. This eliminates the class of bugs where "it worked in UAT because it used slightly different dependencies than what went to production." The Git SHA also creates a complete audit trail: given a production incident at 2pm, you can look at Kubernetes (`kubectl get deployment -o jsonpath='{.spec.template.spec.containers[0].image}'`), extract the SHA, run `git log abc1234def5` to see the exact commit, `git diff` to see exactly what changed, and trace to the PR and the developer. This is invaluable for incident postmortems and regulatory audit requirements in banking.

### Q7: How would you handle a vulnerability discovered in a dependency after the image is deployed to production?

The response has three phases. **Immediate triage**: determine the CVE severity, whether your application actually uses the vulnerable code path (many CVSS 7+ CVEs require specific usage to be exploitable), and whether a fixed version of the dependency is available. Tools like Snyk show whether the vulnerable function is in your call graph.

**Remediation**: update the dependency in `pom.xml` to the patched version, trigger a new pipeline build. The new image is built, SAST/SCA/Trivy runs again to verify the CVE is resolved, and it's deployed. This is why the pipeline runs on every commit — the feedback loop is minutes, not days.

**Prevention**: configure Dependabot or Renovate Bot to automatically open PRs when new dependency versions are published. This keeps the dependency tree fresh, reducing the window between a CVE being published and your team being notified. Also schedule weekly pipeline runs even without code changes — the base Docker image receives patches independently of your code, and weekly rebuilds pick up patched base images without requiring developer action. In a banking context, document the incident for compliance: when was the CVE discovered, when was it patched, was there any customer impact.

### Q8: What is the GitLab CI `needs` keyword and how does it affect pipeline execution?

Without `needs`, GitLab CI runs each stage only after the previous stage completes — all jobs in stage 2 wait for all jobs in stage 1, regardless of dependency. This creates a bottleneck: if you have 6 independent security scanners, they all wait for the last test to finish before any of them start.

The `needs` keyword creates an explicit directed acyclic graph (DAG) of job dependencies, allowing jobs to start as soon as their specific dependencies complete rather than waiting for the entire previous stage. For example: `trivy-scan: needs: [docker-build]` — Trivy starts the moment the Docker build finishes, without waiting for other jobs in the package stage. Combined with `needs: []` (empty list), a job can run at pipeline start with no dependencies at all. The practical effect is significant pipeline time reduction: a pipeline with smart `needs` configuration often completes 30-40% faster than the same pipeline with sequential stages, because independent jobs execute in parallel and downstream jobs don't wait for unrelated upstream work.

### Q9: How do you ensure zero-downtime deployment with database schema changes?

Database migrations are the hardest part of zero-downtime deployment because rolling updates run old and new code simultaneously, but both are connecting to the same database. The solution is the Expand-Contract (or Parallel Change) pattern, applied over multiple deployments.

**Phase 1 — Expand**: the migration adds the new column/table/index without removing anything. Both old and new code must be able to run against this schema. If renaming a column, add the new column and copy data, but keep the old column too. Deploy new application version: both the old (reading old column) and new (reading new column) code work correctly.

**Phase 2 — Migrate**: a background job or batch process migrates all existing data to the new structure.

**Phase 3 — Contract**: after 100% of pods are running the new code and no old code remains, a follow-up migration removes the old column/table. This is a separate deployment from Phase 1.

This is why Liquibase and Flyway are preferred in microservices over JPA `spring.jpa.hibernate.ddl-auto=update` — they give explicit control over migration sequencing and history. Never use auto DDL in production.

### Q10: How do you design the pipeline to give fast feedback to developers without sacrificing security?

The key is parallelism and selective triggers. Unit tests and SAST can run in parallel from the start because neither depends on the other. Integration tests run in parallel with unit tests. OWASP Dependency-Check runs in parallel with SonarQube. Docker build starts immediately after ALL security gates pass (not after every stage individually finishes). This parallelism, combined with Maven dependency caching, typically achieves an 8-10 minute total pipeline time despite running 6+ security tools.

For branch pipelines (feature branches, not main), run only the fast checks: compile, unit tests, SonarQube. Save integration tests, DAST, and full container scans for main branch only — these are the expensive checks that run once per merge. This gives developers near-instant feedback (< 5 minutes on feature branches) for the most common workflow while ensuring the full security suite runs before anything touches a real environment. Also consider test optimization: Surefire's parallel test execution (`-T 4` for 4 threads) and `mvn -pl` for multi-module builds that only retest changed modules further reduce feedback time without sacrificing test coverage.

### Q11: Describe how you would roll back a bad production deployment. What are the safeguards?

The first safeguard is automated rollback: Helm's `--atomic` flag automatically rolls back if pods fail to become ready within the timeout window, before a single request hits broken pods. The second safeguard is the post-deploy health check script — it runs after deployment and explicitly checks error rate and P99 latency in Prometheus. If either metric exceeds the threshold, the script calls `kubectl rollout undo` or `helm rollback` immediately.

If both automated safeguards miss the issue (e.g., a slow-burn degradation that takes 30 minutes to surface), the manual process is: `helm history my-service -n prod` to see previous releases, then `helm rollback my-service <revision> -n prod --wait`. Helm stores release history as Kubernetes Secrets by default, so rollback is always available even if the original CI pipeline is gone. The limitation is database schema — Helm rollback doesn't rollback database migrations. This is why the Expand-Contract pattern is critical: old code must be able to run against the migrated schema. If you have a destructive migration (dropped column), rollback requires a separate forward migration to re-add the column, not a revert of the Helm release. Post-incident: update the post-deploy health check thresholds based on what was missed, add the specific metric that would have caught the issue earlier, and run a blameless postmortem.

### Q12: How do you handle secrets rotation without application downtime?

The core challenge is that traditional secrets require application restart to pick up new values (environment variable change → pod restart). Vault solves this natively through two mechanisms.

**Vault Agent auto-renewal:** The Vault Agent sidecar holds a lease on the secret and renews it before expiration. For dynamic database credentials, Vault generates a new credential pair before the existing one expires, writes it to the in-memory file that Spring Boot reads, and signals the application to reload its DataSource connection pool. Spring Boot Actuator's `/actuator/refresh` endpoint (with Spring Cloud Config) reloads `@RefreshScope` beans from the updated file without restart.

**Graceful credential handoff for static secrets:** when rotation is necessary (security incident, compliance requirement), the sequence is: add the new credential to Vault (keeping the old one active), deploy application version that reads from the new Vault path, verify all pods have picked up the new credential, then deactivate the old credential. Zero-downtime because at no point does any pod lack a valid credential. The operational requirement is that credential rotation is documented and practiced quarterly — not saved for an incident. Teams that only rotate credentials during breaches always make mistakes under pressure.

---

## Architecture Summary

```
Developer Laptop
     │ git push
     ▼
GitLab Repository
     │
     ├─► CI Pipeline (always)
     │    ├─ Build (Maven cache → fast)
     │    ├─ Test (unit || integration, parallel)
     │    ├─ Security (SAST || SCA || DAST, parallel, all non-skippable)
     │    └─ Package (Docker build → Trivy scan → push to registry)
     │
     └─► CD Pipeline (main branch only)
          ├─ DEV (auto, smoke test)
          ├─ UAT (auto, regression test)
          ├─ [MANUAL GATE: human approval]
          └─ PROD (rolling/blue-green, Prometheus post-deploy check, auto-rollback)

Security at Every Layer:
  Code:        SonarQube SAST (your code)
  Dependencies: OWASP DC SCA (their code)
  Runtime:     OWASP ZAP DAST (running app)
  Container:   Trivy (image CVEs)
  Secrets:     Vault (dynamic, short-lived)
  Network:     K8s NetworkPolicy + mTLS
  Cluster:     Non-root pods, read-only FS, dropped capabilities
```

---

## Tech Stack

| Concern | Tool | Why |
|---|---|---|
| CI/CD | GitLab CI | Integrated SCM + pipeline, DAG support |
| Build | Maven + Java 17 | Khai's stack, Spring Boot standard |
| SAST | SonarQube | Quality + security rules, quality gate integration |
| SCA | OWASP Dependency-Check | Free, CVE database integration, CVSS threshold |
| DAST | OWASP ZAP | OpenAPI spec support, API scanning |
| Container Scan | Trivy (Aqua) | Fast, zero-config, excellent CVE DB |
| Registry | GitLab Registry / ECR | Immutable SHA-tagged images |
| Deploy | Helm + Kubernetes | Declarative, history, atomic rollback |
| Secrets | HashiCorp Vault | Dynamic secrets, lease-based rotation |
| Monitoring | Prometheus + Grafana | Post-deploy metric validation |
| Logging | ELK Stack | Audit trail, incident forensics |