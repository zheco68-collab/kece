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
    vi.spyOn(judgersApi, 'getConnections').mockResolvedValue([])
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
    expect(wrapper.text()).toContain('Python')
    expect(wrapper.text()).toContain('C++')
  })

  it('按专池筛选节点列表', async () => {
    const wrapper = mount(AdminJudgersView)
    await flushPromises()

    const poolButton = wrapper.get('button[aria-label="筛选 Python 节点"]')
    await poolButton.trigger('click')

    expect(poolButton.attributes('aria-pressed')).toBe('true')
    expect(wrapper.text()).toContain('py-node-01')
    expect(wrapper.text()).not.toContain('cpp-node-01')

    await wrapper.get('input[type="search"]').setValue('#2')
    expect(wrapper.text()).toContain('没有符合条件的节点')
    await wrapper.get('.filter-clear').trigger('click')
    expect(wrapper.text()).toContain('cpp-node-01')
  })

  it('点击自检触发下发诊断指令', async () => {
    const requestCheckSpy = vi
      .spyOn(judgersApi, 'requestCheck')
      .mockResolvedValue({ checkId: '101', state: 'QUEUED', runtimeVersion: '', detail: null })

    const wrapper = mount(AdminJudgersView)
    await flushPromises()

    const disclosure = wrapper.get('.actions-disclosure')
    await disclosure.get('summary').trigger('click')
    expect(disclosure.attributes('open')).toBeDefined()
    await disclosure.get('button').trigger('click')
    await flushPromises()

    expect(requestCheckSpy).toHaveBeenCalledWith('1')
  })

  it('接入弹窗只要求填写 API 地址', async () => {
    const connect = vi.spyOn(judgersApi, 'connectNode').mockResolvedValue({
      connectionId: '104', apiUrl: 'http://10.20.0.15:19091', nodeId: null,
      status: 'REQUESTED', errorCode: null, errorDetail: null, retryCount: 0,
      createdAt: '2026-11-09T00:00:00', updatedAt: '2026-11-09T00:00:00',
    })
    const wrapper = mount(AdminJudgersView)
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text().includes('接入评测机'))!.trigger('click')
    expect(wrapper.findAll('.connect-modal input')).toHaveLength(1)
    await wrapper.get('.connect-modal input').setValue('http://10.20.0.15:19091')
    await wrapper.findAll('.connect-modal button').find((button) => button.text().includes('开始接入'))!.trigger('click')
    await flushPromises()
    expect(connect).toHaveBeenCalledWith('http://10.20.0.15:19091', expect.any(String))
  })
})
