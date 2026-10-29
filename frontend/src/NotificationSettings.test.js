import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import NotificationSettings from './NotificationSettings';
import * as push from './pushClient';

jest.mock('./pushClient', () => ({
  inspectPush: jest.fn(), enablePush: jest.fn(), disconnectPush: jest.fn(), testPush: jest.fn(),
  homeScreenHint: () => 'On iPhone, add TimeAlert to your Home Screen.',
  pushError: () => 'Notification service unavailable.',
  pushSession: () => 1,
}));
const props = { apiUrl: 'http://localhost/api', token: 'test-only-token', owner: 'test@example.com', isDemo: false };
beforeEach(() => {
  jest.clearAllMocks();
  Object.defineProperty(window, 'Notification', { configurable: true, value: { permission: 'default', requestPermission: jest.fn().mockResolvedValue('granted') } });
});
for (const [state, text] of [
  ['unsupported', 'Notifications are unavailable'], ['blocked', 'Notifications are blocked'],
  ['setup-pending', 'awaiting server setup'], ['error', 'Could not check'],
]) test(`shows ${state} without requesting permission`, async () => {
  push.inspectPush.mockResolvedValue({ state });
  render(<NotificationSettings {...props} />);
  expect(await screen.findByText(new RegExp(text))).toBeInTheDocument();
  expect(Notification.requestPermission).not.toHaveBeenCalled();
  expect(screen.queryByText('Test notification')).not.toBeInTheDocument();
});
test('permission is requested only after Enable; test success says acceptance, not delivery', async () => {
  push.inspectPush.mockResolvedValue({ state: 'permission-required', publicKey: 'public-test-key' });
  push.enablePush.mockResolvedValue(); push.testPush.mockResolvedValue({ status: 'accepted' });
  render(<NotificationSettings {...props} />);
  const enable = await screen.findByRole('button', { name: 'Enable notifications' });
  expect(Notification.requestPermission).not.toHaveBeenCalled();
  fireEvent.click(enable);
  expect(Notification.requestPermission).toHaveBeenCalledTimes(1);
  fireEvent.click(await screen.findByRole('button', { name: 'Test notification' }));
  expect(await screen.findByText(/Delivery to your device is not confirmed/)).toBeInTheDocument();
  expect(push.enablePush).toHaveBeenCalledWith(props.apiUrl, props.token, props.owner, 'public-test-key', 1);
});
test('denied permission does not register a subscription', async () => {
  Notification.requestPermission.mockResolvedValue('denied');
  push.inspectPush.mockResolvedValue({ state: 'permission-required', publicKey: 'public-test-key' });
  render(<NotificationSettings {...props} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Enable notifications' }));
  expect(await screen.findByText(/Notifications are blocked/)).toBeInTheDocument();
  expect(push.enablePush).not.toHaveBeenCalled();
});
test('disable reports local success even if server cleanup fails', async () => {
  push.inspectPush.mockResolvedValue({ state: 'enabled', publicKey: 'public-test-key' });
  push.disconnectPush.mockResolvedValue({ localCleaned: true, serverCleaned: false });
  render(<NotificationSettings {...props} />);
  await screen.findByText('Enabled on this device.');
  fireEvent.click(screen.getByRole('button', { name: 'Disable on this device' }));
  expect(await screen.findByText(/Disabled locally/)).toBeInTheDocument();
  expect(screen.queryByText('Test notification')).not.toBeInTheDocument();
});
test('service failures do not claim a notification was sent', async () => {
  push.inspectPush.mockResolvedValue({ state: 'enabled' });
  push.testPush.mockRejectedValue(new Error('unavailable'));
  render(<NotificationSettings {...props} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Test notification' }));
  await waitFor(() => expect(screen.getByText('Notification service unavailable.')).toBeInTheDocument());
  expect(screen.queryByText(/Accepted by the push service/)).not.toBeInTheDocument();
});
test('demo accounts cannot enable or send push', () => {
  render(<NotificationSettings {...props} isDemo />);
  expect(screen.getByText(/Sign in with a personal account/)).toBeInTheDocument();
  expect(push.inspectPush).not.toHaveBeenCalled();
});
