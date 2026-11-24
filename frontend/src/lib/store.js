import { reactive } from 'vue'

/** Cross-view shared state: the analysis day and a small toast queue. */
export const store = reactive({
  analysisDay: 16,
  siteFilter: 'ALL',
  toasts: [],
  refreshTick: 0
})

let toastId = 0
export function toast(message, kind = 'info') {
  const id = ++toastId
  store.toasts.push({ id, message, kind })
  setTimeout(() => {
    const i = store.toasts.findIndex((t) => t.id === id)
    if (i >= 0) store.toasts.splice(i, 1)
  }, 5200)
}

export function bump() {
  store.refreshTick++
}
