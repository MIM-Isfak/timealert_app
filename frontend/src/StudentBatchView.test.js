import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import axios from 'axios';
import StudentBatchView from './StudentBatchView';

jest.mock('axios');

describe('StudentBatchView', () => {
  const token = 'student-jwt-token';
  const apiUrl = 'http://localhost:8080/api';
  const onToast = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
  });

  test('renders empty state and allows submitting join request', async () => {
    axios.get.mockResolvedValueOnce({ data: [] });
    axios.post.mockResolvedValueOnce({
      data: {
        id: 70,
        batchName: 'Cyber Security 2026',
        status: 'PENDING',
        message: 'Join request submitted successfully. Awaiting representative approval.',
      },
    });
    axios.get.mockResolvedValueOnce({
      data: [{ id: 70, batchName: 'Cyber Security 2026', status: 'PENDING' }],
    });

    render(<StudentBatchView apiUrl={apiUrl} token={token} onToast={onToast} />);

    await waitFor(() => {
      expect(screen.getByText(/you have not joined a batch yet/i)).toBeInTheDocument();
    });

    const input = screen.getByPlaceholderText(/e\.g\. 7X9K2MA3/i);
    fireEvent.change(input, { target: { value: 'CYBER2026' } });
    fireEvent.click(screen.getByRole('button', { name: /join batch/i }));

    await waitFor(() => {
      expect(axios.post).toHaveBeenCalledWith(
        `${apiUrl}/student/join`,
        { invitationCode: 'CYBER2026' },
        expect.any(Object)
      );
      expect(onToast).toHaveBeenCalledWith('success', 'Request Sent', expect.any(String));
    });
  });

  test('displays enrolled batch details when student is approved', async () => {
    const approved = [
      {
        id: 70,
        batchId: 10,
        batchName: 'Computer Science 2026',
        academicYear: 'Year 3',
        semester: 'Semester 1',
        repName: 'Alice Rep',
        repEmail: 'alice.rep@example.com',
        status: 'APPROVED',
      },
    ];
    axios.get.mockResolvedValueOnce({ data: approved });

    render(<StudentBatchView apiUrl={apiUrl} token={token} onToast={onToast} />);

    await waitFor(() => {
      expect(screen.getByText('Computer Science 2026')).toBeInTheDocument();
      expect(screen.getByText(/enrolled member/i)).toBeInTheDocument();
      expect(screen.getByText('Alice Rep')).toBeInTheDocument();
      expect(screen.getByText('alice.rep@example.com')).toBeInTheDocument();
    });
  });

  test('displays pending approval state when request is pending', async () => {
    const pending = [
      {
        id: 71,
        batchId: 10,
        batchName: 'Data Science 2026',
        repName: 'Bob Rep',
        status: 'PENDING',
      },
    ];
    axios.get.mockResolvedValueOnce({ data: pending });

    render(<StudentBatchView apiUrl={apiUrl} token={token} onToast={onToast} />);

    await waitFor(() => {
      expect(screen.getAllByText('Data Science 2026').length).toBeGreaterThanOrEqual(1);
      expect(screen.getByText(/pending approval/i)).toBeInTheDocument();
    });
  });

  test('approved students see published changes and acknowledge the current version', async () => {
    axios.get.mockResolvedValueOnce({ data: [{ id: 70, batchId: 10, batchName: 'CS', status: 'APPROVED' }] });
    axios.get.mockResolvedValueOnce({ data: [{ id: 5, title: 'Algorithms', eventDate: '2027-03-01', startTime: '09:00', endTime: '10:00', status: 'PUBLISHED', version: 2, changeReason: 'Room changed', history: [{ version: 2, action: 'UPDATED', before: 'Room A', after: 'Room B', reason: 'Room changed' }], acknowledged: false }] });
    axios.post.mockResolvedValueOnce({ data: { acknowledged: true } });
    render(<StudentBatchView apiUrl={apiUrl} token={token} onToast={onToast} />);
    expect(await screen.findByText('Algorithms')).toBeInTheDocument();
    expect(screen.getByText(/changed · room changed/i)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /got it/i }));
    await waitFor(() => expect(axios.post).toHaveBeenCalledWith(`${apiUrl}/student/batches/10/timetable/5/acknowledge`, {}, expect.any(Object)));
    await waitFor(() => expect(screen.getByRole('button', { name: /got it/i })).toBeDisabled());
  });

  test('pending and rejected students do not request or see batch timetable', async () => {
    axios.get.mockResolvedValueOnce({ data: [{ id: 70, batchId: 10, batchName: 'Pending batch', status: 'PENDING' }] });
    render(<StudentBatchView apiUrl={apiUrl} token={token} onToast={onToast} />);
    await screen.findByText(/pending approval/i);
    expect(axios.get).toHaveBeenCalledTimes(1);
    expect(screen.queryByText(/published timetable/i)).not.toBeInTheDocument();
  });
});
