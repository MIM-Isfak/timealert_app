import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import axios from 'axios';
import BatchReminderPreferences from './BatchReminderPreferences';

jest.mock('axios');
describe('BatchReminderPreferences', () => {
  beforeEach(() => { jest.clearAllMocks(); });
  test('loads stored preferences and shows pending sync separately from device readiness', async () => {
    axios.get.mockResolvedValueOnce({ data: { fiveMinutes: true, tenMinutes: false, thirtyMinutes: true, examDayBefore: false, notificationSetupPending: true } });
    render(<BatchReminderPreferences apiUrl="/api" token="student-token" deviceState="enabled" />);
    expect(await screen.findByText('This device: notifications enabled')).toBeInTheDocument();
    expect(await screen.findByText('Timetable reminder sync: Pending')).toBeInTheDocument();
    expect(screen.getByLabelText(/10 minutes before/i)).not.toBeChecked();
    expect(screen.getByLabelText(/1 day before exams/i)).not.toBeChecked();
  });
  test('saves all four options and reports ready synchronization', async () => {
    axios.get.mockResolvedValueOnce({ data: { fiveMinutes: true, tenMinutes: true, thirtyMinutes: true, examDayBefore: true, notificationSetupPending: false } });
    axios.put.mockResolvedValueOnce({ data: { notificationSetupPending: false, notificationSyncStatus: 'READY' } });
    render(<BatchReminderPreferences apiUrl="/api" token="student-token" deviceState="permission-required" />);
    fireEvent.click(await screen.findByLabelText(/30 minutes before/i));
    fireEvent.click(screen.getByRole('button', { name: /save reminder preferences/i }));
    await waitFor(() => expect(axios.put).toHaveBeenCalledWith('/api/student/reminder-preferences', expect.objectContaining({ thirtyMinutes: false, examDayBefore: true }), expect.any(Object)));
    expect(await screen.findByText('This device: notifications not enabled')).toBeInTheDocument();
    expect(await screen.findByText('Timetable reminder sync: Ready')).toBeInTheDocument();
  });
});
