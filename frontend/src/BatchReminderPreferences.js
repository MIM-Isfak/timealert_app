import React, { useCallback, useEffect, useState } from 'react';
import axios from 'axios';

const initial = { fiveMinutes: true, tenMinutes: true, thirtyMinutes: true, examDayBefore: true };
export default function BatchReminderPreferences({ apiUrl, token, deviceState }) {
  const [values, setValues] = useState(initial);
  const [syncStatus, setSyncStatus] = useState('loading');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const refresh = useCallback(async () => {
    if (!token) return;
    setSyncStatus('loading');
    try {
      const response = await axios.get(`${apiUrl}/student/reminder-preferences`, { headers: { Authorization: `Bearer ${token}` } });
      setValues({ fiveMinutes: response.data.fiveMinutes, tenMinutes: response.data.tenMinutes, thirtyMinutes: response.data.thirtyMinutes, examDayBefore: response.data.examDayBefore });
      setSyncStatus(response.data.notificationSetupPending ? 'pending' : 'ready');
    } catch (error) { setSyncStatus('pending'); setMessage(error.response?.data?.message || 'Reminder sync status is unavailable.'); }
  }, [apiUrl, token]);
  useEffect(() => { refresh(); }, [refresh, deviceState]);
  const save = async event => {
    event.preventDefault(); setBusy(true); setMessage('');
    try {
      const response = await axios.put(`${apiUrl}/student/reminder-preferences`, values, { headers: { Authorization: `Bearer ${token}` } });
      setSyncStatus(response.data.notificationSetupPending ? 'pending' : 'ready');
      setMessage(response.data.notificationSetupPending ? 'Preferences saved; server synchronization is pending. Retry by saving again later.' : 'Preferences saved and synchronized.');
    } catch (error) { setSyncStatus('pending'); setMessage(error.response?.data?.message || 'Could not save reminder preferences.'); }
    finally { setBusy(false); }
  };
  const options = [
    ['fiveMinutes', '5 minutes before'], ['tenMinutes', '10 minutes before'],
    ['thirtyMinutes', '30 minutes before'], ['examDayBefore', '1 day before exams'],
  ];
  const deviceLabel = deviceState === 'enabled' ? 'notifications enabled' : deviceState === 'loading' ? 'checking notifications' : ['setup-pending','error'].includes(deviceState) ? 'status unavailable' : 'notifications not enabled';
  return <section className="section-card" aria-labelledby="batch-reminders-heading">
    <div className="section-header"><div className="section-icon">◷</div><h2 id="batch-reminders-heading">Batch timetable reminders</h2></div>
    <p className="section-subtitle">Preferences are saved to your account and apply across your signed-in devices.</p>
    <p role="status">This device: {deviceLabel}</p>
    <p role="status">Timetable reminder sync: {syncStatus === 'ready' ? 'Ready' : syncStatus === 'loading' ? 'Checking…' : 'Pending'}</p>
    {message && <p role="status">{message}</p>}
    <form onSubmit={save}>
      <div className="notification-actions" style={{ justifyContent: 'flex-start', flexWrap: 'wrap' }}>
        {options.map(([key, label]) => <label key={key} className="reminder-preference-option" style={{ display: 'inline-flex', gap: 8, alignItems: 'center', padding: '8px 12px' }}><input type="checkbox" checked={values[key]} onChange={e => setValues(current => ({ ...current, [key]: e.target.checked }))} />{label}</label>)}
      </div>
      <button className="btn btn-secondary" type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save reminder preferences'}</button>
      <p className="notification-hint">Push acceptance does not confirm delivery to a device.</p>
    </form>
  </section>;
}
