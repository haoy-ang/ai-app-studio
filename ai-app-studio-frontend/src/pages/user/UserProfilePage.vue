<template>
  <div class="profile-page">
    <div class="profile-card">
      <header class="page-head">
        <h2 class="page-title">个人资料</h2>
        <p class="page-desc">昵称和头像会展示在你创建的应用上</p>
      </header>

      <a-form :model="formState" layout="vertical" @finish="handleSubmit">
        <a-form-item label="头像">
          <div class="avatar-row">
            <a-avatar :size="72" :src="formState.userAvatar || defaultAvatar" />
            <div class="avatar-actions">
              <a-upload
                :show-upload-list="false"
                :before-upload="beforeAvatarUpload"
                :custom-request="handleAvatarUpload"
                accept="image/png,image/jpeg,image/gif,image/webp"
              >
                <a-button :loading="uploading">更换头像</a-button>
              </a-upload>
              <span class="field-tip">支持 png / jpg / gif / webp，不超过 2MB</span>
            </div>
          </div>
        </a-form-item>

        <a-form-item label="昵称" required>
          <a-input
            v-model:value="formState.userName"
            placeholder="请输入昵称"
            :maxlength="20"
            show-count
          />
        </a-form-item>

        <a-form-item label="简介">
          <a-textarea
            v-model:value="formState.userProfile"
            placeholder="简单介绍一下自己（可选）"
            :rows="4"
            :maxlength="200"
            show-count
          />
        </a-form-item>

        <a-form-item>
          <a-space>
            <a-button type="primary" html-type="submit" :loading="submitting">保存</a-button>
            <a-button @click="resetForm">重置</a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import type { UploadProps } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { updateMyProfile, uploadAvatar } from '@/api/userController'
import defaultAvatar from '@/assets/defaultAvatar.png'

/**
 * Ant Design Vue 不会把 customRequest 的参数类型导出成公开名字，
 * 这里从组件 props 上反推，避免再出现 `any`（升级组件库时类型也会跟着变）。
 */
type UploadRequestOption = Parameters<NonNullable<UploadProps['customRequest']>>[0]

const loginUserStore = useLoginUserStore()

const uploading = ref(false)
const submitting = ref(false)

const formState = reactive({
  userName: '',
  userAvatar: '',
  userProfile: '',
})

/**
 * 用当前登录用户初始化表单
 */
const fillForm = () => {
  const user = loginUserStore.loginUser
  formState.userName = user.userName || ''
  formState.userAvatar = user.userAvatar || ''
  formState.userProfile = user.userProfile || ''
}

/**
 * 上传前的本地校验：给出即时反馈，避免为明显不合法的小文件白跑一次网络请求。
 * 注意这只是体验优化 —— 真正的把关在后端（读文件头魔数），前端校验随手就能绕过。
 */
const beforeAvatarUpload = (file: File) => {
  const allowedTypes = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']
  if (!allowedTypes.includes(file.type)) {
    message.error('仅支持 png / jpg / gif / webp 格式的图片')
    return false
  }
  if (file.size > 2 * 1024 * 1024) {
    message.error('头像大小不能超过 2MB')
    return false
  }
  return true
}

/**
 * 自定义上传：走我们的 /user/avatar/upload，拿到 URL 后先填进表单，不立即落库。
 * 这样"传了头像但最后点了取消"不会在库里留下已经改掉的字段。
 */
const handleAvatarUpload = async (options: UploadRequestOption) => {
  uploading.value = true
  try {
    const res = await uploadAvatar(options.file as File)
    if (res.data.code === 0 && res.data.data) {
      formState.userAvatar = res.data.data
      message.success('头像上传成功，记得点保存')
      options.onSuccess?.(res.data.data)
    } else {
      message.error('头像上传失败：' + res.data.message)
      options.onError?.(new Error(res.data.message))
    }
  } catch (error) {
    console.error('上传头像失败：', error)
    message.error('头像上传失败，请重试')
  } finally {
    uploading.value = false
  }
}

/**
 * 保存资料
 */
const handleSubmit = async () => {
  if (!formState.userName.trim()) {
    message.warning('请输入昵称')
    return
  }
  submitting.value = true
  try {
    const res = await updateMyProfile({
      userName: formState.userName.trim(),
      userAvatar: formState.userAvatar,
      userProfile: formState.userProfile,
    })
    if (res.data.code === 0 && res.data.data) {
      // 后端直接返回最新的 LoginUserVO，用它刷新 store，
      // 右上角的头像/昵称立刻跟着变，不需要整页刷新
      loginUserStore.setLoginUser(res.data.data)
      fillForm()
      message.success('保存成功')
    } else {
      message.error('保存失败：' + res.data.message)
    }
  } catch (error) {
    console.error('保存资料失败：', error)
    message.error('保存失败，请重试')
  } finally {
    submitting.value = false
  }
}

/**
 * 重置为当前已保存的资料
 */
const resetForm = () => {
  fillForm()
}

onMounted(async () => {
  // 重新拉一次登录态：store 里可能是上一次进入页面时的旧数据
  try {
    await loginUserStore.fetchLoginUser()
  } catch (error) {
    console.error('获取登录用户信息失败：', error)
  }
  fillForm()
})
</script>

<style scoped>
.profile-page {
  max-width: 640px;
  margin: 0 auto;
  padding: var(--sp-7) var(--sp-5);
}

.profile-card {
  padding: var(--sp-6);
  background: var(--bg-card);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
}

.page-head {
  margin-bottom: var(--sp-6);
}

.page-title {
  margin: 0 0 var(--sp-1);
  font-size: 22px;
  font-weight: 600;
  color: var(--text-1);
  letter-spacing: -0.3px;
}

.page-desc {
  margin: 0;
  font-size: var(--fs-base);
  color: var(--text-3);
}

.avatar-row {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
}

.avatar-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--sp-1);
}

.field-tip {
  font-size: var(--fs-xs);
  color: var(--text-3);
}
</style>
