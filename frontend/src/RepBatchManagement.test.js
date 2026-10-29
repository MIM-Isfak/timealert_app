import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import axios from 'axios';
import RepBatchManagement from './RepBatchManagement';

jest.mock('axios');

describe('RepBatchManagement', () => {
  const token = 'rep-jwt-token';
  const apiUrl = 'http://localhost:8080/api';
  const onToast = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
  });

  test('renders batch creation form when rep has no batches', async () => {
    axios.get.mockResolvedValueOnce({ data: [] });

    render(<RepBatchManagement apiUrl={apiUrl} token={token} onToast={onToast} />);

    await waitFor(() => {
      expect(screen.getByText(/create your course batch/i)).toBeInTheDocument();
      expect(screen.getByLabelText(/batch \/ degree name/i)).toBeInTheDocument();
    });
  });

  test('submits create batch request and displays newly created batch', async () => {
    axios.get.mockResolvedValueOnce({ data: [] });
    const newBatch = {
      id: 10,
      name: 'Software Engineering 2026',
      academicYear: 'Year 3',
      semester: 'Semester 1',
      active: true,
      invitationCode: 'SE2026CODE',
    };
    axios.post.mockResolvedValueOnce({ data: newBatch });
    axios.get.mockResolvedValueOnce({ data: [] }); // requests

    render(<RepBatchManagement apiUrl={apiUrl} token={token} onToast={onToast} />);

    await waitFor(() => {
      expect(screen.getByLabelText(/batch \/ degree name/i)).toBeInTheDocument();
    });

    fireEvent.change(screen.getByLabelText(/batch \/ degree name/i), {
      target: { value: 'Software Engineering 2026' },
    });
    fireEvent.click(screen.getByRole('button', { name: /create batch & generate code/i }));

    await waitFor(() => {
      expect(axios.post).toHaveBeenCalledWith(
        `${apiUrl}/batches`,
        expect.objectContaining({ name: 'Software Engineering 2026' }),
        expect.any(Object)
      );
      expect(screen.getByText('SE2026CODE')).toBeInTheDocument();
    });
  });

  test('rep can approve and reject pending join requests', async () => {
    const existingBatch = {
      id: 10,
      name: 'Software Engineering 2026',
      active: true,
      invitationCode: 'SE2026CODE',
    };
    const pendingRequest = {
      id: 55,
      studentId: 100,
      studentName: 'Charlie Student',
      studentEmail: 'charlie@example.com',
      status: 'PENDING',
    };

    axios.get.mockResolvedValueOnce({ data: [existingBatch] }); // fetchBatches
    axios.get.mockResolvedValueOnce({ data: [pendingRequest] }); // fetchRequests
    axios.post.mockResolvedValueOnce({ data: { ...pendingRequest, status: 'APPROVED' } }); // approve
    axios.get.mockResolvedValueOnce({ data: [] }); // refresh requests
    axios.get.mockResolvedValueOnce({ data: [existingBatch] }); // refresh batch

    render(<RepBatchManagement apiUrl={apiUrl} token={token} onToast={onToast} />);

    await waitFor(() => {
      expect(screen.getByText('Charlie Student')).toBeInTheDocument();
    });

    const approveBtn = screen.getByRole('button', { name: /approve/i });
    fireEvent.click(approveBtn);

    await waitFor(() => {
      expect(axios.post).toHaveBeenCalledWith(
        `${apiUrl}/batches/10/requests/55/approve`,
        {},
        expect.any(Object)
      );
      expect(onToast).toHaveBeenCalledWith('success', 'Student Approved', expect.any(String));
    });
  });
});
