use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum PingMethod {
    #[default]
    ProxyGet,
    ProxyHead,
    Tcp,
    Icmp,
}
impl PingMethod {
    pub fn proxy(self) -> bool {
        matches!(self, Self::ProxyGet | Self::ProxyHead)
    }
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "snake_case")]
pub enum Aggregation {
    Minimum,
    Average,
    #[default]
    Median,
    Last,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub struct PingSettings {
    pub method: PingMethod,
    pub url: String,
    pub timeout_seconds: u64,
    pub attempts: usize,
    pub aggregation: Aggregation,
    pub parallelism: usize,
    pub on_start: bool,
    pub repeat_minutes: u32,
    pub selected_only: bool,
}
impl Default for PingSettings {
    fn default() -> Self {
        Self {
            method: PingMethod::ProxyGet,
            url: "https://cp.cloudflare.com/generate_204".into(),
            timeout_seconds: 5,
            attempts: 3,
            aggregation: Aggregation::Median,
            parallelism: 4,
            on_start: false,
            repeat_minutes: 0,
            selected_only: false,
        }
    }
}
impl PingSettings {
    pub fn validate(&self) -> Result<(), String> {
        if ![3, 5, 10, 15].contains(&self.timeout_seconds)
            || ![1, 2, 3, 5].contains(&self.attempts)
            || ![1, 2, 4, 8].contains(&self.parallelism)
            || ![0, 1, 5, 15, 30, 60].contains(&self.repeat_minutes)
        {
            return Err("invalid diagnostics limits".into());
        }
        let u = url::Url::parse(&self.url).map_err(|_| "invalid test URL")?;
        if !matches!(u.scheme(), "http" | "https")
            || u.host_str().is_none()
            || !u.username().is_empty()
            || u.password().is_some()
            || u.fragment().is_some()
            || self.url.len() > 2048
        {
            return Err("test URL must be HTTP(S), without credentials or fragment".into());
        }
        Ok(())
    }
}
pub fn aggregate(samples: &mut [u64], mode: Aggregation) -> Option<u64> {
    if samples.is_empty() {
        return None;
    }
    Some(match mode {
        Aggregation::Last => *samples.last().unwrap(),
        Aggregation::Average => samples.iter().sum::<u64>() / samples.len() as u64,
        Aggregation::Minimum => *samples.iter().min().unwrap(),
        Aggregation::Median => {
            samples.sort_unstable();
            let n = samples.len();
            if n % 2 == 0 {
                (samples[n / 2 - 1] + samples[n / 2]) / 2
            } else {
                samples[n / 2]
            }
        }
    })
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct AdvancedSettings {
    pub schema_version: u32,
    pub ping: PingSettings,
    pub auto_connect: bool,
    pub auto_node: bool,
    pub auto_max_latency_ms: u64,
    pub auto_excluded: std::collections::HashSet<String>,
    pub compact_nodes: bool,
    pub show_latency: bool,
    pub show_transport: bool,
    pub dns: String,
    pub bypass_lan: bool,
    pub log_level: String,
    pub subscription_timeout_seconds: u64,
    pub subscription_user_agent: String,
}
impl Default for AdvancedSettings {
    fn default() -> Self {
        Self {
            schema_version: 1,
            ping: PingSettings::default(),
            auto_connect: false,
            auto_node: false,
            auto_max_latency_ms: 0,
            auto_excluded: Default::default(),
            compact_nodes: false,
            show_latency: true,
            show_transport: true,
            dns: "1.1.1.1".into(),
            bypass_lan: false,
            log_level: "warning".into(),
            subscription_timeout_seconds: 20,
            subscription_user_agent: String::new(),
        }
    }
}
impl AdvancedSettings {
    pub fn validate(&self) -> Result<(), String> {
        self.ping.validate()?;
        if self.schema_version != 1
            || self.auto_max_latency_ms > 60_000
            || ![5, 10, 20, 30, 60].contains(&self.subscription_timeout_seconds)
            || self.subscription_user_agent.len() > 256
            || !self
                .subscription_user_agent
                .bytes()
                .all(|b| (32..127).contains(&b))
            || !["none", "error", "warning"].contains(&self.log_level.as_str())
            || self.auto_excluded.len() > 10000
        {
            return Err("invalid advanced settings".into());
        }
        self.dns
            .parse::<std::net::IpAddr>()
            .map_err(|_| "DNS must be an IPv4 or IPv6 address")?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn defaults_migrate_and_validate() {
        let s: AdvancedSettings = serde_json::from_str("{}").unwrap();
        s.validate().unwrap();
        assert_eq!(s.ping.attempts, 3);
        assert_eq!(s.ping.parallelism, 4);
        assert!(!s.ping.on_start);
    }
    #[test]
    fn aggregations_and_invalid_input() {
        assert_eq!(aggregate(&mut [90, 10, 20], Aggregation::Median), Some(20));
        assert_eq!(aggregate(&mut [10, 20], Aggregation::Median), Some(15));
        assert_eq!(aggregate(&mut [], Aggregation::Last), None);
        let mut s = PingSettings::default();
        s.url = "https://user:secret@example.com".into();
        assert!(s.validate().is_err());
        s = PingSettings::default();
        s.parallelism = 100;
        assert!(s.validate().is_err());
    }
}
