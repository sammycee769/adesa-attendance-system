import { apiRequest, buildQuery } from './client';
export type AssetRow = Record<string, string | number | boolean | null>;
export interface AssetPage { content: AssetRow[]; totalElements: number; page: number; size: number }
export interface AssetDashboard {
 pendingAssetRequests: number; totalAssets: number; availableAssets: number; assignedAssets: number; underRepair: number;
 damagedAssets: number; pendingReplacements: number; pendingReturns: number; pendingClearances: number;
 byType: {label: string; value: number}[]; byStatus: {label: string; value: number}[];
 byCondition: {label: string; value: number}[]; bySubsidiary: {label: string; value: number}[];
 monthlyRepairs?: {label: string; value: number}[]; recentActivity?: AssetRow[];
}
export const assetApi = '/api/v1/assets';
export function listAssets(section: string, params: Record<string, string | number>, signal?: AbortSignal) {
 return apiRequest<AssetPage>(`${assetApi}${section === 'inventory' ? '' : `/${section}`}${buildQuery(params)}`, {signal});
}
export function assetCommand(path: string, body: unknown, method: 'POST'|'PUT'|'PATCH' = 'POST') {
 return apiRequest<{id: string} | undefined>(`${assetApi}${path}`, {method, body});
}
export function saveAssetFile(blob: Blob, name: string) {
 const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}
