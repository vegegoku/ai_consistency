# Verification evidence

Executed on 2026-10-28 using Temurin Java 21.0.5, Maven 3.9.9 and PostgreSQL 16.15 containers on the local Linux reference machine. No production environment or real customer funds were involved.

Build command:

```sh
JAVA_HOME=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem \
PATH=/home/vegegoku/.sdkman/candidates/java/21.0.5-tem/bin:$PATH mvn verify
```

Latest Maven result: **BUILD SUCCESS**, 35 tests, 0 failures, 0 errors, 0 skipped.

The packaged application was then started in production profile over HTTPS, processed 125.750 JOD, stopped, restarted in a second JVM, queried, and replayed. The receipt was unchanged and the source remained 374.250 JOD before the load run.

`python3 scripts/smoke.py` ran the HTTPS/restart check and `python3 scripts/load.py --url https://localhost:18444 --seconds 60 --rate 100` using ephemeral signing/TLS keys and a disposable PostgreSQL database. It completed 6,000 fresh 0.001 JOD transfers with no errors and p95 22.012 ms. These results include local TLS and HTTP latency; they describe this machine rather than a capacity promise for other hardware.

`database-proof.txt` records 6,001 completed payments totaling 131.750 JOD, 12,002 ledger entries summing to zero, and balances of 368.250 and 231.750 JOD (combined 600.000 JOD, unchanged from the initial baseline).

For repeatable verification with automatic setup and cleanup, run `scripts/verify-packaged.sh`. CI invokes this script, which first runs `mvn verify`. Production commissioning still requires the deployment's real issuer, certificates, least-privilege database role, secret store, backup retention and restore-drill schedule.
