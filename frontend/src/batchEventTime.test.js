import { batchDate, groupBatchEvents, countdownLabel } from './batchEventTime';

const event = (id, date, startsAt, status = 'PUBLISHED') => ({ id, title: `Event ${id}`, eventDate: date, startsAt, endsAt: new Date(Date.parse(startsAt) + 3600000).toISOString(), status });

test('grouping uses batch midnight rather than browser or UTC date', () => {
  const now = new Date('2030-06-02T20:00:00Z'); // Monday in Colombo; Sunday in UTC.
  expect(batchDate(now, 'Asia/Colombo')).toBe('2030-06-03');
  const groups = groupBatchEvents([event(1,'2030-06-02','2030-06-02T14:00:00Z'), event(2,'2030-06-03','2030-06-03T04:00:00Z')],now);
  expect(groups.today.map(e => e.id)).toEqual([2]);expect(groups.week.map(e => e.id)).toEqual([2]);
});
test('week starts Monday and ends Sunday, including earlier events in the selected week', () => {
  const now = new Date('2030-06-05T06:00:00Z');
  const events = [event(1,'2030-06-03','2030-06-03T04:00:00Z'),event(2,'2030-06-09','2030-06-09T04:00:00Z'),event(3,'2030-06-10','2030-06-10T04:00:00Z')];
  const groups = groupBatchEvents(events,now);expect(groups.week.map(e => e.id)).toEqual([1,2]);expect(groups.upcoming.map(e => e.id)).toEqual([2,3]);
});
test('countdown selects the earliest future published instant, excluding drafts, cancellation and started events', () => {
  const now = new Date('2030-06-03T04:00:00Z');
  const events = [event(1,'2030-06-03','2030-06-03T06:00:00Z'),event(2,'2030-06-03','2030-06-03T04:30:00Z'),event(3,'2030-06-03','2030-06-03T04:01:00Z','CANCELLED'),event(4,'2030-06-03','2030-06-03T04:02:00Z','DRAFT'),event(5,'2030-06-03','2030-06-03T03:30:00Z')];
  const groups=groupBatchEvents(events,now);expect(groups.next.id).toBe(2);expect(groups.today.map(e => e.id)).not.toContain(4);expect(groups.today.map(e => e.id)).toContain(3);
  expect(countdownLabel(groups.next.startsAt,now)).toBe('0h 30m 0s');
});
test('countdown handles day boundaries and never shows negative durations', () => {
  const now=new Date('2030-06-03T23:59:59Z');
  expect(countdownLabel('2030-06-05T01:02:03Z',now)).toBe('1d 1h 2m 4s');expect(countdownLabel('2030-06-03T23:59:58Z',now)).toBe('0h 0m 0s');
});
test('UTC instants handle a daylight saving transition and empty calendars', () => {
  const now=new Date('2030-03-10T06:30:00Z');
  expect(countdownLabel('2030-03-10T07:30:00Z',now)).toBe('1h 0m 0s');expect(groupBatchEvents([],now,'America/New_York').next).toBeNull();
});
