import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import Pagination from './Pagination.vue'

describe('Pagination', () => {
  it('jumps directly to a valid page', async () => {
    const wrapper = mount(Pagination, { props: { page: 1, size: 20, total: 829 } })
    await wrapper.get('input[aria-label="跳转页码"]').setValue('37')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('update:page')).toEqual([[37]])
  })

  it('rejects pages outside the available range', async () => {
    const wrapper = mount(Pagination, { props: { page: 1, size: 20, total: 829 } })
    const input = wrapper.get('input[aria-label="跳转页码"]')
    await input.setValue('43')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    await input.setValue('1.5')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('update:page')).toBeUndefined()
  })
})
