<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../lib/api.js'
import { store } from '../lib/store.js'
import StatusBadge from '../components/StatusBadge.vue'
import ReasonChips from '../components/ReasonChips.vue'

const loading = ref(true)
const report = ref(null)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    report.value = await api.get(`/acceptance?day=${store.analysisDay}`)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

const groupOrder = ['A', 'B', 'C', 'D', 'ALL']
const groupTitle = {
  A: 'A — China-only consent, day-11 visit uploaded day 15',
  B: 'B — global withdrawal on day 12',
  C: 'C — protocol amendment effective day 14',
  D: 'D — site transfer stalled at day 9',
  ALL: 'Cross-route equivalence'
}
const groupLede = {
  A: 'A signed a China-only consent on day 10. The visit occurred on day 11 and was only uploaded on day 15. The exclusion must come from the consent scope, not from the late upload.',
  B: 'B withdrew the global purpose on day 12 (local signature time). An event that occurred on day 11 while consent was in force is held for restricted retention; a new visit on day 13 has no basis at all.',
  C: 'Protocol v2.0 adds new imaging fields but only takes effect on day 14, at CN-01. Earlier visits keep the baseline version; the new fields never leak backwards or sideways.',
  D: 'The CN-02 link stopped acknowledging transfers after day 9. Records above the analysis watermark must show as unconfirmed rather than being rendered as zero or absent.',
  ALL: 'The materialised and replay reconstruction routes must produce the same legal set for the same rules, timestamps and records.'
}

function checksFor(group) {
  return (report.value?.checks || []).filter((c) => c.subject === group)
}
</script>

<template>
  <div>
    <div class="page-head">
      <h1>Acceptance</h1>
      <p class="lede">
        Four subjects, three visits, one reference instant. Every card below is produced by the same
        eligibility engine and watermark data that the deliveries use — not a scripted demo.
      </p>
    </div>

    <div v-if="error" class="notice bad">{{ error }}</div>
    <div v-if="loading" class="empty">Evaluating at analysis day {{ store.analysisDay }}…</div>

    <div v-else-if="report" class="stack">
      <div class="notice" :class="report.allPass ? 'ok' : 'bad'">
        <strong>{{ report.summary }}</strong>
      </div>

      <div class="grid cols-2">
        <section v-for="g in groupOrder" :key="g" class="panel">
          <div class="panel-head">
            <h2>{{ groupTitle[g] }}</h2>
            <div class="spacer"></div>
            <StatusBadge :kind="checksFor(g).every((c) => c.pass) ? 'PASS' : 'FINDINGS'" />
          </div>
          <div class="panel-body stack">
            <p class="small muted" style="margin: 0">{{ groupLede[g] }}</p>
            <div v-for="c in checksFor(g)" :key="c.code" class="stack" style="gap: 6px">
              <div class="row" style="align-items: flex-start">
                <span class="chip">{{ c.code }}</span>
                <span class="small" style="flex: 1">{{ c.requirement }}</span>
                <StatusBadge :kind="c.pass ? 'PASS' : 'FINDINGS'" compact />
              </div>
              <div class="small faint" style="padding-left: 44px">
                Expected: {{ c.expected }}
              </div>
              <div style="padding-left: 44px">
                <ReasonChips v-if="c.observed.includes(',')" :codes="c.observed" />
                <span v-else class="mono-sm">{{ c.observed }}</span>
              </div>
            </div>
          </div>
        </section>
      </div>

      <section class="panel">
        <div class="panel-head"><h2>Coverage gaps at day {{ report.analysisDay }}</h2></div>
        <div class="panel-body">
          <div v-if="!report.coverage.length" class="notice ok">No unconfirmed domains at this instant.</div>
          <table v-else>
            <thead><tr><th>Site</th><th>Domain</th><th>Source confirmed</th><th>Transfer ack</th><th>Analysis</th><th>Unconfirmed days</th></tr></thead>
            <tbody>
              <tr v-for="c in report.coverage" :key="c.site + c.domain">
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

      <section class="panel">
        <div class="panel-head"><h2>Design notes</h2></div>
        <div class="panel-body">
          <ul class="small muted" style="margin: 0; padding-left: 18px">
            <li v-for="n in report.extraNotes" :key="n" style="margin-bottom: 6px">{{ n }}</li>
          </ul>
        </div>
      </section>
    </div>
  </div>
</template>
