import React, { useState, useEffect, useCallback, useRef } from 'react';
import axios from 'axios';
import {
  Bell,
  Calendar,
  CheckCircle2,
  Clock,
  LogOut,
  Moon,
  Search,
  Sun,
  Trash2,
  Upload,
  X,
  AlertCircle,
  FileUp,
  Layers,
} from 'lucide-react';
import './App.css';

const API_URL = process.env.REACT_APP_API_URL || 'http://localhost:8080/api';
if (!process.env.REACT_APP_API_URL) {
  // eslint-disable-next-line no-console
  console.error('[TimeAlert] REACT_APP_API_URL is not set. API calls will fall back to localhost — this will fail in production. Set the variable in frontend/.env before deploying.');
}

/* ─── Toast System ──────────────────────────────────────── */
let toastId = 0;

function useToasts() {
  const [toasts, setToasts] = useState([]);

  const dismissToast = useCallback((id) => {
    setToasts(prev =>
      prev.map(t => t.id === id ? { ...t, exiting: true } : t)
    );
    setTimeout(() => {
      setToasts(prev => prev.filter(t => t.id !== id));
    }, 280);
  }, []);

  // dismissToast is declared above and included in deps so addToast never
  // captures a stale closure — removing the eslint suppress that was masking this.
  const addToast = useCallback((type, title, message) => {
    const id = ++toastId;
    setToasts(prev => [...prev, { id, type, title, message, exiting: false }]);
    setTimeout(() => dismissToast(id), 4500);
  }, [dismissToast]);

  return { toasts, addToast, dismissToast };
}

function ToastContainer({ toasts, onDismiss }) {
  const icons = {
    success: <CheckCircle2 size={16} />,
    error:   <AlertCircle size={16} />,
    info:    <Bell size={16} />,
  };
  return (
    <div className="toast-container">
      {toasts.map(t => (
        <div key={t.id} className={`toast ${t.type}${t.exiting ? ' exiting' : ''}`}>
          <div className="toast-icon">{icons[t.type] || icons.info}</div>
          <div className="toast-body">
            {t.title   && <div className="toast-title">{t.title}</div>}
            {t.message && <div className="toast-message">{t.message}</div>}
          </div>
          <button className="toast-close" onClick={() => onDismiss(t.id)}>
            <X size={14} />
          </button>
        </div>
      ))}
    </div>
  );
}

/* ─── Skeleton loader ────────────────────────────────────── */
function ScheduleSkeleton() {
  return (
    <div>
      {[1, 2, 3].map(i => (
        <div key={i} className="skeleton skeleton-event" />
      ))}
    </div>
  );
}

/* ─── Empty state ────────────────────────────────────────── */
function EmptyState({ searching }) {
  return (
    <div className="empty-state">
      <div className="empty-state-icon">
        {searching ? <Search size={32} /> : <Calendar size={32} />}
      </div>
      <h3>{searching ? 'No matching events' : 'No events yet'}</h3>
      <p>
        {searching
          ? 'Try adjusting your search query.'
          : 'Upload a CSV, Excel, or PDF timetable above to populate your schedule.'}
      </p>
    </div>
  );
}

/* ─── Main App ───────────────────────────────────────────── */
function App() {
  const [token,     setToken]     = useState(localStorage.getItem('token') || null);
  const [userEmail, setUserEmail] = useState(localStorage.getItem('email') || '');
  const [userName,  setUserName]  = useState(localStorage.getItem('name')  || '');
  const [isDemo,    setIsDemo]    = useState(localStorage.getItem('isDemo') === 'true');

  // Auth form state
  const [isRegistering, setIsRegistering] = useState(false);
  const [email,    setEmail]    = useState('');
  const [password, setPassword] = useState('');
  const [name,     setName]     = useState('');
  const [authError, setAuthError] = useState('');

  // App state
  const [file,        setFile]        = useState(null);
  const [events,      setEvents]      = useState([]);
  const [loading,     setLoading]     = useState(false);
  const [fetching,    setFetching]    = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [darkMode,    setDarkMode]    = useState(() => {
    try { return localStorage.getItem('darkMode') === 'true'; }
    catch { return false; }
  });
  const [now,         setNow]         = useState(new Date());

  const { toasts, addToast, dismissToast } = useToasts();
  const fileInputRef = useRef(null);

  // Persist dark mode
  useEffect(() => {
    localStorage.setItem('darkMode', darkMode);
    document.body.classList.toggle('dark', darkMode);
  }, [darkMode]);

  // Axios auth header
  useEffect(() => {
    if (token) {
      axios.defaults.headers.common['Authorization'] = `Bearer ${token}`;
    } else {
      delete axios.defaults.headers.common['Authorization'];
    }
  }, [token]);

  const handleLogout = useCallback(() => {
    localStorage.removeItem('token');
    localStorage.removeItem('email');
    localStorage.removeItem('name');
    localStorage.removeItem('isDemo');
    setToken(null);
    setUserEmail('');
    setUserName('');
    setIsDemo(false);
    setEvents([]);
  }, []);

  const fetchEvents = useCallback(async () => {
    if (!token) return;
    setFetching(true);
    try {
      const response = await axios.get(`${API_URL}/events`);
      setEvents(response.data);
    } catch (error) {
      if (error.response && (error.response.status === 401 || error.response.status === 403)) {
        handleLogout();
      } else {
        addToast('error', 'Connection error', 'Could not load events. Is the server running?');
      }
    } finally {
      setFetching(false);
    }
  }, [token, handleLogout, addToast]);

  useEffect(() => {
    if (token) fetchEvents();
  }, [token, fetchEvents]);

  // Live clock tick every 30s
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 30000);
    return () => clearInterval(timer);
  }, []);

  /* ── Auth ─────────────────────────────────────────────── */
  const handleAuthSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);
    setAuthError('');

    if (!email || !email.includes('@')) {
      setAuthError('Please enter a valid email address.');
      setLoading(false);
      return;
    }
    if (password.length < 6) {
      setAuthError('Password must be at least 6 characters.');
      setLoading(false);
      return;
    }
    if (isRegistering && name.trim().length < 2) {
      setAuthError('Name must be at least 2 characters.');
      setLoading(false);
      return;
    }

    try {
      const endpoint = isRegistering ? '/auth/register' : '/auth/login';
      const payload  = isRegistering ? { email, password, name } : { email, password };
      const response = await axios.post(`${API_URL}${endpoint}`, payload);
      const { token: resToken, email: resEmail, name: resName } = response.data;

      localStorage.setItem('token', resToken);
      localStorage.setItem('email', resEmail);
      localStorage.setItem('name',  resName);

      setToken(resToken);
      setUserEmail(resEmail);
      setUserName(resName);
      setEmail('');
      setPassword('');
      setName('');
    } catch (error) {
      if (!error.response) {
        setAuthError('Cannot connect to the server. Please check your connection or try again later.');
      } else if (error.response.status === 429) {
        setAuthError('Too many attempts. Please wait a minute and try again.');
      } else if (error.response.data?.message) {
        setAuthError(error.response.data.message);
      } else {
        setAuthError('Authentication failed. Please try again.');
      }
    }
    setLoading(false);
  };

  /* ── Guest demo login ─────────────────────────────────── */
  const handleDemoLogin = async () => {
    setLoading(true);
    setAuthError('');
    try {
      // No body needed — backend /demo takes no request body.
      // Read isDemo from the response instead of hard-coding an email check.
      const response = await axios.post(`${API_URL}/auth/demo`);
      const { token: resToken, email: resEmail, name: resName, isDemo: resIsDemo } = response.data;

      localStorage.setItem('token',  resToken);
      localStorage.setItem('email',  resEmail);
      localStorage.setItem('name',   resName);
      localStorage.setItem('isDemo', resIsDemo ? 'true' : 'false');

      setToken(resToken);
      setUserEmail(resEmail);
      setUserName(resName);
      setIsDemo(!!resIsDemo);
    } catch (error) {
      if (!error.response) {
        setAuthError('Cannot connect to the server. Please check your connection or try again later.');
      } else if (error.response.status === 429) {
        setAuthError('Too many attempts. Please wait a minute and try again.');
      } else {
        setAuthError('Could not start the demo. Please try again.');
      }
    }
    setLoading(false);
  };

  /* ── Upload ───────────────────────────────────────────── */
  const handleFileChange = (e) => {
    setFile(e.target.files[0]);
  };

  const handleUpload = async () => {
    if (!file) {
      addToast('error', 'No file selected', 'Please choose a file before uploading.');
      return;
    }
    setLoading(true);
    const formData = new FormData();
    formData.append('file', file);
    try {
      const response = await axios.post(`${API_URL}/upload`, formData);
      const msg = response.data?.message || 'Upload successful!';
      addToast('success', 'Upload complete', msg);
      setFile(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
      fetchEvents();
    } catch (error) {
      if (!error.response) {
        addToast('error', 'Upload failed', 'Cannot connect to the server.');
      } else {
        addToast('error', 'Upload failed', error.response.data?.message || 'Check file format and try again.');
      }
    }
    setLoading(false);
  };

  /* ── Clear all ────────────────────────────────────────── */
  const handleClear = async () => {
    if (!window.confirm('Are you sure? All events will be permanently deleted.')) return;
    try {
      await axios.delete(`${API_URL}/events/clear`);
      setEvents([]);
      setFile(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
      addToast('success', 'Schedule cleared', 'All events have been removed.');
    } catch (error) {
      if (!error.response) {
        addToast('error', 'Clear failed', 'Cannot connect to the server.');
      } else {
        addToast('error', 'Clear failed', error.response.data?.message || 'Please try again.');
      }
    }
  };

  /* ── Delete single event ──────────────────────────────── */
  const handleDeleteEvent = async (id) => {
    if (!window.confirm('Delete this event?')) return;
    try {
      await axios.delete(`${API_URL}/events/${id}`);
      setEvents(prev => prev.filter(e => e.id !== id));
    } catch (error) {
      if (error.response?.status === 403) {
        addToast('error', 'Permission denied', 'You do not own this event.');
      } else {
        addToast('error', 'Delete failed', 'Please try again.');
      }
    }
  };

  /* ── Status logic ─────────────────────────────────────── */
  const getEventStatus = (eventDate, startTime, endTime) => {
    const today = [
      now.getFullYear(),
      String(now.getMonth() + 1).padStart(2, '0'),
      String(now.getDate()).padStart(2, '0'),
    ].join('-');

    if (eventDate !== today) return null;

    const [startH, startM] = startTime.split(':').map(Number);
    const [endH,   endM]   = endTime.split(':').map(Number);

    const start = new Date(); start.setHours(startH, startM, 0);
    const end   = new Date(); end.setHours(endH,   endM,   0);

    const diffMs   = start - now;
    const diffMins = Math.floor(diffMs / 60000);

    if (now >= end)          return { type: 'done',    label: 'Completed' };
    if (now >= start)        return { type: 'ongoing', label: 'Ongoing' };
    if (diffMins <= 60)      return { type: 'soon',    label: `Starts in ${diffMins}m` };
    const hrs  = Math.floor(diffMins / 60);
    const mins = diffMins % 60;
    return { type: 'upcoming', label: `Starts in ${hrs}h ${mins}m` };
  };

  /* ── Filtering & grouping ─────────────────────────────── */
  const filteredEvents = events.filter(event =>
    [event.title, event.location, event.description].some(
      field => field?.toLowerCase().includes(searchQuery.toLowerCase())
    )
  );

  const groupedEvents = filteredEvents.reduce((groups, event) => {
    const date = event.eventDate;
    if (!groups[date]) groups[date] = [];
    groups[date].push(event);
    return groups;
  }, {});

  const sortedDates = Object.keys(groupedEvents).sort();

  // User initials for avatar.
  // filter(Boolean) guards against leading/trailing/double spaces that produce
  // empty string segments — n[0] on '' returns undefined which would render as "U".
  const initials = userName
    ? userName.split(' ').filter(Boolean).map(n => n[0]).slice(0, 2).join('').toUpperCase()
    : userEmail.slice(0, 2).toUpperCase();

  /* ── Format date label ────────────────────────────────── */
  const formatDateLabel = (dateStr) => {
    const d = new Date(dateStr + 'T00:00:00');
    const todayStr = [
      now.getFullYear(),
      String(now.getMonth() + 1).padStart(2, '0'),
      String(now.getDate()).padStart(2, '0'),
    ].join('-');
    // Clone the `now` state value (not wall-clock new Date()) so tomorrowDate is
    // consistent with how today was computed above — avoids a 30-second drift window.
    const tomorrowDate = new Date(now);
    tomorrowDate.setDate(now.getDate() + 1);
    const tomorrowStr = [
      tomorrowDate.getFullYear(),
      String(tomorrowDate.getMonth() + 1).padStart(2, '0'),
      String(tomorrowDate.getDate()).padStart(2, '0'),
    ].join('-');

    const longDate = d.toLocaleDateString('en-US', {
      weekday: 'long', year: 'numeric', month: 'long', day: 'numeric',
    });

    if (dateStr === todayStr)     return { label: longDate, isToday: true };
    if (dateStr === tomorrowStr)  return { label: longDate, isToday: false, isTomorrow: true };
    return { label: longDate, isToday: false };
  };

  /* ════════════════════════════════════════════════════════
     AUTH PAGE
  ════════════════════════════════════════════════════════ */
  if (!token) {
    return (
      <div className={`auth-page${darkMode ? ' dark' : ''}`}>
        <div className="auth-container">

          {/* Header */}
          <div className="auth-header">
            <div className="auth-logo">
              <Bell size={24} />
            </div>
            <h1>TimeAlert</h1>
            <p>Smart timetable reminders, right in your inbox</p>
          </div>

          {/* Dark mode toggle */}
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '12px' }}>
            <button className="dark-toggle-btn" onClick={() => setDarkMode(!darkMode)} title="Toggle theme">
              <span className="dark-toggle-icon">
                {darkMode ? <Sun size={16} /> : <Moon size={16} />}
              </span>
            </button>
          </div>

          <div className="auth-card">
            {/* Mode switcher */}
            <div className="auth-mode-switcher">
              <button
                className={`auth-mode-btn${!isRegistering ? ' active' : ''}`}
                onClick={() => { setIsRegistering(false); setAuthError(''); }}
                type="button"
              >
                Log in
              </button>
              <button
                className={`auth-mode-btn${isRegistering ? ' active' : ''}`}
                onClick={() => { setIsRegistering(true); setAuthError(''); }}
                type="button"
              >
                Sign up
              </button>
            </div>

            <form onSubmit={handleAuthSubmit} className="auth-form">
              {isRegistering && (
                <div className="field-group">
                  <label className="field-label" htmlFor="auth-name">Full name</label>
                  <input
                    id="auth-name"
                    type="text"
                    placeholder="Jane Smith"
                    value={name}
                    onChange={e => setName(e.target.value)}
                    className="field-input"
                    autoComplete="name"
                    required
                  />
                </div>
              )}

              <div className="field-group">
                <label className="field-label" htmlFor="auth-email">Email address</label>
                <input
                  id="auth-email"
                  type="email"
                  placeholder="you@example.com"
                  value={email}
                  onChange={e => setEmail(e.target.value)}
                  className="field-input"
                  autoComplete="email"
                  required
                />
              </div>

              <div className="field-group">
                <label className="field-label" htmlFor="auth-password">Password</label>
                <input
                  id="auth-password"
                  type="password"
                  placeholder="••••••••"
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  className="field-input"
                  autoComplete={isRegistering ? 'new-password' : 'current-password'}
                  required
                />
              </div>

              {authError && (
                <div className="auth-error">
                  <AlertCircle size={14} style={{ flexShrink: 0, marginTop: 1 }} />
                  {authError}
                </div>
              )}

              <button type="submit" className="btn btn-primary auth-submit" disabled={loading}>
                {loading ? (
                  <><span className="btn-spinner" /> Processing…</>
                ) : isRegistering ? 'Create account' : 'Log in'}
              </button>
            </form>

            <div className="auth-divider">
              <span>or</span>
            </div>

            <button
              type="button"
              className="btn btn-secondary auth-demo-btn"
              onClick={handleDemoLogin}
              disabled={loading}
            >
              👀 Try the live demo (no signup needed)
            </button>

            <div className="auth-footer">
              {isRegistering ? (
                <>Already have an account?{' '}
                  <button className="auth-link" type="button"
                    onClick={() => { setIsRegistering(false); setAuthError(''); }}>
                    Log in
                  </button>
                </>
              ) : (
                <>Don't have an account?{' '}
                  <button className="auth-link" type="button"
                    onClick={() => { setIsRegistering(true); setAuthError(''); }}>
                    Sign up
                  </button>
                </>
              )}
            </div>
          </div>
        </div>

        <ToastContainer toasts={toasts} onDismiss={dismissToast} />
      </div>
    );
  }

  /* ════════════════════════════════════════════════════════
     MAIN DASHBOARD
  ════════════════════════════════════════════════════════ */
  return (
    <div className={`app${darkMode ? ' dark' : ''}`}>

      {/* ── Top bar ───────────────────────────────────────── */}
      <nav className="topbar">
        <div className="topbar-brand">
          <div className="brand-icon">
            <Bell size={18} />
          </div>
          <h1>TimeAlert</h1>
        </div>

        <div className="topbar-right">
          {/* Dark mode toggle */}
          <button
            className="dark-toggle-btn"
            onClick={() => setDarkMode(!darkMode)}
            title="Toggle theme"
          >
            <span className="dark-toggle-icon">
              {darkMode ? <Sun size={15} /> : <Moon size={15} />}
            </span>
          </button>

          {/* User pill */}
          <div className="user-pill">
            <div className="user-avatar">{initials}</div>
            <span className="user-pill-name">{userName || userEmail}</span>
          </div>

          {/* Logout */}
          <button
            className="icon-btn danger"
            onClick={handleLogout}
            title="Log out"
          >
            <LogOut size={15} />
          </button>
        </div>
      </nav>

      {isDemo && (
        <div className="demo-banner">
          👋 You're in guest demo mode — sample data only, no real emails are sent. Sign up for a real account to get live notifications.
        </div>
      )}

      {/* ── Upload section ────────────────────────────────── */}
      <div className="section-card">
        <div className="section-header">
          <div className="section-icon"><FileUp size={16} /></div>
          <h2>Upload Timetable</h2>
        </div>

        <div className="upload-box">
          <input
            type="file"
            accept=".csv,.xlsx,.xls,.pdf"
            onChange={handleFileChange}
            id="fileInput"
            ref={fileInputRef}
          />
          <label
            htmlFor="fileInput"
            className={`file-label${file ? ' has-file' : ''}`}
          >
            <Upload size={16} />
            {file ? file.name : 'Choose file — CSV, Excel, or PDF'}
          </label>

          <div className="upload-actions">
            <button
              className="btn btn-primary"
              onClick={handleUpload}
              disabled={loading}
              id="upload-btn"
            >
              {loading ? (
                <><span className="btn-spinner" /> Uploading…</>
              ) : (
                <><Upload size={15} /> Upload & Set Notifications</>
              )}
            </button>

            <button
              className="btn btn-danger-ghost"
              onClick={handleClear}
              id="clear-btn"
              title="Delete all events"
            >
              <Trash2 size={15} />
              Clear all
            </button>
          </div>
        </div>
      </div>

      {/* ── Schedule section ──────────────────────────────── */}
      <div className="section-card">
        {/* Title row + search */}
        <div className="schedule-title-row">
          <div className="section-header" style={{ marginBottom: 0 }}>
            <div className="section-icon"><Calendar size={16} /></div>
            <h2>Your Schedule</h2>
          </div>
          <span className="search-count">
            {events.length} event{events.length !== 1 ? 's' : ''}
          </span>
        </div>

        {/* Search bar */}
        <div className="search-wrapper">
          <span className="search-icon-pos"><Search size={15} /></span>
          <input
            id="schedule-search"
            type="text"
            placeholder="Search by title, location or description…"
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            className="search-input"
          />
        </div>

        {searchQuery && (
          <div className="search-meta">
            <span className="search-count">
              {filteredEvents.length} result{filteredEvents.length !== 1 ? 's' : ''} for "{searchQuery}"
            </span>
          </div>
        )}

        {/* Events list */}
        {fetching ? (
          <ScheduleSkeleton />
        ) : sortedDates.length === 0 ? (
          <EmptyState searching={!!searchQuery} />
        ) : (
          sortedDates.map(date => {
            const { label, isToday } = formatDateLabel(date);
            const dayEvents = groupedEvents[date];

            return (
              <div key={date} className="day-group">
                {/* Sticky date header */}
                <div className="day-label">
                  <span className={`day-label-text${isToday ? ' day-label-today' : ''}`}>
                    {label}
                    {isToday && ' — Today'}
                  </span>
                  <span className="day-badge">
                    {dayEvents.length} {dayEvents.length === 1 ? 'event' : 'events'}
                  </span>
                </div>

                {/* Event cards */}
                <div className="events-stack">
                  {dayEvents.map(event => {
                    const status = getEventStatus(event.eventDate, event.startTime, event.endTime);
                    return (
                      <div
                        key={event.id}
                        className={`event-card${status ? ` status-${status.type}` : ''}`}
                      >
                        <div className="event-body">
                          <div className="event-title">{event.title}</div>
                          <div className="event-meta">
                            <span className="event-meta-item">
                              <Clock size={12} />
                              {event.startTime} – {event.endTime}
                            </span>
                            {event.location && (
                              <span className="event-meta-item">
                                <Layers size={12} />
                                {event.location}
                              </span>
                            )}
                          </div>
                          {event.description && (
                            <div className="event-desc">{event.description}</div>
                          )}
                          {status && (
                            <div className={`status-badge ${status.type}`} style={{ marginTop: 8 }}>
                              {status.type === 'done' && <CheckCircle2 size={11} />}
                              {status.label}
                            </div>
                          )}
                        </div>

                        <div className="event-actions">
                          <button
                            className="icon-btn danger"
                            onClick={() => handleDeleteEvent(event.id)}
                            title="Delete event"
                            id={`delete-event-${event.id}`}
                          >
                            <Trash2 size={13} />
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            );
          })
        )}
      </div>

      <ToastContainer toasts={toasts} onDismiss={dismissToast} />
    </div>
  );
}

export default App;