use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Security {
    None,
    Tls,
    Reality,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Transport {
    Raw,
    Websocket,
    Grpc,
    Xhttp,
    Httpupgrade,
}

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

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PersistedState {
    pub groups: Vec<SubscriptionGroup>,
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
    fn from(value: &VlessNode) -> Self {
        Self {
            id: value.id.clone(),
            name: value.name.clone(),
            host: value.host.clone(),
            port: value.port,
            security: value.security,
            transport: value.transport,
        }
    }
}

impl From<&SubscriptionGroup> for GroupView {
    fn from(value: &SubscriptionGroup) -> Self {
        Self {
            id: value.id.clone(),
            name: value.name.clone(),
            updated_at_ms: value.updated_at_ms,
            nodes: value.nodes.iter().map(NodeView::from).collect(),
        }
    }
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum EnginePhase {
    Offline,
    Starting,
    Connected,
    Stopping,
    Error,
}

#[derive(Debug, Clone, Serialize)]
pub struct EngineSnapshot {
    pub phase: EnginePhase,
    pub node_name: Option<String>,
    pub message: Option<String>,
}

impl Default for EngineSnapshot {
    fn default() -> Self {
        Self {
            phase: EnginePhase::Offline,
            node_name: None,
            message: None,
        }
    }
}
