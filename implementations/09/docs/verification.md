# Verification evidence

Verified with Temurin Java 21.0.5, Maven 3.9.9, Docker Engine 29.9.2, and disposable PostgreSQL 16 containers.

Command: `mvn verify` (Java 21 selected in JAVA_HOME/PATH). Exit 0.

{'tests': 44, 'failures': 0, 'errors': 0, 'skipped': 0}

300 successful real HTTP transfers; offered rate=100/s; JWT RS256 validated; PostgreSQL 16; p95=6.859 ms; host=Linux amd64; Java=21.0.5

`docker compose config --quiet` exits 0. The Compose deployment itself was not started; automated tests use independently provisioned PostgreSQL and locally served real JWKS keys. Production TLS certificates, organization-specific OIDC, shared ingress quotas, backup schedules, restore drills and retention governance must be supplied by operators as documented in operations.md.

All seventeen acceptance criteria have corresponding automated coverage or database invariants. The load run is a three second reference-host smoke measurement; sustained production sizing remains an operational activity. Financial history is retained indefinitely; any eventual archival must preserve the configured minimum retention.
