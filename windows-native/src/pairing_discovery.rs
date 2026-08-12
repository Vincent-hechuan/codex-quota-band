use crate::PairingQrOffer;
use serde::Serialize;
use std::io;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket};
use std::thread::{self, JoinHandle};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

pub const PAIRING_DISCOVERY_PORT: u16 = 37_231;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PairingDiscoveryAnnouncement {
    protocol_version: u8,
    #[serde(rename = "type")]
    announcement_type: &'static str,
    computer_fingerprint: String,
    endpoints: Vec<String>,
    expires_at_ms: i64,
}

impl PairingDiscoveryAnnouncement {
    pub fn new(
        computer_fingerprint: String,
        endpoints: Vec<String>,
        expires_at_ms: i64,
    ) -> Result<Self, &'static str> {
        if computer_fingerprint.len() != 64
            || !computer_fingerprint
                .bytes()
                .all(|value| value.is_ascii_digit() || (b'a'..=b'f').contains(&value))
        {
            return Err("invalid computer fingerprint");
        }
        if endpoints.is_empty() || endpoints.len() > 8 || expires_at_ms < 0 {
            return Err("invalid pairing discovery");
        }
        Ok(Self {
            protocol_version: 1,
            announcement_type: "pairing_discovery",
            computer_fingerprint,
            endpoints,
            expires_at_ms,
        })
    }

    pub fn from_offer(offer: &PairingQrOffer) -> Result<Self, &'static str> {
        Self::new(
            offer.computer_fingerprint.clone(),
            offer.endpoints.clone(),
            offer.expires_at_ms,
        )
    }

    pub fn security_code(&self) -> String {
        format!(
            "{}-{}",
            self.computer_fingerprint[..4].to_ascii_uppercase(),
            self.computer_fingerprint[4..8].to_ascii_uppercase()
        )
    }

    pub fn expires_at_ms(&self) -> i64 {
        self.expires_at_ms
    }

    pub fn is_active_at(&self, now_ms: i64) -> bool {
        now_ms >= 0 && now_ms <= self.expires_at_ms
    }

    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }

    pub fn send_once_to(&self, target: SocketAddr) -> Result<usize, io::Error> {
        let socket = UdpSocket::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0))?;
        socket.set_broadcast(true)?;
        let payload = self
            .to_json()
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
        socket.send_to(payload.as_bytes(), target)
    }

    pub fn spawn_broadcast(self) -> Result<JoinHandle<()>, io::Error> {
        let socket = UdpSocket::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0))?;
        socket.set_broadcast(true)?;
        let payload = self
            .to_json()
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?
            .into_bytes();
        Ok(thread::spawn(move || {
            let target = SocketAddrV4::new(Ipv4Addr::BROADCAST, PAIRING_DISCOVERY_PORT);
            while self.is_active_at(now_ms()) {
                let _ = socket.send_to(&payload, target);
                thread::sleep(Duration::from_millis(750));
            }
        }))
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .and_then(|duration| i64::try_from(duration.as_millis()).ok())
        .unwrap_or(i64::MAX)
}
