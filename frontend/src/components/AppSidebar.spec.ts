import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AppSidebar from '@/components/AppSidebar.vue'
import { useUserStore } from '@/stores/user'

const mockPush = vi.fn()
let currentRoutePath = '/'

vi.mock('vue-router', () => ({
  useRoute: () => ({
    get path() {
      return currentRoutePath
    },
  }),
  useRouter: () => ({
    push: mockPush,
  }),
  RouterLink: {
    name: 'RouterLink',
    props: ['to'],
    template: '<a :href="typeof to === \'string\' ? to : to.path" :class="$attrs.class"><slot /></a>',
  },
}))

describe('AppSidebar.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    mockPush.mockReset()
    currentRoutePath = '/'
  })

  it('renders 8 main navigation items', () => {
    const wrapper = mount(AppSidebar)
    const text = wrapper.text()

    expect(text).toContain('首页')
    expect(text).toContain('题库')
    expect(text).toContain('题集')
    expect(text).toContain('课练')
    expect(text).toContain('考试')
    expect(text).toContain('排名')
    expect(text).toContain('公告栏')
    expect(text).toContain('个人主页')
  })

  it('supports expanded and collapsed toggling with localStorage persistence', async () => {
    localStorage.setItem('kece_sidebar_collapsed', 'false')
    const wrapper = mount(AppSidebar)

    expect(wrapper.classes()).not.toContain('is-collapsed')

    const toggleBtn = wrapper.find('.collapse-toggle-btn')
    await toggleBtn.trigger('click')

    expect(wrapper.classes()).toContain('is-collapsed')
    expect(localStorage.getItem('kece_sidebar_collapsed')).toBe('true')

    await toggleBtn.trigger('click')
    expect(wrapper.classes()).not.toContain('is-collapsed')
    expect(localStorage.getItem('kece_sidebar_collapsed')).toBe('false')
  })

  it('displays sub-menu for 题库 (ALGO, SQL, QUIZ)', () => {
    const wrapper = mount(AppSidebar)
    const text = wrapper.text()

    expect(text).toContain('ALGO')
    expect(text).toContain('算法编程')
    expect(text).toContain('SQL')
    expect(text).toContain('数据库题')
    expect(text).toContain('QUIZ')
    expect(text).toContain('客观题库')
  })

  it('handles flyout popover for 题库 in collapsed mode', async () => {
    localStorage.setItem('kece_sidebar_collapsed', 'true')
    const wrapper = mount(AppSidebar)

    expect(wrapper.classes()).toContain('is-collapsed')

    const group = wrapper.find('.nav-accordion-group')
    await group.trigger('mouseenter')

    const popover = wrapper.find('.flyout-popover')
    expect(popover.exists()).toBe(true)
    expect(popover.text()).toContain('// 题库分类')
    expect(popover.text()).toContain('ALGO')
    expect(popover.text()).toContain('SQL')
    expect(popover.text()).toContain('QUIZ')
  })

  it('strictly enforces DOM-level conditional rendering for administration console', async () => {
    const userStore = useUserStore()

    // 1. Unauthenticated guest: admin-section must NOT exist in DOM
    userStore.clearAuth()
    let wrapper = mount(AppSidebar)
    expect(wrapper.find('.admin-section').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('管理控制台')

    // 2. Student role (USER): admin-section must NOT exist in DOM
    userStore.setAuth({
      token: 'mock-token',
      refreshToken: 'mock-refresh',
      user: {
        id: 1,
        username: 'student01',
        email: 'student@kece.edu',
        roles: ['USER'],
      },
    })
    wrapper = mount(AppSidebar)
    expect(wrapper.find('.admin-section').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('管理控制台')

    // 3. Teacher role (TEACHER): admin-section MUST exist in DOM
    userStore.setAuth({
      token: 'mock-token',
      refreshToken: 'mock-refresh',
      user: {
        id: 2,
        username: 'teacher01',
        email: 'teacher@kece.edu',
        roles: ['TEACHER'],
      },
    })
    wrapper = mount(AppSidebar)
    expect(wrapper.find('.admin-section').exists()).toBe(true)
    expect(wrapper.text()).toContain('管理控制台')
    expect(wrapper.find('.admin-nav-item').attributes('href')).toBe('/admin')

    // 4. Admin role (ADMIN): admin-section MUST exist in DOM
    userStore.setAuth({
      token: 'mock-token',
      refreshToken: 'mock-refresh',
      user: {
        id: 3,
        username: 'admin01',
        email: 'admin@kece.edu',
        roles: ['ADMIN'],
      },
    })
    wrapper = mount(AppSidebar)
    expect(wrapper.find('.admin-section').exists()).toBe(true)
    expect(wrapper.text()).toContain('管理控制台')
  })

  it('displays user profile info and logout for authenticated users', async () => {
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'mock-token',
      refreshToken: 'mock-refresh',
      user: {
        id: 10,
        username: 'alice',
        email: 'alice@kece.edu',
        roles: ['ADMIN'],
      },
    })

    const wrapper = mount(AppSidebar)
    expect(wrapper.find('.user-name').text()).toBe('alice')
    expect(wrapper.find('.role-tag').text()).toBe('管理员')

    // Logout button triggers clearAuth and router.push('/login')
    const logoutBtn = wrapper.find('.logout-btn')
    expect(logoutBtn.exists()).toBe(true)
    await logoutBtn.trigger('click')

    expect(userStore.isLoggedIn).toBe(false)
    expect(mockPush).toHaveBeenCalledWith('/login')
  })

  it('displays login button for unauthenticated visitors', () => {
    const userStore = useUserStore()
    userStore.clearAuth()

    const wrapper = mount(AppSidebar)
    expect(wrapper.find('.auth-action-btn').exists()).toBe(true)
    expect(wrapper.find('.auth-action-btn').text()).toContain('登录 / 注册')
  })

  it('correctly activates deep paths like /database as active question', () => {
    currentRoutePath = '/database'
    const wrapper = mount(AppSidebar)

    const questionTrigger = wrapper.find('.nav-accordion-trigger')
    expect(questionTrigger.classes()).toContain('active')

    const dbSubItem = wrapper.findAll('.sub-nav-item').find((el) => el.attributes('href') === '/database')
    expect(dbSubItem?.classes()).toContain('active')
  })
})
