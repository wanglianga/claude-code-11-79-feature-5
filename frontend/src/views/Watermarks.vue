<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../lib/api.js'
import { store, toast, bump } from '../lib/store.js'
import WatermarkBar from '../components/WatermarkBar.vue'
import StatusBadge from '../components/StatusBadge.vue'

const loading = ref(true)
const data = ref(null)
const recovery = ref([])
const selectedSite = ref('CN-02')
const selectedDomain = ref('VISIT')

const DOMAINS = ['VISIT', 'LAB', 'AE', 'IMAGING', 'RANDOMIZATION', 'DEVIATION']

async function load() {
  loading.value = true
  try {
    data.value = await api.get(`/watermarks?day=${store.analysisDay}`)
    recovery.value = await api.get('/recovery')
  } finally {
    loading.value = false
  }
}
onMounted(load)

const sites = computed(() => [...new Set((data.value?.rows || []).map((r) => r.siteCode))])
const rowsForSite = computed(() => (data.value?.rows || []).filter((r) => r.siteCode === selectedSite.value))
const coverageForSite = computed(() => (data.value?.coverage || []).filter((r) => r.siteCode === selectedSite.value))

async function op(path, body, label) {
  try {
    await api.post(path, body)
    toast(label, 'ok')
    await load()
    bump()
  } catch (e) {
    toast(e.message, 'bad')
  }
}

function confirmSource() {
  const row = rowsForSite.value.find((r) => r.domain === selectedDomain.value)
  const seq = row ? Math.max(row.sourceConfirmedSeq, row.sourceConfirmedSeq + 1) : 1
  const v = prompt(`Confirm source sequence for ${selectedSite.value}/${selectedDomain.value}:`, String(seq))
  if (v === null) return
  op('/watermarks/confirm-source', { site: selectedSite.value, domain: selectedDomain.value, seq: Number(v) },
    `Source confirmed to ${v} for ${selectedSite.value}/${selectedDomain.value}`)
}

function ackTransfer() {
  const v = prompt(`Acknowledge transfer through sequence for ${selectedSite.value}/${selectedDomain.value}:`, '')
  if (!v) return
  op('/watermarks/ack-transfer', { site: selectedSite.value, domain: selectedDomain.value, seq: Number(v) },
    `Transfer acknowledged to ${v}`)
}

function foldAnalysis() {
  const v = prompt(`Fold analysis plane through sequence for ${selectedSite.value}/${selectedDomain.value}:`, '')
  if (!v) return
  op('/watermarks/fold-analysis', { site: selectedSite.value, domain: selectedDomain.value, seq: Number(v) },
    `Analysis folded to ${v}`)
}

function breakLink() {
  const reason = prompt(`Reason for interrupting the ${selectedSite.value} link:`, 'planned outage')
  if (reason === null) return
  op('/watermarks/break-link', { site: selectedSite.value, reason }, `Link for ${selectedSite.value} marked broken`)
}

function restoreLink() {
  op('/watermarks/restore-link', { site: selectedSite.value }, `Link for ${selectedSite.value} restored`)
}

async function runRecovery() {
  try {
    const res = await api.post('/recovery/run', { site: selectedSite.value })
    toast(`Recovery check ${res.code}: ${res.status} — ${res.gapCount} gap(s), ${res.duplicateCount} duplicate(s).`,
      res.status === 'PASS' ? 'ok' : 'warn')
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
      <h1>Watermarks &amp; transfer coordination</h1>
      <p class="lede">
        Each domain keeps three independent counters per site: what the source has confirmed, what the transfer
        acknowledged, and what the global analysis plane has folded in. A gap between analysis and source is a
        true unknown — it is reported as unconfirmed, never as an absence of events.
      </p>
    </div>

    <div v-if="loading" class="empty">Loading…</div>
    <template v-else>
      <div class="grid cols-2" style="margin-bottom: 16px">
        <section class="panel">
          <div class="panel-head"><h2>Domain coverage</h2><div class="spacer"></div>
            <span class="small faint">through day {{ data?.day }}</span></div>
          <div class="panel-body stack">
            <WatermarkBar v-for="r in data?.rows || []" :key="r.siteCode + r.domain" :row="{
              siteCode: r.siteCode, domain: r.domain,
              sourceConfirmed: r.sourceConfirmedSeq, transferAck: r.transferAckSeq, analysis: r.analysisSeq,
              unconfirmedDays: (data.coverage || []).find(c => c.site === r.siteCode && c.domain === r.domain)?.unconfirmedDays || []
            }" />
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Operate</h2></div>
          <div class="panel-body stack">
            <div class="grid cols-2">
              <label class="field"><span>Site</span>
                <select v-model="selectedSite">
                  <option v-for="s in sites" :key="s" :value="s">{{ s }}</option>
                </select>
              </label>
              <label class="field"><span>Domain</span>
                <select v-model="selectedDomain">
                  <option v-for="d in DOMAINS" :key="d" :value="d">{{ d }}</option>
                </select>
              </label>
            </div>
            <div class="row wrap">
              <button @click="confirmSource">Confirm source</button>
              <button @click="ackTransfer">Ack transfer</button>
              <button @click="foldAnalysis">Fold analysis</button>
            </div>
            <div class="row wrap">
              <button class="danger" @click="breakLink">Break link</button>
              <button @click="restoreLink">Restore link</button>
              <button class="primary" @click="runRecovery">Run recovery check</button>
            </div>
            <div class="notice small">
              The analysis plane can never fold beyond the transfer acknowledgement; folding is capped there.
              Acknowledging beyond the source-confirmed sequence is rejected.
            </div>
            <div class="divider"></div>
            <div>
              <div class="small faint" style="margin-bottom: 6px">Pinned watermark string for day {{ data?.day }}</div>
              <div class="mono-sm" style="word-break: break-all">{{ data?.pin }}</div>
              <div class="small faint" style="margin-top: 6px">Digest <code>{{ data?.digest?.slice(0, 24) }}…</code></div>
            </div>
          </div>
        </section>
      </div>

      <section class="panel">
        <div class="panel-head"><h2>Unconfirmed days per site</h2></div>
        <div class="panel-body tight">
          <div v-if="!data?.coverage?.length" class="empty">No coverage gaps at this instant.</div>
          <table v-else>
            <thead><tr><th>Site</th><th>Domain</th><th>Source</th><th>Ack</th><th>Analysis</th><th>Unconfirmed</th></tr></thead>
            <tbody>
              <tr v-for="c in data.coverage" :key="c.site + c.domain">
                <td class="mono">{{ c.site }}</td>
                <td>{{ c.domain }}</td>
                <td class="num">{{ c.sourceConfirmed }}</td>
                <td class="num">{{ c.transferAck }}</td>
                <td class="num">{{ c.analysis }}</td>
                <td><span class="badge bad">{{ c.unconfirmedDays.join(' ') }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="panel" style="margin-top: 16px">
        <div class="panel-head"><h2>Watermark registry</h2></div>
        <div class="panel-body tight scroll-y">
          <table>
            <thead>
              <tr><th>Site</th><th>Domain</th><th>Source confirmed</th><th>Transfer ack</th><th>Analysis</th><th>Link</th><th>Updated</th></tr>
            </thead>
            <tbody>
              <tr v-for="r in data?.rows || []" :key="'reg' + r.siteCode + r.domain">
                <td class="mono">{{ r.siteCode }}</td>
                <td class="small">{{ r.domain }}</td>
                <td class="num">{{ r.sourceConfirmedSeq }}</td>
                <td class="num">{{ r.transferAckSeq }}</td>
                <td class="num">{{ r.analysisSeq }}</td>
                <td>
                  <span class="badge" :class="r.linkBrokenAt && (!r.linkRestoredAt || r.linkRestoredAt < r.linkBrokenAt) ? 'bad' : 'ok'">
                    {{ r.linkBrokenAt && (!r.linkRestoredAt || r.linkRestoredAt < r.linkBrokenAt) ? 'interrupted' : 'healthy' }}
                  </span>
                </td>
                <td class="mono-sm faint">{{ fmt(r.updatedAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>
