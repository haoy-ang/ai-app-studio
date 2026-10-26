import { globalIgnores } from 'eslint/config'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import pluginVue from 'eslint-plugin-vue'
import skipFormatting from '@vue/eslint-config-prettier/skip-formatting'

// To allow more languages other than `ts` in `.vue` files, uncomment the following lines:
// import { configureVueProject } from '@vue/eslint-config-typescript'
// configureVueProject({ scriptLangs: ['ts', 'tsx'] })
// More info at https://github.com/vuejs/eslint-config-typescript/#advanced-setup

export default defineConfigWithVueTs(
  {
    name: 'app/files-to-lint',
    files: ['**/*.{ts,mts,tsx,vue}'],
  },

  globalIgnores(['**/dist/**', '**/dist-ssr/**', '**/coverage/**']),

  pluginVue.configs['flat/essential'],
  vueTsConfigs.recommended,
  skipFormatting,

  // ⚠️ 必须放在最后：eslint 的 flat config 是「后面的规则覆盖前面的」，
  // 放在 vueTsConfigs 之前会被它重新打开。
  //
  // src/api 下的接口文件由 `npm run openapi2ts` 生成，生成物首行就是 `// @ts-ignore`，
  // 也不可避免地带 `any`。它们不该被手写代码的规则卡住 —— 一重新生成，这些报错就会回来。
  // 这个目录里唯一的例外是 appVersionController.ts（手写），它本身已经按严格类型写好了。
  {
    name: 'app/generated-api',
    files: ['src/api/*.ts'],
    linterOptions: {
      // 生成物第 2 行固定有一句 `/* eslint-disable */`；上面两条规则关掉之后它就成了
      // "无用的禁用指令"，每个文件报一条 warning。生成文件不归我们管，直接静音。
      reportUnusedDisableDirectives: 'off',
    },
    rules: {
      '@typescript-eslint/ban-ts-comment': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
)
