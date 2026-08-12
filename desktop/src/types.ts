export type Security = "none" | "tls" | "reality";
export type Transport = "raw" | "websocket" | "grpc" | "xhttp" | "httpupgrade";
export type EnginePhase = "offline" | "starting" | "connected" | "stopping" | "error";
export type AppTheme = "amoled" | "graphite" | "matrix";

export interface NodeView { id: string; name: string; host: string; port: number; security: Security; transport: Transport; }
export interface GroupView { id: string; name: string; updated_at_ms: number; nodes: NodeView[]; }
export interface EngineSnapshot { phase: EnginePhase; node_name: string | null; message: string | null; }
export interface TrafficSnapshot { download_bytes_per_second: number; upload_bytes_per_second: number; session_download_bytes: number; session_upload_bytes: number; connected_seconds: number; }
export interface AppPreferences { theme: AppTheme; close_to_tray: boolean; }
export interface SelectionView { group_id: string | null; node_id: string | null; }
