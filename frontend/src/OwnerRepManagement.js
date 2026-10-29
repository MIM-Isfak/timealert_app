import React, { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import { Shield, UserCheck, UserX, Search, AlertCircle, RefreshCw } from 'lucide-react';

export default function OwnerRepManagement({ apiUrl, token, onToast }) {
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(false);
  const [updatingId, setUpdatingId] = useState(null);
  const [search, setSearch] = useState('');
  const [fetchError, setFetchError] = useState('');

  const fetchUsers = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    setFetchError('');
    try {
      const res = await axios.get(`${apiUrl}/owner/users`, {
        headers: { Authorization: `Bearer ${token}` }
      });
      setUsers(res.data);
    } catch (err) {
      const msg = err.response?.data?.message || 'Could not load users.';
      setFetchError(msg);
      if (onToast) onToast('error', 'Error loading users', msg);
    } finally {
      setLoading(false);
    }
  }, [apiUrl, token, onToast]);

  useEffect(() => {
    fetchUsers();
  }, [fetchUsers]);

  const handleRoleChange = async (userId, targetRole) => {
    setUpdatingId(userId);
    try {
      const res = await axios.put(
        `${apiUrl}/owner/users/${userId}/role`,
        { role: targetRole },
        { headers: { Authorization: `Bearer ${token}` } }
      );
      setUsers(prev => prev.map(u => u.id === userId ? { ...u, role: res.data.role } : u));
      if (onToast) {
        onToast('success', 'Role Updated', res.data.message || `User role updated to ${targetRole}.`);
      }
    } catch (err) {
      const msg = err.response?.data?.message || 'Failed to update user role.';
      if (onToast) onToast('error', 'Update Failed', msg);
    } finally {
      setUpdatingId(null);
    }
  };

  const filteredUsers = users.filter(u =>
    (u.name && u.name.toLowerCase().includes(search.toLowerCase())) ||
    (u.email && u.email.toLowerCase().includes(search.toLowerCase()))
  );

  return (
    <div className="section-card owner-management-section">
      <div className="section-header">
        <div className="section-icon">
          <Shield size={16} />
        </div>
        <h2>Representative Management</h2>
      </div>

      <p className="section-subtitle">
        System Owner Control: Promote students to course representatives or revoke representative access.
      </p>

      {fetchError && (
        <div className="auth-error" style={{ marginBottom: 14 }}>
          <AlertCircle size={15} />
          <span>{fetchError}</span>
        </div>
      )}

      <div className="management-toolbar">
        <div className="search-wrapper" style={{ flex: 1, marginBottom: 0 }}>
          <span className="search-icon-pos"><Search size={15} /></span>
          <input
            type="text"
            className="search-input"
            placeholder="Search students or reps by name or email…"
            value={search}
            onChange={e => setSearch(e.target.value)}
          />
        </div>
        <button
          className="btn btn-secondary"
          onClick={fetchUsers}
          disabled={loading}
          title="Refresh list"
        >
          <RefreshCw size={14} className={loading ? 'btn-spinner' : ''} />
          Refresh
        </button>
      </div>

      {loading && users.length === 0 ? (
        <div className="management-empty">Loading accounts…</div>
      ) : filteredUsers.length === 0 ? (
        <div className="management-empty">
          {search ? 'No users matching your search.' : 'No student or rep accounts found.'}
        </div>
      ) : (
        <div className="user-management-list">
          {filteredUsers.map(u => {
            const isOwner = u.role === 'OWNER';
            const isRep = u.role === 'REP';
            const isStudent = u.role === 'STUDENT';
            const isUpdating = updatingId === u.id;

            return (
              <div key={u.id} className="user-management-card">
                <div className="user-mgmt-info">
                  <div className="user-mgmt-name-row">
                    <span className="user-mgmt-name">{u.name || 'Unnamed User'}</span>
                    <span className={`role-badge role-${u.role.toLowerCase()}`}>
                      {u.role}
                    </span>
                  </div>
                  <span className="user-mgmt-email">{u.email}</span>
                </div>

                <div className="user-mgmt-actions">
                  {isOwner && (
                    <span className="mgmt-label-note">Owner Account</span>
                  )}
                  {isStudent && (
                    <button
                      className="btn btn-primary btn-sm"
                      onClick={() => handleRoleChange(u.id, 'REP')}
                      disabled={isUpdating}
                    >
                      <UserCheck size={14} />
                      {isUpdating ? 'Promoting…' : 'Promote to REP'}
                    </button>
                  )}
                  {isRep && (
                    <button
                      className="btn btn-danger-ghost btn-sm"
                      onClick={() => handleRoleChange(u.id, 'STUDENT')}
                      disabled={isUpdating}
                    >
                      <UserX size={14} />
                      {isUpdating ? 'Revoking…' : 'Revoke REP'}
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
