export type Security = "none" | "tls" | "reality";
export type Transport = "raw" | "websocket" | "grpc" | "xhttp" | "httpupgrade";
export type EnginePhase = "offline" | "starting" | "connected" | "stopping" | "error";
export type AppTheme = "amoled" | "graphite" | "matrix";
export type NodeSortMode = "origin" | "delay" | "name";

export interface NodeView { id: string; name: string; host: string; port: number; security: Security; transport: Transport; }
export interface GroupView { id: string; name: string; updated_at_ms: number; nodes: NodeView[]; }
export interface EngineSnapshot { phase: EnginePhase; node_name: string | null; message: string | null; }
export interface TrafficSnapshot { download_bytes_per_second: number; upload_bytes_per_second: number; session_download_bytes: number; session_upload_bytes: number; connected_seconds: number; }
export interface AppPreferences { theme: AppTheme; close_to_tray: boolean; }
export interface SelectionView { group_id: string | null; node_id: string | null; }
export interface UrlTestResult { node_id: string; latency_ms: number; active_tunnel: boolean; }
export interface NodeChangeView { id: string; name: string; }
export interface NodeEditView { id: string; name: string; changed_fields: string[]; }
export interface SubscriptionRefreshResult {
  success: boolean;
  subscription_name: string;
  group: GroupView | null;
  total_nodes: number;
  added: NodeChangeView[];
  edited: NodeEditView[];
  deleted: NodeChangeView[];
  user_message: string | null;
  raw_error: string | null;
}
export interface GeoPoint {
  host: string;
  ip: string | null;
  latitude: number;
  longitude: number;
  country: string;
  country_code: string;
  city: string | null;
  source: "geo_ip" | "name_fallback";
}
