<script setup>
import GhostWidget from './components/GhostWidget.vue'
import { formatDate } from '@/utils/format.js'
</script>

<template>
  <div class="page">
    <h1>数据面板</h1>
    <p>更新时间：{{ formatDate(Date.now()) }}</p>
    <GhostWidget />
  </div>
</template>

<style scoped>
.page {
  padding: 48px;
}
</style>
