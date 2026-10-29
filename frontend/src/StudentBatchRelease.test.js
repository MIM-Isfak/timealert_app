import React from 'react';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import axios from 'axios';
import StudentBatchView from './StudentBatchView';
jest.mock('axios');
const props={apiUrl:'https://api.example.test/api',token:'test-session',onToast:jest.fn()};
const membership={id:1,batchId:10,batchName:'Pilot Batch',status:'APPROVED',timezone:'Asia/Colombo'};
beforeEach(()=>{jest.resetAllMocks();URL.createObjectURL=jest.fn(()=> 'blob:calendar-test');URL.revokeObjectURL=jest.fn();});
afterEach(()=>{jest.useRealTimers();jest.restoreAllMocks();});

test('approved student switches today/week and sees a ticking next-event countdown', async()=>{
  jest.useFakeTimers('modern');jest.setSystemTime(new Date('2030-06-03T04:00:00Z'));
  axios.get.mockResolvedValueOnce({data:[membership]}).mockResolvedValueOnce({data:[
    {id:1,title:'Today class',eventDate:'2030-06-03',startTime:'10:00',endTime:'11:00',startsAt:'2030-06-03T04:30:00Z',endsAt:'2030-06-03T05:30:00Z',status:'PUBLISHED'},
    {id:2,title:'Tomorrow exam',eventDate:'2030-06-04',startTime:'10:00',endTime:'11:00',startsAt:'2030-06-04T04:30:00Z',endsAt:'2030-06-04T05:30:00Z',status:'PUBLISHED'},
  ]});
  render(<StudentBatchView {...props}/>);
  await screen.findByText('Tomorrow exam');
  expect(screen.getByText(/in 0h 30m 0s/)).toBeInTheDocument();act(()=>jest.advanceTimersByTime(1000));expect(screen.getByText(/in 0h 29m 59s/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button',{name:'Today'}));expect(screen.queryByText('Tomorrow exam')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button',{name:'This week'}));expect(screen.getByText('Tomorrow exam')).toBeInTheDocument();
});
test('calendar downloads with authentication and revokes its temporary object URL',async()=>{
  jest.useFakeTimers();const click=jest.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{});
  const blob=new Blob(['BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n'],{type:'text/calendar'});
  axios.get.mockResolvedValueOnce({data:[membership]}).mockResolvedValueOnce({data:[]}).mockResolvedValueOnce({data:blob});
  render(<StudentBatchView {...props}/>);fireEvent.click(await screen.findByRole('button',{name:/export calendar/i}));
  await waitFor(()=>expect(click).toHaveBeenCalledTimes(1));
  expect(axios.get).toHaveBeenLastCalledWith(`${props.apiUrl}/student/batches/10/timetable.ics`,{headers:{Authorization:`Bearer ${props.token}`},responseType:'blob'});
  expect(URL.createObjectURL).toHaveBeenCalledWith(blob);act(()=>jest.advanceTimersByTime(1000));expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:calendar-test');
});
test('failed authorization does not create or download a calendar',async()=>{
  axios.get.mockResolvedValueOnce({data:[membership]}).mockResolvedValueOnce({data:[]}).mockRejectedValueOnce({response:{status:403}});
  render(<StudentBatchView {...props}/>);fireEvent.click(await screen.findByRole('button',{name:/export calendar/i}));
  await waitFor(()=>expect(props.onToast).toHaveBeenCalledWith('error','Calendar export failed',expect.any(String)));expect(URL.createObjectURL).not.toHaveBeenCalled();
});
