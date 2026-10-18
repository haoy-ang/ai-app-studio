import { computed, onUnmounted, ref } from 'vue'

/**
 * 番茄钟计时逻辑：把「秒数倒数 + 暂停 + 重置」从组件里抽出来
 * @param {number} focusMinutes 专注时长（分钟）
 */
export function usePomodoro(focusMinutes = 25) {
  const total = focusMinutes * 60
  const remaining = ref(total)
  const running = ref(false)
  let timer = null

  const progress = computed(() => ((total - remaining.value) / total) * 100)
  const phaseLabel = computed(() => (remaining.value === 0 ? '休息一下' : '专注中'))

  function tick() {
    if (remaining.value <= 0) {
      stop()
      return
    }
    remaining.value -= 1
  }

  function start() {
    if (running.value) {
      return
    }
    running.value = true
    timer = setInterval(tick, 1000)
  }

  function stop() {
    running.value = false
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  function toggle() {
    running.value ? stop() : start()
  }

  function reset() {
    stop()
    remaining.value = total
  }

  onUnmounted(stop)

  return { remaining, running, progress, phaseLabel, toggle, reset }
}
