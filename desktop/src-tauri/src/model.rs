use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Security { None, Tls, Reality }

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Transport { Raw, Websocket, Grpc, Xhttp, Httpupgrade }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VlessNode {
    pub id: String,
    pub name: String,
    pub host: String,
    pub port: u16,
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
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SubscriptionGroup {
    pub id: String,
    pub name: String,
    pub url: String,
    pub updated_at_ms: u64,
    pub nodes: Vec<VlessNode>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum AppTheme { Amoled, Graphite, Matrix }
impl Default for AppTheme { fn default() -> Self { Self::Amoled } }

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppPreferences {
    #[serde(default)]
    pub theme: AppTheme,
    #[serde(default = "default_true")]
    pub close_to_tray: bool,
}
impl Default for AppPreferences {
    fn default() -> Self { Self { theme: AppTheme::Amoled, close_to_tray: true } }
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
}

#[derive(Debug, Clone, Serialize)]
pub struct NodeView {
    pub id: String,
    pub name: String,
    pub host: String,
    pub port: u16,
    pub security: Security,
    pub transport: Transport,
}

#[derive(Debug, Clone, Serialize)]
pub struct GroupView {
    pub id: String,
    pub name: String,
    pub updated_at_ms: u64,
    pub nodes: Vec<NodeView>,
}

impl From<&VlessNode> for NodeView {
    fn from(v: &VlessNode) -> Self {
        Self { id: v.id.clone(), name: v.name.clone(), host: v.host.clone(), port: v.port, security: v.security, transport: v.transport }
    }
}
impl From<&SubscriptionGroup> for GroupView {
    fn from(v: &SubscriptionGroup) -> Self {
        Self { id: v.id.clone(), name: v.name.clone(), updated_at_ms: v.updated_at_ms, nodes: v.nodes.iter().map(NodeView::from).collect() }
    }
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum EnginePhase { Offline, Starting, Connected, Stopping, Error }

#[derive(Debug, Clone, Serialize)]
pub struct EngineSnapshot {
    pub phase: EnginePhase,
    pub node_name: Option<String>,
    pub message: Option<String>,
}
impl Default for EngineSnapshot {
    fn default() -> Self { Self { phase: EnginePhase::Offline, node_name: None, message: None } }
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
