# APEX-3 · Dual-Domain Sync & Cross-Border Egress Control

A platform for a multinational clinical trial that keeps China source records resident locally while letting a
global team use **de-identified derived data** only inside an approved purpose, field, site, observation-window and
protocol-version envelope. It implements the consent ledger, protocol catalogue, dual-domain eligibility
evaluator, watermark/back-fill coordination, snapshot delivery compiler, withdrawal propagator and post-outage
recovery verifier, together with the acceptance scenario for subjects A/B/C/D at analysis day 16.

Stack: **Vue 3 (Vite)** · **Spring Boot 3.5 / JDK** · **PostgreSQL**. Single page app is bundled into the Spring
Boot jar and served together with the REST API.

---

## 1. Quick start

### Option A — Docker Compose (one command)

```bash
cd /workspace/79-feature-5
docker compose up --build
# open http://localhost:8080   (Log in as admin / demo123)
```

Compose builds the frontend, bundles it into the Spring Boot jar, starts PostgreSQL and the app, and seeds the
demo scenario on first boot. Health check: `curl http://localhost:8080/actuator/health`.

> The provided container in this workspace has **no Docker daemon**, so Compose was not executed here. The same
> image build was validated by running the packaged jar directly (Option B) plus the integration/UI test suites.

### Option B — Run locally (used for the verification below)

Prerequisites already present in this environment: `/opt/jdk-25`, `/opt/maven`, `/opt/node-22`, PostgreSQL on
`127.0.0.1:5432` (user/db `ccdev`), Redis not required.

```bash
# 1. database (already running in the workspace image)
psql "host=127.0.0.1 user=ccdev password=ccdev dbname=postgres" -c "CREATE DATABASE ctrial;"   # once

# 2. build the SPA, bundle it, and build the backend jar
cd frontend && npm install && npm run build && cd ..
cd backend  && mvn -DskipTests package

# 3. run
java -jar backend/target/ctrial-sync-1.0.0.jar
# open http://localhost:8080  — schema and demo data seed automatically on first start
```

Frontend developer mode (hot reload, proxies `/api` to :8080):

```bash
cd frontend && npm run dev      # http://localhost:5173
```

The backend reads `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`, `DEMO_SEED` from the environment
(defaults in `backend/src/main/resources/application.yml`).

---

## 2. Test accounts (HTTP Basic)

All demo passwords are `demo123`. Roles are enforced server-side.

| Username | Role | Can do |
|----------|------|--------|
| `admin`    | ADMIN | everything |
| `global`   | GLOBAL_ANALYST (+AUDITOR) | compile cross-border deliveries, read snapshots |
| `china`    | CN_ANALYST | drive watermark / transfer / link operations |
| `auditor`  | AUDITOR | read-only audit, recovery, quarantine |
| `sponsor`  | SPONSOR | record consent & protocol versions |

Role checks that matter and were exercised: a China analyst gets **403** on `POST /api/deliveries/**`; an auditor
gets **403** on `POST /api/consents`.

---

## 3. How the domain model maps to the requirements

| Requirement | Where it lives |
|-------------|----------------|
| Consent ledger: subject, purpose, field group, region, validity interval | `ConsentEntry` — two clocks: `effectiveFrom`/`effectiveTo` (local signature) and `recordedAt` (receipt) |
| Consent validity uses the **local** signed/withdrawn instant, not receipt time | `EligibilityEngine.resolveConsent`, `EligibilityService.loadWorld` (filters on `effectiveFrom ≤ analysis`) |
| Protocol catalogue: version, approvals, effective time, applicable sites | `ProtocolVersion.fullyApproved/approvedAt/activeAt/appliesToSite` |
| Amendment applies only from explicit effective date, only after ethics **and** sponsor approval | `EligibilityEngine.resolveProtocol` |
| Local event ledger: stable id, event time, receipt time, revision chain, source, void | `LocalEvent` (unique on `(sourceSystem,domain,sourceSeq)` and `(stableId,revision)`; no direct identifiers) |
| No raw identity leaves China | `Subject.localIdentity` never projected; `DeliveryRecord` has only pseudonym + projected fields |
| Eligibility per record: 本地可用 / 全球可用 / 待定 + reasons | `Eligibility.LOCAL_AVAILABLE / GLOBAL_AVAILABLE / PENDING / BLOCKED` + `reasonCodes` |
| Intersection (not union) of consent, protocol, approval, purpose scope | `EligibilityEngine.evaluate` — sequential gates, any failure blocks, any undeterminable pends |
| Watermark coordinator: source-confirmed / transfer-ack / analysis, per domain | `Watermark` (three counters), `StateService`, `coverage` view |
| Gaps are UNCONFIRMED, never zero | `WATERMARK_UNCONFIRMED` reason + analysis cap; UI gap band |
| Snapshot compiler pins versions, member summary, field projection, filter, watermark | `DeliveryService.compile`, `Delivery` |
| Withdrawal propagator stops future queries, applies pre-registered retention/destruction | `RevocationService`, `DispositionRule`, `DispositionExecution`; historical delivery summary kept |
| Recovery verifier: gaps, duplicate re-sends, cross-domain digest agreement | `RecoveryService.run` → `RecoveryCheck` |
| Two reconstruction routes producing identical legal sets | `EligibilityRunService` (MATERIALIZED vs REPLAY) + equivalence test; both call one engine |
| Stale snapshots never authorise a new read; old deliveries not pretended erased | `invalidateRuns`, `Delivery.accessRevoked` with summary/digests intact |
| Retransmission after restore not double-counted | `EventIngestService` transport-identity dedupe + `TransportReceipt` |
| Withdrawal/correction race adjudicated by approved rule, else quarantined | `EventIngestService` + `TieBreakRule` + `QuarantineItem` |
| **Analysis definition registry** — two fixed rule sets with applicability intervals | `AnalysisDefinition` (`DEF-LEGACY-W12` exact week-12; `DEF-REVISED-W10-14` closest test in weeks 10–14, class-1 deviation = exclusion), rows `locked=true` |
| **Dual-definition eligibility evaluator** — legacy/revised qualification, not-evaluable and no-egress reasons on the same frozen records, evaluated at each site locally | `DualDefinitionRules` (pure) + `DefinitionComparisonService.evaluateSite` → `DualDefinitionRow` |
| **Local-domain endpoint bridge** — only approved aggregates cross the border | `LocalBridgeAggregate`: reclassification counts, window-selection differences, qualified/denominator uncertainty intervals, never subject rows |
| **Denominator contribution ledger** — site × consent scope × risk stratum ranges | `DenominatorContribution` with overlap fraction, stability floor and standardisation weight |
| **Independent comparison watermark** — separate counter plane that cannot move the frozen baseline | `ComparisonWatermark` (+ local-only `LocalEndpointWatermark`) |
| **Comparison version publisher** — links to, never modifies, the frozen monthly report; blocks merged conclusions | `ComparisonVersion` (`PUBLISHED_BLOCKED`, `mergeBlocked`, block reasons), both `DUAL_BRIDGE` and `COHORT_BRIDGE` produce distinct versions |
| **Difference attribution workbench** | append-only `DifferenceAnnotation`; the published version digest never changes |
| Frozen monthly report permanently retained | `FrozenMonthlyReport` is immutable; publish code asserts digest/counts unchanged and refuses the operation otherwise |

### Reconstruction routes

* **MATERIALIZED** — `POST /api/runs/materialize?day=16` evaluates once and persists one row per candidate. Later
  consent/protocol revisions mark affected runs `STALE`. Queries read the stored rows directly; a stale run is
  never consulted to authorise a read.
* **REPLAY** — `GET /api/runs/replay?day=16` rebuilds from the append-only ledgers at the chosen instant,
  persisting nothing; it requires the watermark completeness proof returned alongside.

Both call the same pure `EligibilityEngine`. The acceptance check `ALL/EQ` and `GET /api/runs/compare?day=16`
assert the two routes produce the same legal membership, verdicts and reason codes. At day 16 both report
**13 legal rows, 0 differences**.

---

## 4. The acceptance scenario (analysis day 16)

Study day 1 = `2026-03-02T09:00Z`; analysis instant for day N = `23:00Z` on that day.
Sites: `CN-01` Shanghai (healthy), `CN-02` Wuhan (link stalled), `SG-01` Singapore (healthy).

| Subject | Timeline | Required outcome |
|---------|----------|------------------|
| **A** | signs China-only consent D10; visit occurs D11; uploaded D15 | **not** global-available (reason = consent scope, not upload delay); locally available |
| **B** | AE occurs D11; withdraws global purpose D12 (signed 09:00, received 20:00); new visit D13 | D11 AE held under restricted retention but reuse globally blocked; D13 visit has no basis; local use continues |
| **C** | protocol v2.0 approved D13, effective D14 at CN-01; imaging D13 and D15 | D13 under v1.2; D15 under v2.0 carrying `IMAGING_ADC`/`IMAGING_TUMOR_VOL`; v2.0 never applies to other sites |
| **D** | CN-02 link stalled after D9; randomisation D5, visit D12 | D5 globally available; D12 **PENDING / UNCONFIRMED** globally, locally available; gap shown, not zero |

If a historical delivery contains B's D11 row (e.g. compile at day 11), propagating B's withdrawal revokes
access to it immediately and applies `DR-RETAIN-25Y` (restricted retention) while the delivery's frozen member
summary and digests stay intact for audit — the delivery is not pretended to have been erased.

---

## 4b. Definition-impact comparison (endpoint definition change)

When the analysis plan changes from **legacy** (only the post-randomisation week-12 test; a class-1 deviation is
a flag) to **revised** (the qualifying test closest to the target week within weeks 10–14; a class-1 deviation is
an exclusion), the platform never overwrites the already-submitted monthly report. Instead it creates a new,
separately watermarked **definition-impact comparison version**.

* `FMR-2026-M05` — the monthly report previously submitted on the legacy frozen plane (8 qualified / denominator
  12, per-stratum mix `LOW=4,MEDIUM=4,HIGH=4`). It is immutable: no code path updates or deletes it, and the
  publisher refuses to finish if its digest changes during a publish.
* The registry contains exactly two locked definitions with their applicability intervals; no third definition
  can be registered in place of either.
* Each site evaluates the same frozen records locally under both definitions (`DualDefinitionRow`): a
  qualification per definition, full reason codes, a not-evaluable flag (e.g. adjudication arriving after the
  legacy watermark) and an egress-block reason (no cross-border consent / withdrawal / above local watermark).
* The **local bridge** emits aggregate-only artefacts — reclassification counts (retained / newly qualified /
  lost / never qualified / indeterminate), window-selection differences and uncertainty intervals. China-site
  subject rows are served only to the China analyst/admin; a global analyst requesting them gets **403**.
* The **denominator contribution ledger** stores ranges per site × consent scope × risk stratum, with the
  observed overlap fraction. Cells below the declared 0.60 floor are marked insufficient.
* Two routes, two distinct versions (never a replacement):
  * **DUAL_BRIDGE** — direct subject-rule correspondence on subjects with both observation opportunities;
    insufficient overlap leaves wide intervals.
  * **COHORT_BRIDGE** — a pre-declared common-observation-opportunity cohort standardised to the frozen
    report's risk-stratum mix; subjects missing the common observation are excluded and a weight-stability
    constraint blocks the conclusion when the exclusion ratio exceeds 0.40.
* A version is published `PUBLISHED_BLOCKED` and **must not** be pooled into a combined efficacy/safety
  conclusion while any block reason is present (insufficient overlap, band wider than 0.25 of the legacy
  denominator, not-evaluable subjects, or unstable cohort weights).

Seeded subjects (analysis day 100): **C** has qualifying W11 and W12 tests (dual opportunity; both definitions
select W12); **E** has only a W11 test (newly qualified under the revised window); **F** has a W12 test plus a
class-1 deviation (retained under legacy, excluded under revised); **D**'s W12 adjudication arrives on day 95,
after the day-90 legacy freeze (legacy not-evaluable, revised qualified → indeterminate); **B**'s cross-border
consent was withdrawn (local encoding retained, egress blocked); **A** holds China-only consent (qualifies
locally, subject rows cannot leave the region).

### Try it

1. Sign in as **china**, open **Definition comparison** → evaluate each site (both routes).
2. Sign in as **global** → select the three runs of one route → **Publish comparison**. Both versions come back
   `PUBLISHED_BLOCKED` with their block reasons; open one to see the aggregate bridge, denominator ranges and the
   read-only link to `FMR-2026-M05`.
3. Re-open **Frozen monthly report**: digest and figures are unchanged. Add an attribution annotation — the
   version digest stays the same.

REST surface (all under `/api/definition-comparison`): `GET /definitions`, `GET /frozen-reports`,
`GET /watermarks`, `GET /endpoint-watermarks`, `POST /evaluate` (CN/admin), `GET /runs`,
`GET /runs/{code}/rows` (403 for cross-border roles on China sites), `POST /publish` (global/admin/sponsor),
`GET /versions`, `GET /versions/{code}`, `POST /annotations`.

---

## 5. Verification (what was actually run here)

All commands below were executed in this workspace against a live instance. Results are quoted verbatim.

### 5.1 Backend integration tests — 29/29 pass

```bash
cd backend && mvn test
```
```
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
Covers: A/B/C/D rules; intersection semantics; both reconstruction routes agreeing (read-only and persisted);
stale-snapshot invalidation; transport dedupe; conflicting-duplicate quarantine; withdrawal/correction race
quarantine; delivery projection has no identity fields and survives revocation as an intact summary; plus 14
definition-comparison tests (locked registry, dual encoding per subject, independent watermarks, aggregate-only
bridge intervals, overlap/weight merge gates, two distinct route versions, frozen-baseline immutability,
append-only annotations).

### 5.2 Frontend render tests (live backend) — 8/8 pass

```bash
cd frontend && npm run test        # vitest + happy-dom, mounts the real views against http://127.0.0.1:8080
```
```
Test Files  1 passed (1)
     Tests  8 passed (8)
```
Asserts the Overview shows **15/15**, the Acceptance cards list A1/B1/C1/D2, the Eligibility pivot renders both
planes and subject D as Pending, the Consent / Deliveries / Watermarks views render without error, and the
Definition comparison workbench renders the two locked definitions and the immutable frozen report.

### 5.3 End-to-end API acceptance (live jar)

| Step | Result |
|------|--------|
| `GET /api/acceptance?day=16` | **15/15 acceptance checks pass** (A1–A3, B1–B5, C1–C3, D1–D3, EQ) |
| `POST /api/deliveries/compile {day:11}` | `DLV-D11-001`, 2 members incl. `PSN-B2`, digest `a5110f88…` |
| `POST /api/deliveries/compile {day:16, route:REPLAY}` | `DLV-D16-001`, 4 members (C×3, D randomisation) |
| `POST /api/revocations/propagate {consentEntryId:3}` | 2 snapshot runs invalidated, 1 delivery access-revoked, 1 row restricted under `DR-RETAIN-25Y` |
| `GET /api/deliveries/DLV-D11-001` after revocation | `readable=false`, `status=ACCESS_REVOKED`, content digest intact, 2 summary lines kept |
| `GET /api/deliveries/DLV-D16-001` | `readable=true`, `status=ACTIVE` (unaffected) |
| `POST /api/recovery/run {site:CN-02}` | `status=FINDINGS`, 2 gaps, 0 duplicate resends, 0 digest mismatches |
| exact re-send of `VST-D4-V1` seq 12 | `duplicate=true` — "Retransmission … acknowledged; counted once." event count stays 9 |
| conflicting re-send (different payload) | `duplicate=true, quarantined=true` → `Q-0001-DUP` |
| `GET /api/watermarks?day=16` | CN-02 source confirmed 12 / ack 9 / analysis 9 → D12 **UNCONFIRMED** |

Definition-impact comparison (also exercised against the live jar with the seeded scenario):

| Step | Result |
|------|--------|
| `GET /api/definition-comparison/definitions` | exactly two `locked` rows: `DEF-LEGACY-W12` (window 12–12) and `DEF-REVISED-W10-14` (window 10–14, closest, class-1 exclusion) |
| `POST /evaluate` as **global** on a China site | **403**; local evaluation is a CN/admin operation |
| `POST /evaluate` (china) × 3 sites × 2 routes | six runs, each with its **own** watermark code (`CWM-…`); CN-02 reports `notEvaluable=1` (D's late adjudication), CN-01 `egress=1` (A's local-only consent), SG-01 `egress=1` (B's withdrawal) |
| `GET /runs/{cn01}/rows` as **global** | **403** `LOCAL_ROW_EGRESS_DENIED`; as **china** the rows return (C retained, E newly qualified, F lost, A egress-blocked) |
| `POST /publish` (global) dual-bridge 3 runs | `CMP-DUAL-001`, `PUBLISHED_BLOCKED`; legacy echoed **8/12**; revised qualified point **4** ∈ **[4, 7]**, denominator **[5, 8]**, window diffs 1; block reasons include `INSUFFICIENT_OVERLAP:4 cell(s) below 0.6` and `NOT_EVALUABLE_SUBJECTS:1` |
| CN-01 efficacy bridge aggregate | retained 2, newly 1, lost 1, window diff 1, qualified interval [3, 4], egress blocked 1 — counts only, no pseudonym in the version summary |
| `POST /publish` cohort-bridge 3 runs | separate `CMP-COHORT-001`; `cohortExcludedCount=5`, block reason `COHORT_WEIGHT_UNSTABLE:excludedRatio=0.83 > 0.4` |
| frozen report re-read after both publishes | same digest `e94460ad…`, 8/12, `immutable=true` — never rewritten, replaced or recomputed |
| `POST /annotations` twice then re-read version | 2 annotations appended, version digest unchanged |
| transfer watermark rows / existing deliveries | unchanged (18 watermark rows; the comparison uses a separate counter plane) |

The pinned watermark string for day 16 is
`CN-01:AE=15(src=15,ack=15); … CN-02:VISIT=12(src=12,ack=9)…` (the CN-02 analysis stays at 9).

---

## 6. Using the console

Open `http://localhost:8080` and sign in (the login screen lists the demo accounts; click one to prefill).

* **Overview** — acceptance score, watermark gaps, deliveries, subjects.
* **Acceptance** — the four subject cards with expected vs observed and reason codes.
* **Consent & protocol** — append grants/withdrawals and protocol versions; per-subject timelines; approved
  envelopes. Recording a withdrawal invalidates affected snapshot runs immediately.
* **Eligibility** — the dual-plane pivot (replay or materialised), with a live route-equivalence badge and the
  source event ledger. The top-bar **analysis day** changes the instant for every screen.
* **Watermarks** — per-domain three-counter bars with an UNCONFIRMED gap band; confirm source / ack transfer /
  fold analysis / break & restore link; run a recovery check.
* **Deliveries** — compile by either route, inspect the pinned envelope, frozen member summary and projected
  records; revocation state is visible.
* **Withdrawal** — propagate a recorded withdrawal, register disposition and tie-break rules, see executions.
* **Recovery** — recovery reports (expand for per-domain digests), simulate a post-restore re-send, resolve
  quarantined races.
* **Definition comparison** — the locked two-definition registry and immutable monthly report; run local
  dual-definition evaluations per site (China role), publish separate dual-bridge/cohort comparison versions
  (global role), inspect aggregate-only bridges, denominator ranges and the merge-block banner, and append
  attribution annotations without changing any frozen output.
* **Audit** — the append-only audit trail of every regulator-relevant transition.

### Try the headline flow

1. As **global**, Deliveries → set analysis day `11` → *Compile delivery* (contains B's D11 AE).
2. As **admin**, Withdrawal → *Propagate* B's withdrawal.
3. Back in Deliveries → open `DLV-D11-001`: content is revoked, but the frozen summary and digests remain.
4. Set day `16`, Acceptances → all checks pass; Eligibility → D's D12 visit is Pending globally, available locally.

---

## 7. Design decisions worth noting

* **One engine, two routes.** Materialisation is a cache over the same `EligibilityEngine`; it can never diverge
  because it is not a second implementation. Snapshots are `STALE`d on later changes and are refused for new
  reads, so an old delivery is never a standing authorisation.
* **Intersection, literally.** Every gate must pass; a single failing gate blocks, a single undeterminable gate
  pends. There is no union anywhere in the evaluator.
* **Two clocks, on purpose.** All validity uses the local signature/withdrawal instant; the receipt instant is
  only an ordering tie-breaker. B's withdrawal is effective at the moment it was signed, not when it reached us.
* **Gaps are first-class.** The analysis watermark can never fold past the transfer acknowledgement, and anything
  above it is `PENDING`, never reported as absent. The UI draws the unconfirmed band instead of a zero.
* **History is preserved.** Revoking a delivery sets `accessRevoked` and applies the registered disposition, but
  the legal-member summary and digests are frozen and kept. Nothing claims the delivery never existed.
* **Isolate, don't guess.** A withdrawal/correction race with equal effective instants and no registered
  tie-break rule is quarantined; it never silently picks a side.
* **A definition change is a new version, never an overwrite.** Both endpoint definitions live in a locked
  registry; each comparison gets its own watermark counter plane and its own version per route. The publisher
  re-reads the frozen monthly report and aborts if its digest moved, so "link but never rewrite" is enforced in
  code rather than by convention.
* **Intervals instead of invented point estimates.** Late adjudications, not-evaluable and egress-blocked
  subjects become lower/upper bounds on the revised qualified count and denominator; an interval that is too
  wide (or a cohort whose weights are unstable) blocks any merged efficacy/safety conclusion.
* **Aggregates are the only cross-border object.** Subject-level dual-definition rows stay in the local plane;
  the bridge carries counts and ranges, and the workbench returns 403 — with an audit entry — when a
  cross-border role asks for a China site's local rows.

---

## 8. Project layout

```
backend/
  src/main/java/com/ctrial/
    model/        JPA entities + Enums (the rule vocabulary), including AnalysisDefinition,
                  FrozenMonthlyReport, ComparisonWatermark, EndpointAssessment, ProtocolDeviationEntry,
                  ComparisonRun, DualDefinitionRow, LocalBridgeAggregate, DenominatorContribution,
                  ComparisonVersion, DifferenceAnnotation
    service/      EligibilityEngine (pure), EligibilityService, EligibilityRunService,
                  DeliveryService, RevocationService, RecoveryService, EventIngestService,
                  StateService, AcceptanceService, AuditService,
                  DualDefinitionRules (pure), DefinitionComparisonService
    web/          ReadController, CommandController, DefinitionComparisonController, SpaConfig
    config/       SecurityConfig, DemoSeeder, ComparisonScenarioSeeder
  src/test/java/com/ctrial/AcceptanceRulesTest.java, DefinitionComparisonTest.java
frontend/
  src/            App, views/*, components/*, lib/api.js, lib/store.js, ui.test.js
Dockerfile, compose.yaml
```
