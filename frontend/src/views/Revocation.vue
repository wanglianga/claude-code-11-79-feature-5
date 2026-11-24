<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../lib/api.js'
import { toast, bump } from '../lib/store.js'
import StatusBadge from '../components/StatusBadge.vue'

const loading = ref(true)
const consents = ref([])
const rules = ref(null)
const deliveries = ref([])
const executions = ref([])
const lastOutcome = ref(null)
const busy = ref(false)

async function load() {
  loading.value = true
  try {
    const [c, r, d] = await Promise.all([api.get('/consents'), api.get('/rules'), api.get('/deliveries')])
    consents.value = c
    rules.value = r
    deliveries.value = d
    executions.value = r.executions || []
  } finally {
    loading.value = false
  }
}
onMounted(load)

const withdrawals = computed(() => consents.value.filter((c) => c.kind === 'WITHDRAWAL'))
const dispositionRules = computed(() => rules.value?.dispositionRules || [])
const tieBreakRules = computed(() => rules.value?.tieBreakRules || [])

const showRule = ref(false)
const ruleForm = ref({
  code: 'DR-DESTROY-30D',
  scope: 'STUDY',
  siteCode: '',
  purpose: '',
  action: 'DESTROY_AFTER',
  retainYears: null,
  destroyAfterDays: 30,
  legalBasis: ''
})

async function propagate(entry) {
  if (!confirm(`Propagate withdrawal #${entry.id} for ${entry.subject}?\n\nThis will revoke access to any delivery containing the affected rows and apply the registered disposition. The original delivery summary is preserved.`)) return
  busy.value = true
  try {
    const res = await api.post('/revocations/propagate', { consentEntryId: entry.id })
    lastOutcome.value = res
    toast(res.summary, 'warn')
    await load()
    bump()
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}

async function addRule() {
  busy.value = true
  try {
    await api.post('/rules/disposition', {
      ...ruleForm.value,
      purpose: ruleForm.value.purpose || null,
      destroyAfterDays: ruleForm.value.action === 'DESTROY_AFTER' ? ruleForm.value.destroyAfterDays : null,
      retainYears: ruleForm.value.action === 'RETAIN_RESTRICTED' ? ruleForm.value.retainYears : null
    })
    toast(`Disposition rule ${ruleForm.value.code} registered.`, 'ok')
    showRule.value = false
    await load()
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
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
      <h1>Withdrawal propagation</h1>
      <p class="lede">
        A withdrawal stops future queries at its effective instant. Propagating it revokes access to already-generated
        deliveries that contain affected rows — immediately — while keeping each delivery's original summary and digests
        for audit. The disposition (retain restricted, or destroy after a registered interval) comes from a rule that
        was registered <em>before</em> the withdrawal.
      </p>
    </div>

    <div v-if="loading" class="empty">Loading…</div>
    <template v-else>
      <div v-if="lastOutcome" class="notice warn" style="margin-bottom: 16px">
        <strong>{{ lastOutcome.summary }}</strong>
        <div class="small" style="margin-top: 6px">
          Dispositions: {{ lastOutcome.dispositions.join('; ') || 'none' }}.
          Deliveries affected: {{ lastOutcome.deliveryCodes.join(', ') || 'none' }}.
        </div>
      </div>

      <div class="grid cols-2">
        <section class="panel">
          <div class="panel-head"><h2>Recorded withdrawals</h2></div>
          <div class="panel-body tight">
            <div v-if="!withdrawals.length" class="empty">No withdrawals recorded.</div>
            <table v-else>
              <thead><tr><th>#</th><th>Subject</th><th>Purposes</th><th>Effective</th><th></th></tr></thead>
              <tbody>
                <tr v-for="w in withdrawals" :key="w.id">
                  <td class="num">{{ w.id }}</td>
                  <td><span class="chip">{{ w.subject }}</span></td>
                  <td class="mono-sm">{{ w.purposes }}</td>
                  <td class="mono-sm">D{{ w.effectiveFromDay }}<br />{{ fmt(w.effectiveFrom) }}</td>
                  <td><button class="danger sm" :disabled="busy" @click="propagate(w)">Propagate</button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Deliveries &amp; access</h2></div>
          <div class="panel-body tight scroll-y">
            <div v-if="!deliveries.length" class="empty">No deliveries compiled.</div>
            <table v-else>
              <thead><tr><th>Code</th><th>Members</th><th>Status</th><th>Access</th></tr></thead>
              <tbody>
                <tr v-for="d in deliveries" :key="d.code">
                  <td class="mono">{{ d.code }}</td>
                  <td class="num">{{ d.legalMemberCount }}</td>
                  <td><StatusBadge :kind="d.status" compact /></td>
                  <td>
                    <span class="badge" :class="d.accessRevoked ? 'bad' : 'ok'">{{ d.accessRevoked ? 'revoked' : 'open' }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>

      <div class="grid cols-2" style="margin-top: 16px">
        <section class="panel">
          <div class="panel-head"><h2>Disposition rules</h2><div class="spacer"></div>
            <button class="sm" @click="showRule = !showRule">+ Register rule</button></div>
          <div class="panel-body tight">
            <div v-if="showRule" class="panel-body stack" style="border-bottom: 1px solid var(--line-soft)">
              <div class="grid cols-2">
                <label class="field"><span>Code</span><input v-model="ruleForm.code" /></label>
                <label class="field"><span>Scope</span>
                  <select v-model="ruleForm.scope"><option>STUDY</option><option>SITE</option></select>
                </label>
                <label class="field"><span>Site (for site scope)</span><input v-model="ruleForm.siteCode" placeholder="CN-02" /></label>
                <label class="field"><span>Purpose (blank = any)</span>
                  <select v-model="ruleForm.purpose">
                    <option value="">any</option>
                    <option>GLOBAL_ANALYSIS</option><option>CHINA_LOCAL_ANALYSIS</option><option>LOCAL_ANALYSIS</option>
                  </select>
                </label>
                <label class="field"><span>Action</span>
                  <select v-model="ruleForm.action">
                    <option>RETAIN_RESTRICTED</option><option>DESTROY_AFTER</option>
                  </select>
                </label>
                <label class="field" v-if="ruleForm.action === 'RETAIN_RESTRICTED'"><span>Retain years</span>
                  <input v-model.number="ruleForm.retainYears" type="number" /></label>
                <label class="field" v-if="ruleForm.action === 'DESTROY_AFTER'"><span>Destroy after (days)</span>
                  <input v-model.number="ruleForm.destroyAfterDays" type="number" /></label>
              </div>
              <label class="field"><span>Legal basis (required)</span><textarea v-model="ruleForm.legalBasis"></textarea></label>
              <div class="row"><button class="primary" :disabled="busy" @click="addRule">Register</button>
                <button @click="showRule = false">Cancel</button></div>
            </div>
            <table>
              <thead><tr><th>Code</th><th>Scope</th><th>Action</th><th>Basis</th></tr></thead>
              <tbody>
                <tr v-for="r in dispositionRules" :key="r.code">
                  <td class="mono">{{ r.code }}</td>
                  <td class="small">{{ r.scope }}{{ r.siteCode ? ' · ' + r.siteCode : '' }}</td>
                  <td><StatusBadge :kind="r.action" compact /></td>
                  <td class="small faint" style="max-width: 320px">{{ r.legalBasis }}</td>
                </tr>
                <tr v-if="!dispositionRules.length"><td colspan="4" class="empty">No rules registered.</td></tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="panel">
          <div class="panel-head"><h2>Disposition executions</h2></div>
          <div class="panel-body tight scroll-y">
            <div v-if="!executions.length" class="empty">No dispositions executed yet.</div>
            <table v-else>
              <thead><tr><th>Rule</th><th>Delivery</th><th>Subject</th><th>State</th><th>Rows</th></tr></thead>
              <tbody>
                <tr v-for="e in executions" :key="e.id">
                  <td class="mono">{{ e.rule?.code }}</td>
                  <td class="mono">{{ e.delivery?.code }}</td>
                  <td class="mono-sm">{{ e.subject?.pseudonym || '—' }}</td>
                  <td><StatusBadge :kind="e.state" compact /></td>
                  <td class="num">{{ e.affectedRows }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </div>

      <section v-if="tieBreakRules.length" class="panel" style="margin-top: 16px">
        <div class="panel-head"><h2>Withdrawal / correction tie-break rules</h2></div>
        <div class="panel-body tight">
          <table>
            <thead><tr><th>Code</th><th>Precedence</th><th>Rationale</th></tr></thead>
            <tbody>
              <tr v-for="t in tieBreakRules" :key="t.code">
                <td class="mono">{{ t.code }}</td>
                <td><span class="chip">{{ t.precedence }}</span></td>
                <td class="small faint">{{ t.rationale }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
      <div v-else class="notice" style="margin-top: 16px">
        No tie-break rule is registered. A withdrawal/correction race with equal effective instants will therefore be
        <strong>quarantined</strong>, not resolved by guessing.
      </div>
    </template>
  </div>
</template>
