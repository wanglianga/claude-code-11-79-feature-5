<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../lib/api.js'
import { store, toast } from '../lib/store.js'
import StatusBadge from '../components/StatusBadge.vue'
import ReasonChips from '../components/ReasonChips.vue'

const loading = ref(true)
const mode = ref('replay') // replay | materialized
const replay = ref(null)
const compare = ref(null)
const events = ref([])
const run = ref(null)
const error = ref('')

const JSON_ = (o) => JSON.stringify(o)

async function load() {
  loading.value = true
  error.value = ''
  try {
    replay.value = await api.get(`/runs/replay?day=${store.analysisDay}`)
    events.value = await api.get('/events')
    compare.value = await api.get(`/runs/compare?day=${store.analysisDay}`)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function materialize() {
  try {
    run.value = await api.post(`/runs/materialize?day=${store.analysisDay}`, {})
    toast(`Materialised snapshot ${run.value.code}: ${run.value.rowCount} rows.`, 'ok')
  } catch (e) {
    toast(e.message, 'bad')
  }
}

const rows = computed(() => {
  if (mode.value === 'replay') return replay.value?.rows || []
  return []
})

// pivot into subject|stableId, with a global and a local column
const pivoted = computed(() => {
  const map = new Map()
  for (const r of replay.value?.rows || []) {
    const key = `${r.pseudonym}|${r.stableId}#${r.revision}`
    if (!map.has(key)) {
      map.set(key, { key, pseudonym: r.pseudonym, site: r.siteCode, domain: r.domain, stableId: r.stableId, revision: r.revision, localDay: r.localDay })
    }
    map.get(key)[r.plane] = r
  }
  return [...map.values()]
})

const filter = ref('')
const filtered = computed(() => {
  const f = filter.value.trim().toLowerCase()
  if (!f) return pivoted.value
  return pivoted.value.filter((p) =>
    p.pseudonym.toLowerCase().includes(f) ||
    p.domain.toLowerCase().includes(f) ||
    p.stableId.toLowerCase().includes(f) ||
    (p.GLOBAL?.eligibility || '').toLowerCase().includes(f) ||
    (p.LOCAL?.eligibility || '').toLowerCase().includes(f)
  )
})

function subjectLetter(p) {
  const m = { 'PSN-A1': 'A', 'PSN-B2': 'B', 'PSN-C3': 'C', 'PSN-D4': 'D' }
  return m[p] || '•'
}
</script>

<template>
  <div>
    <div class="page-head">
      <h1>Dual-domain eligibility</h1>
      <p class="lede">
        Every candidate record is evaluated against the <strong>intersection</strong> of consent, protocol,
        approval, purpose scope and watermark. A record that passes every gate is available; one failing gate
        blocks it; one undeterminable gate (e.g. an unconfirmed sequence) makes it pending. No gate is ever
        unioned in.
      </p>
    </div>

    <div v-if="error" class="notice bad">{{ error }}</div>

    <div class="row" style="margin-bottom: 14px">
      <div class="tabs" style="margin: 0; border: none">
        <button class="tab" :class="{ active: mode === 'replay' }" @click="mode = 'replay'">Replay route</button>
        <button class="tab" :class="{ active: mode === 'materialized' }" @click="mode = 'materialized'">Materialised route</button>
      </div>
      <div class="spacer" style="flex: 1"></div>
      <input v-model="filter" placeholder="Filter subject / domain…" style="width: 220px" />
      <button @click="load">Reload</button>
      <button class="primary" @click="materialize">Materialise day {{ store.analysisDay }}</button>
    </div>

    <div v-if="loading" class="empty">Evaluating…</div>

    <template v-else>
      <div class="grid cols-3" style="margin-bottom: 16px">
        <div class="stat">
          <div class="label">Replay rows</div>
          <div class="value">{{ replay?.rows?.length || 0 }}</div>
          <div class="foot">rebuilt from ledgers, not stored</div>
        </div>
        <div class="stat">
          <div class="label">Completeness</div>
          <div class="value" :style="{ color: replay?.completenessOk ? 'var(--ok)' : 'var(--warn)' }">
            {{ replay?.completenessOk ? 'Complete' : 'Gaps' }}
          </div>
          <div class="foot">{{ (replay?.completenessNotes || []).length }} unconfirmed note(s)</div>
        </div>
        <div class="stat">
          <div class="label">Routes agree</div>
          <div class="value" :style="{ color: compare?.equal ? 'var(--ok)' : 'var(--bad)' }">
            {{ compare?.equal ? 'Yes' : 'No' }}
          </div>
          <div class="foot">{{ compare?.materializedLegal }} vs {{ compare?.replayLegal }} legal rows</div>
        </div>
      </div>

      <div v-if="mode === 'materialized' && !run" class="notice">
        The materialised route stores one row per candidate at materialisation time. Choose
        <strong>Materialise</strong> above to create a snapshot for day {{ store.analysisDay }}; until then the
        grid below shows the replay route's live evaluation.
      </div>

      <section class="panel">
        <div class="panel-head">
          <h2>Evaluated records</h2>
          <div class="spacer"></div>
          <span class="small faint">local plane = source-confirmed · global plane = analysis watermark</span>
        </div>
        <div class="panel-body tight">
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Subject</th><th>Record</th><th>Day</th>
                  <th>Global plane</th><th>Local plane</th><th>Reasons (global)</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="p in filtered" :key="p.key">
                  <td>
                    <div class="row"><span class="chip">{{ subjectLetter(p.pseudonym) }}</span>
                      <span class="mono-sm">{{ p.pseudonym }}</span></div>
                    <div class="faint mono-sm">{{ p.site }}</div>
                  </td>
                  <td>
                    <div class="mono-sm">{{ p.stableId }}<span class="faint">#{{ p.revision }}</span></div>
                    <div class="faint small">{{ p.domain }}</div>
                  </td>
                  <td class="num">D{{ p.localDay }}</td>
                  <td>
                    <StatusBadge v-if="p.GLOBAL" :kind="p.GLOBAL.eligibility" compact />
                    <div v-if="p.GLOBAL?.protocolVersion" class="faint mono-sm" style="margin-top: 3px">via {{ p.GLOBAL.protocolVersion }}</div>
                  </td>
                  <td>
                    <StatusBadge v-if="p.LOCAL" :kind="p.LOCAL.eligibility" compact />
                    <div v-if="p.LOCAL?.protocolVersion" class="faint mono-sm" style="margin-top: 3px">via {{ p.LOCAL.protocolVersion }}</div>
                  </td>
                  <td style="max-width: 360px">
                    <ReasonChips :codes="p.GLOBAL?.reasonCodes || ''" />
                    <div v-if="p.GLOBAL?.reasons" class="small faint" style="margin-top: 4px">{{ p.GLOBAL.reasons }}</div>
                  </td>
                </tr>
                <tr v-if="!filtered.length">
                  <td colspan="6" class="empty">No records at this analysis instant.</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <div class="grid cols-2" style="margin-top: 16px">
        <section class="panel">
          <div class="panel-head"><h2>Source event ledger</h2></div>
          <div class="panel-body tight scroll-y">
            <table>
              <thead><tr><th>Record</th><th>Subject</th><th>Domain</th><th>Occurred</th><th>Seq</th></tr></thead>
              <tbody>
                <tr v-for="e in events" :key="e.id">
                  <td class="mono-sm">{{ e.stableId }}#{{ e.revision }}</td>
                  <td class="mono-sm">{{ e.subject }}</td>
                  <td class="small">{{ e.domain }}</td>
                  <td class="mono-sm">D{{ e.occurredDay }}</td>
                  <td class="num">{{ e.sourceSeq }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Why intersection, not union</h2></div>
          <div class="panel-body small muted stack">
            <p style="margin: 0">
              <strong>Consent</strong> can grant a purpose for a region and a set of field groups — but only
              within the record's occurrence window.
            </p>
            <p style="margin: 0">
              <strong>Protocol</strong> decides which field groups are in scope, and only from its explicit effective
              date — never merely from the approval date.
            </p>
            <p style="margin: 0">
              <strong>Approval</strong> narrows purpose, sites, destination regions, protocol versions and the
              observation window. It can never broaden what consent allows.
            </p>
            <p style="margin: 0">
              <strong>Watermarks</strong> decide whether a record is confirmed. Above the analysis watermark a global
              evaluation stays <em>pending</em> — never silently zero.
            </p>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>
