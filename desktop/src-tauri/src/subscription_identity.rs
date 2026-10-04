use serde::{Deserialize, Serialize};

pub const VALIDATION_ERROR: &str = "HWID must be 10–64 characters: ASCII letters, digits, = or -.";

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct SubscriptionIdentity(String);

impl SubscriptionIdentity {
    pub fn generate() -> Result<Self, String> {
        let mut bytes = [0u8; 16];
        getrandom::fill(&mut bytes)
            .map_err(|_| "could not generate a secure subscription identity".to_string())?;
        let mut value = String::from("dot-");
        use std::fmt::Write;
        for byte in bytes {
            write!(&mut value, "{byte:02x}").expect("writing to a string");
        }
        Ok(Self(value))
    }

    pub fn parse(value: String) -> Result<Self, String> {
        let identity = Self(value);
        identity.validate()?;
        Ok(identity)
    }

    pub fn validate(&self) -> Result<(), String> {
        if (10..=64).contains(&self.0.len())
            && self
                .0
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'=' || b == b'-')
        {
            Ok(())
        } else {
            Err(VALIDATION_ERROR.into())
        }
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Debug for SubscriptionIdentity {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("[HWID redacted]")
    }
}

pub fn os_version() -> String {
    #[cfg(windows)]
    {
        let version = windows_version::OsVersion::current();
        format!("{}.{}.{}", version.major, version.minor, version.build)
    }
    #[cfg(not(windows))]
    {
        "unknown".into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generation_is_compatible_and_independent() {
        let mut values = std::collections::HashSet::new();
        for _ in 0..1000 {
            let identity = SubscriptionIdentity::generate().unwrap();
            identity.validate().unwrap();
            assert_eq!(identity.as_str().len(), 36);
            assert!(identity.as_str().starts_with("dot-"));
            assert!(identity.as_str()[4..]
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)));
            assert!(values.insert(identity.as_str().to_string()));
            assert!(!format!("{identity:?}").contains(identity.as_str()));
        }
    }

    #[test]
    fn validation_matches_android_and_frontend_fixtures_without_normalization() {
        let fixtures: serde_json::Value = serde_json::from_str(include_str!(
            "../../../tests/fixtures/subscription-hwid.json"
        ))
        .unwrap();
        for case in fixtures["cases"].as_array().unwrap() {
            let value = case["value"].as_str().unwrap();
            let result = SubscriptionIdentity::parse(value.to_string());
            assert_eq!(result.is_ok(), case["valid"].as_bool().unwrap());
            if let Ok(identity) = result {
                assert_eq!(identity.as_str(), value);
            }
        }
    }

    #[cfg(windows)]
    #[test]
    fn metadata_uses_actual_windows_version() {
        let version = os_version();
        let parts: Vec<_> = version
            .split('.')
            .map(|part| part.parse::<u32>().unwrap())
            .collect();
        assert_eq!(parts.len(), 3);
        assert!(parts[0] >= 10);
        assert!(parts[2] > 0);
    }
}
