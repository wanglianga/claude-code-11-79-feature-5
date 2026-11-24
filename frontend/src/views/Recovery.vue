<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../lib/api.js'
import { store, toast, bump } from '../lib/store.js'
import StatusBadge from '../components/StatusBadge.vue'

const loading = ref(true)
const checks = ref([])
const quarantine = ref([])
const watermarks = ref(null)
const sites = ref([])
const site = ref('CN-02')
const busy = ref(false)

async function load() {
  loading.value = true
  try {
    const [r, q, w] = await Promise.all([api.get('/recovery'), api.get('/quarantine'), api.get(`/watermarks?day=${store.analysisDay}`)])
    checks.value = r
    quarantine.value = q
    watermarks.value = w
    sites.value = [...new Set((w.rows || []).map((x) => x.siteCode))]
  } finally {
    loading.value = false
  }
}
onMounted(load)

const openQuarantine = computed(() => quarantine.value.filter((q) => q.state === 'OPEN'))

async function run() {
  busy.value = true
  try {
    const res = await api.post('/recovery/run', { site: site.value })
    toast(`Recovery ${res.code} — ${res.status}`, res.status === 'PASS' ? 'ok' : 'warn')
    await load()
    bump()
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}

async function resolve(q) {
  const note = prompt(`Resolution note for ${q.code}:`, 'reviewed and accepted')
  if (note === null) return
  try {
    await api.post(`/quarantine/${q.code}/resolve`, { note })
    toast(`${q.code} resolved.`, 'ok')
    await load()
  } catch (e) {
    toast(e.message, 'bad')
  }
}

function parseReport(c) {
  try { return JSON.parse(c.reportJson) } catch { return null }
}

const expanded = ref(null)
function toggle(code) {
  expanded.value = expanded.value === code ? null : code
}

// simulate a re-send after restore so the duplicate path is visible
const resendForm = ref({ stableId: 'VST-D4-V1', pseudonym: 'PSN-D4', domain: 'VISIT', sourceSystem: 'CN-HUB-2', sourceSeq: 12, day: 12, hour: 10 })
const resending = ref(false)

async function resend() {
  resending.value = true
  try {
    const occurred = new Date(Date.UTC(2026, 2, 2, 9 + (resendForm.value.day - 1) * 24 + resendForm.value.hour, 0, 0)).toISOString()
    const res = await api.post('/events', {
      stableId: resendForm.value.stableId,
      revision: 1,
      pseudonym: resendForm.value.pseudonym,
      domain: resendForm.value.domain,
      occurredAt: occurred,
      receivedAt: occurred,
      sourceSystem: resendForm.value.sourceSystem,
      sourceSeq: resendForm.value.sourceSeq,
      protocolVersion: 'v1.2',
      fieldGroups: 'DEMOG,VISIT_VITALS',
      payloadJson: '{"bp":"126/82","hr":88,"tempC":37.1}',
      voided: false
    })
    toast(res.message, res.duplicate ? 'warn' : 'ok')
    await load()
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    resending.value = false
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
      <h1>Recovery &amp; quarantine</h1>
      <p class="lede">
        After a link outage the recovery verifier checks for sequence gaps, duplicate re-sends and per-domain digest
        agreement before the gap can be trusted. Races the platform cannot adjudicate from a registered rule are
        isolated here rather than resolved by picking a side.
      </p>
    </div>

    <div v-if="loading" class="empty">Loading…</div>
    <template v-else>
      <div class="grid cols-2" style="margin-bottom: 16px">
        <section class="panel">
          <div class="panel-head"><h2>Run recovery check</h2></div>
          <div class="panel-body stack">
            <div class="row">
              <label class="field" style="flex: 1"><span>Site</span>
                <select v-model="site">
                  <option v-for="s in sites" :key="s" :value="s">{{ s }}</option>
                </select>
              </label>
              <button class="primary" :disabled="busy" @click="run" style="align-self: flex-end">Run check</button>
            </div>
            <div class="notice small">
              The check re-derives each domain's ledger digest from the stored events and compares it with the
              transport receipts, then lists any sequences between the acknowledgement and the source-confirmed
              watermark that the ledger does not contain. A pass means no gaps, no unresolved duplicates and
              matching digests.
            </div>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Simulate a post-restore re-send</h2></div>
          <div class="panel-body stack">
            <div class="small muted">
              Re-sending an already-ingested event with the same transport identity must resolve to the existing
              row and never be counted twice.
            </div>
            <div class="grid cols-2">
              <label class="field"><span>Stable id</span><input v-model="resendForm.stableId" /></label>
              <label class="field"><span>Pseudonym</span><input v-model="resendForm.pseudonym" /></label>
              <label class="field"><span>Source system</span><input v-model="resendForm.sourceSystem" /></label>
              <label class="field"><span>Source sequence</span><input v-model.number="resendForm.sourceSeq" type="number" /></label>
            </div>
            <button :disabled="resending" @click="resend">Re-send event</button>
          </div>
        </section>
      </div>

      <section class="panel">
        <div class="panel-head"><h2>Recovery checks</h2></div>
        <div class="panel-body tight">
          <div v-if="!checks.length" class="empty">No recovery checks run yet.</div>
          <table v-else>
            <thead><tr><th>Code</th><th>Site</th><th>Run at</th><th>Status</th><th>Gaps</th><th>Duplicates</th><th>Mismatches</th><th></th></tr></thead>
            <tbody>
              <template v-for="c in checks" :key="c.code">
                <tr>
                  <td class="mono">{{ c.code }}</td>
                  <td class="mono">{{ c.siteCode }}</td>
                  <td class="mono-sm faint">{{ fmt(c.runAt) }}</td>
                  <td><StatusBadge :kind="c.status" compact /></td>
                  <td class="num">{{ c.gapCount }}</td>
                  <td class="num">{{ c.duplicateCount }}</td>
                  <td class="num">{{ c.mismatchCount }}</td>
                  <td><button class="sm ghost" @click="toggle(c.code)">{{ expanded === c.code ? 'Hide' : 'Report' }}</button></td>
                </tr>
                <tr v-if="expanded === c.code">
                  <td colspan="8">
                    <div class="stack small" style="padding: 6px 0">
                      <div v-for="d in (parseReport(c)?.domains || [])" :key="d.domain" class="row wrap">
                        <span class="chip">{{ d.domain }}</span>
                        <span :class="d.digestConsistent ? 'badge ok' : 'badge bad'">{{ d.digestConsistent ? 'digest ok' : 'digest mismatch' }}</span>
                        <span v-if="d.gapSequences?.length" class="badge bad">gaps {{ d.gapSequences.join(',') }}</span>
                        <span v-if="d.duplicateResends" class="badge warn">{{ d.duplicateResends }} re-send(s)</span>
                        <span class="mono-sm faint">{{ d.ledgerDigest?.slice(0, 20) }}…</span>
                      </div>
                      <div v-for="s in (parseReport(c)?.summary || [])" :key="s" class="muted">{{ s }}</div>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </section>

      <section class="panel" style="margin-top: 16px">
        <div class="panel-head"><h2>Quarantine</h2><div class="spacer"></div>
          <span class="small" :class="openQuarantine.length ? 'warn' : 'faint'">{{ openQuarantine.length }} open</span></div>
        <div class="panel-body tight">
          <div v-if="!quarantine.length" class="empty">Nothing quarantined.</div>
          <table v-else>
            <thead><tr><th>Code</th><th>Kind</th><th>Subject / record</th><th>Detail</th><th>State</th><th></th></tr></thead>
            <tbody>
              <tr v-for="q in quarantine" :key="q.code">
                <td class="mono">{{ q.code }}</td>
                <td><span class="chip">{{ q.kind }}</span></td>
                <td class="mono-sm">{{ q.subjectPseudonym || '—' }}<br />{{ q.stableId || '' }}</td>
                <td class="small muted" style="max-width: 400px">{{ q.detail }}</td>
                <td><StatusBadge :kind="q.state" compact /></td>
                <td><button v-if="q.state === 'OPEN'" class="sm" @click="resolve(q)">Resolve</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>
