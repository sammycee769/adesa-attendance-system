import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { EventsLeadPage } from './EventsLeadPage';
const mocks=vi.hoisted(()=>({request:vi.fn(),download:vi.fn()}));
vi.mock('../../api/client',()=>({apiRequest:mocks.request,apiDownload:mocks.download}));
const event={id:'event-1',slug:'demo-test',name:'Demo event',demo:true,active:true,config:{publicUrl:'http://localhost:3000'}};
beforeEach(()=>{mocks.request.mockReset();mocks.request.mockImplementation(async(path:string)=>{
 if(path.endsWith('/access'))return{view:true,manage:false,redeem:false};
 if(path.endsWith('/events'))return[event];
 if(path.endsWith('/dashboard'))return{registrations:1,converted:0,byBusiness:[{interest:'ENERGY',count:1}],byGame:[],vouchers:{issued:0,redeemed:0,unredeemed:0,expired:0},prizes:[],messages:[]};
 if(path.endsWith('/participants'))return[{id:'p1',full_name:'Synthetic Person',email:'person@example.test',phone:'+2348012345678',interest:'ENERGY',marketing_consent:false,registered_at:'2026-11-26T10:00:00Z',converted:false}];
 return[];
 });});
it('renders event totals and QR without management controls for a viewer',async()=>{render(<EventsLeadPage/>);expect(await screen.findByText('Registrations')).toBeInTheDocument();expect(screen.getByRole('link',{name:/Download print-ready QR/})).toHaveAttribute('href','http://localhost:3000/api/campaign/qr?event=demo-test');expect(screen.getByRole('img',{name:'Registration QR code for Demo event'})).toHaveAttribute('src','http://localhost:3000/api/campaign/qr?event=demo-test&inline=1');expect(screen.getByRole('link',{name:/Open full-size QR display/})).toHaveAttribute('href','http://localhost:3000/play/demo-test/qr');expect(screen.queryByRole('button',{name:'Events'})).not.toBeInTheDocument();});
it('shows consent explicitly and allows participant search',async()=>{const user=userEvent.setup();render(<EventsLeadPage/>);await screen.findByText('Registrations');await user.click(screen.getByRole('button',{name:'Participants'}));expect(screen.getByText('No marketing')).toBeInTheDocument();await user.type(screen.getByRole('textbox',{name:'Search participants'}),'no matching name');expect(screen.queryByText('Synthetic Person')).not.toBeInTheDocument();});
it('does not grant redemption controls to a viewer',async()=>{const user=userEvent.setup();render(<EventsLeadPage/>);await screen.findByText('Registrations');await user.click(screen.getByRole('button',{name:'Redemption'}));expect(screen.getByRole('button',{name:'Verify'})).toBeInTheDocument();expect(screen.queryByRole('button',{name:'Confirm single-use redemption'})).not.toBeInTheDocument();});
it('opens the selected event automatically and can activate it directly',async()=>{
 const managed={...event,active:false,approved:true,description:'Demo',starts_at:'2030-01-01T09:00:00Z',ends_at:'2030-01-02T18:00:00Z',config:{games:['SPIN'],weights:{REGULAR:40,CONSULTATION:25,PREMIUM:20,GIFT:10,GRAND:5},validityDays:90,lowStockThreshold:3,termsVersion:'v1',terms:'One attempt',publicUrl:'http://localhost:3000',notifyEmail:'staff@example.test',questions:[]}};
 mocks.request.mockImplementation(async(path:string,options?:{method?:string;body?:any})=>{
  if(path.endsWith('/access'))return{view:true,manage:true,redeem:true};
  if(path===`/api/v1/campaign/staff/events/${event.id}`&&options?.method==='PUT'){managed.active=options.body.active;return;}
  if(path.endsWith('/events'))return[managed];
  if(path.endsWith('/dashboard'))return{registrations:0,byBusiness:[],byGame:[],vouchers:{},prizes:[{active:true,approved:true,remaining:2}],messages:[]};
  if(path.endsWith('/participants'))return[];
  return[];
 });
 const user=userEvent.setup();render(<EventsLeadPage/>);await screen.findByText('Registrations');await user.click(screen.getByRole('button',{name:'Events'}));
 expect(screen.getByLabelText('Event name')).toHaveValue('Demo event');
 await user.click(screen.getByRole('button',{name:'Activate event'}));
 expect(await screen.findByText(/Event activated\. Registration follows/)).toBeInTheDocument();
 expect(mocks.request).toHaveBeenCalledWith('/api/v1/campaign/staff/events/event-1',expect.objectContaining({method:'PUT',body:expect.objectContaining({active:true,id:'event-1'})}));
 expect(screen.getByRole('button',{name:'Deactivate event'})).toBeInTheDocument();
});
