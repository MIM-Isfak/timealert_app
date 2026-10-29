import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import axios from 'axios';
import OwnerRepManagement from './OwnerRepManagement';

jest.mock('axios');

describe('OwnerRepManagement', () => {
  const token = 'owner-jwt-token';
  const apiUrl = 'http://localhost:8080/api';
  const onToast = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
  });

  test('renders user list and allows promoting student to REP', async () => {
    const mockUsers = [
      { id: 1, email: 'student1@example.com', name: 'Alice Student', role: 'STUDENT' },
      { id: 2, email: 'rep1@example.com', name: 'Bob Rep', role: 'REP' },
    ];
    axios.get.mockResolvedValueOnce({ data: mockUsers });
    axios.put.mockResolvedValueOnce({ data: { id: 1, role: 'REP', message: 'User promoted to REP.' } });

    render(<OwnerRepManagement apiUrl={apiUrl} token={token} onToast={onToast} />);

    await waitFor(() => {
      expect(screen.getByText('Alice Student')).toBeInTheDocument();
      expect(screen.getByText('Bob Rep')).toBeInTheDocument();
    });

    const promoteBtn = screen.getByRole('button', { name: /promote to rep/i });
    fireEvent.click(promoteBtn);

    await waitFor(() => {
      expect(axios.put).toHaveBeenCalledWith(
        `${apiUrl}/owner/users/1/role`,
        { role: 'REP' },
        expect.any(Object)
      );
      expect(onToast).toHaveBeenCalledWith('success', 'Role Updated', expect.stringContaining('promoted'));
    });
  });

  test('allows revoking REP status back to STUDENT', async () => {
    const mockUsers = [
      { id: 2, email: 'rep1@example.com', name: 'Bob Rep', role: 'REP' },
    ];
    axios.get.mockResolvedValueOnce({ data: mockUsers });
    axios.put.mockResolvedValueOnce({ data: { id: 2, role: 'STUDENT', message: 'User REP access revoked.' } });

    render(<OwnerRepManagement apiUrl={apiUrl} token={token} onToast={onToast} />);

    await waitFor(() => {
      expect(screen.getByText('Bob Rep')).toBeInTheDocument();
    });

    const revokeBtn = screen.getByRole('button', { name: /revoke rep/i });
    fireEvent.click(revokeBtn);

    await waitFor(() => {
      expect(axios.put).toHaveBeenCalledWith(
        `${apiUrl}/owner/users/2/role`,
        { role: 'STUDENT' },
        expect.any(Object)
      );
      expect(onToast).toHaveBeenCalledWith('success', 'Role Updated', expect.stringContaining('revoked'));
    });
  });
});
