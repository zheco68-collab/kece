import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const {
  pushMock,
  replaceMock,
  leaveGuards,
  routerState,
  profileApiMock,
  authApiMock,
  confirmMock,
  toastMock,
} = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  leaveGuards: [] as Array<() => Promise<boolean | void> | boolean | void>,
  routerState: {
    route: {
      path: '/account/settings',
      query: {} as Record<string, string>,
    },
  },
  profileApiMock: {
    getProfile: vi.fn(),
    updateProfile: vi.fn(),
    uploadAvatar: vi.fn(),
    changePassword: vi.fn(),
    changeEmail: vi.fn(),
  },
  authApiMock: {
    refresh: vi.fn(),
  },
  confirmMock: vi.fn(),
  toastMock: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
  },
}))

vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => routerState.route,
    useRouter: () => ({ push: pushMock, replace: replaceMock }),
    onBeforeRouteLeave: (cb: () => Promise<boolean | void> | boolean | void) => {
      leaveGuards.push(cb)
    },
  }
})

vi.mock('@/api/profile', () => ({ profileApi: profileApiMock }))
vi.mock('@/api/auth', () => ({ authApi: authApiMock }))
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => ({ confirm: confirmMock }) }))
vi.mock('@/composables/useToast', () => ({ useToast: () => toastMock }))

import AccountSettingsView from './AccountSettingsView.vue'
import { useUserStore } from '@/stores/user'

describe('AccountSettingsView', () => {
  const baseProfile = {
    id: 27,
    username: 'zheco68',
    email: 'zheco68@kece.edu',
    avatarUrl: null,
    bio: 'initial bio',
    githubUrl: 'https://github.com/zheco68',
    roles: ['USER'],
    createdAt: '2026-10-01T08:00:00Z',
  }

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    leaveGuards.length = 0
    routerState.route = reactive({
      path: '/account/settings',
      query: {},
    })
    replaceMock.mockImplementation(async (loc: { query?: Record<string, string> }) => {
      if (loc.query) {
        routerState.route.query = { ...loc.query }
      }
    })
    setActivePinia(createPinia())
    const userStore = useUserStore()
    userStore.setAuth({
      token: 'acc-token',
      refreshToken: 'ref-token',
      user: {
        id: 27,
        username: 'zheco68',
        email: 'zheco68@kece.edu',
        roles: ['USER'],
      },
    })
    profileApiMock.getProfile.mockResolvedValue({ ...baseProfile })
    profileApiMock.updateProfile.mockResolvedValue({
      ...baseProfile,
      bio: 'updated bio',
      githubUrl: 'https://github.com/zheco68-new',
    })
    profileApiMock.uploadAvatar.mockResolvedValue({
      avatarUrl: '/api/v1/uploads/avatars/avatar_27_1.png',
    })
    confirmMock.mockResolvedValue(true)
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:mock-avatar'),
      revokeObjectURL: vi.fn(),
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads profile and syncs ?tab=query across left navigation tabs', async () => {
    routerState.route.query = { tab: 'security' }
    const wrapper = mount(AccountSettingsView)
    await flushPromises()

    expect(profileApiMock.getProfile).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-test="tab-security"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.text()).toContain('UID: 27')
    expect(wrapper.text()).toContain('zheco68@kece.edu')

    await wrapper.find('[data-test="tab-profile"]').trigger('click')
    expect(replaceMock).toHaveBeenCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ tab: 'profile' }) }),
    )
    expect(wrapper.find('[data-test="tab-profile"]').attributes('aria-selected')).toBe('true')
  })

  it('auto-uploads valid avatar immediately on file selection and updates userStore', async () => {
    const wrapper = mount(AccountSettingsView)
    await flushPromises()

    const file = new File(['png-bytes'], 'avatar.png', { type: 'image/png' })
    const input = wrapper.find<HTMLInputElement>('input[type="file"]')
    Object.defineProperty(input.element, 'files', {
      value: [file],
      configurable: true,
    })
    await input.trigger('change')
    await flushPromises()

    expect(profileApiMock.uploadAvatar).toHaveBeenCalledWith(file)
    expect(useUserStore().userInfo?.avatarUrl).toBe('/api/v1/uploads/avatars/avatar_27_1.png')
    expect(toastMock.success).toHaveBeenCalled()
  })

  it('validates githubUrl protocol and saves bio + githubUrl', async () => {
    const wrapper = mount(AccountSettingsView)
    await flushPromises()

    await wrapper.find('#settings-github').setValue('javascript:alert(1)')
    await wrapper.find('[data-test="profile-form"]').trigger('submit')
    expect(profileApiMock.updateProfile).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('http://')

    await wrapper.find('#settings-bio').setValue('updated bio')
    await wrapper.find('#settings-github').setValue('https://github.com/zheco68-new')
    await wrapper.find('[data-test="profile-form"]').trigger('submit')
    await flushPromises()

    expect(profileApiMock.updateProfile).toHaveBeenCalledWith(
      expect.objectContaining({
        bio: 'updated bio',
        githubUrl: 'https://github.com/zheco68-new',
      }),
    )
    expect(toastMock.success).toHaveBeenCalled()
  })

  it('prompts confirmation before switching tabs or leaving route when profile form has unsaved edits', async () => {
    confirmMock.mockResolvedValueOnce(false).mockResolvedValueOnce(false)
    const wrapper = mount(AccountSettingsView)
    await flushPromises()

    await wrapper.find('#settings-bio').setValue('unsaved draft')
    await wrapper.find('[data-test="tab-security"]').trigger('click')
    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-test="tab-profile"]').attributes('aria-selected')).toBe('true')

    const leaveResult = await leaveGuards[0]?.()
    expect(confirmMock).toHaveBeenCalledTimes(2)
    expect(leaveResult).toBe(false)
  })

  it('validates password change and forces full logout + cross-tab event to /login on success', async () => {
    routerState.route.query = { tab: 'security' }
    profileApiMock.changePassword.mockResolvedValue(undefined)

    const wrapper = mount(AccountSettingsView)
    await flushPromises()

    await wrapper.find('#sec-old-pwd').setValue('old123456')
    await wrapper.find('#sec-new-pwd').setValue('123')
    await wrapper.find('#sec-confirm-pwd').setValue('123')
    await wrapper.find('[data-test="password-form"]').trigger('submit')
    expect(profileApiMock.changePassword).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('6')

    await wrapper.find('#sec-new-pwd').setValue('new654321')
    await wrapper.find('#sec-confirm-pwd').setValue('mismatch')
    await wrapper.find('[data-test="password-form"]').trigger('submit')
    expect(profileApiMock.changePassword).not.toHaveBeenCalled()

    await wrapper.find('#sec-confirm-pwd').setValue('new654321')
    await wrapper.find('[data-test="password-form"]').trigger('submit')
    await flushPromises()

    expect(profileApiMock.changePassword).toHaveBeenCalledWith({
      oldPassword: 'old123456',
      newPassword: 'new654321',
    })
    expect(useUserStore().isLoggedIn).toBe(false)
    expect(localStorage.getItem('kece:logout_event')).toBeTruthy()
    expect(toastMock.success).toHaveBeenCalled()
    expect(pushMock).toHaveBeenCalledWith('/login')
  })

  it('validates username rules, saves username update, and silently refreshes token without logging out', async () => {
    const newUsername = '新昵称_01'
    profileApiMock.updateProfile.mockResolvedValue({
      ...baseProfile,
      username: newUsername,
    })
    authApiMock.refresh.mockResolvedValue({
      token: 'refreshed-acc-token',
      refreshToken: 'refreshed-ref-token',
      user: {
        id: 27,
        username: newUsername,
        email: 'zheco68@kece.edu',
        roles: ['USER'],
      },
    })

    const wrapper = mount(AccountSettingsView)
    await flushPromises()

    const usernameInput = wrapper.find<HTMLInputElement>('#settings-username')
    expect(usernameInput.element.value).toBe('zheco68')

    await usernameInput.setValue('bad@name')
    await wrapper.find('[data-test="profile-form"]').trigger('submit')
    expect(profileApiMock.updateProfile).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('@')

    await usernameInput.setValue(newUsername)
    await wrapper.find('[data-test="profile-form"]').trigger('submit')
    await flushPromises()

    expect(profileApiMock.updateProfile).toHaveBeenCalledWith({
      username: newUsername,
      bio: 'initial bio',
      githubUrl: 'https://github.com/zheco68',
    })
    expect(authApiMock.refresh).toHaveBeenCalledWith('ref-token')
    expect(useUserStore().userInfo?.username).toBe(newUsername)
    expect(useUserStore().token).toBe('refreshed-acc-token')
    expect(useUserStore().isLoggedIn).toBe(true)
  })

  it('validates email rebind and forces full logout + cross-tab event to /login on success', async () => {
    routerState.route.query = { tab: 'security' }
    profileApiMock.changeEmail.mockResolvedValue(undefined)

    const wrapper = mount(AccountSettingsView)
    await flushPromises()

    await wrapper.find('#sec-new-email').setValue('zheco68@kece.edu')
    await wrapper.find('#sec-email-pwd').setValue('secret123')
    await wrapper.find('[data-test="email-form"]').trigger('submit')
    expect(profileApiMock.changeEmail).not.toHaveBeenCalled()

    await wrapper.find('#sec-new-email').setValue('newmail@kece.edu')
    await wrapper.find('#sec-email-pwd').setValue('secret123')
    await wrapper.find('[data-test="email-form"]').trigger('submit')
    await flushPromises()

    expect(profileApiMock.changeEmail).toHaveBeenCalledWith({
      currentPassword: 'secret123',
      newEmail: 'newmail@kece.edu',
    })
    expect(useUserStore().isLoggedIn).toBe(false)
    expect(localStorage.getItem('kece:logout_event')).toBeTruthy()
    expect(toastMock.success).toHaveBeenCalled()
    expect(pushMock).toHaveBeenCalledWith('/login')
  })

  it('renders top account HUD summary, supports ArrowRight/ArrowLeft tab navigation, and removes nested sidebar rail', async () => {
    const wrapper = mount(AccountSettingsView)
    await flushPromises()

    expect(wrapper.find('.settings-nav').exists()).toBe(false)

    const hud = wrapper.find('[data-test="account-hud-card"]')
    expect(hud.exists()).toBe(true)
    expect(hud.text()).toContain('zheco68')
    expect(hud.text()).toContain('UID: 27')
    expect(hud.text()).toContain('zheco68@kece.edu')
    expect(hud.text()).toContain('SYS.VERIFIED')

    const tabProfile = wrapper.find('[data-test="tab-profile"]')
    const tabSecurity = wrapper.find('[data-test="tab-security"]')
    await tabProfile.trigger('keydown', { key: 'ArrowRight' })
    await flushPromises()

    expect(tabSecurity.attributes('aria-selected')).toBe('true')
    expect(replaceMock).toHaveBeenCalledWith(
      expect.objectContaining({ query: expect.objectContaining({ tab: 'security' }) }),
    )

    await tabSecurity.trigger('keydown', { key: 'ArrowLeft' })
    await flushPromises()

    expect(tabProfile.attributes('aria-selected')).toBe('true')
  })

  it('syncs live preview card while typing, restores draft via reset button, and avoids global borderless .field-input class', async () => {
    const wrapper = mount(AccountSettingsView)
    await flushPromises()

    expect(wrapper.find('#settings-username').classes()).not.toContain('field-input')
    expect(wrapper.find('#settings-bio').classes()).not.toContain('field-input')
    expect(wrapper.find('#settings-github').classes()).not.toContain('field-input')

    const preview = wrapper.find('[data-test="live-profile-card"]')
    expect(preview.exists()).toBe(true)
    expect(preview.text()).toContain('zheco68')
    expect(preview.text()).toContain('initial bio')

    const resetBtn = wrapper.find('[data-test="reset-draft-btn"]')
    expect(resetBtn.attributes('disabled')).toBeDefined()

    await wrapper.find('#settings-username').setValue('draft_hero')
    await wrapper.find('#settings-bio').setValue('live preview bio')
    await wrapper.find('#settings-github').setValue('https://github.com/draft-hero')

    expect(preview.text()).toContain('draft_hero')
    expect(preview.text()).toContain('live preview bio')
    expect(preview.text()).toContain('https://github.com/draft-hero')
    expect(wrapper.find('[data-test="account-hud-card"]').text()).toContain('DRAFT.UNSAVED')
    expect(resetBtn.attributes('disabled')).toBeUndefined()

    await resetBtn.trigger('click')
    expect((wrapper.find<HTMLInputElement>('#settings-username').element).value).toBe('zheco68')
    expect((wrapper.find<HTMLTextAreaElement>('#settings-bio').element).value).toBe('initial bio')
    expect((wrapper.find<HTMLInputElement>('#settings-github').element).value).toBe(
      'https://github.com/zheco68',
    )
    expect(preview.text()).toContain('zheco68')
    expect(await leaveGuards[0]?.()).toBe(true)

    await wrapper.find('[data-test="tab-security"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('#sec-new-email').classes()).not.toContain('field-input')
    expect(wrapper.find('#sec-email-pwd').classes()).not.toContain('field-input')
    expect(wrapper.find('#sec-old-pwd').classes()).not.toContain('field-input')
    expect(wrapper.find('#sec-new-pwd').classes()).not.toContain('field-input')
    expect(wrapper.find('#sec-confirm-pwd').classes()).not.toContain('field-input')
  })
})
