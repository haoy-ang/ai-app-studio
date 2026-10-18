<template>
  <div id="userManagePage">
    <!-- 搜索表单 -->
    <a-form layout="inline" :model="searchParams" @finish="doSearch">
      <a-form-item label="账号">
        <a-input v-model:value="searchParams.userAccount" placeholder="输入账号" />
      </a-form-item>
      <a-form-item label="用户名">
        <a-input v-model:value="searchParams.userName" placeholder="输入用户名" />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" html-type="submit">搜索</a-button>
      </a-form-item>
    </a-form>
    <a-divider />
    <!-- 表格 -->
    <a-table
      :columns="columns"
      :data-source="data"
      :pagination="pagination"
      @change="doTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'userAvatar'">
          <a-image :src="record.userAvatar" :width="120" />
        </template>
        <template v-else-if="column.dataIndex === 'userRole'">
          <div v-if="record.userRole === 'admin'">
            <a-tag color="green">管理员</a-tag>
          </div>
          <div v-else>
            <a-tag color="blue">普通用户</a-tag>
          </div>
        </template>
        <template v-else-if="column.dataIndex === 'createTime'">
          {{ dayjs(record.createTime).format('YYYY-MM-DD HH:mm:ss') }}
        </template>
        <template v-else-if="column.key === 'action'">
          <!--
            自己的账号不给删：删掉就立刻失去后台入口，而且这时候没有第二个账号能救。
            后端也拦了这一条（UserService#removeUser），前端只是提前把按钮关掉，少一次无效点击。
          -->
          <a-tooltip v-if="record.id === loginUserStore.loginUser.id" title="不能删除当前登录的账号">
            <a-button danger disabled>删除</a-button>
          </a-tooltip>
          <a-button v-else danger @click="doDelete(record)">删除</a-button>
        </template>
      </template>
    </a-table>
  </div>
</template>
<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { deleteUser, listUserVoByPage } from '@/api/userController.ts'
import { message, Modal } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import dayjs from 'dayjs'

const loginUserStore = useLoginUserStore()

const columns = [
  {
    title: 'id',
    dataIndex: 'id',
  },
  {
    title: '账号',
    dataIndex: 'userAccount',
  },
  {
    title: '用户名',
    dataIndex: 'userName',
  },
  {
    title: '头像',
    dataIndex: 'userAvatar',
  },
  {
    title: '简介',
    dataIndex: 'userProfile',
  },
  {
    title: '用户角色',
    dataIndex: 'userRole',
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
  },
  {
    title: '操作',
    key: 'action',
  },
]

// 展示的数据
const data = ref<API.UserVO[]>([])
const total = ref(0)

// 搜索条件
const searchParams = reactive<API.UserQueryRequest>({
  pageNum: 1,
  pageSize: 10,
})

// 获取数据
const fetchData = async () => {
  const res = await listUserVoByPage({
    ...searchParams,
  })
  if (res.data.data) {
    data.value = res.data.data.records ?? []
    total.value = res.data.data.totalRow ?? 0
  } else {
    message.error('获取数据失败，' + res.data.message)
  }
}

// 分页参数
const pagination = computed(() => {
  return {
    current: searchParams.pageNum ?? 1,
    pageSize: searchParams.pageSize ?? 10,
    total: total.value,
    showSizeChanger: true,
    showTotal: (total: number) => `共 ${total} 条`,
  }
})

// 表格分页变化时的操作
const doTableChange = (page: { current: number; pageSize: number }) => {
  searchParams.pageNum = page.current
  searchParams.pageSize = page.pageSize
  fetchData()
}

// 搜索数据
const doSearch = () => {
  // 重置页码
  searchParams.pageNum = 1
  fetchData()
}

// 删除数据
// 用户删除是「连坐」操作：应用、对话、版本、已部署页面都会一起没，必须二次确认
const doDelete = (record: API.UserVO) => {
  // 雪花 id 是字符串，直接透传。⚠️ 千万不要 Number(id)：
  // 它超出 JS 安全整数范围，转完末尾几位就变了，后端会查不到这个用户
  // （旧代码正是这么写的，于是「提示删除成功、刷新后用户还在」）
  const userId = record.id
  if (!userId) {
    return
  }

  Modal.confirm({
    title: `删除用户「${record.userName || record.userAccount || '未命名'}」？`,
    content: '该用户名下的应用、对话记录、版本快照，以及已部署的线上页面都会一起删除，且无法恢复。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      try {
        const res = await deleteUser({ id: userId })
        // code === 0 只代表「请求处理完了」，data 才是「真的删掉了没有」
        if (res.data.code === 0 && res.data.data) {
          message.success('删除成功')
          fetchData()
        } else {
          message.error('删除失败：' + (res.data.message || '后端未删除任何记录'))
        }
      } catch (error) {
        console.error('删除用户失败：', error)
        message.error('删除失败')
      }
    },
  })
}

// 页面加载时请求一次
onMounted(() => {
  fetchData()
})
</script>

<style scoped>
#userManagePage {
  max-width: var(--layout-max);
  margin: 0 auto;
  padding: var(--sp-6) var(--sp-5) var(--sp-7);
}
</style>
