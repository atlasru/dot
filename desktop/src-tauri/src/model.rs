use std::collections::HashMap;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Security { None, Tls, Reality }

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Transport { Raw, Websocket, Grpc, Xhttp, Httpupgrade }

#[derive(Clone, Serialize, Deserialize)]
pub struct ProxyNode {
    pub id: String,
    pub name: String,
    pub host: String,
    pub port: u16,
    #[serde(default)]
    pub user_id: String,
    pub encryption: String,
    pub flow: Option<String>,
    pub security: Security,
    pub transport: Transport,
    pub sni: Option<String>,
    pub fingerprint: Option<String>,
    pub public_key: Option<String>,
    pub short_id: Option<String>,
    pub spider_x: Option<String>,
    pub mldsa65_verify: Option<String>,
    pub path: Option<String>,
    pub host_header: Option<String>,
    pub service_name: Option<String>,
    pub mode: Option<String>,
    pub alpn: Vec<String>,
    pub raw_uri: String,
    #[serde(default)]
    pub proxy_config: ProxyConfig,
}

#[derive(Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "protocol", content = "config", rename_all = "lowercase")]
pub enum ProxyConfig {
    Vless,
    Hysteria2(Hysteria2Config),
}
impl Default for ProxyConfig { fn default() -> Self { Self::Vless } }

#[derive(Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Hysteria2Config {
    pub auth: String,
    pub sni: Option<String>,
    pub insecure: bool,
    pub alpn: Vec<String>,
    pub congestion: Option<String>,
    pub up: Option<String>,
    pub down: Option<String>,
    pub salamander_password: Option<String>,
    pub packet_size: Option<String>,
}

impl ProxyNode {
    pub fn protocol(&self) -> &'static str {
        match self.proxy_config { ProxyConfig::Vless => "VLESS", ProxyConfig::Hysteria2(_) => "HY2" }
    }
    pub fn redact(&self, text: &str) -> String {
        let mut safe = text.replace(&self.raw_uri, "[proxy URI redacted]");
        if let ProxyConfig::Hysteria2(config) = &self.proxy_config {
            for secret in [Some(config.auth.as_str()), config.salamander_password.as_deref()].into_iter().flatten() {
                if !secret.is_empty() { safe = safe.replace(secret, "***"); }
            }
        } else if !self.user_id.is_empty() { safe = safe.replace(&self.user_id, "***"); }
        safe
    }
}
impl std::fmt::Debug for ProxyNode {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ProxyNode").field("id", &self.id).field("name", &self.name)
            .field("host", &self.host).field("port", &self.port)
            .field("protocol", &self.protocol()).finish_non_exhaustive()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SubscriptionGroup {
    pub id: String,
    pub name: String,
    pub url: String,
    pub updated_at_ms: u64,
    pub nodes: Vec<ProxyNode>,
    #[serde(default)]
    pub hwid: Option<crate::subscription_identity::SubscriptionIdentity>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum AppTheme { Amoled, Graphite, Matrix }
impl Default for AppTheme { fn default() -> Self { Self::Amoled } }

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum NodeSortMode { Origin, Delay, Name }
impl Default for NodeSortMode { fn default() -> Self { Self::Origin } }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NodeLatency {
    pub node_id: String,
    pub latency_ms: Option<u64>,
    #[serde(default)]
    pub failed: bool,
    pub tested_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppPreferences {
    #[serde(default)]
    pub theme: AppTheme,
    #[serde(default = "default_true")]
    pub close_to_tray: bool,
    #[serde(default)]
    pub refresh_on_start: bool,
    #[serde(default)]
    pub refresh_interval_hours: u32,
}
impl Default for AppPreferences {
    fn default() -> Self { Self { theme: AppTheme::Amoled, close_to_tray: true, refresh_on_start: false, refresh_interval_hours: 0 } }
}
fn default_true() -> bool { true }

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PersistedState {
    #[serde(default)]
    pub groups: Vec<SubscriptionGroup>,
    #[serde(default)]
    pub selected_group_id: Option<String>,
    #[serde(default)]
    pub selected_node_id: Option<String>,
    #[serde(default)]
    pub preferences: AppPreferences,
    #[serde(default)]
    pub latencies: HashMap<String, NodeLatency>,
    #[serde(default)]
    pub sort_modes: HashMap<String, NodeSortMode>,
    #[serde(default)]
    pub favorites: std::collections::HashSet<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct NodeView {
    pub id: String,
    pub name: String,
    pub host: String,
    pub port: u16,
    pub protocol: &'static str,
    pub security: Security,
    pub transport: Transport,
    pub latency_ms: Option<u64>,
    pub latency_failed: bool,
    pub favorite: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct GroupView {
    pub id: String,
    pub name: String,
    pub updated_at_ms: u64,
    pub sort_mode: NodeSortMode,
    pub remote: bool,
    pub nodes: Vec<NodeView>,
    pub hwid: Option<crate::subscription_identity::SubscriptionIdentity>,
}

impl From<&ProxyNode> for NodeView {
    fn from(v: &ProxyNode) -> Self {
        Self {
            id: v.id.clone(),
            name: v.name.clone(),
            host: v.host.clone(),
            port: v.port,
            protocol: v.protocol(),
            security: v.security,
            transport: v.transport,
            latency_ms: None,
            latency_failed: false,
            favorite: false,
        }
    }
}
impl From<&SubscriptionGroup> for GroupView {
    fn from(v: &SubscriptionGroup) -> Self {
        Self {
            id: v.id.clone(),
            name: v.name.clone(),
            updated_at_ms: v.updated_at_ms,
            sort_mode: NodeSortMode::Origin,
            remote: !v.url.is_empty(),
            nodes: v.nodes.iter().map(NodeView::from).collect(),
            hwid: v.hwid.clone(),
        }
    }
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum EnginePhase { Offline, Starting, Connected, Stopping, Error }

#[derive(Debug, Clone, Serialize)]
pub struct EngineSnapshot {
    pub phase: EnginePhase,
    pub node_name: Option<String>,
    pub node_id: Option<String>,
    pub message: Option<String>,
}
impl Default for EngineSnapshot {
    fn default() -> Self { Self { phase: EnginePhase::Offline, node_name: None, node_id: None, message: None } }
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct TrafficSnapshot {
    pub download_bytes_per_second: u64,
    pub upload_bytes_per_second: u64,
    pub session_download_bytes: u64,
    pub session_upload_bytes: u64,
    pub connected_seconds: u64,
}

#[derive(Debug, Clone, Serialize)]
pub struct SelectionView {
    pub group_id: Option<String>,
    pub node_id: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct UrlTestResult {
    pub node_id: String,
    pub latency_ms: u64,
    pub active_tunnel: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct GroupUrlTestResult {
    pub group_id: String,
    pub succeeded: usize,
    pub failed: usize,
    pub results: Vec<UrlTestResult>,
    pub failed_node_ids: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct NodeChangeView {
    pub id: String,
    pub name: String,
}
impl From<&ProxyNode> for NodeChangeView {
    fn from(node: &ProxyNode) -> Self { Self { id: node.id.clone(), name: node.name.clone() } }
}

#[derive(Debug, Clone, Serialize)]
pub struct NodeEditView {
    pub before: NodeChangeView,
    pub after: NodeChangeView,
    pub changed_fields: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct SubscriptionRefreshResult {
    pub group: GroupView,
    pub added: Vec<NodeChangeView>,
    pub deleted: Vec<NodeChangeView>,
    pub edited: Vec<NodeEditView>,
    pub unchanged: usize,
    pub selected_node_removed: bool,
}
