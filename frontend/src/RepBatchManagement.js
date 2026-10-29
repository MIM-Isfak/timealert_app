import React, { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import BatchTimetableManagement from './BatchTimetableManagement';
import {
  Users,
  PlusCircle,
  Copy,
  Check,
  CheckCircle2,
  XCircle,
  Clock,
  Layers,
  AlertCircle,
  RefreshCw,
} from 'lucide-react';

export default function RepBatchManagement({ apiUrl, token, onToast }) {
  const [batches, setBatches] = useState([]);
  const [loading, setLoading] = useState(false);
  const [creating, setCreating] = useState(false);
  const [requests, setRequests] = useState([]);
  const [requestsLoading, setRequestsLoading] = useState(false);
  const [actionInProgress, setActionInProgress] = useState(null);
  const [copiedCode, setCopiedCode] = useState(false);
  const [selectedBatchId, setSelectedBatchId] = useState(null);
  const [timezone, setTimezone] = useState('Asia/Colombo');
  const [timezoneBusy, setTimezoneBusy] = useState(false);

  // Form state
  const [name, setName] = useState('');
  const [academicYear, setAcademicYear] = useState('');
  const [semester, setSemester] = useState('');
  const [formError, setFormError] = useState('');

  const fetchBatches = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const res = await axios.get(`${apiUrl}/rep/batches`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      setBatches(res.data);
      if (res.data.length > 0) {
        const selected = res.data.find(batch => batch.id === selectedBatchId) || res.data[0];
        setSelectedBatchId(selected.id);
        fetchRequests(selected.id);
      }
    } catch (err) {
      const msg = err.response?.data?.message || 'Failed to load batches.';
      if (onToast) onToast('error', 'Batch Load Failed', msg);
    } finally {
      setLoading(false);
    }
  }, [apiUrl, token, onToast, selectedBatchId]); // eslint-disable-line react-hooks/exhaustive-deps

  const fetchRequests = async (batchId) => {
    setRequestsLoading(true);
    try {
      const res = await axios.get(`${apiUrl}/batches/${batchId}/requests`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      setRequests(res.data);
    } catch (err) {
      const msg = err.response?.data?.message || 'Failed to load join requests.';
      if (onToast) onToast('error', 'Request Load Failed', msg);
    } finally {
      setRequestsLoading(false);
    }
  };

  useEffect(() => {
    fetchBatches();
  }, [fetchBatches]);

  const handleCreateBatch = async (e) => {
    e.preventDefault();
    setFormError('');

    if (!name.trim() || name.trim().length < 2) {
      setFormError('Batch name must be at least 2 characters.');
      return;
    }

    setCreating(true);
    try {
      const res = await axios.post(
        `${apiUrl}/batches`,
        { name: name.trim(), academicYear: academicYear.trim(), semester: semester.trim() },
        { headers: { Authorization: `Bearer ${token}` } }
      );
      setBatches([res.data]);
      setSelectedBatchId(res.data.id);
      setName('');
      setAcademicYear('');
      setSemester('');
      if (onToast) onToast('success', 'Batch Created', `Batch "${res.data.name}" is ready!`);
      fetchRequests(res.data.id);
    } catch (err) {
      const msg = err.response?.data?.message || 'Could not create batch.';
      setFormError(msg);
      if (onToast) onToast('error', 'Creation Failed', msg);
    } finally {
      setCreating(false);
    }
  };

  const handleApprove = async (batchId, requestId) => {
    setActionInProgress(requestId);
    try {
      await axios.post(
        `${apiUrl}/batches/${batchId}/requests/${requestId}/approve`,
        {},
        { headers: { Authorization: `Bearer ${token}` } }
      );
      if (onToast) onToast('success', 'Student Approved', 'Join request was approved.');
      fetchRequests(batchId);
      // Refresh batch counts
      const res = await axios.get(`${apiUrl}/rep/batches`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      setBatches(res.data);
    } catch (err) {
      const msg = err.response?.data?.message || 'Failed to approve request.';
      if (onToast) onToast('error', 'Approval Error', msg);
    } finally {
      setActionInProgress(null);
    }
  };

  const handleReject = async (batchId, requestId) => {
    setActionInProgress(requestId);
    try {
      await axios.post(
        `${apiUrl}/batches/${batchId}/requests/${requestId}/reject`,
        {},
        { headers: { Authorization: `Bearer ${token}` } }
      );
      if (onToast) onToast('info', 'Request Rejected', 'Join request was rejected.');
      fetchRequests(batchId);
      // Refresh batch counts
      const res = await axios.get(`${apiUrl}/rep/batches`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      setBatches(res.data);
    } catch (err) {
      const msg = err.response?.data?.message || 'Failed to reject request.';
      if (onToast) onToast('error', 'Rejection Error', msg);
    } finally {
      setActionInProgress(null);
    }
  };

  const handleCopyCode = (code) => {
    navigator.clipboard.writeText(code);
    setCopiedCode(true);
    if (onToast) onToast('success', 'Code Copied', 'Invitation code copied to clipboard!');
    setTimeout(() => setCopiedCode(false), 2500);
  };

  const activeBatch = batches.find(batch => batch.id === selectedBatchId) || batches[0] || null;
  useEffect(() => { if (activeBatch?.timezone) setTimezone(activeBatch.timezone); }, [activeBatch?.id, activeBatch?.timezone]);
  const saveTimezone = async () => {
    setTimezoneBusy(true);
    try {
      const response = await axios.put(`${apiUrl}/batches/${activeBatch.id}`, { timezone }, { headers: { Authorization: `Bearer ${token}` } });
      setBatches(current => current.map(batch => batch.id === activeBatch.id ? response.data : batch));
      onToast?.('success', 'Timezone saved', response.data.notificationSetupPending ? 'Reminder synchronization is pending. Retry it after the Worker is available.' : 'Future reminders use this batch timezone.');
    } catch (err) { onToast?.('error', 'Timezone not saved', err.response?.data?.message || 'Enter a valid IANA timezone.'); }
    finally { setTimezoneBusy(false); }
  };
  const pendingRequests = requests.filter(r => r.status === 'PENDING');
  const approvedMembers = requests.filter(r => r.status === 'APPROVED');

  return (
    <div className="section-card rep-batch-section">
      <div className="section-header">
        <div className="section-icon">
          <Users size={16} />
        </div>
        <h2>Batch & Request Management</h2>
      </div>

      <p className="section-subtitle">
        Representative Workspace: Manage your student batch, share the invitation code, and approve student join requests.
      </p>

      {loading && batches.length === 0 ? (
        <div className="management-empty">Loading your batch details…</div>
      ) : !activeBatch ? (
        /* Create Batch Form */
        <div className="create-batch-card">
          <h3 className="subheading-card">
            <PlusCircle size={16} /> Create Your Course Batch
          </h3>
          <p className="hint-text" style={{ marginBottom: 14 }}>
            As a course representative, create your batch to generate a unique invitation code for your students.
          </p>

          {formError && (
            <div className="auth-error" style={{ marginBottom: 14 }}>
              <AlertCircle size={15} />
              <span>{formError}</span>
            </div>
          )}

          <form onSubmit={handleCreateBatch} className="batch-form">
            <div className="form-group">
              <label htmlFor="batchName">Batch / Degree Name *</label>
              <input
                id="batchName"
                type="text"
                className="search-input"
                placeholder="e.g. Software Engineering 2026"
                value={name}
                onChange={e => setName(e.target.value)}
                required
              />
            </div>

            <div className="form-row">
              <div className="form-group" style={{ flex: 1 }}>
                <label htmlFor="academicYear">Academic Year (optional)</label>
                <input
                  id="academicYear"
                  type="text"
                  className="search-input"
                  placeholder="e.g. Year 3 or 2025/2026"
                  value={academicYear}
                  onChange={e => setAcademicYear(e.target.value)}
                />
              </div>

              <div className="form-group" style={{ flex: 1 }}>
                <label htmlFor="semester">Semester (optional)</label>
                <input
                  id="semester"
                  type="text"
                  className="search-input"
                  placeholder="e.g. Semester 1"
                  value={semester}
                  onChange={e => setSemester(e.target.value)}
                />
              </div>
            </div>

            <button
              type="submit"
              className="btn btn-primary"
              disabled={creating}
              style={{ marginTop: 10 }}
            >
              {creating ? 'Creating Batch…' : 'Create Batch & Generate Code'}
            </button>
          </form>
        </div>
      ) : (
        /* Batch Overview & Requests */
        <div className="batch-active-workspace">
          {batches.length > 1 && <div className="form-group" style={{ marginBottom: 16 }}><label htmlFor="managed-batch">Select batch to manage</label><select id="managed-batch" className="search-input" value={activeBatch.id} onChange={e => { const id = Number(e.target.value); setSelectedBatchId(id); fetchRequests(id); }}>{batches.map(batch => <option key={batch.id} value={batch.id}>{batch.name}</option>)}</select></div>}
          <BatchTimetableManagement apiUrl={apiUrl} token={token} batchId={activeBatch.id} onToast={onToast} />
          <div className="batch-info-banner" style={{ marginTop: 16 }}><div className="form-group" style={{ flex: 1 }}><label htmlFor="batch-timezone">Batch timezone (IANA)</label><input id="batch-timezone" className="search-input" value={timezone} onChange={e => setTimezone(e.target.value)} placeholder="Asia/Colombo" /><span className="notification-hint">Local timetable times are converted using this timezone.</span></div><button className="btn btn-secondary" onClick={saveTimezone} disabled={timezoneBusy}>{timezoneBusy ? 'Saving…' : 'Save timezone'}</button><button className="btn btn-secondary" onClick={async () => { try { const response = await axios.post(`${apiUrl}/batches/${activeBatch.id}/timetable/notifications/resync`, {}, { headers: { Authorization: `Bearer ${token}` } }); onToast?.(response.data.notificationSetupPending ? 'info' : 'success', response.data.notificationSetupPending ? 'Reminder sync pending' : 'Reminder sync ready', response.data.notificationSetupPending ? 'Use retry after server setup is available.' : 'Upcoming timetable reminders are synchronized.'); } catch (e) { onToast?.('error', 'Resynchronization failed', e.response?.data?.message || 'Could not retry reminder setup.'); } }}>Retry reminder sync</button></div>
          {/* Batch Details Card */}
          <div className="batch-info-banner">
            <div className="batch-info-main">
              <div className="batch-name-row">
                <span className="batch-display-name">{activeBatch.name}</span>
                <span className={`status-badge ${activeBatch.active ? 'ongoing' : 'done'}`}>
                  {activeBatch.active ? 'Active Batch' : 'Inactive'}
                </span>
              </div>
              <div className="batch-meta-row">
                {activeBatch.academicYear && (
                  <span className="event-meta-item">
                    <Layers size={13} /> {activeBatch.academicYear}
                  </span>
                )}
                {activeBatch.semester && (
                  <span className="event-meta-item">
                    <Clock size={13} /> {activeBatch.semester}
                  </span>
                )}
              </div>
            </div>

            {/* Invitation Code Box */}
            <div className="invitation-code-box">
              <span className="invitation-code-label">Student Invitation Code</span>
              <div className="invitation-code-display">
                <span className="invitation-code-value">{activeBatch.invitationCode}</span>
                <button
                  className="icon-btn"
                  onClick={() => handleCopyCode(activeBatch.invitationCode)}
                  title="Copy Invitation Code"
                >
                  {copiedCode ? <Check size={14} color="var(--green)" /> : <Copy size={14} />}
                </button>
              </div>
              <span className="invitation-code-hint">Students submit this code in their app to join</span>
            </div>
          </div>

          {/* Pending Requests Section */}
          <div className="requests-section" style={{ marginTop: 24 }}>
            <div className="section-header" style={{ marginBottom: 12 }}>
              <div className="section-icon" style={{ background: 'var(--amber-bg)', color: 'var(--amber)' }}>
                <Clock size={15} />
              </div>
              <h3>Pending Join Requests ({pendingRequests.length})</h3>
              <button
                className="btn btn-secondary btn-sm"
                onClick={() => fetchRequests(activeBatch.id)}
                disabled={requestsLoading}
                style={{ marginLeft: 'auto' }}
              >
                <RefreshCw size={13} className={requestsLoading ? 'btn-spinner' : ''} />
                Refresh
              </button>
            </div>

            {requestsLoading && requests.length === 0 ? (
              <div className="management-empty">Checking join requests…</div>
            ) : pendingRequests.length === 0 ? (
              <div className="management-empty">
                No pending join requests right now. Students who enter your code will appear here.
              </div>
            ) : (
              <div className="request-items-list">
                {pendingRequests.map(req => {
                  const inProgress = actionInProgress === req.id;
                  return (
                    <div key={req.id} className="request-card">
                      <div className="request-student-details">
                        <span className="request-student-name">{req.studentName || 'Student'}</span>
                        <span className="request-student-email">{req.studentEmail}</span>
                      </div>

                      <div className="request-actions">
                        <button
                          className="btn btn-primary btn-sm"
                          onClick={() => handleApprove(activeBatch.id, req.id)}
                          disabled={inProgress}
                        >
                          <CheckCircle2 size={14} />
                          {inProgress ? 'Updating…' : 'Approve'}
                        </button>
                        <button
                          className="btn btn-danger-ghost btn-sm"
                          onClick={() => handleReject(activeBatch.id, req.id)}
                          disabled={inProgress}
                        >
                          <XCircle size={14} />
                          {inProgress ? 'Updating…' : 'Reject'}
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {/* Approved Members Summary */}
          <div className="members-section" style={{ marginTop: 24 }}>
            <div className="section-header" style={{ marginBottom: 12 }}>
              <div className="section-icon" style={{ background: 'var(--green-bg)', color: 'var(--green)' }}>
                <CheckCircle2 size={15} />
              </div>
              <h3>Approved Students ({approvedMembers.length})</h3>
            </div>

            {approvedMembers.length === 0 ? (
              <div className="management-empty">No approved students yet.</div>
            ) : (
              <div className="approved-members-grid">
                {approvedMembers.map(m => (
                  <div key={m.id} className="approved-member-pill">
                    <span className="member-name">{m.studentName || 'Student'}</span>
                    <span className="member-email">{m.studentEmail}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
