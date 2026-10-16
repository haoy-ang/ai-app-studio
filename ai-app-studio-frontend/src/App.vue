<script setup lang="ts">
import BasicLayout from '@/layouts/BasicLayout.vue'

/**
 * Ant Design Vue 主题令牌
 *
 * 与 src/styles/theme.css 中的 CSS 变量保持同一套取值，
 * 目的是让第三方组件（按钮、输入框、分页、弹窗）和自研样式
 * 共用一种视觉语言，避免出现「两套设计」的割裂感。
 *
 * ⚠️ 改这里的色值必须同步改 theme.css，否则 Antd 组件和自研组件会慢慢跑偏。
 */
const antdTheme = {
  token: {
    // 行动色：全站唯一可点击色
    colorPrimary: '#2563ff',
    colorLink: '#2563ff',
    colorLinkHover: '#1a52ef',
    // 文字与边框
    colorText: '#0b1524',
    colorTextSecondary: '#3d4a5f',
    colorTextTertiary: '#6b7a90',
    colorBorder: '#e4e8ef',
    colorBorderSecondary: '#eef1f6',
    colorBgContainer: '#ffffff',
    colorBgLayout: '#f6f7f9',
    // 几何：圆角收紧，和 theme.css 的圆角阶梯对齐
    borderRadius: 8,
    borderRadiusLG: 10,
    fontSize: 14,
    controlHeight: 36,
    // 阴影统一走"偏硬偏短"的工程感，不用 Antd 默认的大范围柔光
    boxShadowSecondary: '0 6px 16px -6px rgba(11, 21, 36, 0.14)',
  },
}
</script>

<template>
  <a-config-provider :theme="antdTheme">
    <BasicLayout />
  </a-config-provider>
</template>

<style>
/* 基础重置与设计变量已统一收敛到 src/styles/theme.css */
html,
body {
  overflow-x: hidden;
}
</style>
