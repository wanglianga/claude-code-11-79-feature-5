<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../lib/api.js'
import { store, toast, bump } from '../lib/store.js'
import StatusBadge from '../components/StatusBadge.vue'
import ReasonChips from '../components/ReasonChips.vue'

const loading = ref(true)
const deliveries = ref([])
const selected = ref(null)
const detail = ref(null)
const runs = ref([])
const compare = ref(null)
const busy = ref(false)
const route = ref('MATERIALIZED')

async function load() {
  loading.value = true
  try {
    const [d, r] = await Promise.all([api.get('/deliveries'), api.get('/runs')])
    deliveries.value = d
    runs.value = r
    compare.value = await api.get(`/runs/compare?day=${store.analysisDay}`)
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function open(code) {
  selected.value = code
  detail.value = await api.get(`/deliveries/${code}`)
}

async function compile() {
  busy.value = true
  try {
    const d = await api.post('/deliveries/compile', {
      day: store.analysisDay,
      purpose: 'GLOBAL_ANALYSIS',
      route: route.value
    })
    toast(`Compiled ${d.code}: ${d.legalMemberCount} member(s); digest ${d.contentDigest.slice(0, 12)}…`, 'ok')
    await load()
    await open(d.code)
    bump()
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}

async function materialize() {
  try {
    const r = await api.post(`/runs/materialize?day=${store.analysisDay}`, {})
    toast(`Snapshot ${r.code}: ${r.rowCount} rows; status ${r.status}.`, 'ok')
    await load()
  } catch (e) {
    toast(e.message, 'bad')
  }
}

function fmt(dt) {
  if (!dt) return '—'
  return new Date(dt).toISOString().replace('T', ' ').slice(0, 16)
}
</script>

<template>
  <div>
    <div class="page-head">
      <h1>Snapshots &amp; deliveries</h1>
      <p class="lede">
        The compiler pins the approved versions, the legal member summary, the field projection, the filter and
        the per-domain source watermarks used. A compiled delivery is the audit anchor — but it is never a
        standing authorisation for a new read.
      </p>
    </div>

    <div class="row" style="margin-bottom: 16px">
      <label class="field" style="flex-direction: row; align-items: center; gap: 8px">
        <span>Route</span>
        <select v-model="route">
          <option value="MATERIALIZED">Materialised</option>
          <option value="REPLAY">Replay</option>
        </select>
      </label>
      <button class="primary" :disabled="busy" @click="compile">Compile delivery at day {{ store.analysisDay }}</button>
      <button @click="materialize">Materialise snapshot day {{ store.analysisDay }}</button>
      <div class="spacer" style="flex: 1"></div>
      <span class="small" :class="compare?.equal ? 'faint' : 'bad'">
        routes {{ compare?.equal ? 'agree' : 'diverge' }} · {{ compare?.materializedLegal }}/{{ compare?.replayLegal }}
      </span>
    </div>

    <div v-if="loading" class="empty">Loading…</div>
    <div v-else class="grid cols-2">
      <section class="panel">
        <div class="panel-head"><h2>Deliveries</h2><div class="spacer"></div>
          <span class="small faint">{{ deliveries.length }} compiled</span></div>
        <div class="panel-body tight scroll-y">
          <div v-if="!deliveries.length" class="empty">No deliveries yet. Compile one above.</div>
          <table v-else>
            <thead><tr><th>Code</th><th>Day</th><th>Members</th><th>Status</th></tr></thead>
            <tbody>
              <tr v-for="d in deliveries" :key="d.code" @click="open(d.code)" style="cursor: pointer"
                  :style="selected === d.code ? 'background: rgba(88,166,255,0.06)' : ''">
                <td class="mono">{{ d.code }}</td>
                <td class="num">D{{ d.analysisDay }}</td>
                <td class="num">{{ d.legalMemberCount }}</td>
                <td><StatusBadge :kind="d.status" compact /></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head"><h2>Delivery detail</h2></div>
        <div class="panel-body">
          <div v-if="!detail" class="empty">Select a delivery to inspect its pinned envelope and members.</div>
          <div v-else class="stack">
            <div class="row">
              <h3 class="mono">{{ detail.delivery.code }}</h3>
              <StatusBadge :kind="detail.delivery.status" compact />
              <div class="spacer" style="flex: 1"></div>
              <span class="badge" :class="detail.readable ? 'ok' : 'bad'">{{ detail.readable ? 'readable' : 'content revoked' }}</span>
            </div>
            <dl class="kv">
              <dt>Analysis day</dt><dd>D{{ detail.delivery.analysisDay }} ({{ fmt(detail.delivery.analysisAt) }})</dd>
              <dt>Approved versions</dt><dd class="mono-sm">{{ detail.delivery.approvedVersions }}</dd>
              <dt>Field projection</dt><dd class="mono-sm">{{ detail.delivery.fieldProjection }}</dd>
              <dt>Member filter</dt><dd class="mono-sm faint">{{ detail.delivery.memberFilter }}</dd>
              <dt>Legal members</dt><dd class="num">{{ detail.delivery.legalMemberCount }} ({{ detail.recordCount }} content rows)</dd>
              <dt>Member digest</dt><dd class="mono-sm">{{ detail.delivery.legalMemberDigest }}</dd>
              <dt>Content digest</dt><dd class="mono-sm">{{ detail.delivery.contentDigest }}</dd>
              <dt>Route</dt><dd>{{ detail.delivery.route }}</dd>
              <dt>Watermarks</dt><dd class="mono-sm faint" style="word-break: break-all">{{ detail.delivery.sourceWatermarks }}</dd>
            </dl>

            <div v-if="detail.delivery.accessRevoked" class="notice bad">
              Access revoked {{ fmt(detail.delivery.revokedAt) }} — {{ detail.delivery.revokedReason }}
              The summary and digests above are the preserved audit anchor.
            </div>

            <div class="divider"></div>
            <div>
              <div class="small faint" style="margin-bottom: 6px">Frozen member summary (kept even after revocation)</div>
              <div class="mono-sm scroll-y" style="max-height: 180px; line-height: 1.7">
                <div v-for="(line, i) in detail.summaryLines" :key="i">{{ line }}</div>
              </div>
            </div>

            <div v-if="detail.readable">
              <div class="divider"></div>
              <div class="small faint" style="margin-bottom: 6px">Projected records (de-identified)</div>
              <div class="mono-sm scroll-y" style="max-height: 220px; line-height: 1.7">
                <div v-for="r in detail.records" :key="r.id">
                  {{ r.subjectPseudonym }} · {{ r.stableId }}#{{ r.revision }} · {{ r.domain }} ·
                  {{ r.projectedJson }}
                </div>
              </div>
            </div>
            <div v-else class="notice">
              The projected content is no longer readable. The frozen summary above remains available for audit.
            </div>
          </div>
        </div>
      </section>
    </div>

    <section class="panel" style="margin-top: 16px">
      <div class="panel-head"><h2>Snapshot runs</h2><div class="spacer"></div>
        <span class="small faint">materialised snapshots invalidate on later consent / protocol revisions</span></div>
      <div class="panel-body tight">
        <div v-if="!runs.length" class="empty">No snapshot runs yet.</div>
        <table v-else>
          <thead><tr><th>Code</th><th>Day</th><th>Route</th><th>Status</th><th>Rows</th><th>Global</th><th>Local</th><th>Pending</th><th>Complete</th><th>Note</th></tr></thead>
          <tbody>
            <tr v-for="r in runs" :key="r.code">
              <td class="mono">{{ r.code }}</td>
              <td class="num">D{{ r.analysisDay }}</td>
              <td class="small">{{ r.route }}</td>
              <td><StatusBadge :kind="r.status" compact /></td>
              <td class="num">{{ r.rowCount }}</td>
              <td class="num">{{ r.globalCount }}</td>
              <td class="num">{{ r.localCount }}</td>
              <td class="num">{{ r.pendingCount }}</td>
              <td>{{ r.completenessOk ? 'yes' : 'gaps' }}</td>
              <td class="small faint">{{ r.staleReason || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>
