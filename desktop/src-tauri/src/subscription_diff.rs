use crate::model::VlessNode;

#[derive(Debug, Clone, PartialEq, Eq)]
struct NodeIdentity {
    user_id: String,
    host: String,
    port: u16,
}

fn identity(node: &VlessNode) -> NodeIdentity {
    NodeIdentity {
        user_id: node.user_id.to_lowercase(),
        host: node.host.to_lowercase(),
        port: node.port,
    }
}

#[derive(Debug, Clone)]
pub struct NodeMatch {
    pub before: VlessNode,
    pub after: VlessNode,
}

#[derive(Debug, Clone)]
pub struct NodeEdit {
    pub before: VlessNode,
    pub after: VlessNode,
    pub changed_fields: Vec<String>,
}

#[derive(Debug, Clone, Default)]
pub struct SubscriptionDiff {
    pub added: Vec<VlessNode>,
    pub deleted: Vec<VlessNode>,
    pub edited: Vec<NodeEdit>,
    pub unchanged: Vec<NodeMatch>,
}

impl SubscriptionDiff {
    pub fn replacement_for(&self, old_node_id: &str) -> Option<&VlessNode> {
        self.unchanged
            .iter()
            .find(|entry| entry.before.id == old_node_id)
            .map(|entry| &entry.after)
            .or_else(|| {
                self.edited
                    .iter()
                    .find(|entry| entry.before.id == old_node_id)
                    .map(|entry| &entry.after)
            })
    }
}

pub fn calculate(old_nodes: &[VlessNode], new_nodes: &[VlessNode]) -> SubscriptionDiff {
    let mut identities = Vec::<NodeIdentity>::new();
    for node in old_nodes.iter().chain(new_nodes.iter()) {
        let key = identity(node);
        if !identities.contains(&key) {
            identities.push(key);
        }
    }

    let mut diff = SubscriptionDiff::default();

    for key in identities {
        let mut old_remaining: Vec<VlessNode> = old_nodes
            .iter()
            .filter(|node| identity(node) == key)
            .cloned()
            .collect();
        let mut new_remaining: Vec<VlessNode> = new_nodes
            .iter()
            .filter(|node| identity(node) == key)
            .cloned()
            .collect();

        let mut old_index = 0;
        while old_index < old_remaining.len() {
            let exact_index = new_remaining
                .iter()
                .position(|candidate| candidate.raw_uri == old_remaining[old_index].raw_uri);
            if let Some(new_index) = exact_index {
                let before = old_remaining.remove(old_index);
                let after = new_remaining.remove(new_index);
                diff.unchanged.push(NodeMatch { before, after });
            } else {
                old_index += 1;
            }
        }

        let pair_count = old_remaining.len().min(new_remaining.len());
        for _ in 0..pair_count {
            let before = old_remaining.remove(0);
            let after = new_remaining.remove(0);
            let fields = changed_fields(&before, &after);
            if fields.is_empty() {
                diff.unchanged.push(NodeMatch { before, after });
            } else {
                diff.edited.push(NodeEdit {
                    before,
                    after,
                    changed_fields: fields,
                });
            }
        }

        diff.deleted.extend(old_remaining);
        diff.added.extend(new_remaining);
    }

    diff
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::vless::parse_vless;

    fn node(uri: &str) -> VlessNode { parse_vless(uri).unwrap() }

    #[test]
    fn keeps_exact_node_unchanged() {
        let original = node("vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls&type=tcp#France%20%232");
        let diff = calculate(std::slice::from_ref(&original), std::slice::from_ref(&original));
        assert_eq!(diff.unchanged.len(), 1);
        assert!(diff.added.is_empty());
        assert!(diff.deleted.is_empty());
        assert!(diff.edited.is_empty());
    }

    #[test]
    fn treats_same_endpoint_with_new_metadata_as_edit() {
        let before = node("vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls&type=tcp#France%20%232");
        let after = node("vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls&type=tcp&sni=edge.example.com#France%20%2310");
        let diff = calculate(&[before.clone()], &[after.clone()]);
        assert_eq!(diff.edited.len(), 1);
        assert_eq!(diff.replacement_for(&before.id).map(|n| n.id.as_str()), Some(after.id.as_str()));
        assert!(diff.edited[0].changed_fields.contains(&"name".to_string()));
        assert!(diff.edited[0].changed_fields.contains(&"SNI".to_string()));
    }

    #[test]
    fn separates_different_endpoints() {
        let before = node("vless://11111111-1111-4111-8111-111111111111@old.example.com:443?security=tls&type=tcp#old");
        let after = node("vless://11111111-1111-4111-8111-111111111111@new.example.com:443?security=tls&type=tcp#new");
        let diff = calculate(&[before], &[after]);
        assert_eq!(diff.deleted.len(), 1);
        assert_eq!(diff.added.len(), 1);
        assert!(diff.edited.is_empty());
    }

    #[test]
    fn duplicate_identity_matches_exact_uri_before_edit_pairing() {
        let exact = node("vless://same@node.example:443?security=tls&type=tcp#tcp");
        let old_other = node("vless://same@node.example:443?security=tls&type=ws#ws");
        let fresh_exact = exact.clone();
        let fresh_other = node("vless://same@node.example:443?security=tls&type=ws#ws-renamed");

        let diff = calculate(&[exact.clone(), old_other.clone()], &[fresh_other.clone(), fresh_exact.clone()]);

        assert_eq!(diff.replacement_for(&exact.id).map(|node| node.raw_uri.as_str()), Some(fresh_exact.raw_uri.as_str()));
        assert_eq!(diff.replacement_for(&old_other.id).map(|node| node.raw_uri.as_str()), Some(fresh_other.raw_uri.as_str()));
        assert_eq!(diff.edited.len(), 1);
    }
}
