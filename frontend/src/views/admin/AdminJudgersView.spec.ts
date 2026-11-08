import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import AdminJudgersView from './AdminJudgersView.vue'
import { judgersApi, type JudgeNodeView, type HostGroupView } from '@/api/judgers'
import * as confirmModule from '@/composables/useConfirm'

describe('AdminJudgersView.vue', () => {
  const mockNodes: JudgeNodeView[] = [
    {
      nodeId: '1',
      hostGroupId: '1',
      name: 'py-node-01',
      pool: 'python',
      runtimeSpec: 'python-3.11',
      baseUrl: 'https://127.0.0.1:9091',
      state: 'ENABLED',
      health: 'HEALTHY',
      maxSlots: 4,
      localSlots: 4,
      running: 1,
      revision: 2,
      runtimeVersion: 'Python 3.11.8',
      heartbeatAt: '2026-11-08T01:00:00',
    },
    {
      nodeId: '2',
      hostGroupId: '1',
      name: 'cpp-node-01',
      pool: 'cpp',
      runtimeSpec: 'cpp-gcc13',
      baseUrl: null,
      state: 'DISABLED',
      health: 'OFFLINE',
      maxSlots: 4,
      localSlots: 0,
      running: 0,
      revision: 1,
      runtimeVersion: null,
      heartbeatAt: null,
    },
  ]

  const mockHosts: HostGroupView[] = [
    {
      id: '1',
      name: 'baremetal-01',
      maxSlots: 16,
      revision: 1,
    },
  ]

  beforeEach(() => {
    vi.spyOn(judgersApi, 'getNodes').mockResolvedValue(mockNodes)
    vi.spyOn(judgersApi, 'getHostGroups').mockResolvedValue(mockHosts)
    vi.spyOn(confirmModule, 'useConfirm').mockReturnValue({
      state: ref(null),
      confirm: vi.fn().mockResolvedValue(true),
      resolve: vi.fn(),
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('渲染评测机列表与宿主机分组信息', async () => {
    const wrapper = mount(AdminJudgersView)
    await flushPromises()

    expect(wrapper.text()).toContain('评测机管理')
    expect(wrapper.text()).toContain('py-node-01')
    expect(wrapper.text()).toContain('cpp-node-01')
    expect(wrapper.text()).toContain('baremetal-01')
    expect(wrapper.text()).toContain('PYTHON')
    expect(wrapper.text()).toContain('CPP')
  })

  it('按专池筛选节点列表', async () => {
    const wrapper = mount(AdminJudgersView)
    await flushPromises()

    const poolSelect = wrapper.find('select[aria-label="按专池筛选"]')
    await poolSelect.setValue('python')

    expect(wrapper.text()).toContain('py-node-01')
    expect(wrapper.text()).not.toContain('cpp-node-01')
  })

  it('点击自检触发下发诊断指令', async () => {
    const requestCheckSpy = vi
      .spyOn(judgersApi, 'requestCheck')
      .mockResolvedValue({ checkId: '101', state: 'QUEUED', runtimeVersion: '', detail: null })

    const wrapper = mount(AdminJudgersView)
    await flushPromises()

    // 找到包含自检的按钮
    const checkBtn = wrapper.findAll('.action-btn').find((b) => b.text().includes('自检'))
    expect(checkBtn).toBeDefined()
    await checkBtn!.trigger('click')
    await flushPromises()

    expect(requestCheckSpy).toHaveBeenCalledWith('1')
  })
})
