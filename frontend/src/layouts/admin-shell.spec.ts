import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { reactive } from 'vue'
import AdminLayout from './AdminLayout.vue'

const { state, push, clearAuth } = vi.hoisted(() => ({
  state: { role: 'TEACHER' },
  push: vi.fn(),
  clearAuth: vi.fn(),
}))
const route = reactive({ path: '/admin/questions', fullPath: '/admin/questions', meta: { title: '编程题管理' } })
vi.mock('@/stores/user', () => ({
  useUserStore: () => ({ userInfo: { username: 'teacher' }, hasRole: (role: string) => state.role === role, clearAuth }),
}))
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push }),
  RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
  RouterView: { template: '<div />' },
}))

describe('admin navigation shell', () => {
  beforeEach(() => {
    state.role = 'TEACHER'
    route.path = '/admin/questions'
    route.fullPath = '/admin/questions'
    clearAuth.mockClear()
    push.mockClear()
  })

  it('groups available routes while hiding administrator-only entries from teachers', () => {
    const wrapper = mount(AdminLayout)
    expect(wrapper.find('a[href="/admin/overview"]').text()).toBe('概况')
    expect(wrapper.text()).toContain('题库管理')
    expect(wrapper.find('a[href="/admin/questions"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/admin/solutions"]').exists()).toBe(false)
    expect(wrapper.find('a[href="/admin/users"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('评测集群在线')
  })

  it('shows administrator entries and closes mobile navigation after route change', async () => {
    state.role = 'ADMIN'
    const wrapper = mount(AdminLayout)
    expect(wrapper.find('a[href="/admin/solutions"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/admin/users"]').exists()).toBe(true)
    await wrapper.get('.menu-toggle').trigger('click')
    expect(wrapper.get('.menu-toggle').attributes('aria-expanded')).toBe('true')
    route.path = '/admin/exams'
    route.fullPath = '/admin/exams'
    await wrapper.vm.$nextTick()
    expect(wrapper.get('.menu-toggle').attributes('aria-expanded')).toBe('false')
  })

  it('uses distinct icon outlines for objective questions and solutions', () => {
    state.role = 'ADMIN'
    const wrapper = mount(AdminLayout)
    const objectiveOutline = wrapper.get('a[href="/admin/objective-questions"] .nav-icon path').attributes('d')
    const solutionOutline = wrapper.get('a[href="/admin/solutions"] .nav-icon path').attributes('d')
    expect(objectiveOutline).not.toBe(solutionOutline)
  })

  it('clears authentication and navigates to login on logout', async () => {
    const wrapper = mount(AdminLayout)
    await wrapper.get('.logout-btn').trigger('click')
    expect(clearAuth).toHaveBeenCalledOnce()
    expect(push).toHaveBeenCalledWith('/login')
  })

  it('closes navigation with Escape while focus remains on the toggle', async () => {
    const wrapper = mount(AdminLayout)
    await wrapper.get('.menu-toggle').trigger('click')
    await wrapper.get('.menu-toggle').trigger('keydown', { key: 'Escape' })
    expect(wrapper.get('.menu-toggle').attributes('aria-expanded')).toBe('false')
  })
})
