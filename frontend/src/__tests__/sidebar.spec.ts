import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { systemApi } from '@/api/system'
import AppSidebar from '@/components/AppSidebar.vue'
import DefaultLayout from '@/layouts/DefaultLayout.vue'
import AdminLayout from '@/layouts/AdminLayout.vue'
import router from '@/router'
import { useUserStore } from '@/stores/user'

describe('AppSidebar.vue 侧边栏与方案 C 鉴权测试 (frontend/src/__tests__/sidebar.spec.ts)', () => {
  beforeEach(async () => {
    localStorage.clear()
    setActivePinia(createPinia())
    await router.push('/')
  })

  function mountSidebar() {
    return mount(AppSidebar, {
      global: {
        plugins: [router],
      },
    })
  }

  it('1. 正常渲染 8 个前台主导航项 (首页、题库、题集、课练、考试、排名、公告栏、个人主页)', async () => {
    const wrapper = mountSidebar()
    const text = wrapper.text()

    // 验证 8 个主导航标题文本均展示
    expect(text).toContain('首页')
    expect(text).toContain('题库')
    expect(text).toContain('题集')
    expect(text).toContain('课练')
    expect(text).toContain('考试')
    expect(text).toContain('排名')
    expect(text).toContain('公告栏')
    expect(text).toContain('个人主页')

    // 初始状态下题库与题集手风琴默认收起（accordion-content 不在 DOM 中）
    expect(wrapper.findAll('.accordion-content')).toHaveLength(0)

    // 点击手风琴展开项后，验证子路由链接渲染
    const triggers = wrapper.findAll('.nav-accordion-trigger')
    for (const trigger of triggers) {
      await trigger.trigger('click')
    }

    // 验证 8 大导航目标路由
    const links = wrapper.findAll('a').map((el) => el.attributes('href'))
    expect(links).toContain('/')
    expect(links).toContain('/questions')
    expect(links.some((href) => href?.startsWith('/problem-sets'))).toBe(true)
    expect(links).toContain('/problem-sets?category=ALGO')
    expect(links).toContain('/practice')
    expect(links).toContain('/exams')
    expect(links).toContain('/rankings')
    expect(links).toContain('/announcements')
    expect(links).toContain('/profile')
    expect(links).toContain('/account/settings')
  })

  it('2. 未登录游客视角：方案 C 控制台 DOM 节点绝对不存在，展示登录注册入口', () => {
    const userStore = useUserStore()
    userStore.clearAuth()

    const wrapper = mountSidebar()

    // 核心安全红线：未登录视角下后台管理入口 DOM 节点不可存在 (严格 v-if)
    expect(wrapper.find('.admin-section').exists()).toBe(false)
    expect(wrapper.find('.admin-nav-item').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('管理控制台')

    // 确保任何 a 标签均不包含 /admin 链接
    const links = wrapper.findAll('a').map((el) => el.attributes('href'))
    expect(links.some((href) => href === '/admin' || href?.startsWith('/admin/'))).toBe(false)

    // 底部展示登录/注册入口
    const loginLink = wrapper.find('.auth-action-btn')
    expect(loginLink.exists()).toBe(true)
    expect(loginLink.attributes('href')).toBe('/login')
  })

  it('3. 普通学生视角 (USER)：方案 C 控制台 DOM 节点绝对不存在', () => {
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'mock-student-token',
      refreshToken: 'mock-refresh-token',
      user: {
        id: 101,
        username: 'student_tester',
        email: 'student@kece.edu',
        roles: ['USER'],
      },
    })

    const wrapper = mountSidebar()

    // 学生视角下后台管理入口 DOM 节点不可存在
    expect(wrapper.find('.admin-section').exists()).toBe(false)
    expect(wrapper.find('.admin-nav-item').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('管理控制台')

    const links = wrapper.findAll('a').map((el) => el.attributes('href'))
    expect(links.some((href) => href === '/admin' || href?.startsWith('/admin/'))).toBe(false)
  })

  it('4. 管理员视角 (ADMIN)：方案 C 控制台入口正确渲染并包含指向 /admin 的链接', () => {
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'mock-admin-token',
      refreshToken: 'mock-refresh-token',
      user: {
        id: 1,
        username: 'admin_root',
        email: 'admin@kece.edu',
        roles: ['ADMIN'],
      },
    })

    const wrapper = mountSidebar()

    // 管理员视角下 .admin-section 节点正确渲染
    const adminSection = wrapper.find('.admin-section')
    expect(adminSection.exists()).toBe(true)
    expect(wrapper.text()).toContain('// ADMINISTRATION')
    expect(wrapper.text()).toContain('管理控制台')

    // 控制台入口正确包含指向 /admin 的链接
    const adminLink = wrapper.find('.admin-nav-item')
    expect(adminLink.exists()).toBe(true)
    expect(adminLink.attributes('href')).toBe('/admin')
  })

  it('5. 教师视角 (TEACHER)：方案 C 控制台入口正确渲染并包含指向 /admin 的链接', () => {
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'mock-teacher-token',
      refreshToken: 'mock-refresh-token',
      user: {
        id: 2,
        username: 'teacher_wang',
        email: 'wang@kece.edu',
        roles: ['TEACHER'],
      },
    })

    const wrapper = mountSidebar()

    expect(wrapper.find('.admin-section').exists()).toBe(true)
    expect(wrapper.text()).toContain('管理控制台')

    const adminLink = wrapper.find('.admin-nav-item')
    expect(adminLink.exists()).toBe(true)
    expect(adminLink.attributes('href')).toBe('/admin')
  })

  it('6. 用户头像与用户名点击跳转至 /profile', () => {
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'mock-user-token',
      refreshToken: 'mock-refresh-token',
      user: {
        id: 27,
        username: 'zheco68',
        email: 'zheco@kece.edu',
        roles: ['USER'],
      },
    })

    const wrapper = mountSidebar()

    // 检查用户头像链接是否指向 /profile
    const avatarLink = wrapper.find('.user-avatar-link')
    expect(avatarLink.exists()).toBe(true)
    expect(avatarLink.attributes('href')).toBe('/profile')

    // 检查用户名链接是否指向 /profile 并展示真实用户名
    const nameLink = wrapper.find('.user-name-link')
    expect(nameLink.exists()).toBe(true)
    expect(nameLink.attributes('href')).toBe('/profile')
    expect(nameLink.text()).toContain('zheco68')
  })

  it('7. 手风琴触发器使用原生 <button> 并支持 aria-expanded / aria-controls 与 Escape 收起及焦点回归', async () => {
    const wrapper = mount(AppSidebar, {
      attachTo: document.body,
      global: {
        plugins: [router],
      },
    })

    try {
      const triggers = wrapper.findAll('button.nav-accordion-trigger')
      expect(triggers).toHaveLength(2)

      const questionsTrigger = triggers[0]
      const problemSetsTrigger = triggers[1]

      expect(questionsTrigger.attributes('type')).toBe('button')
      expect(questionsTrigger.attributes('aria-expanded')).toBe('false')
      expect(questionsTrigger.attributes('aria-controls')).toBe('sidebar-questions-panel')
      expect(problemSetsTrigger.attributes('type')).toBe('button')
      expect(problemSetsTrigger.attributes('aria-expanded')).toBe('false')
      expect(problemSetsTrigger.attributes('aria-controls')).toBe('sidebar-problem-sets-panel')

      // 展开题库手风琴
      await questionsTrigger.trigger('click')
      expect(questionsTrigger.attributes('aria-expanded')).toBe('true')
      const questionsPanel = wrapper.find('#sidebar-questions-panel')
      expect(questionsPanel.exists()).toBe(true)

      // 焦点在子菜单项上按 Escape：收起面板并将焦点归还至触发按钮
      const subItem = questionsPanel.find('.sub-nav-item')
      ;(subItem.element as HTMLElement).focus()
      expect(document.activeElement).toBe(subItem.element)
      await subItem.trigger('keydown', { key: 'Escape' })

      expect(questionsTrigger.attributes('aria-expanded')).toBe('false')
      expect(wrapper.find('#sidebar-questions-panel').exists()).toBe(false)
      expect(document.activeElement).toBe(questionsTrigger.element)

      // 展开题集手风琴并在触发器上按 Escape 收起
      await problemSetsTrigger.trigger('click')
      expect(problemSetsTrigger.attributes('aria-expanded')).toBe('true')
      expect(wrapper.find('#sidebar-problem-sets-panel').exists()).toBe(true)

      await problemSetsTrigger.trigger('keydown', { key: 'Escape' })
      expect(problemSetsTrigger.attributes('aria-expanded')).toBe('false')
      expect(wrapper.find('#sidebar-problem-sets-panel').exists()).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })

  it('8. 折叠态 .flyout-popover 支持键盘 focusin/focusout 开合与 Escape 焦点归还', async () => {
    localStorage.setItem('kece_sidebar_collapsed', 'true')
    const wrapper = mount(AppSidebar, {
      attachTo: document.body,
      global: {
        plugins: [router],
      },
    })

    try {
      const groups = wrapper.findAll('.nav-accordion-group')
      const questionsGroup = groups[0]
      const problemSetsGroup = groups[1]
      const questionsTrigger = questionsGroup.find('button.nav-accordion-trigger')
      const problemSetsTrigger = problemSetsGroup.find('button.nav-accordion-trigger')

      expect(questionsTrigger.attributes('aria-expanded')).toBe('false')
      expect(questionsTrigger.attributes('aria-controls')).toBe('sidebar-questions-flyout')

      // focusin 触发题库浮层展开
      await questionsTrigger.trigger('focusin')
      expect(questionsTrigger.attributes('aria-expanded')).toBe('true')
      const questionsFlyout = wrapper.find('#sidebar-questions-flyout')
      expect(questionsFlyout.exists()).toBe(true)

      // 焦点移入浮层子项时不会因 focusout 误关
      const firstFlyoutItem = questionsFlyout.find('.flyout-item')
      await questionsTrigger.trigger('focusout', { relatedTarget: firstFlyoutItem.element })
      expect(wrapper.find('#sidebar-questions-flyout').exists()).toBe(true)

      // 焦点在浮层子项时按 Escape 关闭浮层并归还焦点至触发器，且不立即重开
      ;(firstFlyoutItem.element as HTMLElement).focus()
      await firstFlyoutItem.trigger('keydown', { key: 'Escape' })
      expect(questionsTrigger.attributes('aria-expanded')).toBe('false')
      expect(wrapper.find('#sidebar-questions-flyout').exists()).toBe(false)
      expect(document.activeElement).toBe(questionsTrigger.element)

      // focusin 触发题集浮层展开，focusout 移出组外时关闭
      await problemSetsTrigger.trigger('focusin')
      expect(problemSetsTrigger.attributes('aria-expanded')).toBe('true')
      expect(wrapper.find('#sidebar-problem-sets-flyout').exists()).toBe(true)

      await problemSetsGroup.trigger('focusout', { relatedTarget: document.body })
      expect(problemSetsTrigger.attributes('aria-expanded')).toBe('false')
      expect(wrapper.find('#sidebar-problem-sets-flyout').exists()).toBe(false)
    } finally {
      wrapper.unmount()
    }
  })
})

describe('DefaultLayout.vue 桌面外壳无障碍与遥测状态测试', () => {
  beforeEach(async () => {
    localStorage.clear()
    setActivePinia(createPinia())
    await router.push('/')
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('首节点提供跳转主内容链接、#main-content 地标及无障碍遥测胶囊', async () => {
    vi.spyOn(systemApi, 'getClusterSummary').mockResolvedValue({
      asOf: Date.now(),
      freshness: 'FRESH',
      readyPools: 3,
      totalPools: 5,
      pools: [],
    })

    const wrapper = mount(DefaultLayout, {
      global: {
        plugins: [router],
        stubs: {
          RouterView: true,
          ConfirmModal: true,
          ToastHost: true,
        },
      },
    })

    await flushPromises()

    const skipLink = wrapper.find('.default-layout > .skip-link')
    expect(skipLink.exists()).toBe(true)
    expect(skipLink.attributes('href')).toBe('#main-content')
    expect(skipLink.text()).toBe('跳到主要内容')

    const main = wrapper.find('main#main-content.main-content')
    expect(main.exists()).toBe(true)
    expect(main.attributes('tabindex')).toBe('-1')

    const telemetry = wrapper.find('.telemetry-pill')
    expect(telemetry.attributes('role')).toBe('status')
    expect(telemetry.attributes('aria-live')).toBe('polite')
    expect(telemetry.find('.dot-status').attributes('aria-hidden')).toBe('true')
    expect(telemetry.text()).toContain('POOLS: 3/5 READY')

    wrapper.unmount()
  })
})

describe('路由守卫 Fail-Secure 404 防嗅探与鉴权加固测试', () => {
  beforeEach(async () => {
    localStorage.clear()
    setActivePinia(createPinia())
    await router.push('/')
  })

  it('未登录游客访问 /admin 触发 Fail-Secure 静默重定向至 404 not-found (防嗅探)', async () => {
    const userStore = useUserStore()
    userStore.clearAuth()

    await router.push('/admin')
    expect(router.currentRoute.value.name).toBe('not-found')
  })

  it('未登录游客访问 /admin/questions 触发 Fail-Secure 静默重定向至 404 not-found (防嗅探)', async () => {
    const userStore = useUserStore()
    userStore.clearAuth()

    await router.push('/admin/questions')
    expect(router.currentRoute.value.name).toBe('not-found')
  })

  it('普通学生访问 /admin/questions 触发 Fail-Secure 静默重定向至 404 not-found', async () => {
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'token',
      refreshToken: 'refresh',
      user: {
        id: 99,
        username: 'student',
        email: null,
        roles: ['USER'],
      },
    })

    await router.push('/admin/questions')
    expect(router.currentRoute.value.name).toBe('not-found')
  })

  it('普通学生访问 /admin/users 触发 Fail-Secure 静默重定向至 404 not-found', async () => {
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'token',
      refreshToken: 'refresh',
      user: {
        id: 99,
        username: 'student',
        email: null,
        roles: ['USER'],
      },
    })

    await router.push('/admin/users')
    expect(router.currentRoute.value.name).toBe('not-found')
  })

  it('教师访问 /admin/questions 允许正常通行', async () => {
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'token',
      refreshToken: 'refresh',
      user: {
        id: 10,
        username: 'teacher',
        email: null,
        roles: ['TEACHER'],
      },
    })

    await router.push('/admin/questions')
    expect(router.currentRoute.value.name).toBe('admin-questions')
  })

  it('教师访问仅限管理员的 /admin/users 触发 Fail-Secure 静默重定向至 404 not-found', async () => {
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'token',
      refreshToken: 'refresh',
      user: {
        id: 10,
        username: 'teacher',
        email: null,
        roles: ['TEACHER'],
      },
    })

    await router.push('/admin/users')
    expect(router.currentRoute.value.name).toBe('not-found')
  })

  it('管理员访问 /admin/users 允许正常通行', async () => {
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'token',
      refreshToken: 'refresh',
      user: {
        id: 1,
        username: 'admin',
        email: null,
        roles: ['ADMIN'],
      },
    })

    await router.push('/admin/users')
    expect(router.currentRoute.value.name).toBe('admin-users')
  })

  it('未登录用户访问 /account/settings 重定向至登录页，登录后可直达并更新顶栏面包屑', async () => {
    const userStore = useUserStore()
    userStore.clearAuth()

    await router.push('/account/settings')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/account/settings')

    userStore.setAuth({
      token: 'token',
      refreshToken: 'refresh',
      user: {
        id: 27,
        username: 'zheco68',
        email: 'zheco@kece.edu',
        roles: ['USER'],
      },
    })
    vi.spyOn(systemApi, 'getClusterSummary').mockResolvedValue({
      asOf: Date.now(),
      freshness: 'FRESH',
      readyPools: 2,
      totalPools: 2,
      pools: [],
    })

    await router.push('/account/settings')
    expect(router.currentRoute.value.name).toBe('account-settings')

    const layoutWrapper = mount(DefaultLayout, {
      global: {
        plugins: [router],
        stubs: {
          RouterView: true,
          ConfirmModal: true,
          ToastHost: true,
        },
      },
    })
    await flushPromises()
    expect(layoutWrapper.find('.bc-current').text()).toBe('ACCOUNT // SETTINGS')
    layoutWrapper.unmount()
  })

  it('AdminLayout 底部用户区包含跳转 /account/settings 的快捷入口', async () => {
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'token',
      refreshToken: 'refresh',
      user: {
        id: 1,
        username: 'admin',
        email: 'admin@kece.edu',
        roles: ['ADMIN'],
      },
    })
    await router.push('/admin/overview')

    const adminWrapper = mount(AdminLayout, {
      global: {
        plugins: [router],
        stubs: {
          RouterView: true,
          ConfirmModal: true,
          ToastHost: true,
        },
      },
    })
    await flushPromises()
    const settingsEntry = adminWrapper.find('.sidebar-footer a[href="/account/settings"]')
    expect(settingsEntry.exists()).toBe(true)
    adminWrapper.unmount()
  })
})
