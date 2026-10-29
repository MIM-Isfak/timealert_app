export function batchDate(now, timezone) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(now);
  const get = type => parts.find(part => part.type === type).value;
  return `${get('year')}-${get('month')}-${get('day')}`;
}

export function groupBatchEvents(events, now, timezone = 'Asia/Colombo') {
  const today = batchDate(now, timezone);
  const monday = new Date(`${today}T00:00:00Z`);
  monday.setUTCDate(monday.getUTCDate() - (monday.getUTCDay() + 6) % 7);
  const start = monday.toISOString().slice(0, 10);
  monday.setUTCDate(monday.getUTCDate() + 7);
  const end = monday.toISOString().slice(0, 10);
  const visible = events.filter(event => ['PUBLISHED', 'CANCELLED'].includes(event.status));
  const upcoming = visible.filter(event => event.status === 'CANCELLED' ? event.eventDate >= today :
    event.endsAt ? Date.parse(event.endsAt) > now.getTime() : event.eventDate >= today);
  const next = visible.filter(event => event.status === 'PUBLISHED' && Date.parse(event.startsAt) > now.getTime())
    .sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt))[0] || null;
  return { today: visible.filter(event => event.eventDate === today),
    week: visible.filter(event => event.eventDate >= start && event.eventDate < end), upcoming, next };
}

export function countdownLabel(startsAt, now) {
  let seconds = Math.max(0, Math.ceil((Date.parse(startsAt) - now.getTime()) / 1000));
  const days = Math.floor(seconds / 86400); seconds %= 86400;
  const hours = Math.floor(seconds / 3600); seconds %= 3600;
  const minutes = Math.floor(seconds / 60); seconds %= 60;
  return `${days ? `${days}d ` : ''}${hours}h ${minutes}m ${seconds}s`;
}
