<script setup>
const props = defineProps({
  kind: { type: String, required: true }, // LOCAL_AVAILABLE | GLOBAL_AVAILABLE | PENDING | BLOCKED
  compact: { type: Boolean, default: false }
})

const map = {
  LOCAL_AVAILABLE: { cls: 'local', label: 'Local available' },
  GLOBAL_AVAILABLE: { cls: 'ok', label: 'Global available' },
  PENDING: { cls: 'pend', label: 'Pending' },
  BLOCKED: { cls: 'bad', label: 'Blocked' },
  ELIGIBLE: { cls: 'ok', label: 'Eligible' },
  UNDETERMINED: { cls: 'pend', label: 'Undetermined' },
  NOT_ELIGIBLE: { cls: 'bad', label: 'Not eligible' },
  VALID: { cls: 'ok', label: 'Valid' },
  STALE: { cls: 'warn', label: 'Stale' },
  AUDIT_ONLY: { cls: 'muted', label: 'Audit only' },
  ACTIVE: { cls: 'ok', label: 'Active' },
  ACCESS_REVOKED: { cls: 'bad', label: 'Access revoked' },
  DESTROYED: { cls: 'muted', label: 'Destroyed' },
  GRANT: { cls: 'ok', label: 'Grant' },
  WITHDRAWAL: { cls: 'bad', label: 'Withdrawal' },
  APPROVED: { cls: 'ok', label: 'Approved' },
  PARTIALLY_APPROVED: { cls: 'warn', label: 'Partially approved' },
  WITHDRAWN: { cls: 'bad', label: 'Withdrawn' },
  DRAFT: { cls: 'muted', label: 'Draft' },
  OPEN: { cls: 'warn', label: 'Open' },
  RESOLVED: { cls: 'ok', label: 'Resolved' },
  PASS: { cls: 'ok', label: 'Pass' },
  FINDINGS: { cls: 'warn', label: 'Findings' },
  APPLIED: { cls: 'local', label: 'Applied' },
  SCHEDULED: { cls: 'warn', label: 'Scheduled' },
  EXECUTED: { cls: 'ok', label: 'Executed' },
  RETAIN_RESTRICTED: { cls: 'local', label: 'Retain restricted' },
  DESTROY_AFTER: { cls: 'bad', label: 'Destroy after' }
}

function meta() {
  return map[props.kind] || { cls: 'muted', label: props.kind || '—' }
}
</script>

<template>
  <span class="badge" :class="meta().cls">
    <span v-if="!compact" class="pip"></span>{{ meta().label }}
  </span>
</template>
