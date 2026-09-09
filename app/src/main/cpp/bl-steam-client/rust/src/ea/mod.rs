//! EA storefront client — the account, its licences, and the socket the game asks for them on.
//!
//! This is a sibling of [`crate::store_dl`], not part of it: EA is the one store in the app where
//! nothing is bought or downloaded. The games arrive through Steam. What EA owns is *permission* —
//! whether the copy of an EA-published title sitting in a Steam library is allowed to start — and
//! answering that is the whole job here.
//!
//! Three pieces, in dependency order:
//!
//! 1. [`lsx`] — the localhost listener the game's Origin SDK connects to. Capture mode works today;
//!    serve mode waits on a captured transcript.
//! 2. `auth` — one interactive EA sign-in, then a refresh token. Never a stored password.
//! 3. `ooa` — fetch the licence, persist it as `<contentId>.dlf`, and hand it to [`lsx`] when the
//!    game asks. Refreshing roughly fortnightly instead of per launch is the point: each fresh
//!    activation is spent against EA's "too many computers" quota, and that quota, not our code, has
//!    set the pace on every EA problem we have.
//!
//! # The rule this module lives under
//!
//! Anything that fails here must fail *backwards*, into the EA Desktop chain that already ships and
//! already launches games. A user whose game starts today must never find it broken because our
//! licence path had an opinion. Every entry point returns enough for the caller to decide "fall
//! back", and no path blocks a launch waiting on us.

pub mod crypto;
pub mod lsx;

/// Environment the game process needs so its Origin SDK talks to us instead of EA Desktop.
///
/// These names are the SDK's, not ours. They are delivered exactly the way the Steam block already
/// is — through the shortcut's `envVars=`, applied by `GuestProgramLauncherComponent` — so this
/// carries no new launch machinery.
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub struct LaunchEnv {
    /// Port of our [`lsx::LsxServer`]. Without this the SDK looks for real EA Desktop.
    pub lsx_port: u16,
    /// EA's id for the title being launched.
    pub content_id: String,
    pub generic_auth_token: String,
    pub launch_user_auth_token: String,
    pub access_token_jws: String,
    pub rtp_launch_code: String,
}

impl LaunchEnv {
    /// Render as `KEY=VALUE` pairs for the shortcut's env block.
    ///
    /// Empty fields are omitted rather than exported blank: an empty `EAGenericAuthToken` is not the
    /// same as an absent one to the SDK, and exporting a blank would present us as a launcher that
    /// is present but broken instead of one that is not there — which is exactly the state the EA
    /// Desktop fallback needs to see.
    pub fn to_pairs(&self) -> Vec<(String, String)> {
        let mut out = Vec::new();
        if self.lsx_port != 0 {
            out.push(("EALsxPort".into(), self.lsx_port.to_string()));
        }
        for (key, value) in [
            ("ContentId", &self.content_id),
            ("EAGenericAuthToken", &self.generic_auth_token),
            ("EALaunchUserAuthToken", &self.launch_user_auth_token),
            ("EAAccessTokenJWS", &self.access_token_jws),
            ("EARtPLaunchCode", &self.rtp_launch_code),
        ] {
            if !value.is_empty() {
                out.push((key.into(), value.clone()));
            }
        }
        out
    }

    /// True when this block is complete enough to put us in the launch path at all.
    ///
    /// The caller uses this as the fallback gate: false means leave the environment alone and let
    /// EA Desktop start, which is the behaviour that ships today.
    pub fn is_usable(&self) -> bool {
        self.lsx_port != 0 && !self.content_id.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn incomplete_env_never_claims_the_launch() {
        assert!(!LaunchEnv::default().is_usable());
        assert!(!LaunchEnv { lsx_port: 5000, ..Default::default() }.is_usable());
        assert!(LaunchEnv {
            lsx_port: 5000,
            content_id: "1035208".into(),
            ..Default::default()
        }
        .is_usable());
    }

    #[test]
    fn blank_tokens_are_omitted_not_exported_empty() {
        let env = LaunchEnv {
            lsx_port: 5000,
            content_id: "1035208".into(),
            ..Default::default()
        };
        let pairs = env.to_pairs();
        assert_eq!(pairs.len(), 2, "only the two set fields: {pairs:?}");
        assert!(pairs.iter().any(|(k, v)| k == "EALsxPort" && v == "5000"));
        assert!(pairs.iter().any(|(k, v)| k == "ContentId" && v == "1035208"));
        assert!(!pairs.iter().any(|(k, _)| k == "EAGenericAuthToken"));
    }
}
