use crate::{TlsIdentityError, TlsIdentityKey};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

const IDENTITY_MAGIC: &[u8] = b"CQTI\x01";
const PHONE_TOKEN_HASH_MAGIC: &[u8] = b"CQPH\x01";

#[derive(Debug)]
pub enum IdentityStoreError {
    Io(io::Error),
    InvalidFile,
    Identity(TlsIdentityError),
}

impl std::fmt::Display for IdentityStoreError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "identity file I/O failed: {error}"),
            Self::InvalidFile => formatter.write_str("invalid persisted identity file"),
            Self::Identity(error) => write!(formatter, "invalid persisted identity: {error:?}"),
        }
    }
}

impl std::error::Error for IdentityStoreError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(error) => Some(error),
            Self::InvalidFile | Self::Identity(_) => None,
        }
    }
}

impl From<io::Error> for IdentityStoreError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

pub struct TlsIdentityStore {
    path: PathBuf,
}

pub struct PhoneTokenHashStore {
    path: PathBuf,
}

impl PhoneTokenHashStore {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    pub fn load(&self) -> Result<Option<[u8; 32]>, IdentityStoreError> {
        if !self.path.exists() {
            return Ok(None);
        }
        let payload = fs::read(&self.path)?;
        if payload.len() != PHONE_TOKEN_HASH_MAGIC.len() + 32
            || !payload.starts_with(PHONE_TOKEN_HASH_MAGIC)
        {
            return Err(IdentityStoreError::InvalidFile);
        }
        let mut hash = [0_u8; 32];
        hash.copy_from_slice(&payload[PHONE_TOKEN_HASH_MAGIC.len()..]);
        Ok(Some(hash))
    }

    pub fn save(&self, hash: Option<[u8; 32]>) -> Result<(), IdentityStoreError> {
        let Some(hash) = hash else {
            match fs::remove_file(&self.path) {
                Ok(()) => return Ok(()),
                Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(()),
                Err(error) => return Err(error.into()),
            }
        };
        if let Some(parent) = self
            .path
            .parent()
            .filter(|parent| !parent.as_os_str().is_empty())
        {
            fs::create_dir_all(parent)?;
        }
        let mut payload = Vec::with_capacity(PHONE_TOKEN_HASH_MAGIC.len() + hash.len());
        payload.extend_from_slice(PHONE_TOKEN_HASH_MAGIC);
        payload.extend_from_slice(&hash);
        atomic_write(&self.path, &payload)
    }
}

impl TlsIdentityStore {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    pub fn load_or_create(&self) -> Result<TlsIdentityKey, IdentityStoreError> {
        if self.path.exists() {
            return self.load();
        }
        let identity = TlsIdentityKey::generate().map_err(IdentityStoreError::Identity)?;
        self.persist(&identity)?;
        Ok(identity)
    }

    pub fn load(&self) -> Result<TlsIdentityKey, IdentityStoreError> {
        let payload = fs::read(&self.path)?;
        if payload.len() <= IDENTITY_MAGIC.len() || !payload.starts_with(IDENTITY_MAGIC) {
            return Err(IdentityStoreError::InvalidFile);
        }
        let protected = &payload[IDENTITY_MAGIC.len()..];
        let identity = unprotect(protected).map_err(IdentityStoreError::Identity)?;
        TlsIdentityKey::from_private_key_der(identity).map_err(IdentityStoreError::Identity)
    }

    pub fn persist(&self, identity: &TlsIdentityKey) -> Result<(), IdentityStoreError> {
        if let Some(parent) = self
            .path
            .parent()
            .filter(|parent| !parent.as_os_str().is_empty())
        {
            fs::create_dir_all(parent)?;
        }
        let protected = protect(identity).map_err(IdentityStoreError::Identity)?;
        let mut payload = Vec::with_capacity(IDENTITY_MAGIC.len() + protected.len());
        payload.extend_from_slice(IDENTITY_MAGIC);
        payload.extend_from_slice(&protected);
        atomic_write(&self.path, &payload)
    }
}

fn atomic_write(path: &Path, payload: &[u8]) -> Result<(), IdentityStoreError> {
    let temporary = path.with_extension("tmp");
    fs::write(&temporary, payload)?;
    match fs::rename(&temporary, path) {
        Ok(()) => Ok(()),
        Err(error) => {
            let _ = fs::remove_file(&temporary);
            Err(IdentityStoreError::Io(error))
        }
    }
}

#[cfg(windows)]
fn protect(identity: &TlsIdentityKey) -> Result<Vec<u8>, TlsIdentityError> {
    identity.protect_for_current_user()
}

#[cfg(windows)]
fn unprotect(payload: &[u8]) -> Result<Vec<u8>, TlsIdentityError> {
    TlsIdentityKey::from_current_user_protected(payload)
        .map(|identity| identity.private_key_der().to_vec())
}

#[cfg(not(windows))]
fn protect(identity: &TlsIdentityKey) -> Result<Vec<u8>, TlsIdentityError> {
    Ok(identity.private_key_der().to_vec())
}

#[cfg(not(windows))]
fn unprotect(payload: &[u8]) -> Result<Vec<u8>, TlsIdentityError> {
    Ok(payload.to_vec())
}
