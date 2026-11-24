<script setup>
defineProps({
  codes: { type: String, default: '' }
})
</script>

<template>
  <div class="reason-list">
    <span v-for="c in codes.split(',').filter(Boolean)" :key="c" class="chip">{{ c }}</span>
  </div>
</template>
