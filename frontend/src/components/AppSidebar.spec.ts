import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import AppSidebar from '@/components/AppSidebar.vue'
import { useUserStore } from '@/stores/user'

const mockPush = vi.fn()
let currentRoutePath = '/'
let currentRouteQuery: Record<string, any> = {}

vi.mock('vue-router', () => ({
  useRoute: () => ({
    get path() {
      return currentRoutePath
    },
    get query() {
      return currentRouteQuery
    },
  }),
  useRouter: () => ({
    push: mockPush,
  }),
  RouterLink: {
    name: 'RouterLink',
    props: ['to'],
    computed: {
      resolvedHref(this: any): string {
        const to = this.to
        if (typeof to === 'string') return to
        if (!to) return ''
        const path = to.path || ''
        if (to.query && Object.keys(to.query).length > 0) {
          const qs = new URLSearchParams(to.query).toString()
          return `${path}?${qs}`
        }
        return path
      },
    },
    template: '<a :href="resolvedHref" :class="$attrs.class"><slot /></a>',
  },
}))

describe('AppSidebar.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    mockPush.mockReset()
    currentRoutePath = '/'
    currentRouteQuery = {}
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

  it('displays sub-menu for 题库 (ALGO, SQL, QUIZ) when active or expanded', () => {
    currentRoutePath = '/questions'
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
    expect(wrapper.find('.role-tag').classes()).toContain('role-admin')

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

  it('toggles questions accordion open and closed on click without route push', async () => {
    mockPush.mockClear()
    currentRoutePath = '/'
    const wrapper = mount(AppSidebar)

    const questionsGroup = wrapper.findAll('.nav-accordion-group').find((g) => g.text().includes('题库'))
    expect(questionsGroup).toBeDefined()
    const questionTrigger = questionsGroup!.find('.nav-accordion-trigger')
    // 初始状态下收起：accordion-content 不存在
    expect(questionsGroup!.find('.accordion-content').exists()).toBe(false)

    // 第一次点击：展开手风琴
    await questionTrigger.trigger('click')
    expect(questionsGroup!.find('.accordion-content').exists()).toBe(true)
    expect(mockPush).not.toHaveBeenCalled()

    // 第二次点击：收起手风琴
    await questionTrigger.trigger('click')
    expect(questionsGroup!.find('.accordion-content').exists()).toBe(false)
    expect(mockPush).not.toHaveBeenCalled()
  })

  it('renders 3 problem sets sub-items with corresponding query parameters in expanded mode', () => {
    currentRoutePath = '/problem-sets'
    const wrapper = mount(AppSidebar)
    const psGroup = wrapper.findAll('.nav-accordion-group').find((g) => g.text().includes('题集'))
    expect(psGroup).toBeDefined()

    const text = psGroup!.text()
    expect(text).toContain('ALGO')
    expect(text).toContain('算法题集')
    expect(text).toContain('SQL')
    expect(text).toContain('数据库题集')
    expect(text).toContain('QUIZ')
    expect(text).toContain('客观题集')

    const subLinks = psGroup!.findAll('.sub-nav-item')
    expect(subLinks).toHaveLength(3)

    expect(subLinks[0].attributes('href')).toBe('/problem-sets?category=ALGO')
    expect(subLinks[0].attributes('href')).toContain('?category=ALGO')
    expect(subLinks[0].text()).toContain('ALGO')
    expect(subLinks[0].text()).toContain('算法题集')

    expect(subLinks[1].attributes('href')).toBe('/problem-sets?category=DB')
    expect(subLinks[1].attributes('href')).toContain('?category=DB')
    expect(subLinks[1].text()).toContain('SQL')
    expect(subLinks[1].text()).toContain('数据库题集')

    expect(subLinks[2].attributes('href')).toBe('/problem-sets?category=OBJECTIVE')
    expect(subLinks[2].attributes('href')).toContain('?category=OBJECTIVE')
    expect(subLinks[2].text()).toContain('QUIZ')
    expect(subLinks[2].text()).toContain('客观题集')
  })

  it('toggles problem sets accordion open and closed on click without route push', async () => {
    mockPush.mockClear()
    currentRoutePath = '/'
    const wrapper = mount(AppSidebar)

    const psGroup = wrapper.findAll('.nav-accordion-group').find((g) => g.text().includes('题集'))
    expect(psGroup).toBeDefined()

    const psTrigger = psGroup!.find('.nav-accordion-trigger')
    // 初始状态下收起：accordion-content 不存在
    expect(psGroup!.find('.accordion-content').exists()).toBe(false)

    // 第一次点击：展开题集手风琴
    await psTrigger.trigger('click')
    expect(psGroup!.find('.accordion-content').exists()).toBe(true)
    expect(mockPush).not.toHaveBeenCalled()

    // 第二次点击：收起题集手风琴
    await psTrigger.trigger('click')
    expect(psGroup!.find('.accordion-content').exists()).toBe(false)
    expect(mockPush).not.toHaveBeenCalled()
  })

  it('handles flyout popover for 题集 in 68px collapsed mode with // PROBLEM SETS header and 3 categories', async () => {
    localStorage.setItem('kece_sidebar_collapsed', 'true')
    const wrapper = mount(AppSidebar)

    expect(wrapper.classes()).toContain('is-collapsed')

    const psGroup = wrapper.findAll('.nav-accordion-group')[1]
    await psGroup.trigger('mouseenter')

    const popover = psGroup.find('.flyout-popover')
    expect(popover.exists()).toBe(true)
    expect(popover.text()).toContain('// PROBLEM SETS')
    expect(popover.text()).toContain('ALGO')
    expect(popover.text()).toContain('算法题集')
    expect(popover.text()).toContain('SQL')
    expect(popover.text()).toContain('数据库题集')
    expect(popover.text()).toContain('QUIZ')
    expect(popover.text()).toContain('客观题集')

    const flyoutLinks = popover.findAll('.flyout-item')
    expect(flyoutLinks).toHaveLength(3)
    expect(flyoutLinks[0].attributes('href')).toBe('/problem-sets?category=ALGO')
    expect(flyoutLinks[1].attributes('href')).toBe('/problem-sets?category=DB')
    expect(flyoutLinks[2].attributes('href')).toBe('/problem-sets?category=OBJECTIVE')
  })

  it('correctly activates SQL 数据库题集 when route has category=DB', () => {
    currentRoutePath = '/problem-sets'
    currentRouteQuery = { category: 'DB' }
    const wrapper = mount(AppSidebar)

    const psGroup = wrapper.findAll('.nav-accordion-group').find((g) => g.text().includes('题集'))
    expect(psGroup).toBeDefined()

    const psTrigger = psGroup!.find('.nav-accordion-trigger')
    expect(psTrigger.classes()).toContain('active')

    const subLinks = psGroup!.findAll('.sub-nav-item')
    const algoItem = subLinks.find((el) => el.attributes('href')?.includes('category=ALGO'))
    const dbItem = subLinks.find((el) => el.attributes('href')?.includes('category=DB'))
    const quizItem = subLinks.find((el) => el.attributes('href')?.includes('category=OBJECTIVE'))

    expect(algoItem?.classes()).not.toContain('active')
    expect(dbItem?.classes()).toContain('active')
    expect(quizItem?.classes()).not.toContain('active')
  })
  it('keeps questions and problem sets accordions collapsed by default on initial home route', () => {
    currentRoutePath = '/'
    const wrapper = mount(AppSidebar)
    const openAccordions = wrapper.findAll('.accordion-content')
    expect(openAccordions).toHaveLength(0)
  })
})
