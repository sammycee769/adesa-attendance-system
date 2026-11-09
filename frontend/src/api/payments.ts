import { apiRequest } from './client';
export interface PaymentConfig {
 owner_employee_id?: string|null; owner_name?: string|null; owner_email?: string|null; id: string; code: string; name: string; amount: number; currency: 'NGN'|'USD'; estimated_ngn: string;
 scheduled_day: number; renewal_day: number|null; description: string; to_address: string; cc_addresses: string;
 subject: string; body: string; enabled: boolean; version: number; scheduled_date: string; adjusted_date: string;
 next_request_date: string; renewal_date: string|null; last_successful_send: string|null; upcoming: string[];
}
export interface PaymentMessage {from: string; to: string; cc: string; subject: string; text: string; html: string; signatureConfigured: boolean}
export interface PaymentRecord {id: string; name: string; billing_month: string; send_date: string; status: string; attempts: number; snapshot: string; sent_at: string|null; provider_message_id: string|null}
export interface DeliveryAttempt {id: string; request_id: string; attempt: number; started_at: string; finished_at: string|null; result: string; detail: string|null; provider_message_id: string|null}
export interface PaymentAudit {id: number; actor: string; action: string; target: string; detail: string; created_at: string}
export interface PaymentSettings {enabled: boolean; signature_html: string; transport: string; sender: string; timezone: string; sending_time: string}
export interface PaymentDashboard {payments: PaymentConfig[]; settings: PaymentSettings; history: PaymentRecord[]; attempts: DeliveryAttempt[]; audit: PaymentAudit[]; canManage?: boolean; employees?: {id:string;full_name:string;email:string}[]}
const base='/api/v1/admin/recurring-payments';
export const paymentApi={
 dashboard:(signal?:AbortSignal)=>apiRequest<PaymentDashboard>('/api/v1/recurring-payments',{signal}),
 save:(payment:PaymentConfig)=>apiRequest<void>(`${base}/${payment.id}`,{method:'PUT',body:{
  ownerEmployeeId:payment.owner_employee_id??null,name:payment.name,amount:payment.amount,currency:payment.currency,estimatedNgn:payment.estimated_ngn,
  scheduledDay:payment.scheduled_day,renewalDay:payment.renewal_day,description:payment.description,
  toAddress:payment.to_address,ccAddresses:payment.cc_addresses,subject:payment.subject,body:payment.body,enabled:payment.enabled,version:payment.version}}),
 create:(payment:PaymentConfig)=>apiRequest<{id:string}>(base,{method:'POST',body:{ownerEmployeeId:payment.owner_employee_id??null,name:payment.name,amount:payment.amount,currency:payment.currency,estimatedNgn:payment.estimated_ngn,scheduledDay:payment.scheduled_day,renewalDay:payment.renewal_day,description:payment.description,toAddress:payment.to_address,ccAddresses:payment.cc_addresses,subject:payment.subject,body:payment.body,enabled:payment.enabled,version:0}}),
 settings:(enabled:boolean,signatureHtml:string)=>apiRequest<void>(`${base}/settings`,{method:'PUT',body:{enabled,signatureHtml}}),
 preview:(id:string,month:string)=>apiRequest<PaymentMessage>(`/api/v1/recurring-payments/${id}/preview`,{method:'POST',body:{month}}),
 generate:(id:string,month:string)=>apiRequest<{id:string}>(`${base}/${id}/generate`,{method:'POST',body:{month}}),
 recover:(id:string,reason:string)=>apiRequest<void>(`${base}/requests/${id}/recover`,{method:'POST',body:{reason}}),
 reconcile:(id:string,accepted:boolean,providerId:string,reason:string)=>apiRequest<void>(`${base}/requests/${id}/reconcile`,{method:'POST',body:{accepted,providerId,reason}}),
};
