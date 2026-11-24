<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../lib/api.js'
import { store, toast } from '../lib/store.js'
import StatusBadge from '../components/StatusBadge.vue'

const loading = ref(true)
const definitions = ref([])
const frozenReports = ref([])
const watermarks = ref([])
const endpointWatermarks = ref([])
const runs = ref([])
const versions = ref([])
const selectedVersion = ref(null)
const selectedRun = ref(null)
const runRows = ref([])
const rowAccessError = ref('')
const busy = ref(false)

const evalForm = ref({ frozenReportCode: '', siteCode: 'CN-01', route: 'DUAL_BRIDGE', day: 100 })
const chosenRunCodes = ref([])
const annotationForm = ref({ differenceKind: 'WINDOW_SELECTION', siteCode: '', endpointKind: 'EFFICACY', explanation: '', attributedTo: '' })

const siteOptions = ['CN-01', 'CN-02', 'SG-01']

async function load() {
  loading.value = true
  try {
    const [d, f, w, ew, r, v] = await Promise.all([
      api.get('/definition-comparison/definitions'),
      api.get('/definition-comparison/frozen-reports'),
      api.get('/definition-comparison/watermarks'),
      api.get('/definition-comparison/endpoint-watermarks'),
      api.get('/definition-comparison/runs'),
      api.get('/definition-comparison/versions')
    ])
    definitions.value = d
    frozenReports.value = f
    watermarks.value = w
    endpointWatermarks.value = ew
    runs.value = r
    versions.value = v
    if (f.length && !evalForm.value.frozenReportCode) evalForm.value.frozenReportCode = f[0].code
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function evaluate() {
  busy.value = true
  try {
    const r = await api.post('/definition-comparison/evaluate', { ...evalForm.value, day: store.analysisDay || 100 })
    toast(`Run ${r.runCode}: ${r.subjectCount} subjects, notEvaluable=${r.notEvaluable}, egressBlocked=${r.egressBlocked}`, 'ok')
    await load()
    await openRun(r.runCode)
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}

function toggleRun(code) {
  const i = chosenRunCodes.value.indexOf(code)
  if (i >= 0) chosenRunCodes.value.splice(i, 1)
  else chosenRunCodes.value.push(code)
}

async function publish() {
  if (!chosenRunCodes.value.length) {
    toast('Select at least one evaluation run of the same route.', 'bad')
    return
  }
  busy.value = true
  try {
    const v = await api.post('/definition-comparison/publish', { runCodes: chosenRunCodes.value })
    toast(`Published ${v.code} — mergeBlocked=${v.mergeBlocked}`, v.mergeBlocked ? 'bad' : 'ok')
    chosenRunCodes.value = []
    await load()
    await openVersion(v.code)
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}

async function openVersion(code) {
  selectedRun.value = null
  runRows.value = []
  selectedVersion.value = await api.get(`/definition-comparison/versions/${code}`)
}

async function openRun(code) {
  selectedVersion.value = null
  selectedRun.value = runs.value.find((r) => r.code === code)
  rowAccessError.value = ''
  runRows.value = []
  try {
    runRows.value = await api.get(`/definition-comparison/runs/${code}/rows`)
  } catch (e) {
    rowAccessError.value = e.body?.error || e.message
  }
}

async function annotate() {
  if (!selectedVersion.value || !annotationForm.value.explanation) {
    toast('Open a version and write an explanation first.', 'bad')
    return
  }
  try {
    await api.post('/definition-comparison/annotations', {
      versionId: selectedVersion.value.version.id,
      ...annotationForm.value
    })
    toast('Annotation appended; the comparison version itself is unchanged.', 'ok')
    annotationForm.value.explanation = ''
    await openVersion(selectedVersion.value.version.code)
  } catch (e) {
    toast(e.message, 'bad')
  }
}

const statusKind = (s) => (s === 'PUBLISHED' ? 'VALID' : s === 'PUBLISHED_BLOCKED' ? 'STALE' : 'DRAFT')
const blockReasons = (v) => (v.blockReasons === 'NONE' ? [] : (v.blockReasons || '').split(';'))
const fmt = (dt) => (!dt ? '—' : new Date(dt).toISOString().replace('T', ' ').slice(0, 16))
const pct = (x) => `${(x * 100).toFixed(0)}%`
</script>

<template>
  <div>
    <div class="page-head">
      <h1>Definition-impact comparison workbench</h1>
      <p class="lede">
        Two locked endpoint definitions (legacy week-12 vs revised weeks-10–14 with class-1 deviation exclusion)
        are evaluated locally on the same frozen records. Only approved aggregates cross the border, and every
        comparison is a new separately-watermarked version that links to — never rewrites — the frozen monthly
        report.
      </p>
    </div>

    <div v-if="loading" class="empty">Loading…</div>
    <div v-else>
      <!-- Registry + frozen baseline -->
      <div class="grid cols-2">
        <section class="panel">
          <div class="panel-head"><h2>Analysis definition registry</h2><div class="spacer"></div>
            <span class="small faint">two fixed rules, applicability intervals pinned</span></div>
          <div class="panel-body tight">
            <table>
              <thead><tr><th>Code</th><th>Window</th><th>Closest pick</th><th>Class-1 deviation</th><th>Applicable</th><th>State</th></tr></thead>
              <tbody>
                <tr v-for="d in definitions" :key="d.code">
                  <td class="mono-sm">{{ d.code }}<br /><span class="small faint">{{ d.definitionVersion }}</span></td>
                  <td class="num">W{{ d.windowStartWeek }}–{{ d.windowEndWeek }}</td>
                  <td>{{ d.closestToTarget ? 'closest to W12' : 'exact W12 only' }}</td>
                  <td>{{ d.deviationClass1Exclusion ? 'exclusion' : 'flag only' }}</td>
                  <td class="small">{{ fmt(d.applicableFrom) }}<br />…{{ fmt(d.applicableTo) }}</td>
                  <td><span class="badge ok">{{ d.locked ? 'LOCKED' : 'editable' }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Frozen monthly report (permanent baseline)</h2></div>
          <div class="panel-body tight">
            <div v-for="f in frozenReports" :key="f.code" class="stack" style="gap: 6px">
              <div class="row"><strong class="mono">{{ f.code }}</strong><div class="spacer" style="flex:1"></div>
                <span class="badge bad">{{ f.immutable ? 'IMMUTABLE' : '' }}</span></div>
              <dl class="kv">
                <dt>Period / submitted</dt><dd>{{ f.periodLabel }} · {{ fmt(f.submittedAt) }} by {{ f.frozenBy }}</dd>
                <dt>Legacy qualified / denominator</dt><dd class="num">{{ f.legacyQualifiedTotal }} / {{ f.legacyDenominatorTotal }}</dd>
                <dt>Stratum denominators</dt><dd class="mono-sm">{{ f.stratumDenominators }}</dd>
                <dt>Pinned transfer watermark</dt><dd class="mono-sm faint" style="word-break:break-all">{{ f.pinnedWatermark.slice(0, 180) }}…</dd>
                <dt>Digest</dt><dd class="mono-sm">{{ f.reportDigest.slice(0, 20) }}…</dd>
              </dl>
            </div>
          </div>
        </section>
      </div>

      <!-- Evaluation controls -->
      <section class="panel" style="margin-top: 16px">
        <div class="panel-head"><h2>Local dual-definition evaluation</h2><div class="spacer"></div>
          <span class="small faint">independent comparison watermark per run</span></div>
        <div class="panel-body">
          <div class="row" style="gap: 10px; flex-wrap: wrap; align-items: end">
            <label class="field"><span>Frozen report</span>
              <select v-model="evalForm.frozenReportCode">
                <option v-for="f in frozenReports" :key="f.code" :value="f.code">{{ f.code }}</option>
              </select>
            </label>
            <label class="field"><span>Site (local plane)</span>
              <select v-model="evalForm.siteCode">
                <option v-for="s in siteOptions" :key="s" :value="s">{{ s }}</option>
              </select>
            </label>
            <label class="field"><span>Bridge route</span>
              <select v-model="evalForm.route">
                <option value="DUAL_BRIDGE">Dual bridge (direct subject correspondence)</option>
                <option value="COHORT_BRIDGE">Common-observation cohort (standardised)</option>
              </select>
            </label>
            <label class="field"><span>Analysis day</span><input type="number" v-model.number="evalForm.day" min="1" max="365" /></label>
            <button class="primary" :disabled="busy" @click="evaluate">Evaluate site locally</button>
            <div class="spacer" style="flex:1"></div>
            <button :disabled="busy || !chosenRunCodes.length" @click="publish">Publish comparison from {{ chosenRunCodes.length }} run(s)</button>
          </div>

          <table style="margin-top: 14px">
            <thead><tr><th>Publish</th><th>Run</th><th>Site</th><th>Route</th><th>Subjects</th><th>Not evaluable</th>
              <th>Egress blocked</th><th>Watermark</th></tr></thead>
            <tbody>
              <tr v-for="r in runs" :key="r.code" style="cursor: pointer" @click="openRun(r.code)"
                  :style="selectedRun?.code === r.code ? 'background: rgba(88,166,255,0.06)' : ''">
                <td @click.stop><input type="checkbox" :checked="chosenRunCodes.includes(r.code)" @change="toggleRun(r.code)" /></td>
                <td class="mono-sm">{{ r.code }}</td>
                <td>{{ r.siteCode }}</td>
                <td class="small">{{ r.route }}</td>
                <td class="num">{{ r.subjectCount }}</td>
                <td class="num">{{ r.notEvaluableCount }}</td>
                <td class="num">{{ r.egressBlockedCount }}</td>
                <td class="mono-sm faint">{{ r.watermarkCode }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Local row drill-down -->
      <section v-if="selectedRun" class="panel" style="margin-top: 16px">
        <div class="panel-head"><h2>Local-plane rows — {{ selectedRun.code }}</h2></div>
        <div class="panel-body tight">
          <div v-if="rowAccessError" class="notice bad">{{ rowAccessError }}</div>
          <table v-else>
            <thead><tr><th>Subject</th><th>Site</th><th>Stratum</th><th>Legacy</th><th>Legacy reasons</th>
              <th>Revised</th><th>Revised reasons</th><th>Reclass.</th><th>Egress</th></tr></thead>
            <tbody>
              <tr v-for="r in runRows" :key="r.id">
                <td class="mono-sm">{{ r.subjectPseudonym }}</td>
                <td>{{ r.siteCode }}</td>
                <td>{{ r.riskStratum }}</td>
                <td><span class="badge" :class="r.legacyQualification === 'QUALIFIED' ? 'ok' : r.legacyQualification === 'NOT_EVALUABLE' ? 'pend' : 'bad'">{{ r.legacyQualification }}</span></td>
                <td class="small faint" style="max-width: 220px">{{ r.legacyReasons }}</td>
                <td><span class="badge" :class="r.revisedQualification === 'QUALIFIED' ? 'ok' : r.revisedQualification === 'NOT_EVALUABLE' ? 'pend' : 'bad'">{{ r.revisedQualification }}</span></td>
                <td class="small faint" style="max-width: 220px">{{ r.revisedReasons }}</td>
                <td>{{ r.reclassification }}</td>
                <td><span class="badge" :class="r.egressBlock === 'NONE' ? 'ok' : 'bad'">{{ r.egressBlock }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Published versions -->
      <section class="panel" style="margin-top: 16px">
        <div class="panel-head"><h2>Published comparison versions</h2><div class="spacer"></div>
          <span class="small faint">each route is a separate version; the frozen report is never replaced</span></div>
        <div class="panel-body tight">
          <table>
            <thead><tr><th>Code</th><th>Route</th><th>Status</th><th>Legacy (echoed)</th><th>Revised qualified interval</th>
              <th>Revised denom interval</th><th>Window diffs</th><th>Coverage Δ</th><th>Merge</th></tr></thead>
            <tbody>
              <tr v-for="v in versions" :key="v.code" style="cursor: pointer" @click="openVersion(v.code)"
                  :style="selectedVersion?.version.code === v.code ? 'background: rgba(88,166,255,0.06)' : ''">
                <td class="mono-sm">{{ v.code }}</td>
                <td class="small">{{ v.route }}</td>
                <td><StatusBadge :kind="statusKind(v.status)" compact /></td>
                <td class="num">{{ v.legacyQualified }}/{{ v.legacyDenominator }}</td>
                <td class="num">{{ v.revisedQualifiedPoint }} ∈ [{{ v.revisedQualifiedLower }}, {{ v.revisedQualifiedUpper }}]</td>
                <td class="num">[{{ v.revisedDenominatorLower }}, {{ v.revisedDenominatorUpper }}]</td>
                <td class="num">{{ v.windowSelectionDiffTotal }}</td>
                <td class="num" :class="v.legacyCoverageDelta >= 0 ? 'ok' : 'bad'">{{ v.legacyCoverageDelta >= 0 ? '+' : '' }}{{ v.legacyCoverageDelta }}</td>
                <td><span class="badge" :class="v.mergeBlocked ? 'bad' : 'ok'">{{ v.mergeBlocked ? 'BLOCKED' : 'allowed' }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Version detail -->
      <template v-if="selectedVersion">
        <div v-if="selectedVersion.version.mergeBlocked" class="notice bad" style="margin-top: 16px">
          <strong>Merged efficacy/safety conclusion blocked.</strong>
          <ul style="margin: 6px 0 0 18px">
            <li v-for="b in blockReasons(selectedVersion.version)" :key="b">{{ b }}</li>
          </ul>
          This comparison may inform interpretation only; it must not be pooled into a combined endpoint conclusion.
        </div>

        <div class="grid cols-2" style="margin-top: 16px">
          <section class="panel">
            <div class="panel-head"><h2>Local bridge aggregates (only exportable artefact)</h2></div>
            <div class="panel-body tight">
              <table>
                <thead><tr><th>Site</th><th>Endpoint</th><th>Retained</th><th>Newly</th><th>Lost</th><th>Indet.</th>
                  <th>Window diff</th><th>Qualified interval</th><th>Egress blocked</th></tr></thead>
                <tbody>
                  <tr v-for="a in selectedVersion.bridgeAggregates" :key="a.id">
                    <td>{{ a.siteCode }}</td><td class="small">{{ a.endpointKind }}</td>
                    <td class="num">{{ a.retainedCount }}</td><td class="num">{{ a.newlyQualifiedCount }}</td>
                    <td class="num">{{ a.lostCount }}</td><td class="num">{{ a.indeterminateCount }}</td>
                    <td class="num">{{ a.windowSelectionDiffCount }}</td>
                    <td class="num">[{{ a.uncertaintyLower }}, {{ a.uncertaintyUpper }}]</td>
                    <td class="num">{{ a.egressBlockedCount }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>

          <section class="panel">
            <div class="panel-head"><h2>Denominator contribution ledger</h2><div class="spacer"></div>
              <span class="small faint">site × consent scope × risk stratum</span></div>
            <div class="panel-body tight">
              <table>
                <thead><tr><th>Cell</th><th>Legacy n</th><th>Revised range</th><th>Overlap</th><th>Weight</th></tr></thead>
                <tbody>
                  <tr v-for="c in selectedVersion.denominatorContributions" :key="c.id">
                    <td class="mono-sm">{{ c.siteCode }}<br />{{ c.consentScope }} · {{ c.riskStratum }}</td>
                    <td class="num">{{ c.legacyDenominator }}</td>
                    <td class="num">[{{ c.denominatorLower }}, {{ c.denominatorUpper }}]</td>
                    <td><span class="badge" :class="c.overlapInsufficient ? 'bad' : 'ok'">{{ pct(c.overlapFraction) }}</span></td>
                    <td class="num">{{ c.standardisationWeight.toFixed(3) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </div>

        <section class="panel" style="margin-top: 16px">
          <div class="panel-head"><h2>Attribution workbench — {{ selectedVersion.version.code }}</h2>
            <div class="spacer"></div><span class="small faint">annotations are append-only</span></div>
          <div class="panel-body">
            <div class="row" style="gap: 10px; flex-wrap: wrap; align-items: end">
              <label class="field"><span>Difference kind</span>
                <select v-model="annotationForm.differenceKind">
                  <option>WINDOW_SELECTION</option><option>NEWLY_QUALIFIED</option><option>LOST</option>
                  <option>INDETERMINATE</option><option>OVERLAP</option><option>EGRESS</option>
                </select>
              </label>
              <label class="field"><span>Endpoint</span>
                <select v-model="annotationForm.endpointKind"><option>EFFICACY</option><option>SAFETY</option></select>
              </label>
              <label class="field"><span>Site</span><input v-model="annotationForm.siteCode" placeholder="CN-01" /></label>
              <label class="field" style="flex: 2"><span>Explanation</span><input v-model="annotationForm.explanation" /></label>
              <button @click="annotate">Add annotation</button>
            </div>
            <table style="margin-top: 12px">
              <thead><tr><th>When</th><th>Kind</th><th>Cell</th><th>Explanation</th><th>By</th></tr></thead>
              <tbody>
                <tr v-for="a in selectedVersion.annotations" :key="a.id">
                  <td class="small">{{ fmt(a.createdAt) }}</td><td>{{ a.differenceKind }}</td>
                  <td class="small">{{ a.siteCode }} / {{ a.endpointKind }}</td><td>{{ a.explanation }}</td>
                  <td class="small">{{ a.createdBy }}</td>
                </tr>
              </tbody>
            </table>
            <div class="divider"></div>
            <div class="small faint">
              Independent watermark(s): <span class="mono-sm">{{ selectedVersion.version.watermarkCode }}</span> ·
              linked (read-only) baseline: <span class="mono-sm">{{ selectedVersion.version.linkedFrozenReportCode }}</span>
              <span v-if="selectedVersion.frozenReport"> · baseline digest {{ selectedVersion.frozen.reportDigest.slice(0, 20) }}… unchanged</span>
            </div>
          </div>
        </section>
      </template>

      <!-- Independent watermarks -->
      <section class="panel" style="margin-top: 16px">
        <div class="panel-head"><h2>Independent comparison watermarks</h2><div class="spacer"></div>
          <span class="small faint">separate counter plane; never moves the frozen baseline</span></div>
        <div class="panel-body tight">
          <table>
            <thead><tr><th>Code</th><th>Label</th><th>Linked baseline</th><th>Captured</th><th>Pin</th></tr></thead>
            <tbody>
              <tr v-for="w in watermarks" :key="w.code">
                <td class="mono-sm">{{ w.code }}</td><td class="small">{{ w.label }}</td>
                <td class="mono-sm">{{ w.linkedFrozenReportCode }}</td><td class="small">{{ fmt(w.capturedAt) }}</td>
                <td class="mono-sm faint" style="max-width: 380px; word-break: break-all">{{ w.pin }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>
  </div>
</template>
