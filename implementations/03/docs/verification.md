# Verification record — implementation 03

Command: `JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem mvn -B verify`

Result: BUILD SUCCESS, exit 0, 2026-10-28. Full output is `final-verification.log`; generated reports and packaged service are under `target/`.

| Suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| com.example.payments.DatabaseTest | 10 | 0 | 0 | 0 |
| com.example.payments.HttpTest | 3 | 0 | 0 | 0 |
| com.example.payments.ParserTest | 8 | 0 | 0 | 0 |
| com.example.payments.ApiTest | 4 | 0 | 0 | 0 |
| com.example.payments.RestartIT | 1 | 0 | 0 | 0 |

Total: 26 tests, zero failures/errors/skips. PostgreSQL 16 tests exercise real SQL locks, deferred balance triggers, immutable records, failures injected by a database credit trigger, concurrent requests, and actual packaged JVM restart.

Local workload: 1000 transfers scheduled at 100 requests/second, elapsed 9.998s, p95 7.250ms. Linux amd64, Java 21.0.5, 16 available processors, local PostgreSQL container and HTTP loopback. This short measurement excludes external network/TLS and is not a production guarantee. The integration test asserts p95 below 500ms. Detailed report: `target/performance.json`.

Additional checks: `docker compose config --quiet` and `python3 -m py_compile scripts/dev-token.py` exited 0. Docker image build, production TLS/IdP deployment, offsite backup restoration and longer representative load were not executed; setup and operational instructions are delivered. Flyway 9 emits a PostgreSQL 16 compatibility warning; migrations and all database tests pass. No functional implementation placeholders remain.
