// this file is a copy of monero-oxide's simple-request-transport, but uses reqwest instead of hyper

use core::{future::Future, time::Duration};
use std::sync::Arc;

use digest_auth::{AuthContext, WwwAuthenticateHeader};
use reqwest::{Client, StatusCode};
use tokio::sync::Mutex;
use url::Url;
use zeroize::Zeroizing;

use monero_daemon_rpc::{HttpTransport, MoneroDaemon, prelude::InterfaceError};

use crate::util::HexFormatted;

const DEFAULT_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Debug)]
enum Authentication {
    Unauthenticated,
    Authenticated { username: Zeroizing<String>, password: Zeroizing<String>, state: Arc<Mutex<Option<(WwwAuthenticateHeader, u64)>>> },
}

/// An HTTP(S) transport to connect to a Monero daemon.
///
/// Supports plain HTTP, HTTPS, and SOCKS5 proxies. Digest authentication
/// is handled automatically when credentials are included in the URL.
#[derive(Clone, Debug)]
pub struct MonfluoTransport {
    read_response_before_next_request: Arc<Mutex<()>>,
    authentication: Authentication,
    request_timeout: Duration,
    base_url: String,
    client: Client,
}

impl MonfluoTransport {
    fn digest_auth_challenge(response: &reqwest::Response) -> Result<Option<(WwwAuthenticateHeader, u64)>, InterfaceError> {
        Ok(if let Some(header) = response.headers().get("www-authenticate") {
            Some((
                digest_auth::parse(
                    header
                        .to_str()
                        .map_err(|_| InterfaceError::InvalidInterface("www-authenticate header wasn't a string".to_owned()))?,
                )
                .map_err(|_| InterfaceError::InvalidInterface("invalid digest-auth response".to_owned()))?,
                0,
            ))
        } else {
            None
        })
    }

    fn parse_url(url: &str) -> Result<(String, Option<(String, String)>), InterfaceError> {
        let mut parsed = Url::parse(url).map_err(|_| InterfaceError::InterfaceError("invalid URL".to_owned()))?;

        let username = parsed.username();
        let password = parsed.password();

        if !username.is_empty() || password.is_some() {
            let username = username.to_string();
            let password = password.unwrap_or("").to_string();
            parsed
                .set_username("")
                .map_err(|_| InterfaceError::InterfaceError("failed to clear username from URL".to_owned()))?;
            parsed
                .set_password(None)
                .map_err(|_| InterfaceError::InterfaceError("failed to clear password from URL".to_owned()))?;
            Ok((parsed.to_string(), Some((username, password))))
        } else {
            Ok((parsed.to_string(), None))
        }
    }

    fn create_client(proxy: Option<reqwest::Proxy>) -> Result<Client, InterfaceError> {
        let mut builder = Client::builder();
        if let Some(p) = proxy {
            builder = builder.proxy(p);
        }
        builder
            .build()
            .map_err(|e| InterfaceError::InterfaceError(format!("failed to create HTTP client: {e}")))
    }

    /// Create a new HTTP(S) RPC connection.
    ///
    /// The URL is parsed with the [`url`] crate. A daemon requiring authentication may be used
    /// by including the username and password in the URL
    /// (`scheme://username:password@domain.tld`).
    ///
    /// # Arguments
    ///
    /// * `url` - The daemon URL (e.g., `http://localhost:18081` or `http://user:pass@localhost:18081`)
    /// * `proxy` - Optional SOCKS5 proxy (e.g., `reqwest::Proxy::all("socks5h://127.0.0.1:1080")?`)
    pub async fn new(url: String, proxy: Option<reqwest::Proxy>) -> Result<MoneroDaemon<MonfluoTransport>, InterfaceError> {
        Self::with_custom_timeout(url, DEFAULT_TIMEOUT, proxy).await
    }

    /// Create a new HTTP(S) RPC connection with a custom timeout.
    ///
    /// # Arguments
    ///
    /// * `url` - The daemon URL (e.g., `http://localhost:18081` or `http://user:pass@localhost:18081`)
    /// * `request_timeout` - Maximum duration for each request
    /// * `proxy` - Optional SOCKS5 proxy (e.g., `reqwest::Proxy::all("socks5h://127.0.0.1:1080")?`)
    pub async fn with_custom_timeout(url: String, request_timeout: Duration, proxy: Option<reqwest::Proxy>) -> Result<MoneroDaemon<MonfluoTransport>, InterfaceError> {
        let client = Self::create_client(proxy)?;
        let (base_url, credentials) = Self::parse_url(&url)?;

        let authentication = if let Some((username, password)) = credentials {
            let response = client
                .post(&base_url)
                .body(vec![])
                .send()
                .await
                .map_err(|e| InterfaceError::InterfaceError(format!("couldn't make request: {e:?}")))?;

            let challenge = Self::digest_auth_challenge(&response)?;

            Authentication::Authenticated {
                username: Zeroizing::new(username),
                password: Zeroizing::new(password),
                state: Arc::new(Mutex::new(challenge)),
            }
        } else {
            Authentication::Unauthenticated
        };

        MoneroDaemon::new(MonfluoTransport {
            read_response_before_next_request: Arc::new(Mutex::new(())),
            authentication,
            request_timeout,
            base_url,
            client,
        })
        .await
    }

    async fn inner_post(&self, route: &str, body: Vec<u8>, response_size_limit: Option<usize>) -> Result<Vec<u8>, InterfaceError> {
        let _guard = self.read_response_before_next_request.lock().await;

        let url = format!("{}{}", self.base_url, route);

        for attempt in 0..2 {
            match &self.authentication {
                Authentication::Unauthenticated => {
                    let response = self
                        .client
                        .post(&url)
                        .body(body)
                        .send()
                        .await
                        .map_err(|e| InterfaceError::InterfaceError(format!("{e:?}")))?;
                    return Self::body_from_response(response, response_size_limit).await;
                }
                Authentication::Authenticated {
                    username,
                    password,
                    state,
                } => {
                    let mut state_lock = state.lock().await;

                    if state_lock.is_none() {
                        let response = self
                            .client
                            .post(&url)
                            .body(body.clone())
                            .send()
                            .await
                            .map_err(|e| InterfaceError::InterfaceError(format!("{e:?}")))?;
                        *state_lock = Self::digest_auth_challenge(&response)?;
                    }

                    let mut request_builder = self.client.post(&url).body(body.clone());

                    if let Some((challenge, cnonce)) = state_lock.as_mut() {
                        *cnonce += 1;

                        let mut ctx = AuthContext::new_post::<_, _, _, &[u8]>(username.as_str(), password.as_str(), route, None);
                        ctx.set_custom_cnonce(HexFormatted::borrowed(&cnonce.to_le_bytes()).to_string());

                        let auth_header = challenge
                            .respond(&ctx)
                            .map_err(|_| InterfaceError::InvalidInterface("couldn't respond to digest-auth challenge".to_owned()))?
                            .to_header_string();

                        request_builder = request_builder.header("Authorization", auth_header);
                    }

                    let response_result = request_builder.send().await;

                    let (response, error) = match response_result {
                        Ok(r) => (Some(r), None),
                        Err(e) => (None, Some(InterfaceError::InterfaceError(format!("{e:?}")))),
                    };

                    let (is_error, is_stale) = match &response {
                        Some(r) => (
                            false,
                            if r.status() == StatusCode::UNAUTHORIZED {
                                r.headers()
                                    .get("www-authenticate")
                                    .and_then(|h| h.to_str().ok())
                                    .map(|s| s.contains("stale"))
                                    .unwrap_or(false)
                            } else {
                                false
                            },
                        ),
                        None => (true, false),
                    };

                    if is_error || is_stale {
                        *state_lock = None;
                        if attempt == 0 {
                            continue;
                        }
                        if let Some(e) = error {
                            return Err(e);
                        } else {
                            debug_assert!(is_stale);
                            return Err(InterfaceError::InvalidInterface("node claimed fresh connection had stale authentication".to_owned()));
                        }
                    }

                    let response = response.expect("no response yet also no error?");
                    let bytes = response
                        .bytes()
                        .await
                        .map_err(|e| InterfaceError::InterfaceError(format!("{e:?}")))?;

                    if let Some(limit) = response_size_limit
                        && bytes.len() > limit
                    {
                        return Err(InterfaceError::InterfaceError(format!("response size {} exceeds limit {}", bytes.len(), limit)));
                    }

                    return Ok(bytes.to_vec());
                }
            }
        }

        unreachable!()
    }

    async fn body_from_response(response: reqwest::Response, response_size_limit: Option<usize>) -> Result<Vec<u8>, InterfaceError> {
        let bytes = response
            .bytes()
            .await
            .map_err(|e| InterfaceError::InterfaceError(format!("{e:?}")))?;

        if let Some(limit) = response_size_limit
            && bytes.len() > limit
        {
            return Err(InterfaceError::InterfaceError(format!("response size {} exceeds limit {}", bytes.len(), limit)));
        }

        Ok(bytes.to_vec())
    }
}

impl HttpTransport for MonfluoTransport {
    fn post(&self, route: &str, body: Vec<u8>, response_size_limit: Option<usize>) -> impl Send + Future<Output = Result<Vec<u8>, InterfaceError>> {
        async move {
            tokio::time::timeout(self.request_timeout, self.inner_post(route, body, response_size_limit))
                .await
                .map_err(|e| InterfaceError::InterfaceError(format!("timeout reached: {e:?}")))?
        }
    }
}
