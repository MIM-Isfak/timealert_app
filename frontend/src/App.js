import React, { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import './App.css';

const API_URL = process.env.REACT_APP_API_URL || 'http://localhost:8080/api';
const PUBLIC_KEY = process.env.REACT_APP_VAPID_PUBLIC_KEY;

function urlBase64ToUint8Array(base64String) {
  const padding = '='.repeat((4 - base64String.length % 4) % 4);
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
  const rawData = window.atob(base64);
  const outputArray = new Uint8Array(rawData.length);
  for (let i = 0; i < rawData.length; ++i) {
    outputArray[i] = rawData.charCodeAt(i);
  }
  return outputArray;
}

function App() {
  const [file, setFile] = useState(null);
  const [events, setEvents] = useState([]);
  const [message, setMessage] = useState('');
  const [loading, setLoading] = useState(false);
  const [notifStatus, setNotifStatus] = useState('');
  const [searchQuery, setSearchQuery] = useState('');
  const [darkMode, setDarkMode] = useState(false);
  const [now, setNow] = useState(new Date());

  const subscribeUser = useCallback(async (reg) => {
    try {
      if (!PUBLIC_KEY) {
        setNotifStatus('⚠️ VAPID key not configured');
        return;
      }
      const permission = await Notification.requestPermission();
      if (permission !== 'granted') {
        setNotifStatus('❌ Notification permission denied!');
        return;
      }
      const subscription = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(PUBLIC_KEY)
      });
      const subData = subscription.toJSON();
      await axios.post(`${API_URL}/notifications/subscribe`, {
        endpoint: subData.endpoint,
        p256dh: subData.keys.p256dh,
        auth: subData.keys.auth
      });
      setNotifStatus('✅ Notifications enabled!');
    } catch (error) {
      setNotifStatus('⚠️ Notification setup failed');
    }
  }, []);

  const registerServiceWorker = useCallback(async () => {
    if ('serviceWorker' in navigator && 'PushManager' in window) {
      try {
        const reg = await navigator.serviceWorker.register('/service-worker.js');
        await subscribeUser(reg);
      } catch (error) {
        console.log('Service Worker error:', error);
      }
    }
  }, [subscribeUser]);

  const fetchEvents = async () => {
    try {
      const response = await axios.get(`${API_URL}/events`);
      setEvents(response.data);
    } catch (error) {
      setMessage('⚠️ Could not load events. Is the server running?');
    }
  };

  useEffect(() => {
    fetchEvents();
    registerServiceWorker();
    const timer = setInterval(() => setNow(new Date()), 30000);
    return () => clearInterval(timer);
  }, [registerServiceWorker]);

  const handleFileChange = (e) => {
    setFile(e.target.files[0]);
    setMessage('');
  };

  const handleUpload = async () => {
    if (!file) {
      setMessage('Please select a file first!');
      return;
    }
    setLoading(true);
    const formData = new FormData();
    formData.append('file', file);
    try {
      const response = await axios.post(`${API_URL}/upload`, formData);
      setMessage(response.data);
      fetchEvents();
    } catch (error) {
      setMessage('Upload failed! Check file format.');
    }
    setLoading(false);
  };

  const handleClear = async () => {
    if (!window.confirm('Are you sure? All events will be deleted!')) return;
    try {
      await axios.delete(`${API_URL}/events/clear`);
      setEvents([]);
      setMessage('✅ All events cleared!');
      setFile(null);
    } catch (error) {
      setMessage('Clear failed!');
    }
  };

  const handleDeleteEvent = async (id) => {
    if (!window.confirm('Delete this event?')) return;
    try {
      await axios.delete(`${API_URL}/events/${id}`);
      setEvents(events.filter(e => e.id !== id));
    } catch (error) {
      setMessage('❌ Delete failed!');
    }
  };

  const getEventStatus = (eventDate, startTime, endTime) => {
    const today = now.getFullYear() + '-' +
      String(now.getMonth() + 1).padStart(2, '0') + '-' +
      String(now.getDate()).padStart(2, '0');

    if (eventDate !== today) return null;

    const [startH, startM] = startTime.split(':').map(Number);
    const [endH, endM] = endTime.split(':').map(Number);

    const start = new Date();
    start.setHours(startH, startM, 0);

    const end = new Date();
    end.setHours(endH, endM, 0);

    const diffMs = start - now;
    const diffMins = Math.floor(diffMs / 60000);

    if (now >= end) {
      return { type: 'done', label: '✅ Completed' };
    } else if (now >= start) {
      return { type: 'ongoing', label: '🟢 Ongoing now' };
    } else if (diffMins <= 60) {
      return { type: 'soon', label: `⏳ Starts in ${diffMins}m` };
    } else {
      const hrs = Math.floor(diffMins / 60);
      const mins = diffMins % 60;
      return { type: 'upcoming', label: `🕐 Starts in ${hrs}h ${mins}m` };
    }
  };

  const filteredEvents = events.filter(event =>
    event.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
    event.location.toLowerCase().includes(searchQuery.toLowerCase()) ||
    event.description.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const groupByDate = (events) => {
    return events.reduce((groups, event) => {
      const date = event.eventDate;
      if (!groups[date]) groups[date] = [];
      groups[date].push(event);
      return groups;
    }, {});
  };

  const groupedEvents = groupByDate(filteredEvents);

  return (
    <div className={`app ${darkMode ? 'dark' : ''}`}>

      <div className="header">
        <h1>TimeAlert App</h1>
        <p>Upload your timetable and get notified!</p>
        <button
          className="dark-toggle"
          onClick={() => setDarkMode(!darkMode)}
        >
          {darkMode ? '☀️ Light Mode' : '🌙 Dark Mode'}
        </button>
        {notifStatus && (
          <div className="notif-status">{notifStatus}</div>
        )}
      </div>

      <div className="upload-section">
        <h2>📁 Upload Timetable</h2>
        <div className="upload-box">
          <input
            type="file"
            accept=".csv,.xlsx,.xls,.pdf"
            onChange={handleFileChange}
            id="fileInput"
          />
          <label htmlFor="fileInput" className="file-label">
            {file ? file.name : 'Choose File (CSV / Excel / PDF)'}
          </label>
          <button
            className="upload-btn"
            onClick={handleUpload}
            disabled={loading}
          >
            {loading ? (
              <span className="spinner-row">
                <span className="spinner"></span> Uploading...
              </span>
            ) : 'Upload & Set Notifications'}
          </button>
          <button className="clear-btn" onClick={handleClear}>
            Clear All Events
          </button>
        </div>
        {message && (
          <div className={`message ${message.includes('failed') || message.includes('❌') || message.includes('⚠️') ? 'error' : 'success'}`}>
            {message}
          </div>
        )}
      </div>

      <div className="search-section">
        <input
          type="text"
          placeholder="🔍 Search by title, location or description..."
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          className="search-input"
        />
        {searchQuery && (
          <span className="search-count">
            {filteredEvents.length} event(s) found
          </span>
        )}
      </div>

      <div className="schedule-section">
        <h2>📅 Your Schedule</h2>
        {Object.keys(groupedEvents).length === 0 ? (
          <div className="empty">
            <p>{searchQuery
              ? '🔍 No events match your search!'
              : 'No events yet. Upload a timetable to get started!'}
            </p>
          </div>
        ) : (
          Object.keys(groupedEvents).sort().map(date => (
            <div key={date} className="day-card">
              <div className="day-header">
                📆 {new Date(date).toLocaleDateString('en-US', {
                  weekday: 'long',
                  year: 'numeric',
                  month: 'long',
                  day: 'numeric'
                })}
                <span className="event-count">
                  {groupedEvents[date].length} events
                </span>
              </div>
              <div className="events-list">
                {groupedEvents[date].map(event => (
                  <div key={event.id} className="event-card">
                    <div className="event-header-row">
                      <div className="event-title">{event.title}</div>
                      <button
                        className="delete-event-btn"
                        onClick={() => handleDeleteEvent(event.id)}
                        title="Delete event"
                      >
                        Delete
                      </button>
                    </div>
                    <div className="event-details">
                      🕐 {event.startTime} - {event.endTime}
                      &nbsp;&nbsp;{event.location}
                    </div>
                    <div className="event-desc">{event.description}</div>
                    {(() => {
                      const status = getEventStatus(
                        event.eventDate,
                        event.startTime,
                        event.endTime
                      );
                      if (!status) return null;
                      return (
                        <div className={`event-status ${status.type}`}>
                          {status.label}
                        </div>
                      );
                    })()}
                  </div>
                ))}
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}

export default App;