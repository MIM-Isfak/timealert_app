import React, { useCallback, useEffect, useState } from 'react';
import axios from 'axios';

const blank = { title: '', description: '', eventDate: '', startTime: '', endTime: '', location: '', type: 'CLASS', repeatStart: '', repeatEnd: '' };

export default function BatchTimetableManagement({ apiUrl, token, batchId, onToast }) {
  const [events, setEvents] = useState([]);
  const [form, setForm] = useState(blank);
  const [file, setFile] = useState(null);
  const [busy, setBusy] = useState(false);
  const [preview, setPreview] = useState(null);
  const [editing, setEditing] = useState(null);
  const headers = { Authorization: `Bearer ${token}` };
  const refresh = useCallback(async () => {
    try { const r = await axios.get(`${apiUrl}/batches/${batchId}/timetable`, { headers }); setEvents(r.data); }
    catch (e) { onToast?.('error', 'Timetable unavailable', e.response?.data?.message || 'Could not load timetable.'); }
  }, [apiUrl, batchId, token]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { refresh(); }, [refresh]);
  const create = async e => {
    e.preventDefault(); setBusy(true);
    try { await axios.post(`${apiUrl}/batches/${batchId}/timetable`, form, { headers }); setForm(blank); await refresh(); onToast?.('success', 'Draft saved', 'Timetable occurrence(s) are ready to publish.'); }
    catch (err) { onToast?.('error', 'Could not save event', err.response?.data?.message || 'Check the event details.'); }
    finally { setBusy(false); }
  };
  const importFile = async () => {
    if (!file) return;
    const data = new FormData(); data.append('file', file); setBusy(true);
    try { const r = await axios.post(`${apiUrl}/batches/${batchId}/timetable/import-preview`, data, { headers }); setPreview(r.data); await refresh(); }
    catch (err) { onToast?.('error', 'Import failed', err.response?.data?.message || 'Could not preview this file.'); }
    finally { setBusy(false); }
  };
  const publish = async () => { setBusy(true); try { await axios.post(`${apiUrl}/batches/${batchId}/timetable/publish`, {}, { headers }); await refresh(); onToast?.('success', 'Published', 'Draft timetable events are now visible to approved students.'); } catch (e) { onToast?.('error', 'Publish failed', e.response?.data?.message || 'Could not publish.'); } finally { setBusy(false); } };
  const saveEdit = async e => {
    e.preventDefault();
    try { await axios.put(`${apiUrl}/batches/${batchId}/timetable/${editing.id}`, editing, { headers }); setEditing(null); await refresh(); }
    catch (err) { onToast?.('error', 'Edit failed', err.response?.data?.message || 'Could not update event.'); }
  };
  const cancel = async event => {
    const reason = window.prompt('Cancellation reason (shown to students)', ''); if (reason === null) return;
    if (!reason.trim()) { onToast?.('error', 'Reason required', 'Add a cancellation reason for students.'); return; }
    try { await axios.post(`${apiUrl}/batches/${batchId}/timetable/${event.id}/cancel`, { reason }, { headers }); await refresh(); }
    catch (e) { onToast?.('error', 'Cancellation failed', e.response?.data?.message || 'Could not cancel event.'); }
  };
  return <section className="section-card" aria-label="Batch timetable">
    <div className="section-header"><div className="section-icon">◷</div><h3>Shared timetable</h3><button className="btn btn-primary btn-sm" onClick={publish} disabled={busy || !events.some(e => e.status === 'DRAFT')}>Publish drafts</button></div>
    <p className="section-subtitle">Create individual events or repeat weekly. Drafts stay private until published.</p>
    <form onSubmit={create} className="batch-form">
      <div className="form-row"><div className="form-group" style={{ flex: 2 }}><label>Subject / title</label><input aria-label="Subject / title" className="search-input" value={form.title} required onChange={e => setForm({ ...form, title: e.target.value })} /></div><div className="form-group"><label>Type</label><select aria-label="Type" className="search-input" value={form.type} onChange={e => setForm({ ...form, type: e.target.value })}>{['CLASS','EXAM','DEADLINE','OTHER'].map(t => <option key={t}>{t}</option>)}</select></div></div>
      <div className="form-row">{[['Event date','eventDate','date'],['Start','startTime','time'],['End','endTime','time'],['Room / location','location','text']].map(([label,key,type]) => <div className="form-group" style={{ flex: 1 }} key={key}><label>{label}</label><input aria-label={label} className="search-input" type={type} value={form[key]} required={key !== 'location'} onChange={e => setForm({ ...form, [key]: e.target.value })} /></div>)}</div>
      <div className="form-group"><label>Description</label><textarea className="search-input" value={form.description} onChange={e => setForm({ ...form, description: e.target.value })} /></div>
      <div className="form-row"><div className="form-group" style={{ flex: 1 }}><label>Repeat weekly from (optional)</label><input aria-label="Repeat weekly from" className="search-input" type="date" value={form.repeatStart} onChange={e => setForm({ ...form, repeatStart: e.target.value })} /></div><div className="form-group" style={{ flex: 1 }}><label>Through date</label><input aria-label="Through date" className="search-input" type="date" value={form.repeatEnd} onChange={e => setForm({ ...form, repeatEnd: e.target.value })} /></div><button className="btn btn-secondary" type="submit" disabled={busy}>Save draft</button></div>
    </form>
    <div className="upload-actions" style={{ marginTop: 18 }}><input aria-label="Import timetable file" type="file" accept=".csv,.xlsx,.xls,.pdf" onChange={e => setFile(e.target.files[0])} /><button className="btn btn-secondary" onClick={importFile} disabled={!file || busy}>Preview import</button></div>
    {preview && <div className="management-empty" role="status">Import preview: {preview.preview.length} draft rows added; {preview.duplicateCount} duplicates skipped. {preview.skippedReasons?.length ? <details><summary>Skipped rows ({preview.skippedReasons.length})</summary>{preview.skippedReasons.map(r => <div key={r}>{r}</div>)}</details> : null}</div>}
    {editing && <form onSubmit={saveEdit} className="batch-form" aria-label="Edit timetable event" style={{ marginTop: 18 }}><h4>Edit published event</h4><div className="form-row">{[['Subject / title','title','text'],['Date','eventDate','date'],['Start','startTime','time'],['End','endTime','time'],['Room / location','location','text']].map(([label,key,type]) => <div className="form-group" style={{ flex: 1 }} key={key}><label>{label}</label><input aria-label={`Edit ${label}`} className="search-input" type={type} value={editing[key] || ''} required={key !== 'location'} onChange={e => setEditing({ ...editing, [key]: e.target.value })} /></div>)}</div><div className="form-row"><div className="form-group" style={{ flex: 1 }}><label>Type</label><select aria-label="Edit type" className="search-input" value={editing.type} onChange={e => setEditing({ ...editing, type: e.target.value })}>{['CLASS','EXAM','DEADLINE','OTHER'].map(t => <option key={t}>{t}</option>)}</select></div><div className="form-group" style={{ flex: 2 }}><label>Change reason (shown to students)</label><input aria-label="Change reason" className="search-input" value={editing.reason || ''} onChange={e => setEditing({ ...editing, reason: e.target.value })} /></div><button type="submit" className="btn btn-primary">Save changes</button><button type="button" className="btn btn-secondary" onClick={() => setEditing(null)}>Close</button></div></form>}
    <div className="request-items-list" style={{ marginTop: 16 }}>{events.map(event => <article className="request-card" key={event.id}><div className="request-student-details"><strong>{event.title}</strong><span>{event.eventDate} · {event.startTime}–{event.endTime} · {event.location || event.type}</span><span>{event.status} · {event.acknowledgementCount || 0} acknowledgements · v{event.version}</span><span>Reminder sync: {event.notificationSetupPending ? 'Pending' : 'Ready'}</span>{event.history?.map(h => <details key={h.version}><summary>Change history v{h.version} · {h.action}</summary><p>Before: {h.before}</p><p>After: {h.after}</p><p>{h.reason}</p></details>)}</div><div className="request-actions">{event.status !== 'CANCELLED' && <><button className="btn btn-secondary btn-sm" onClick={() => setEditing({ ...event, reason: event.changeReason || '' })}>Edit</button><button className="btn btn-danger-ghost btn-sm" onClick={() => cancel(event)}>Cancel</button></>}</div></article>)}</div>
  </section>;
}
