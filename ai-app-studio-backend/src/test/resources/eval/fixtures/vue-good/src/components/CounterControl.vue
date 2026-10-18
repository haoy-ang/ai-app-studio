<script setup>
defineProps({
  running: { type: Boolean, default: false }
})

defineEmits(['toggle', 'reset'])
</script>

<template>
  <div class="controls">
    <button class="primary" type="button" @click="$emit('toggle')">
      {{ running ? '暂停' : '开始' }}
    </button>
    <button class="ghost" type="button" @click="$emit('reset')">重置</button>
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  gap: 12px;
}

button {
  border: none;
  border-radius: 8px;
  padding: 12px 28px;
  font-size: 15px;
  cursor: pointer;
  font-family: inherit;
}

.primary {
  background: var(--accent);
  color: #1a1110;
  font-weight: 600;
}

.ghost {
  background: transparent;
  color: var(--muted);
  border: 1px solid var(--surface);
}
</style>
