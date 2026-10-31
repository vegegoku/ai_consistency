# Verification evidence

Verified on 2026-10-28 with Java 21.0.5, Maven 3.9.9, Docker 29.9.2 and PostgreSQL 16.15 Testcontainers.

Command: `JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem PATH=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem/bin:$PATH mvn -B verify`

Result: **BUILD SUCCESS**; 24 tests, zero failures, errors or skipped tests.

| Suite | Tests |
|---|---:|
| SecurityPolicyTest | 4 |
| PaymentIntegrationTest | 14 |
| PaymentCodecTest | 5 |
| ProcessDurabilityIT | 1 |

The process integration test launched the executable jar twice against the same PostgreSQL instance, validated a real signed JWT through a local JWKS server, discarded the first receipt, recovered it after restart, and confirmed replay had one financial effect. It then sent 200 real HTTP transfers at an offered rate of 100 requests/second: measured p95 9 ms, all 201 responses, 201 total completed payments, 402 ledger entries, and total balance 600.000 JOD. This short local smoke load is not a sustained capacity guarantee. `scripts/load.js` provides a 60-second k6 reference workload with p95/error thresholds.

Additional checks: `docker compose config --quiet` passed with local example environment values. The local Keycloak realm imported successfully into Keycloak 26.7.3, and its Alice token contained subject `alice`, audience `payment-service`, and `payments:read payments:write` scopes. Tokens and development containers were not retained. OpenAPI structure and every required error response were checked.

Production TLS, trusted OIDC configuration, restricted runtime credentials, central rate quotas, backup retention and restore drills require deployment-specific infrastructure settings as described in `operations.md`. No production service or credentials were created.
