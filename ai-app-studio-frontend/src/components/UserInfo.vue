<template>
  <div class="user-info">
    <a-avatar :src="user?.userAvatar || defaultAvatar" :size="size" />
    <span v-if="showName" class="user-name">{{ user?.userName?.trim() || '用户' }}</span>
  </div>
</template>

<script setup lang="ts">
import defaultAvatar from '@/assets/defaultAvatar.png'

interface Props {
  /**
   * 用户信息。
   * 这里刻意用结构类型而不是某个具体 VO：应用详情传进来的是 AppUserVO（公开脱敏版，
   * 不含 userAccount），管理端传的是 UserVO，而本组件只需要昵称和头像 ——
   * 为一个字段差异去定义联合类型得不偿失。
   */
  user?: { userName?: string; userAvatar?: string }
  size?: number | 'small' | 'default' | 'large'
  showName?: boolean
}

withDefaults(defineProps<Props>(), {
  size: 'default',
  showName: true,
})
</script>

<style scoped>
.user-info {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.user-name {
  font-size: var(--fs-base);
  color: var(--text-1);
}
</style>
