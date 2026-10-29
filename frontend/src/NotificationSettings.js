import React, { useCallback, useEffect, useState } from 'react';
import { enablePush, disconnectPush, homeScreenHint, inspectPush, pushError, pushSession, testPush } from './pushClient';
import BatchReminderPreferences from './BatchReminderPreferences';
const labels = {
  loading: 'Checking notification setup…', unsupported: 'Notifications are unavailable in this browser or context.',
  'permission-required': 'Enable notifications to receive alerts on this device, even when the tab is closed.',
  blocked: 'Notifications are blocked. Allow them in browser or device settings, then check again.',
  'setup-pending': 'Notifications are awaiting server setup. Your timetable still works.',
  enabled: 'Enabled on this device.', error: 'Could not check notification setup.',
};
export default function NotificationSettings({ apiUrl, token, owner, isDemo, loggingOut, userRole }) {
  const [setup, setSetup] = useState({ state: 'loading' });
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const refresh = useCallback(async () => {
    setBusy(true); setMessage('');
    try { setSetup(await inspectPush(apiUrl, token, owner)); }
    catch (error) { setSetup({ state: 'error' }); setMessage(pushError(error)); }
    finally { setBusy(false); }
  }, [apiUrl, token, owner]);
  useEffect(() => {
    if (isDemo) return;
    let cancelled = false;
    inspectPush(apiUrl, token, owner).then(value => { if (!cancelled) setSetup(value); })
      .catch(error => { if (!cancelled) { setSetup({ state: 'error' }); setMessage(pushError(error)); } });
    return () => { cancelled = true; };
  }, [apiUrl, token, owner, isDemo]);
  const enable = async () => {
    const session = pushSession();
    setBusy(true); setMessage('');
    try {
      // Request permission directly from the click, before network/worker awaits.
      const permission = Notification.permission === 'granted' ? 'granted' : await Notification.requestPermission();
      if (permission !== 'granted') { setSetup({ ...setup, state: permission === 'denied' ? 'blocked' : 'permission-required' }); return; }
      await enablePush(apiUrl, token, owner, setup.publicKey, session);
      setSetup({ ...setup, state: 'enabled' });
    } catch (error) { setMessage(pushError(error)); setSetup({ ...setup, state: 'error' }); }
    finally { setBusy(false); }
  };
  const test = async () => {
    setBusy(true); setMessage('');
    try { await testPush(apiUrl, token, owner); setMessage('Accepted by the push service. Delivery to your device is not confirmed.'); }
    catch (error) {
      setMessage(pushError(error));
      if ([404, 410].includes(error.response?.status)) setSetup({ ...setup, state: 'permission-required' });
    } finally { setBusy(false); }
  };
  const disable = async () => {
    setBusy(true); setMessage('');
    try {
      const result = await disconnectPush(apiUrl, token, owner);
      if (!result.localCleaned) { setMessage('Local unsubscription failed. Block notifications in browser settings or try again.'); return; }
      setSetup({ ...setup, state: setup.publicKey ? 'permission-required' : setup.state });
      setMessage(result.serverCleaned ? 'Disabled on this device.' : 'Disabled locally. Server cleanup could not be confirmed.');
    } finally { setBusy(false); }
  };
  const disabled = busy || loggingOut;
  return <>
  <section className="section-card notification-settings" aria-labelledby="notification-heading">
    <h2 id="notification-heading">Device notifications</h2>
    {isDemo ? <p>Sign in with a personal account to enable device notifications.</p> : <>
      <p role="status">{labels[setup.state]}</p>
      <p className="notification-hint">{homeScreenHint()}</p>
      {message && <p role="status">{message}</p>}
      <div className="notification-actions">
        {setup.state === 'permission-required' && setup.publicKey && <button className="btn btn-primary" disabled={disabled} onClick={enable}>Enable notifications</button>}
        {setup.state === 'enabled' && <button className="btn btn-primary" disabled={disabled} onClick={test}>Test notification</button>}
        {setup.state !== 'unsupported' && setup.state !== 'loading' &&
          <button className="btn btn-danger-ghost" disabled={disabled} onClick={disable}>Disable on this device</button>}
        <button className="btn btn-secondary" disabled={disabled} onClick={refresh}>Check again</button>
      </div>
    </>}
  </section>
  {!isDemo && userRole === 'STUDENT' && <BatchReminderPreferences apiUrl={apiUrl} token={token} deviceState={setup.state} />}
  </>;
}
