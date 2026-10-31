# Static Source Analysis Report Plan

> **For agentic workers:** This is a one-off report deliverable; do not create a reusable analyzer or modify any implementation.

**Goal:** Create a self-contained HTML report that compares all 20 payment-service implementations using source evidence.

**Architecture:** Scan each numbered implementation, exclude build outputs, caches, logs, and copied requirement documents from source metrics, and embed the resulting metrics and evidence in one static HTML page. Use inline CSS/SVG/JavaScript only, with no external runtime dependencies.

**Tech Stack:** Python standard library for one-off source inspection; HTML, CSS, inline SVG, and small vanilla JavaScript for charts and table filtering.

**Scope:** Compare source/test volume, framework/build configuration, package/layer shape, test-structure indicators, and bounded security/maintainability signals. Separate directly observed facts from heuristic findings, include severity/confidence and relative file/line evidence, and explain limitations. Do not run builds or tests or change implementation code.

**Approaches:** A reusable analyzer could regenerate later reports, but the user selected a one-off HTML deliverable. A live dashboard adds runtime dependencies without improving this offline comparison. Use one self-contained HTML report with an aggregate overview, cross-project charts/table, then detailed evidence for each implementation.

**Files:** Create only `static-source-analysis.html` as the deliverable. Do not modify any of the 20 implementation directories.

**Plan:**
1. Inspect all 20 projects and compute consistent source, test, framework, layout, and configuration metrics; validate any heuristic finding against the cited source.
2. Build the HTML around summary KPIs, comparative charts, sortable/filterable comparison rows, and per-project detail sections.
3. Check that every implementation appears, evidence paths resolve relative to the report, charts work without network access, and the final HTML is readable as a local file.
