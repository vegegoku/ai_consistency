# Verification record

Final command (2026-10-28):

```sh
JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem mvn --batch-mode clean verify
```

**Result: BUILD SUCCESS, exit 0; 28 tests, 0 failures, 0 errors, 0 skipped.** The Maven lifecycle compiled the Java 21 service, ran unit and PostgreSQL API/security/concurrency/fault-injection tests, packaged the deployable Quarkus application, and ran the packaged-JVM restart integration test. A transient pool acquisition failure without an SQL state is covered separately and returns 503; unanticipated database invariant/fault injection errors return 500 with full rollback.

The packaged service was stopped and restarted against the same PostgreSQL 16 database. It returned the identical original receipt and replayed without moving money a second time. The subsequent load probe sent 500 unique payment instructions at an offered 100 requests/second for 5 seconds: **0 errors, p95 7.276 ms**. Reference host: Intel Core i7-11700, 16 logical CPUs, about 61 GiB RAM, Docker 29.9.2, PostgreSQL 16.15, connection pool 24. Traffic used local HTTP; this is an engineering smoke benchmark, and production TLS/network/replication qualification remains deployment-specific.

`docker compose config --quiet` and `python3 -m py_compile scripts/load-test.py` also exited successfully. Machine-readable results are in `verification-results.json`. The deployable entry point is `target/quarkus-app/quarkus-run.jar`, with its sibling `lib`, `app`, and `quarkus` directories. The Dockerfile copies this complete distribution.

No known functional acceptance gaps remain in the implemented scope. Deployment requires real identity-provider configuration, immutable principal-to-customer mapping, TLS certificates, least-privilege database credentials, backup/restore scheduling, legally approved retention policy, protected operational endpoints, and representative performance qualification. These environment-owned tasks are documented in OPERATIONS.md and cannot be established from a local development service alone.
