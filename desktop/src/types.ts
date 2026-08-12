export type Security = "none" | "tls" | "reality";
export type Transport = "raw" | "websocket" | "grpc" | "xhttp" | "httpupgrade";
export type EnginePhase = "offline" | "starting" | "connected" | "stopping" | "error";

export interface NodeView {
  id: string;
  name: string;
  host: string;
  port: number;
  security: Security;
  transport: Transport;
}

export interface GroupView {
  id: string;
  name: string;
  updated_at_ms: number;
  nodes: NodeView[];
}

export interface EngineSnapshot {
  phase: EnginePhase;
  node_name: string | null;
  message: string | null;
}
