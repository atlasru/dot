export type Security = "none" | "tls" | "reality";
export type Transport = "raw" | "websocket" | "grpc" | "xhttp" | "httpupgrade";
export type EnginePhase = "offline" | "starting" | "connected" | "stopping" | "error";
export type AppTheme = "amoled" | "graphite" | "matrix";
export type NodeSortMode = "origin" | "delay" | "name";

export interface NodeView {
  id: string;
  name: string;
  host: string;
  port: number;
  security: Security;
  transport: Transport;
  latency_ms: number | null;
  latency_failed: boolean;
  favorite: boolean;
}

export interface GroupView {
  id: string;
  name: string;
  updated_at_ms: number;
  sort_mode: NodeSortMode;
  remote: boolean;
  nodes: NodeView[];
}

export interface EngineSnapshot { phase: EnginePhase; node_name: string | null; node_id: string | null; message: string | null; }
export interface TrafficSnapshot { download_bytes_per_second: number; upload_bytes_per_second: number; session_download_bytes: number; session_upload_bytes: number; connected_seconds: number; }
export interface AppPreferences { theme: AppTheme; close_to_tray: boolean; refresh_on_start: boolean; refresh_interval_hours: number; }
export interface SelectionView { group_id: string | null; node_id: string | null; }
export interface UrlTestResult { node_id: string; latency_ms: number; active_tunnel: boolean; }
export interface GroupUrlTestResult { group_id: string; succeeded: number; failed: number; results: UrlTestResult[]; failed_node_ids: string[]; }

export interface NodeChangeView { id: string; name: string; }
export interface NodeEditView { before: NodeChangeView; after: NodeChangeView; changed_fields: string[]; }
export interface SubscriptionRefreshResult {
  group: GroupView;
  added: NodeChangeView[];
  deleted: NodeChangeView[];
  edited: NodeEditView[];
  unchanged: number;
  selected_node_removed: boolean;
}

export interface RefreshNotice { group_id: string; attempted_at_ms: number; error: string | null; result: SubscriptionRefreshResult | null; }
