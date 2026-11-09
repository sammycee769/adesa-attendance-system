import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AssetManagementPage } from './AssetPage';
import type { AssetRow } from '../../api/assets';

const mocks=vi.hoisted(()=>({request:vi.fn(),command:vi.fn(),list:vi.fn(),download:vi.fn(),user:{id:'employee',role:'ADMIN'}}));
vi.mock('../../auth/AuthProvider',()=>({useAuth:()=>({user:mocks.user})}));
vi.mock('../../api/client',async()=>{const actual=await vi.importActual('../../api/client');return {...actual,apiRequest:mocks.request,apiDownload:mocks.download};});
vi.mock('../../api/assets',async()=>{const actual=await vi.importActual('../../api/assets');return {...actual,listAssets:mocks.list,assetCommand:mocks.command};});
const asset:AssetRow={id:'asset',asset_code:'ADESA-LAP-0001',brand:'Local Brand',model:'Test model',serial_number:'TEST-SERIAL',purchase_date:'2020-01-01',condition:'GOOD',status:'AVAILABLE',employee_id:'employee',version:0};
let rows:AssetRow[]=[];
function renderPage(section:string) {return render(<MemoryRouter initialEntries={[`/assets/${section}`]}><Routes><Route path="/assets/:section" element={<AssetManagementPage/>}/></Routes></MemoryRouter>);}
async function dialog(button:string) {await userEvent.click(await screen.findByRole('button',{name:button}));return screen.getByRole('dialog');}
async function save() {await userEvent.click(screen.getByRole('button',{name:'Confirm and save'}));await waitFor(()=>expect(mocks.command).toHaveBeenCalled());}
beforeEach(()=>{
 rows=[];mocks.user={id:'employee',role:'ADMIN'};mocks.command.mockReset();mocks.command.mockResolvedValue({id:'new'});mocks.request.mockReset();mocks.list.mockReset();
 mocks.request.mockImplementation(async(path:string)=>{
  if(path.includes('/employees'))return [{id:'employee',full_name:'Synthetic Employee',email:'synthetic@example.test'}];
  if(path.endsWith('/subsidiaries'))return [{id:'subsidiary',name:'Local Subsidiary'}];
  if(path.endsWith('/dashboard'))return {pendingAssetRequests:0,totalAssets:3,availableAssets:2,assignedAssets:1,underRepair:0,damagedAssets:0,pendingReplacements:0,pendingReturns:0,pendingClearances:0,byType:[{label:'LAPTOP',value:3}],byStatus:[],byCondition:[],bySubsidiary:[],monthlyRepairs:[],recentActivity:[]};
  return [];
 });
 mocks.list.mockImplementation(async(section:string)=>({content:section==='inventory'||section==='available'?[asset]:rows,totalElements:section==='inventory'||section==='available'?1:rows.length,page:0,size:20}));
});
describe('IT asset workflows',()=>{
 it('renders dashboard statistics and database charts',async()=>{renderPage('dashboard');expect(await screen.findByText('Total Assets')).toBeInTheDocument();expect(await screen.findAllByText('3')).toHaveLength(2);expect(screen.getByText('Monthly Repair Activity')).toBeInTheDocument();});
 it('registers an asset with identity and condition fields',async()=>{
  renderPage('inventory');const form=await dialog('Add asset');await userEvent.selectOptions(within(form).getByLabelText('Asset type'),'LAPTOP');
  for(const [label,value] of [['Brand','Local Brand'],['Model','Model 1'],['Serial number','SERIAL-2'],['Purchase date','2020-01-01']] as const)await userEvent.type(within(form).getByLabelText(label),value);
  await userEvent.selectOptions(within(form).getByLabelText('Condition'),'GOOD');await save();expect(mocks.command).toHaveBeenCalledWith('',expect.objectContaining({assetType:'LAPTOP',serialNumber:'SERIAL-2'}),'POST');
 });
 it('submits an assignment using existing employees',async()=>{
  renderPage('assignments');await waitFor(()=>expect(mocks.request).toHaveBeenCalledWith(expect.stringContaining('/employees'),expect.anything()));const form=await dialog('Assign asset');
  await userEvent.selectOptions(within(form).getByLabelText('Available asset'),'asset');await userEvent.selectOptions(within(form).getByLabelText('Employee (search the roster above)'),'employee');await userEvent.selectOptions(within(form).getByLabelText('Subsidiary'),'subsidiary');await save();expect(mocks.command).toHaveBeenCalledWith('/assignments',expect.objectContaining({employeeId:'employee',assetId:'asset'}),'POST');
 });
 it('reports an issue for a personal asset',async()=>{
  mocks.user.role='EMPLOYEE';renderPage('issues');await waitFor(()=>expect(mocks.list).toHaveBeenCalledWith('inventory',expect.anything(),expect.anything()));const form=await dialog('Report issue');
  await userEvent.selectOptions(within(form).getByLabelText('Asset'),'asset');await userEvent.type(within(form).getByLabelText('Issue title'),'Battery fault');await userEvent.type(within(form).getByLabelText('Description'),'Synthetic issue');await userEvent.selectOptions(within(form).getByLabelText('Category'),'BATTERY');await userEvent.selectOptions(within(form).getByLabelText('Priority'),'HIGH');await save();expect(mocks.command).toHaveBeenCalledWith('/issues',expect.objectContaining({title:'Battery fault'}),'POST');
 });
 it('moves a repair into review',async()=>{
  rows=[{id:'repair',asset_code:'ADESA-LAP-0001',status:'REPORTED'}];renderPage('repairs');const form=await dialog('Manage repair');await userEvent.selectOptions(within(form).getByLabelText('Repair status'),'UNDER_REVIEW');await userEvent.selectOptions(within(form).getByLabelText('Repair decision'),'REPAIR');await userEvent.type(within(form).getByLabelText('Decision / supporting notes'),'Reviewing fault');await save();expect(mocks.command).toHaveBeenCalledWith('/repairs/repair',expect.objectContaining({status:'UNDER_REVIEW'}),'PATCH');
 });
 it('submits a replacement request',async()=>{
  mocks.user.role='EMPLOYEE';renderPage('replacements');await waitFor(()=>expect(mocks.list).toHaveBeenCalled());const form=await dialog('Request replacement');await userEvent.selectOptions(within(form).getByLabelText('Asset'),'asset');await userEvent.selectOptions(within(form).getByLabelText('Reason'),'FAULTY');await userEvent.type(within(form).getByLabelText('Replacement description'),'Synthetic replacement');await save();expect(mocks.command).toHaveBeenCalledWith('/replacements',expect.objectContaining({reason:'FAULTY'}),'POST');
 });
 it('requires inspected condition when receiving an asset',async()=>{
  rows=[{id:'return',asset_code:'ADESA-LAP-0001',status:'PENDING',display_status:'PENDING'}];renderPage('returns');const form=await dialog('Receive and verify');await userEvent.selectOptions(within(form).getByLabelText('Inspected condition'),'GOOD');await userEvent.type(within(form).getByLabelText('Decision / supporting notes'),'Physically verified');await save();expect(mocks.command).toHaveBeenCalledWith('/returns/return/complete',expect.objectContaining({condition:'GOOD'}),'POST');
 });
 it('confirms a clearance decision and shows backend failures',async()=>{
  rows=[{id:'clearance',employee_name:'Synthetic Employee',status:'PENDING'}];renderPage('clearance');const form=await dialog('Approve clearance');mocks.command.mockRejectedValueOnce(new Error('Outstanding assets prevent clearance'));await userEvent.type(within(form).getByLabelText('Decision / supporting notes'),'Reviewed obligations');await save();expect(await screen.findByRole('alert')).toHaveTextContent('Outstanding assets prevent clearance');expect(screen.getByRole('dialog')).toBeInTheDocument();
 });
 it('hides restricted employee actions and sections',async()=>{
  mocks.user.role='EMPLOYEE';renderPage('inventory');await screen.findByText('TEST-SERIAL');expect(screen.queryByRole('button',{name:'Add asset'})).not.toBeInTheDocument();expect(screen.queryByRole('link',{name:'Employee Clearance'})).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'Retire'})).not.toBeInTheDocument();
 });
 it('allows HR clearance initiation without approval controls',async()=>{
  mocks.user.role='HR';rows=[{id:'clearance',status:'PENDING'}];renderPage('clearance');await screen.findByRole('button',{name:'Initiate clearance'});expect(screen.queryByRole('button',{name:'Approve clearance'})).not.toBeInTheDocument();expect(screen.queryByRole('link',{name:'Repairs & Maintenance'})).not.toBeInTheDocument();
 });
});

describe('employee asset and repair requests',()=>{
 function availableCatalogue() {
  mocks.list.mockImplementation(async(section:string)=>({content:section==='available'?[{id:'available',asset_code:'ADESA-PHN-0042',brand:'Available Brand',model:'Available model'}]:section==='inventory'?[{...asset,status:'ASSIGNED'}]:rows,totalElements:section==='available'||section==='inventory'?1:rows.length,page:0,size:20}));
  mocks.request.mockImplementation(async(path:string)=>path.endsWith('/request-subsidiaries')?[{id:'subsidiary',name:'Local Subsidiary'}]:[]);
 }
 it('shows only catalogue devices when an employee requests an asset',async()=>{
  mocks.user.role='EMPLOYEE';availableCatalogue();renderPage('requests');
  await waitFor(()=>expect(mocks.list).toHaveBeenCalledWith('available',expect.anything(),expect.anything()));
  const form=await dialog('Request an asset');
  expect(within(form).queryByRole('option',{name:/ADESA-LAP-0001/})).not.toBeInTheDocument();
  await userEvent.selectOptions(within(form).getByLabelText('Available asset'),'available');
  await userEvent.selectOptions(within(form).getByLabelText('Subsidiary'),'subsidiary');
  await userEvent.type(within(form).getByLabelText('Why do you need this asset?'),'Synthetic work requirement');
  await save();expect(mocks.command).toHaveBeenCalledWith('/requests',{assetId:'available',subsidiaryId:'subsidiary',reason:'Synthetic work requirement'},'POST');
  expect(screen.queryByRole('button',{name:'Approve request'})).not.toBeInTheDocument();
 });
 it('keeps the form and explains when the chosen device is no longer available',async()=>{
  mocks.user.role='EMPLOYEE';availableCatalogue();renderPage('requests');
  await waitFor(()=>expect(mocks.list).toHaveBeenCalledWith('available',expect.anything(),expect.anything()));
  const form=await dialog('Request an asset');await userEvent.selectOptions(within(form).getByLabelText('Available asset'),'available');
  await userEvent.selectOptions(within(form).getByLabelText('Subsidiary'),'subsidiary');await userEvent.type(within(form).getByLabelText('Why do you need this asset?'),'Work device');
  mocks.command.mockRejectedValueOnce(new Error('This asset is already reserved by a pending request'));await save();
  expect(await screen.findByRole('alert')).toHaveTextContent('already reserved');expect(screen.getByRole('dialog')).toBeInTheDocument();
 });
 it('lets an employee request repair without approval or cost controls',async()=>{
  mocks.user.role='EMPLOYEE';availableCatalogue();renderPage('repairs');
  await waitFor(()=>expect(mocks.list).toHaveBeenCalledWith('inventory',expect.anything(),expect.anything()));
  const form=await dialog('Request a repair');await userEvent.selectOptions(within(form).getByLabelText('Asset'),'asset');
  await userEvent.type(within(form).getByLabelText('Problem description'),'Battery no longer charges');
  expect(within(form).queryByLabelText('Actual cost')).not.toBeInTheDocument();
  await save();expect(mocks.command).toHaveBeenCalledWith('/repairs/requests',expect.objectContaining({assetId:'asset',description:'Battery no longer charges'}),'POST');
  expect(screen.queryByRole('button',{name:'Manage repair'})).not.toBeInTheDocument();
 });
 it('lets IT approve a pending employee asset request',async()=>{
  mocks.user={id:'it',role:'IT_ADMIN'};rows=[{id:'request',asset_code:'ADESA-PHN-0042',employee_id:'employee',status:'PENDING'}];availableCatalogue();renderPage('requests');
  const form=await dialog('Approve request');await userEvent.type(within(form).getByLabelText('Decision / supporting notes'),'Approved for assigned duties');await save();
  expect(mocks.command).toHaveBeenCalledWith('/requests/request/approve',{notes:'Approved for assigned duties'},'POST');
 });
});
