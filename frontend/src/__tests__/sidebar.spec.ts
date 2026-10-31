import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import AppSidebar from '@/components/AppSidebar.vue'
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

  it('1. 正常渲染 8 个前台主导航项 (首页、题库、题集、课练、考试、排名、公告栏、个人主页)', () => {
    const wrapper = mountSidebar()
    const text = wrapper.text()

    // 检查 8 大主导航文本
    expect(text).toContain('首页')
    expect(text).toContain('题库')
    expect(text).toContain('题集')
    expect(text).toContain('课练')
    expect(text).toContain('考试')
    expect(text).toContain('排名')
    expect(text).toContain('公告栏')
    expect(text).toContain('个人主页')

    // 检查 8 大导航的目标路由链接
    const links = wrapper.findAll('a').map((el) => el.attributes('href'))
    expect(links).toContain('/')
    expect(links).toContain('/questions')
    expect(links).toContain('/problem-sets')
    expect(links).toContain('/practice')
    expect(links).toContain('/exams')
    expect(links).toContain('/rankings')
    expect(links).toContain('/announcements')
    expect(links).toContain('/profile')
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
})
