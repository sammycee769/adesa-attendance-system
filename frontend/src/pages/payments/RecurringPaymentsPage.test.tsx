import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { RecurringPaymentsPage } from './RecurringPaymentsPage';
import { paymentApi, type PaymentDashboard } from '../../api/payments';
const auth=vi.hoisted(()=>({role:'ADMIN'}));
vi.mock('../../auth/AuthProvider',()=>({useAuth:()=>({user:{role:auth.role}})}));
vi.mock('../../api/payments',()=>({paymentApi:{dashboard:vi.fn(),settings:vi.fn(),preview:vi.fn(),generate:vi.fn(),save:vi.fn(),recover:vi.fn(),reconcile:vi.fn(),create:vi.fn()}}));
const data:PaymentDashboard={settings:{enabled:false,signature_html:'',transport:'mock',sender:'christian@adesahq.com',timezone:'Africa/Lagos',sending_time:'09:00'},payments:[{
 id:'zoho',code:'ZOHO',name:'Zoho Subscription Renewal',amount:72842,currency:'NGN',estimated_ngn:'',scheduled_day:16,renewal_day:null,description:'',to_address:'Finance Manager <finance@adesahq.com>',cc_addresses:'femi@adesahq.com, payment@adesahq.com, blessing@adesahq.com',subject:'Request for Payment – Zoho Subscription Renewal',body:'Dear Finance Team,\nBest regards,',enabled:true,version:0,scheduled_date:'2026-11-16',adjusted_date:'2026-11-16',next_request_date:'2026-11-16T09:00:00+01:00',renewal_date:null,last_successful_send:null,upcoming:['2026-11-16T09:00:00+01:00']}],history:[],attempts:[],audit:[]};
beforeEach(()=>{auth.role='ADMIN';vi.resetAllMocks();vi.mocked(paymentApi.dashboard).mockResolvedValue(data);});
it('displays disabled automation, recipients and the mock-only notice',async()=>{
 render(<RecurringPaymentsPage/>);
 expect(await screen.findByText('Zoho Subscription Renewal')).toBeInTheDocument();
 expect(screen.getByText('Automatic sending disabled')).toBeInTheDocument();
 expect(screen.getByText(/Preview delivery only/)).toBeInTheDocument();
 expect(screen.getByLabelText('Enable automatic sending')).not.toBeChecked();
 expect(screen.getByText('Finance Manager <finance@adesahq.com>')).toBeInTheDocument();
 expect(paymentApi.settings).not.toHaveBeenCalled();
});
it('previews in a sandbox and never queues delivery',async()=>{
 const user=userEvent.setup();vi.mocked(paymentApi.preview).mockResolvedValue({from:'christian@adesahq.com',to:'finance@adesahq.com',cc:'',subject:'Preview subject',text:'Best regards,',html:'<p>Existing signature</p>',signatureConfigured:true});
 render(<RecurringPaymentsPage/>);await user.click(await screen.findByRole('button',{name:'Preview email'}));
 await screen.findByRole('heading',{name:'Email preview'});
 expect(screen.getByTitle('Payment email preview')).toHaveAttribute('sandbox','');
 expect(screen.getByTitle('Payment email preview')).toHaveAttribute('srcdoc','<p>Existing signature</p>');
 expect(paymentApi.recover).not.toHaveBeenCalled();expect(paymentApi.settings).not.toHaveBeenCalled();
});
it('edits validated fields and saves through the existing API client',async()=>{
 const user=userEvent.setup();render(<RecurringPaymentsPage/>);await user.click(await screen.findByRole('button',{name:/^Edit$/}));
 const amount=screen.getByLabelText('Amount');await user.clear(amount);await user.type(amount,'73000');
 await user.click(screen.getByRole('button',{name:'Save changes'}));
 await waitFor(()=>expect(paymentApi.save).toHaveBeenCalledWith(expect.objectContaining({amount:73000,scheduled_day:16})));
});
it('shows API errors without claiming success',async()=>{
 vi.mocked(paymentApi.dashboard).mockRejectedValue(new Error('Access denied'));
 render(<RecurringPaymentsPage/>);expect(await screen.findByRole('alert')).toHaveTextContent('Access denied');
});

it('shows employee schedules without management controls',async()=>{
 auth.role='EMPLOYEE';render(<RecurringPaymentsPage/>);
 await screen.findByText('Zoho Subscription Renewal');
 expect(screen.queryByRole('button',{name:'Add recurring payment'})).not.toBeInTheDocument();
 expect(screen.queryByRole('button',{name:'Save automation settings'})).not.toBeInTheDocument();
 expect(screen.queryByRole('button',{name:/^Edit$/})).not.toBeInTheDocument();
 expect(screen.queryByRole('button',{name:'Record preview'})).not.toBeInTheDocument();
 expect(screen.getByRole('button',{name:'Preview email'})).toBeInTheDocument();
});
it('shows an empty employee dashboard without exposing admin assignments',async()=>{
 auth.role='EMPLOYEE';vi.mocked(paymentApi.dashboard).mockResolvedValue({...data,payments:[],canManage:false});
 render(<RecurringPaymentsPage/>);expect(await screen.findByText('No recurring payments have been assigned to you yet.')).toBeInTheDocument();
});
