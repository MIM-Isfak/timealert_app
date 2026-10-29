import React, { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import {
  GraduationCap,
  CheckCircle2,
  Clock,
  Layers,
  Mail,
  User,
  AlertCircle,
  KeyRound,
  RefreshCw,
} from 'lucide-react';

export default function StudentBatchView({ apiUrl, token, onToast }) {
  const [memberships, setMemberships] = useState([]);
  const [loading, setLoading] = useState(false);
  const [invitationCode, setInvitationCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [joinError, setJoinError] = useState('');
  const [joinSuccess, setJoinSuccess] = useState('');

  const fetchMemberships = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    try {
      const res = await axios.get(`${apiUrl}/student/memberships`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      setMemberships(res.data);
    } catch (err) {
      const msg = err.response?.data?.message || 'Failed to load batch membership.';
      if (onToast) onToast('error', 'Membership Error', msg);
    } finally {
      setLoading(false);
    }
  }, [apiUrl, token, onToast]);

  useEffect(() => {
    fetchMemberships();
  }, [fetchMemberships]);

  const handleJoinSubmit = async (e) => {
    e.preventDefault();
    setJoinError('');
    setJoinSuccess('');

    if (!invitationCode.trim()) {
      setJoinError('Please enter an invitation code.');
      return;
    }

    setSubmitting(true);
    try {
      const res = await axios.post(
        `${apiUrl}/student/join`,
        { invitationCode: invitationCode.trim() },
        { headers: { Authorization: `Bearer ${token}` } }
      );
      setJoinSuccess(res.data.message || 'Join request submitted! Awaiting representative approval.');
      setInvitationCode('');
      if (onToast) {
        onToast('success', 'Request Sent', 'Join request submitted! Awaiting representative approval.');
      }
      fetchMemberships();
    } catch (err) {
      const msg = err.response?.data?.message || 'Failed to submit join request.';
      setJoinError(msg);
      if (onToast) onToast('error', 'Join Failed', msg);
    } finally {
      setSubmitting(false);
    }
  };

  const approvedMembership = memberships.find(m => m.status === 'APPROVED');
  const pendingMembership = memberships.find(m => m.status === 'PENDING');

  return (
    <div className="section-card student-batch-section">
      <div className="section-header">
        <div className="section-icon">
          <GraduationCap size={16} />
        </div>
        <h2>Batch & Course Membership</h2>
        <button
          className="btn btn-secondary btn-sm"
          onClick={fetchMemberships}
          disabled={loading}
          style={{ marginLeft: 'auto' }}
          title="Refresh membership status"
        >
          <RefreshCw size={13} className={loading ? 'btn-spinner' : ''} />
          Refresh
        </button>
      </div>

      <p className="section-subtitle">
        Join your course batch using the invitation code provided by your representative to receive batch notifications.
      </p>

      {/* Current Enrolled Batch */}
      {approvedMembership ? (
        <div className="student-membership-card approved">
          <div className="membership-card-top">
            <div className="membership-title-row">
              <span className="membership-batch-name">{approvedMembership.batchName}</span>
              <span className="status-badge ongoing">
                <CheckCircle2 size={12} /> Enrolled Member
              </span>
            </div>
            <div className="membership-meta-row">
              {approvedMembership.academicYear && (
                <span className="event-meta-item">
                  <Layers size={13} /> {approvedMembership.academicYear}
                </span>
              )}
              {approvedMembership.semester && (
                <span className="event-meta-item">
                  <Clock size={13} /> {approvedMembership.semester}
                </span>
              )}
            </div>
          </div>

          <div className="membership-rep-details">
            <span className="membership-rep-title">Course Representative:</span>
            <div className="rep-contact-row">
              <span className="event-meta-item">
                <User size={13} /> {approvedMembership.repName || 'Representative'}
              </span>
              <span className="event-meta-item">
                <Mail size={13} /> {approvedMembership.repEmail}
              </span>
            </div>
          </div>
        </div>
      ) : pendingMembership ? (
        /* Pending Request Status */
        <div className="student-membership-card pending">
          <div className="membership-card-top">
            <div className="membership-title-row">
              <span className="membership-batch-name">{pendingMembership.batchName}</span>
              <span className="status-badge soon">
                <Clock size={12} /> Pending Approval
              </span>
            </div>
            <p className="membership-pending-hint">
              Your request to join <strong>{pendingMembership.batchName}</strong> has been submitted.
              Awaiting review from representative <strong>{pendingMembership.repName || pendingMembership.repEmail}</strong>.
            </p>
          </div>
        </div>
      ) : (
        /* No Batch Yet Empty State */
        <div className="student-empty-batch">
          <p>You have not joined a batch yet. Enter your course representative's invitation code below.</p>
        </div>
      )}

      {/* Join Request Form */}
      <div className="join-batch-form-wrapper" style={{ marginTop: 20 }}>
        <h3 className="subheading-card">
          <KeyRound size={15} /> Submit Batch Invitation Code
        </h3>

        {joinError && (
          <div className="auth-error" style={{ marginBottom: 12 }}>
            <AlertCircle size={15} />
            <span>{joinError}</span>
          </div>
        )}

        {joinSuccess && (
          <div className="toast success" style={{ marginBottom: 12, padding: '10px 14px' }}>
            <CheckCircle2 size={15} />
            <span>{joinSuccess}</span>
          </div>
        )}

        <form onSubmit={handleJoinSubmit} className="join-form-row">
          <div className="join-input-wrap">
            <input
              type="text"
              className="search-input monospace-input"
              placeholder="e.g. 7X9K2MA3"
              value={invitationCode}
              onChange={e => setInvitationCode(e.target.value.toUpperCase())}
              maxLength={16}
              disabled={submitting}
              autoCapitalize="characters"
            />
          </div>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={submitting || !invitationCode.trim()}
          >
            {submitting ? 'Submitting…' : 'Join Batch'}
          </button>
        </form>
      </div>
    </div>
  );
}
