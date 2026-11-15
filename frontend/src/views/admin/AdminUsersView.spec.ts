import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import AdminUsersView from './AdminUsersView.vue';

const { adminApiMock, confirmMock, toastMock } = vi.hoisted(() => ({
  adminApiMock: {
    listUsers: vi.fn(),
    updateUserRoles: vi.fn(),
    updateUserStatus: vi.fn(),
    resetUserPassword: vi.fn(),
  },
  confirmMock: vi.fn(),
  toastMock: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

vi.mock('@/api/admin', () => ({ adminApi: adminApiMock }));
vi.mock('@/composables/useConfirm', () => ({ useConfirm: () => ({ confirm: confirmMock }) }));
vi.mock('@/composables/useToast', () => ({ useToast: () => toastMock }));
vi.mock('@/stores/user', () => ({
  useUserStore: () => ({ userInfo: { id: 1, username: 'admin', roles: ['ADMIN'] } }),
}));

let wrapper: ReturnType<typeof mount> | null = null;

describe('AdminUsersView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    confirmMock.mockResolvedValue(true);

    adminApiMock.listUsers.mockResolvedValue({
      list: [
        {
          id: 1,
          username: 'admin',
          email: 'admin@kece.edu',
          roles: ['ADMIN'],
          status: 1,
          createdAt: '2026-10-01T08:00:00Z',
        },
        {
          id: 2,
          username: 'teacher1',
          email: 'teacher1@kece.edu',
          roles: ['TEACHER'],
          status: 1,
          createdAt: '2026-10-02T09:00:00Z',
        },
      ],
      total: 2,
    });

    adminApiMock.updateUserRoles.mockResolvedValue({
      id: 2,
      username: 'teacher1',
      email: 'teacher1@kece.edu',
      roles: ['ADMIN'],
      status: 1,
      createdAt: '2026-10-02T09:00:00Z',
    });

    adminApiMock.updateUserStatus.mockResolvedValue({
      id: 2,
      username: 'teacher1',
      email: 'teacher1@kece.edu',
      roles: ['ADMIN'],
      status: 0,
      createdAt: '2026-10-02T09:00:00Z',
    });

    adminApiMock.resetUserPassword.mockResolvedValue(undefined);
  });

  afterEach(() => {
    wrapper?.unmount();
    wrapper = null;
    document.body.innerHTML = '';
  });

  it('keeps table rows clean with badge + single manage button and protects current account inside drawer', async () => {
    wrapper = mount(AdminUsersView, { attachTo: document.body });
    await flushPromises();

    // No inline <select> inside table body
    expect(wrapper.find('tbody select').exists()).toBe(false);
    expect(wrapper.findAll('tbody .manage-btn')).toHaveLength(2);
    expect(wrapper.text()).toContain('当前账号');

    // Open drawer for self
    await wrapper.findAll('tbody .manage-btn')[0]!.trigger('click');
    await nextTick();

    const drawer = document.body.querySelector('.manage-drawer');
    expect(drawer).not.toBeNull();
    expect(drawer?.textContent).toContain('当前登录账号受安全保护');
    const selfSaveBtn = drawer?.querySelector<HTMLButtonElement>('.save-role-btn');
    const selfStatusBtn = drawer?.querySelector<HTMLButtonElement>('.status-toggle-btn');
    const selfResetBtn = drawer?.querySelector<HTMLButtonElement>('.reset-pwd-btn');
    expect(selfSaveBtn?.disabled).toBe(true);
    expect(selfStatusBtn?.disabled).toBe(true);
    expect(selfResetBtn?.disabled).toBe(true);

    // Escape closes drawer
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await nextTick();
    expect(document.body.querySelector('.manage-drawer')).toBeNull();
  });

  it('executes role update, status disable, and password reset inside drawer with confirmation', async () => {
    wrapper = mount(AdminUsersView, { attachTo: document.body });
    await flushPromises();

    await wrapper.findAll('tbody .manage-btn')[1]!.trigger('click');
    await nextTick();

    const drawer = document.body.querySelector('.manage-drawer')!;
    const roleSelect = drawer.querySelector<HTMLSelectElement>('.drawer-select')!;
    roleSelect.value = 'ADMIN';
    roleSelect.dispatchEvent(new Event('change'));
    await nextTick();

    drawer.querySelector<HTMLButtonElement>('.save-role-btn')!.click();
    await flushPromises();
    expect(adminApiMock.updateUserRoles).toHaveBeenCalledWith(2, ['ADMIN']);

    drawer.querySelector<HTMLButtonElement>('.status-toggle-btn')!.click();
    await flushPromises();
    expect(adminApiMock.updateUserStatus).toHaveBeenCalledWith(2, 0);

    const pwdInput = drawer.querySelector<HTMLInputElement>('input[type="password"]')!;
    pwdInput.value = 'newpass123';
    pwdInput.dispatchEvent(new Event('input'));
    await nextTick();
    drawer.querySelector<HTMLButtonElement>('.reset-pwd-btn')!.click();
    await flushPromises();
    expect(adminApiMock.resetUserPassword).toHaveBeenCalledWith(2, 'newpass123');
  });
});
