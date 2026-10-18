<script setup>
import { computed } from 'vue'
import CounterControl from './components/CounterControl.vue'
import { usePomodoro } from './composables/usePomodoro.js'

const { remaining, running, progress, toggle, reset, phaseLabel } = usePomodoro(25)

const timeText = computed(() => {
  const total = Math.max(0, remaining.value)
  const minutes = String(Math.floor(total / 60)).padStart(2, '0')
  const seconds = String(total % 60).padStart(2, '0')
  return `${minutes}:${seconds}`
})
</script>

<template>
  <main class="shell">
    <p class="phase">{{ phaseLabel }}</p>
    <h1 class="clock">{{ timeText }}</h1>

    <div class="bar">
      <div class="bar-fill" :style="{ width: progress + '%' }"></div>
    </div>

    <CounterControl
      :running="running"
      @toggle="toggle"
      @reset="reset"
    />

    <p class="hint">专注 25 分钟，然后站起来走两分钟。</p>
  </main>
</template>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 24px;
  min-height: 100vh;
  padding: 48px 24px;
}

.phase {
  color: var(--muted);
  letter-spacing: 0.16em;
  text-transform: uppercase;
  font-size: 12px;
}

.clock {
  font-size: 96px;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.04em;
}

.bar {
  width: 260px;
  height: 4px;
  border-radius: 999px;
  background: var(--surface);
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  background: var(--accent);
  transition: width 0.6s linear;
}

.hint {
  color: var(--muted);
  font-size: 13px;
}
</style>
