import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { adminApi } from '@/api/admin'
import ProblemSetEditView from './ProblemSetEditView.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: {} }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/stores/user', () => ({
  useUserStore: () => ({ hasRole: () => true }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ error: vi.fn(), success: vi.fn(), info: vi.fn() }),
}))

vi.mock('@/api/admin', () => ({
  adminApi: {
    listQuestions: vi.fn(),
    listDbQuestions: vi.fn(),
    listObjectiveQuestions: vi.fn(),
  },
}))

describe('ProblemSetEditView question picker', () => {
  beforeEach(() => {
    vi.mocked(adminApi.listQuestions).mockReset()
    vi.mocked(adminApi.listQuestions).mockImplementation(async (params) => ({
      list: [{ id: params.id || (params.page || 1), title: '测试题', difficulty: 'EASY' }] as Awaited<ReturnType<typeof adminApi.listQuestions>>['list'],
      total: params.id ? 1 : 111,
      page: params.page || 1,
      size: params.size || 12,
    }))
  })

  it('searches a question number as an exact ID', async () => {
    const wrapper = mount(ProblemSetEditView)
    await flushPromises()
    await wrapper.get('input.picker-search').setValue('#103')
    await new Promise((resolve) => setTimeout(resolve, 350))
    await flushPromises()

    expect(adminApi.listQuestions).toHaveBeenLastCalledWith({
      id: 103,
      keyword: undefined,
      page: 1,
      size: 12,
    })
    expect(wrapper.text()).toContain('#103')
    wrapper.unmount()
  })

  it('loads questions beyond the first page', async () => {
    const wrapper = mount(ProblemSetEditView)
    await flushPromises()
    const next = wrapper.findAll('.picker-panel button').find((button) => button.text() === '下一页')
    expect(next).toBeDefined()
    await next!.trigger('click')
    await flushPromises()

    expect(adminApi.listQuestions).toHaveBeenLastCalledWith({
      id: undefined,
      keyword: undefined,
      page: 2,
      size: 12,
    })
    wrapper.unmount()
  })
})
