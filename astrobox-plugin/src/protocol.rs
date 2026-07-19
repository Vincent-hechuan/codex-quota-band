use serde::Deserialize;
use serde_json::{Value, json};
use std::net::Ipv4Addr;

#[derive(Debug, PartialEq)]
pub struct QuotaRequest {
    pub nonce: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct RawQuotaRequest {
    #[serde(rename = "type")]
    kind: String,
    nonce: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct PairingResponse {
    token: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RawDeepLinkPairingPayload {
    protocol_version: u8,
    kind: String,
    base_urls: Vec<String>,
    code: String,
}

#[derive(Debug, PartialEq)]
pub struct DeepLinkPairingPayload {
    pub base_urls: Vec<String>,
    pub code: String,
}

pub fn parse_quota_request(payload: &str) -> Result<QuotaRequest, String> {
    let request: RawQuotaRequest =
        serde_json::from_str(payload).map_err(|_| "invalid band request".to_string())?;
    if request.kind != "quota_request" || !valid_nonce(&request.nonce) {
        return Err("invalid band request".into());
    }
    Ok(QuotaRequest {
        nonce: request.nonce,
    })
}

pub fn validate_base_url(input: &str) -> Result<String, String> {
    let normalized = input.trim().strip_suffix('/').unwrap_or(input.trim());
    let authority = normalized
        .strip_prefix("http://")
        .ok_or_else(|| "endpoint must use http on a private network".to_string())?;
    if authority.contains(['/', '?', '#', '@']) {
        return Err("endpoint must contain only a private IPv4 address and port".into());
    }
    let (host, port_text) = authority
        .split_once(':')
        .ok_or_else(|| "endpoint port is required".to_string())?;
    if port_text.contains(':') {
        return Err("endpoint must use IPv4".into());
    }
    let address: Ipv4Addr = host
        .parse()
        .map_err(|_| "endpoint must use a numeric IPv4 address".to_string())?;
    let octets = address.octets();
    let is_private = octets[0] == 10
        || (octets[0] == 172 && (16..=31).contains(&octets[1]))
        || (octets[0] == 192 && octets[1] == 168)
        || (octets[0] == 169 && octets[1] == 254);
    if !is_private {
        return Err("endpoint must be on a private network".into());
    }
    let port: u16 = port_text
        .parse()
        .map_err(|_| "endpoint port is invalid".to_string())?;
    if port == 0 {
        return Err("endpoint port is invalid".into());
    }
    Ok(format!("http://{address}:{port}"))
}

pub fn pairing_request_body(code: &str) -> Result<String, String> {
    if code.len() != 6 || !code.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err("pairing code must contain six digits".into());
    }
    serde_json::to_string(&json!({ "code": code })).map_err(|_| "pairing request failed".into())
}

pub fn parse_deeplink_pairing_payload(payload: &str) -> Result<DeepLinkPairingPayload, String> {
    const MAXIMUM_PAYLOAD_BYTES: usize = 2_048;
    const MAXIMUM_ENDPOINTS: usize = 8;

    if payload.len() > MAXIMUM_PAYLOAD_BYTES {
        return Err("pairing payload is too large".into());
    }
    let raw: RawDeepLinkPairingPayload =
        serde_json::from_str(payload).map_err(|_| "invalid pairing payload".to_string())?;
    if raw.protocol_version != 1 || raw.kind != "codex-quota-pairing" {
        return Err("unsupported pairing payload".into());
    }
    if raw.base_urls.is_empty() || raw.base_urls.len() > MAXIMUM_ENDPOINTS {
        return Err("pairing endpoints are invalid".into());
    }
    if raw.code.len() != 6 || !raw.code.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err("pairing code must contain six digits".into());
    }

    let mut base_urls = Vec::with_capacity(raw.base_urls.len());
    for candidate in raw.base_urls {
        let normalized = validate_base_url(&candidate)?;
        if !base_urls.contains(&normalized) {
            base_urls.push(normalized);
        }
    }
    Ok(DeepLinkPairingPayload {
        base_urls,
        code: raw.code,
    })
}

pub fn parse_pairing_token(body: &str) -> Result<String, String> {
    let response: PairingResponse =
        serde_json::from_str(body).map_err(|_| "invalid pairing response".to_string())?;
    if response.token.len() != 43
        || !response
            .token
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
    {
        return Err("invalid pairing response".into());
    }
    Ok(response.token)
}

pub fn snapshot_envelope(snapshot: &str, nonce: &str) -> Result<String, String> {
    if !valid_nonce(nonce) {
        return Err("invalid nonce".into());
    }
    let snapshot: Value =
        serde_json::from_str(snapshot).map_err(|_| "invalid snapshot response".to_string())?;
    if !snapshot.is_object() || snapshot.get("protocolVersion").and_then(Value::as_u64) != Some(1) {
        return Err("unsupported snapshot protocol".into());
    }
    serde_json::to_string(&json!({
        "type": "quota_snapshot",
        "nonce": nonce,
        "snapshot": snapshot,
    }))
    .map_err(|_| "snapshot envelope failed".into())
}

pub fn error_envelope(nonce: &str, code: &str) -> String {
    json!({
        "type": "quota_error",
        "nonce": if valid_nonce(nonce) { nonce } else { "invalid" },
        "code": code,
    })
    .to_string()
}

fn valid_nonce(nonce: &str) -> bool {
    !nonce.is_empty()
        && nonce.len() <= 64
        && nonce
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    #[test]
    fn band_request_contract_is_closed_and_nonce_is_bounded() {
        assert_eq!(
            parse_quota_request(r#"{"type":"quota_request","nonce":"band-42"}"#),
            Ok(QuotaRequest {
                nonce: "band-42".into()
            })
        );
        assert!(
            parse_quota_request(r#"{"type":"quota_request","nonce":"band-42","token":"leak"}"#)
                .is_err()
        );
        assert!(parse_quota_request(r#"{"type":"quota_request","nonce":"bad space"}"#).is_err());
        assert!(
            parse_quota_request(&format!(
                r#"{{"type":"quota_request","nonce":"{}"}}"#,
                "a".repeat(65)
            ))
            .is_err()
        );
    }

    #[test]
    fn only_numeric_private_http_endpoints_are_accepted() {
        assert_eq!(
            validate_base_url("http://192.168.31.8:17321/"),
            Ok("http://192.168.31.8:17321".into())
        );
        for rejected in [
            "https://192.168.31.8:17321",
            "http://8.8.8.8:17321",
            "http://user@192.168.31.8:17321",
            "http://192.168.31.8:17321/path",
            "http://localhost:17321",
        ] {
            assert!(validate_base_url(rejected).is_err(), "{rejected}");
        }
    }

    #[test]
    fn pairing_and_snapshot_envelopes_only_accept_the_versioned_contract() {
        assert_eq!(
            pairing_request_body("123456"),
            Ok(r#"{"code":"123456"}"#.into())
        );
        assert!(pairing_request_body("1234567").is_err());

        let token = "A".repeat(43);
        assert_eq!(
            parse_pairing_token(&format!(r#"{{"token":"{token}"}}"#)),
            Ok(token.clone())
        );
        assert!(parse_pairing_token(r#"{"token":"short"}"#).is_err());

        let snapshot = r#"{"protocolVersion":1,"sourceStatus":"ok"}"#;
        let envelope: Value = serde_json::from_str(
            &snapshot_envelope(snapshot, "band-42").expect("valid snapshot envelope"),
        )
        .unwrap();
        assert_eq!(envelope["type"], "quota_snapshot");
        assert_eq!(envelope["nonce"], "band-42");
        assert_eq!(envelope["snapshot"]["protocolVersion"], 1);
        assert!(snapshot_envelope(r#"{"protocolVersion":2}"#, "band-42").is_err());
    }

    #[test]
    fn deeplink_pairing_payload_is_closed_versioned_and_private() {
        let payload = parse_deeplink_pairing_payload(
            r#"{"protocolVersion":1,"kind":"codex-quota-pairing","baseUrls":["http://192.168.3.2:17321","http://10.0.0.8:17321"],"code":"123456"}"#,
        )
        .expect("valid DeepLink pairing payload");
        assert_eq!(
            payload.base_urls,
            vec![
                "http://192.168.3.2:17321".to_string(),
                "http://10.0.0.8:17321".to_string()
            ]
        );
        assert_eq!(payload.code, "123456");

        for rejected in [
            r#"{"protocolVersion":2,"kind":"codex-quota-pairing","baseUrls":["http://192.168.3.2:17321"],"code":"123456"}"#,
            r#"{"protocolVersion":1,"kind":"wrong","baseUrls":["http://192.168.3.2:17321"],"code":"123456"}"#,
            r#"{"protocolVersion":1,"kind":"codex-quota-pairing","baseUrls":["http://8.8.8.8:17321"],"code":"123456"}"#,
            r#"{"protocolVersion":1,"kind":"codex-quota-pairing","baseUrls":[],"code":"123456"}"#,
            r#"{"protocolVersion":1,"kind":"codex-quota-pairing","baseUrls":["http://192.168.3.2:17321"],"code":"12345"}"#,
            r#"{"protocolVersion":1,"kind":"codex-quota-pairing","baseUrls":["http://192.168.3.2:17321"],"code":"123456","token":"leak"}"#,
        ] {
            assert!(
                parse_deeplink_pairing_payload(rejected).is_err(),
                "accepted {rejected}"
            );
        }
    }
}
