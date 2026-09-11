use std::cmp::Ordering;

use crate::model::{NodeSortMode, NodeView};

pub fn sort_nodes(nodes: &mut [NodeView], mode: NodeSortMode) {
    match mode {
        NodeSortMode::Origin => {}
        NodeSortMode::Name => nodes.sort_by(|left, right| natural_compare(&left.name, &right.name)),
        NodeSortMode::Delay => {
            let has_results = nodes.iter().any(|node| node.latency_ms.is_some() || node.latency_failed);
            if !has_results { return; }
            nodes.sort_by(|left, right| {
                let left_rank = latency_rank(left);
                let right_rank = latency_rank(right);
                match left_rank.cmp(&right_rank) {
                    Ordering::Equal => match (left.latency_ms, right.latency_ms) {
                        (Some(a), Some(b)) if a != b => a.cmp(&b),
                        _ => natural_compare(&left.name, &right.name),
                    },
                    other => other,
                }
            });
        }
    }
}

fn latency_rank(node: &NodeView) -> u8 {
    if node.latency_ms.is_some() { 0 }
    else if node.latency_failed { 1 }
    else { 2 }
}

pub fn natural_compare(left: &str, right: &str) -> Ordering {
    let left_chars: Vec<char> = left.chars().collect();
    let right_chars: Vec<char> = right.chars().collect();
    let mut left_index = 0;
    let mut right_index = 0;

    while left_index < left_chars.len() && right_index < right_chars.len() {
        let left_char = left_chars[left_index];
        let right_char = right_chars[right_index];

        if left_char.is_ascii_digit() && right_char.is_ascii_digit() {
            let left_start = left_index;
            let right_start = right_index;
            while left_index < left_chars.len() && left_chars[left_index].is_ascii_digit() { left_index += 1; }
            while right_index < right_chars.len() && right_chars[right_index].is_ascii_digit() { right_index += 1; }

            let left_raw: String = left_chars[left_start..left_index].iter().collect();
            let right_raw: String = right_chars[right_start..right_index].iter().collect();
            let left_number = left_raw.trim_start_matches('0');
            let right_number = right_raw.trim_start_matches('0');
            let left_number = if left_number.is_empty() { "0" } else { left_number };
            let right_number = if right_number.is_empty() { "0" } else { right_number };

            match left_number.len().cmp(&right_number.len()) {
                Ordering::Equal => match left_number.cmp(right_number) {
                    Ordering::Equal => continue,
                    other => return other,
                },
                other => return other,
            }
        }

        let folded_left: String = left_char.to_lowercase().collect();
        let folded_right: String = right_char.to_lowercase().collect();
        match folded_left.cmp(&folded_right) {
            Ordering::Equal => {
                left_index += 1;
                right_index += 1;
            }
            other => return other,
        }
    }

    match (left_index < left_chars.len(), right_index < right_chars.len()) {
        (true, false) => Ordering::Greater,
        (false, true) => Ordering::Less,
        _ => left.to_lowercase().cmp(&right.to_lowercase()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{Security, Transport};

    fn node(id: &str, name: &str, latency_ms: Option<u64>, latency_failed: bool) -> NodeView {
        NodeView {
            id: id.into(), name: name.into(), host: "example.com".into(), port: 443,
            security: Security::Tls, transport: Transport::Raw, latency_ms, latency_failed,
        }
    }

    #[test]
    fn natural_name_sort_orders_numbers_numerically() {
        let mut nodes = vec![
            node("10", "France #10", None, false),
            node("2", "France #2", None, false),
            node("1", "France #1", None, false),
        ];
        sort_nodes(&mut nodes, NodeSortMode::Name);
        assert_eq!(nodes.iter().map(|n| n.name.as_str()).collect::<Vec<_>>(), vec!["France #1", "France #2", "France #10"]);
    }

    #[test]
    fn delay_sort_puts_success_then_failed_then_untested() {
        let mut nodes = vec![
            node("u", "Untested", None, false),
            node("f", "Failed", None, true),
            node("b", "Slow", Some(80), false),
            node("a", "Fast", Some(20), false),
        ];
        sort_nodes(&mut nodes, NodeSortMode::Delay);
        assert_eq!(nodes.iter().map(|n| n.id.as_str()).collect::<Vec<_>>(), vec!["a", "b", "f", "u"]);
    }

    #[test]
    fn delay_sort_is_stable_until_there_are_results() {
        let mut nodes = vec![node("b", "B", None, false), node("a", "A", None, false)];
        sort_nodes(&mut nodes, NodeSortMode::Delay);
        assert_eq!(nodes.iter().map(|n| n.id.as_str()).collect::<Vec<_>>(), vec!["b", "a"]);
    }
}
