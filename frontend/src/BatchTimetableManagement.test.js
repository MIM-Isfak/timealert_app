import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import axios from 'axios';
import BatchTimetableManagement from './BatchTimetableManagement';

jest.mock('axios');
describe('BatchTimetableManagement', () => {
  const apiUrl = 'http://localhost:8080/api'; const token = 'rep-token';
  beforeEach(() => { jest.clearAllMocks(); axios.get.mockResolvedValue({ data: [] }); });
  test('creates a weekly draft series and publishes the drafts', async () => {
    axios.post.mockResolvedValueOnce({ data: [] }).mockResolvedValueOnce({ data: [] });
    render(<BatchTimetableManagement apiUrl={apiUrl} token={token} batchId={4} />);
    fireEvent.change(screen.getByLabelText(/subject \/ title/i), { target: { value: 'Algorithms' } });
    fireEvent.change(screen.getByLabelText(/event date/i), { target: { value: '2027-02-01' } });
    fireEvent.change(screen.getByLabelText(/^start$/i), { target: { value: '09:00' } });
    fireEvent.change(screen.getByLabelText(/^end$/i), { target: { value: '10:00' } });
    fireEvent.change(screen.getByLabelText(/repeat weekly from/i), { target: { value: '2027-02-01' } });
    fireEvent.change(screen.getByLabelText(/through date/i), { target: { value: '2027-02-15' } });
    fireEvent.click(screen.getByRole('button', { name: /save draft/i }));
    await waitFor(() => expect(axios.post).toHaveBeenCalledWith(`${apiUrl}/batches/4/timetable`, expect.objectContaining({ repeatEnd: '2027-02-15' }), expect.any(Object)));
  });
  test('previews an import and exposes skipped-row reasons before publish', async () => {
    axios.post.mockResolvedValueOnce({ data: { preview: [{ id: 1, title: 'Imported', status: 'DRAFT' }], skippedReasons: ['Row 3: Missing date'], duplicateCount: 0 } });
    render(<BatchTimetableManagement apiUrl={apiUrl} token={token} batchId={4} />);
    const input = screen.getByLabelText(/import timetable file/i);
    fireEvent.change(input, { target: { files: [new File(['Subject,Date'], 'list.csv', { type: 'text/csv' })] } });
    fireEvent.click(screen.getByRole('button', { name: /preview import/i }));
    expect(await screen.findByText(/row 3: missing date/i)).toBeInTheDocument();
    expect(axios.post.mock.calls[0][0]).toContain('/batches/4/timetable/import-preview');
  });
  test('renders published acknowledgement counts and change history', async () => {
    axios.get.mockResolvedValue({ data: [{ id: 2, title: 'Exam', eventDate: '2027-03-01', startTime: '09:00', endTime: '11:00', status: 'PUBLISHED', version: 2, acknowledgementCount: 4, history: [{ version: 2, action: 'UPDATED', before: 'Old room', after: 'New room', reason: 'Room changed' }] }] });
    render(<BatchTimetableManagement apiUrl={apiUrl} token={token} batchId={4} />);
    expect(await screen.findByText(/4 acknowledgements/i)).toBeInTheDocument();
    fireEvent.click(screen.getByText(/change history v2/i));
    expect(screen.getByText(/before: old room/i)).toBeInTheDocument();
  });
});
