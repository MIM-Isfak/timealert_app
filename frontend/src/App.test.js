import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import axios from 'axios';
import { disconnectPush } from './pushClient';
import App from './App';

jest.mock('axios', () => ({ get: jest.fn(), post: jest.fn(), defaults: { headers: { common: {} } } }));
jest.mock('./pushClient', () => ({ disconnectPush: jest.fn() }));
jest.mock('./NotificationSettings', () => () => <div>Device notifications</div>);
beforeEach(() => { localStorage.clear(); jest.clearAllMocks(); });
test('renders the real login form without requesting notification permission', () => {
  render(<App />);
  expect(screen.getByRole('heading', { name: 'TimeAlert' })).toBeInTheDocument();
  expect(screen.getByLabelText('Email address')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Create account' })).not.toBeInTheDocument();
});
test('logout completes even if notification cleanup is unavailable', async () => {
  localStorage.setItem('token', 'test-only-token');
  localStorage.setItem('email', 'test@example.com');
  axios.get.mockResolvedValue({ data: [] });
  disconnectPush.mockRejectedValue(new Error('unavailable'));
  render(<App />);
  fireEvent.click(screen.getByTitle('Log out'));
  await waitFor(() => expect(screen.getByLabelText('Email address')).toBeInTheDocument());
  expect(localStorage.getItem('token')).toBeNull();
  expect(disconnectPush).toHaveBeenCalledTimes(1);
});
