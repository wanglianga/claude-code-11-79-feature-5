<script setup>
import { computed } from 'vue'

const props = defineProps({
  row: { type: Object, required: true } // { siteCode, domain, sourceConfirmed, transferAck, analysis, unconfirmedDays }
})

const max = computed(() => Math.max(props.row.sourceConfirmed, props.row.transferAck, props.row.analysis, 1))
const pct = (v) => `${(v / max.value) * 100}%`
const hasGap = computed(() => props.row.sourceConfirmed > props.row.analysis || (props.row.unconfirmedDays || []).length > 0)
</script>

<template>
  <div class="wm-row">
    <div>
      <div class="mono-sm" style="font-weight: 600">{{ row.siteCode }}</div>
      <div class="faint mono-sm">{{ row.domain }}</div>
    </div>
    <div>
      <div class="wm-track" :title="`source=${row.sourceConfirmed} ack=${row.transferAck} analysis=${row.analysis}`">
        <div class="wm-seg analysis" :style="{ width: pct(row.analysis) }" title="analysis plane"></div>
        <div class="wm-seg acked" :style="{ width: pct(Math.max(0, row.transferAck - row.analysis)) }" title="transfer acknowledged, not yet analysed"></div>
        <div class="wm-seg gap" :style="{ width: pct(Math.max(0, row.sourceConfirmed - row.transferAck)) }" title="source confirmed, transfer not acked — UNCONFIRMED"></div>
      </div>
      <div class="row small" style="margin-top: 4px; gap: 14px">
        <span class="faint">src <span class="num">{{ row.sourceConfirmed }}</span></span>
        <span class="faint">ack <span class="num">{{ row.transferAck }}</span></span>
        <span class="faint">analysis <span class="num">{{ row.analysis }}</span></span>
        <span v-if="hasGap" class="badge bad" style="margin-left: auto">UNCONFIRMED {{ (row.unconfirmedDays || []).join(' ') }}</span>
      </div>
    </div>
  </div>
</template>
