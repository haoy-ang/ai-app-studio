<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-header">
        <h2 class="auth-title">欢迎回来</h2>
        <p class="auth-desc">登录后即可创建和管理你的应用</p>
      </div>

      <a-form :model="formState" name="basic" autocomplete="off" @finish="handleSubmit">
        <a-form-item name="userAccount" :rules="[{ required: true, message: '请输入账号' }]">
          <a-input v-model:value="formState.userAccount" placeholder="请输入账号" size="large" />
        </a-form-item>
        <a-form-item
          name="userPassword"
          :rules="[
            { required: true, message: '请输入密码' },
            { min: 8, message: '密码长度不能小于 8 位' },
          ]"
        >
          <a-input-password
            v-model:value="formState.userPassword"
            placeholder="请输入密码"
            size="large"
          />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" size="large" block>登录</a-button>
        </a-form-item>
      </a-form>

      <div class="auth-tips">
        还没有账号？
        <RouterLink to="/user/register">立即注册</RouterLink>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { reactive } from 'vue'
import { userLogin } from '@/api/userController'
import { useLoginUserStore } from '@/stores/loginUser'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'

const formState = reactive<API.UserLoginRequest>({
  userAccount: '',
  userPassword: '',
})

const router = useRouter()
const loginUserStore = useLoginUserStore()

/**
 * 提交表单
 * @param values
 */
const handleSubmit = async (values: API.UserLoginRequest) => {
  const res = await userLogin(values)
  // 登录成功，把登录态保存到全局状态中
  if (res.data.code === 0 && res.data.data) {
    await loginUserStore.fetchLoginUser()
    message.success('登录成功')
    router.push({
      path: '/',
      replace: true,
    })
  } else {
    message.error('登录失败，' + res.data.message)
  }
}
</script>

<style scoped>
.auth-page {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - var(--header-h) - 200px);
  padding: var(--sp-7) var(--sp-5);
  overflow: hidden;
}

/*
 * 蓝图网格 + 一处柔光：原先白卡片压在白底上，只靠 1px 边框区分，几乎没有分离度。
 * 加上背景层之后卡片自然"浮"起来，且不需要把卡片做成玻璃拟态那么重。
 */
.auth-page::before,
.auth-page::after {
  content: '';
  position: absolute;
  z-index: 0;
  pointer-events: none;
}

.auth-page::before {
  inset: 0;
  background-image:
    linear-gradient(to right, var(--grid-line) 1px, transparent 1px),
    linear-gradient(to bottom, var(--grid-line) 1px, transparent 1px);
  background-size: var(--grid-size) var(--grid-size);
  -webkit-mask-image: radial-gradient(80% 64% at 50% 44%, #000 26%, transparent 78%);
  mask-image: radial-gradient(80% 64% at 50% 44%, #000 26%, transparent 78%);
}

.auth-page::after {
  width: 620px;
  height: 380px;
  left: 50%;
  top: 6%;
  transform: translateX(-50%);
  background: radial-gradient(closest-side, rgba(37, 99, 255, 0.09), transparent 72%);
}

.auth-card {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 400px;
  padding: var(--sp-6);
  background: var(--bg-card);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-lg);
}

.auth-header {
  margin-bottom: var(--sp-5);
}

.auth-title {
  margin: 0 0 var(--sp-1);
  font-size: 24px;
  font-weight: 700;
  color: var(--text-1);
  letter-spacing: -0.6px;
}

.auth-desc {
  margin: 0;
  font-size: var(--fs-base);
  color: var(--text-3);
}

.auth-tips {
  margin-top: var(--sp-1);
  text-align: center;
  font-size: var(--fs-sm);
  color: var(--text-3);
}
</style>
