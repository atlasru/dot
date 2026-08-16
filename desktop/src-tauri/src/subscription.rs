use base64::{engine::general_purpose, Engine as _};
use reqwest::blocking::Client;
use std::{collections::{BTreeSet, HashMap}, time::Duration};

use crate::{model::{NodeChangeView, NodeEditView, VlessNode}, vless::parse_vless};

pub struct SubscriptionClient { http: Client }

impl SubscriptionClient {
    pub fn new() -> Result<Self, String> {
        let http = Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(20))
            .user_agent("dot-desktop/0.2.2-alpha.1")
            .build()
            .map_err(|e| format!("failed to initialize HTTP client: {e}"))?;
        Ok(Self { http })
    }

    pub fn fetch(&self, url: &str) -> Result<Vec<VlessNode>, String> {
        let response = self.http.get(url).header("Accept", "*/*").send()
            .map_err(|e| format!("subscription request failed: {e}"))?;
        let status = response.status();
        if !status.is_success() { return Err(format!("subscription server returned HTTP {}", status.as_u16())); }
        let body = response.text().map_err(|e| format!("failed to read subscription response: {e}"))?;
        decode_subscription(&body)
    }
}

pub fn decode_subscription(body: &str) -> Result<Vec<VlessNode>, String> {
    let body = body.trim().trim_start_matches('\u{feff}').trim();
    if body.is_empty() { return Err("subscription response is empty".into()); }
    let plaintext = if body.to_ascii_lowercase().contains("vless://") {
        body.to_string()
    } else {
        decode_base64(body).ok_or_else(|| "unsupported subscription format".to_string())?
    };

    let mut nodes = Vec::new();
    let mut errors = Vec::new();
    for token in plaintext.split(|c: char| c.is_whitespace()).map(str::trim).filter(|s| s.to_ascii_lowercase().starts_with("vless://")) {
        match parse_vless(token) { Ok(node) => nodes.push(node), Err(error) => errors.push(error) }
    }
    if nodes.is_empty() {
        if let Some(first) = errors.first() { Err(format!("subscription contains no usable VLESS nodes: {first}")) }
        else { Err("subscription contains no VLESS nodes".into()) }
    } else { Ok(nodes) }
}

#[derive(Debug, Clone)]
pub struct NodeMatch { pub before_id: String, pub after_id: String }

#[derive(Debug, Clone, Default)]
pub struct SubscriptionDiff {
    pub added: Vec<NodeChangeView>,
    pub deleted: Vec<NodeChangeView>,
    pub edited: Vec<NodeEditView>,
    pub matches: Vec<NodeMatch>,
}

pub fn diff_nodes(old_nodes: &[VlessNode], new_nodes: &[VlessNode]) -> SubscriptionDiff {
    let mut old_by_identity: HashMap<String, Vec<&VlessNode>> = HashMap::new();
    let mut new_by_identity: HashMap<String, Vec<&VlessNode>> = HashMap::new();
    for node in old_nodes { old_by_identity.entry(identity(node)).or_default().push(node); }
    for node in new_nodes { new_by_identity.entry(identity(node)).or_default().push(node); }

    let identities: BTreeSet<String> = old_by_identity.keys().chain(new_by_identity.keys()).cloned().collect();
    let mut result = SubscriptionDiff::default();
    for key in identities {
        let mut old = old_by_identity.remove(&key).unwrap_or_default();
        let mut new = new_by_identity.remove(&key).unwrap_or_default();

        let mut index = 0;
        while index < old.len() {
            if let Some(exact) = new.iter().position(|candidate| candidate.raw_uri == old[index].raw_uri) {
                let before = old.remove(index);
                let after = new.remove(exact);
                result.matches.push(NodeMatch { before_id: before.id.clone(), after_id: after.id.clone() });
            } else { index += 1; }
        }

        let pairs = old.len().min(new.len());
        for _ in 0..pairs {
            let before = old.remove(0);
            let after = new.remove(0);
            let fields = changed_fields(before, after);
            result.matches.push(NodeMatch { before_id: before.id.clone(), after_id: after.id.clone() });
            if !fields.is_empty() {
                result.edited.push(NodeEditView { id: after.id.clone(), name: after.name.clone(), changed_fields: fields });
            }
        }
        result.deleted.extend(old.into_iter().map(NodeChangeView::from));
        result.added.extend(new.into_iter().map(NodeChangeView::from));
    }
    result
}

pub fn replacement_for(diff: &SubscriptionDiff, old_id: &str) -> Option<String> {
    diff.matches.iter().find(|item| item.before_id == old_id).map(|item| item.after_id.clone())
}

pub fn user_message_for_error(raw: &str) -> String {
    let lower = raw.to_ascii_lowercase();
    if lower.contains("timed out") || lower.contains("timeout") { "The subscription server timed out.".into() }
    else if lower.contains("dns") || lower.contains("resolve") || lower.contains("name or service") { "Could not resolve the subscription server.".into() }
    else if lower.contains("certificate") || lower.contains("tls") { "The subscription server TLS connection failed.".into() }
    else if lower.contains("http 401") || lower.contains("http 403") { "The subscription server rejected access.".into() }
    else if lower.contains("http 404") { "The subscription URL was not found.".into() }
    else if lower.contains("http 5") { "The subscription server returned an error.".into() }
    else if lower.contains("empty") { "The subscription returned no content.".into() }
    else if lower.contains("unsupported subscription") || lower.contains("no vless") || lower.contains("no usable") { "The subscription contains no supported VLESS nodes.".into() }
    else if lower.contains("request failed") || lower.contains("connect") { "Could not reach the subscription server.".into() }
    else { "The subscription could not be updated.".into() }
}

pub fn redact_error(raw: &str) -> String {
    let mut value = raw.to_string();
    while let Some(start) = value.to_ascii_lowercase().find("vless://") {
        let end = value[start..].find(char::is_whitespace).map(|offset| start + offset).unwrap_or(value.len());
        value.replace_range(start..end, "vless://[redacted]");
    }
    value
}

fn identity(node: &VlessNode) -> String {
    format!("{}|{}|{}", node.user_id.to_ascii_lowercase(), node.host.to_ascii_lowercase(), node.port)
}

fn changed_fields(before: &VlessNode, after: &VlessNode) -> Vec<String> {
    let mut fields = Vec::new();
    if before.name != after.name { fields.push("name".into()); }
    if !before.host.eq_ignore_ascii_case(&after.host) { fields.push("host".into()); }
    if before.port != after.port { fields.push("port".into()); }
    if before.security != after.security { fields.push("security".into()); }
    if before.transport != after.transport { fields.push("transport".into()); }
    if before.sni != after.sni { fields.push("SNI".into()); }
    if before.fingerprint != after.fingerprint { fields.push("fingerprint".into()); }
    if before.flow != after.flow { fields.push("flow".into()); }
    if before.public_key != after.public_key { fields.push("REALITY key".into()); }
    if before.short_id != after.short_id { fields.push("short ID".into()); }
    if before.path != after.path { fields.push("path".into()); }
    if before.host_header != after.host_header { fields.push("host header".into()); }
    if before.service_name != after.service_name { fields.push("service name".into()); }
    if before.encryption != after.encryption { fields.push("encryption".into()); }
    fields
}

fn decode_base64(input: &str) -> Option<String> {
    let compact: String = input.chars().filter(|c| !c.is_whitespace()).collect();
    for engine in [&general_purpose::STANDARD, &general_purpose::STANDARD_NO_PAD, &general_purpose::URL_SAFE, &general_purpose::URL_SAFE_NO_PAD] {
        if let Ok(bytes) = engine.decode(compact.as_bytes()) {
            if let Ok(text) = String::from_utf8(bytes) {
                if text.to_ascii_lowercase().contains("vless://") { return Some(text); }
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::engine::general_purpose::STANDARD;
    const LINK: &str = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls&type=ws&path=%2Fdot#node";

    #[test] fn accepts_plaintext() { assert_eq!(decode_subscription(LINK).unwrap().len(), 1); }
    #[test] fn accepts_base64() { assert_eq!(decode_subscription(&STANDARD.encode(LINK.as_bytes())).unwrap().len(), 1); }
    #[test]
    fn detects_edit_without_treating_it_as_delete_add() {
        let old = decode_subscription(LINK).unwrap();
        let next = decode_subscription("vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls&type=ws&path=%2Fnext#renamed").unwrap();
        let diff = diff_nodes(&old, &next);
        assert_eq!(diff.edited.len(), 1);
        assert!(diff.added.is_empty());
        assert!(diff.deleted.is_empty());
        assert!(diff.edited[0].changed_fields.contains(&"name".to_string()));
        assert!(diff.edited[0].changed_fields.contains(&"path".to_string()));
    }
}
