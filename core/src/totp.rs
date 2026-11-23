//! Time-based one-time passwords (TOTP, RFC 6238), read from the settings an entry stores.
//!
//! KeePass apps store TOTP settings in one of three ways, all read here:
//!
//! - **`otp`** (KeePassXC, KeePassDX, Keepass2Android): an `otpauth://totp/…` URI, the same
//!   thing a setup QR code holds. KeePassXC also accepts the KeeOTP plugin's
//!   `key=…&size=…&step=…` form here, and so do we.
//! - **`TimeOtp-*`** (KeePass 2.47 and later): the secret in one of `TimeOtp-Secret` (UTF-8),
//!   `TimeOtp-Secret-Hex`, `TimeOtp-Secret-Base32`, or `TimeOtp-Secret-Base64`, plus optional
//!   `TimeOtp-Length`, `TimeOtp-Period`, and `TimeOtp-Algorithm`.
//! - **`TOTP Seed`** with **`TOTP Settings`** (`period;digits`, or `period;S` for Steam): what
//!   KeePassXC wrote before 2.6, and the TrayTOTP plugin for KeePass 2. KeePassXC still reads it.
//!
//! If an entry has more than one, `otp` wins, then `TimeOtp-*`, as in KeePassXC.

use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;
use hmac::{EagerHash, Hmac, KeyInit, Mac};
use sha1::Sha1;
use sha2::{Sha256, Sha512};
use zeroize::Zeroizing;

use crate::error::{Error, Result};
use crate::model::Entry;
use crate::util::hex_decode;

/// The field KeePassXC and KeePassDX keep their TOTP settings in.
pub const OTP_FIELD: &str = "otp";

const KEEPASS_SECRETS: [(&str, SecretEncoding); 4] = [
    ("TimeOtp-Secret", SecretEncoding::Utf8),
    ("TimeOtp-Secret-Hex", SecretEncoding::Hex),
    ("TimeOtp-Secret-Base32", SecretEncoding::Base32),
    ("TimeOtp-Secret-Base64", SecretEncoding::Base64),
];
const KEEPASS_LENGTH: &str = "TimeOtp-Length";
const KEEPASS_PERIOD: &str = "TimeOtp-Period";
const KEEPASS_ALGORITHM: &str = "TimeOtp-Algorithm";
const LEGACY_SEED: &str = "TOTP Seed";
const LEGACY_SETTINGS: &str = "TOTP Settings";

/// Steam Guard's codes: 5 characters from this alphabet instead of digits.
const STEAM_ALPHABET: &[u8; 26] = b"23456789BCDFGHJKMNPQRTVWXY";
const STEAM_LENGTH: u32 = 5;

const DEFAULT_DIGITS: u32 = 6;
const DEFAULT_PERIOD: u32 = 30;
/// KeePassXC's limits. A code longer than 10 digits can't be made from the 31 bits RFC 4226
/// takes from the HMAC, and a period longer than a day isn't a real setup.
const MAX_DIGITS: u32 = 10;
const MAX_PERIOD: u32 = 86_400;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TotpAlgorithm {
    Sha1,
    Sha256,
    Sha512,
}

#[derive(Clone, Copy)]
enum SecretEncoding {
    Utf8,
    Hex,
    Base32,
    Base64,
}

/// An entry's TOTP settings, ready to make codes. The secret is wiped when this is dropped.
pub struct Totp {
    secret: Zeroizing<Vec<u8>>,
    algorithm: TotpAlgorithm,
    /// The code's length: digits, or characters for Steam.
    length: u32,
    period: u32,
    steam: bool,
    /// The fields the settings were read from.
    fields: Vec<String>,
}

impl std::fmt::Debug for Totp {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Never the secret.
        f.debug_struct("Totp")
            .field("algorithm", &self.algorithm)
            .field("length", &self.length)
            .field("period", &self.period)
            .field("steam", &self.steam)
            .finish_non_exhaustive()
    }
}

impl Totp {
    /// Reads an entry's TOTP settings. `Ok(None)` if it has none. `Err(Error::Invalid)`, with a
    /// sentence for the user, if it has some that can't be used (a secret that isn't valid
    /// Base32, an unknown algorithm, a counter-based HOTP setup, and so on).
    pub fn from_entry(entry: &Entry<'_>) -> Result<Option<Self>> {
        let present = |key: &str| entry.field(key).filter(|v| !v.trim().is_empty());

        if let Some(value) = present(OTP_FIELD) {
            return parse_otp_field(value).map(|t| Some(t.read_from(&[OTP_FIELD])));
        }

        if let Some((key, encoding)) = KEEPASS_SECRETS
            .iter()
            .find(|(key, _)| present(key).is_some())
        {
            let secret = decode_secret(present(key).unwrap_or_default(), *encoding)?;
            let mut fields = vec![*key];
            let mut setting = |name: &'static str| {
                let value = present(name).map(str::trim);
                if value.is_some() {
                    fields.push(name);
                }
                value
            };
            let length = setting(KEEPASS_LENGTH)
                .map(parse_length)
                .transpose()?
                .unwrap_or(DEFAULT_DIGITS);
            let period = setting(KEEPASS_PERIOD)
                .map(parse_period)
                .transpose()?
                .unwrap_or(DEFAULT_PERIOD);
            let algorithm = setting(KEEPASS_ALGORITHM)
                .map(parse_algorithm)
                .transpose()?
                .unwrap_or(TotpAlgorithm::Sha1);
            return Self::new(secret, algorithm, length, period, false)
                .map(|t| Some(t.read_from(&fields)));
        }

        if let Some(seed) = present(LEGACY_SEED) {
            let secret = decode_secret(seed, SecretEncoding::Base32)?;
            let (mut length, mut period, mut steam) = (DEFAULT_DIGITS, DEFAULT_PERIOD, false);
            let mut fields = vec![LEGACY_SEED];
            if let Some(settings) = present(LEGACY_SETTINGS) {
                fields.push(LEGACY_SETTINGS);
                // "period;digits", or "period;S" for Steam. TrayTOTP adds a third part (a time
                // server), which doesn't matter here.
                let mut parts = settings.split(';').map(str::trim);
                period = parse_period(parts.next().unwrap_or_default())?;
                match parts.next() {
                    Some("S") => steam = true,
                    Some(digits) if !digits.is_empty() => length = parse_length(digits)?,
                    _ => {}
                }
            }
            return Self::new(secret, TotpAlgorithm::Sha1, length, period, steam)
                .map(|t| Some(t.read_from(&fields)));
        }

        Ok(None)
    }

    fn new(
        secret: Zeroizing<Vec<u8>>,
        algorithm: TotpAlgorithm,
        length: u32,
        period: u32,
        steam: bool,
    ) -> Result<Self> {
        if secret.is_empty() {
            return Err(invalid("The TOTP secret is empty."));
        }
        Ok(Self {
            secret,
            algorithm,
            length: if steam { STEAM_LENGTH } else { length },
            period,
            steam,
            fields: Vec::new(),
        })
    }

    fn read_from(mut self, fields: &[&str]) -> Self {
        self.fields = fields.iter().map(|f| (*f).to_owned()).collect();
        self
    }

    /// The code for a moment in time, given in seconds since 1970 (UTC). ASCII: digits, or
    /// Steam's letters and digits.
    pub fn code_at(&self, unix_seconds: u64) -> Zeroizing<String> {
        let counter = unix_seconds / u64::from(self.period);
        let mut value = u64::from(self.truncated_hmac(counter));
        let mut code = Zeroizing::new(String::with_capacity(self.length as usize));
        if self.steam {
            for _ in 0..self.length {
                code.push(STEAM_ALPHABET[(value % 26) as usize] as char);
                value /= 26;
            }
        } else {
            value %= 10u64.pow(self.length);
            // Digit by digit, so no unwiped copy is left behind by formatting.
            let mut digits = Zeroizing::new([0u8; MAX_DIGITS as usize]);
            for d in digits[..self.length as usize].iter_mut().rev() {
                *d = b'0' + (value % 10) as u8;
                value /= 10;
            }
            for &d in &digits[..self.length as usize] {
                code.push(d as char);
            }
        }
        code
    }

    /// RFC 4226's "dynamic truncation" of the HMAC of the counter: 31 bits.
    fn truncated_hmac(&self, counter: u64) -> u32 {
        let message = counter.to_be_bytes();
        let mac: Zeroizing<Vec<u8>> = Zeroizing::new(match self.algorithm {
            TotpAlgorithm::Sha1 => hmac::<Sha1>(&self.secret, &message),
            TotpAlgorithm::Sha256 => hmac::<Sha256>(&self.secret, &message),
            TotpAlgorithm::Sha512 => hmac::<Sha512>(&self.secret, &message),
        });
        let offset = (mac[mac.len() - 1] & 0x0f) as usize;
        let bytes: [u8; 4] = mac[offset..offset + 4].try_into().expect("4 bytes");
        u32::from_be_bytes(bytes) & 0x7fff_ffff
    }

    /// How long each code lasts, in seconds.
    pub fn period(&self) -> u32 {
        self.period
    }

    /// How many characters a code has.
    pub fn length(&self) -> u32 {
        self.length
    }

    pub fn algorithm(&self) -> TotpAlgorithm {
        self.algorithm
    }

    /// Whether codes are Steam Guard's letters and digits rather than plain digits.
    pub fn is_steam(&self) -> bool {
        self.steam
    }

    /// The names of the fields these settings were read from, so the entry screen can show the
    /// code in their place.
    pub fn fields(&self) -> &[String] {
        &self.fields
    }
}

fn hmac<D: EagerHash>(key: &[u8], message: &[u8]) -> Vec<u8> {
    let mut mac = Hmac::<D>::new_from_slice(key).expect("HMAC takes any key length");
    mac.update(message);
    mac.finalize().into_bytes().to_vec()
}

/// The `otp` field: an `otpauth://` URI, KeeOTP's `key=…` form, or (leniently) a bare Base32
/// secret with the usual settings.
fn parse_otp_field(value: &str) -> Result<Totp> {
    let value = value.trim();
    let scheme = "otpauth://";
    // `get` and not `[..]`: the tenth byte can be the middle of a character (in Cyrillic, say),
    // and slicing there would panic.
    if value
        .get(..scheme.len())
        .is_some_and(|start| start.eq_ignore_ascii_case(scheme))
    {
        return parse_otpauth(&value[scheme.len()..]);
    }
    let params = query_params(value);
    if let Some(key) = param(&params, "key") {
        // KeeOTP: key, size, step, otpHashMode.
        let secret = decode_secret(key, SecretEncoding::Base32)?;
        let length = param(&params, "size").map(parse_length).transpose()?;
        let period = param(&params, "step").map(parse_period).transpose()?;
        let algorithm = param(&params, "otphashmode")
            .map(parse_algorithm)
            .transpose()?;
        return Totp::new(
            secret,
            algorithm.unwrap_or(TotpAlgorithm::Sha1),
            length.unwrap_or(DEFAULT_DIGITS),
            period.unwrap_or(DEFAULT_PERIOD),
            false,
        );
    }
    if value.contains(['=', '&', ':', '/']) {
        return Err(invalid(
            "The TOTP settings in the otp field couldn't be read.",
        ));
    }
    let secret = decode_secret(value, SecretEncoding::Base32)?;
    Totp::new(
        secret,
        TotpAlgorithm::Sha1,
        DEFAULT_DIGITS,
        DEFAULT_PERIOD,
        false,
    )
}

/// What follows `otpauth://`: `totp/<label>?secret=…&algorithm=…&digits=…&period=…`.
fn parse_otpauth(rest: &str) -> Result<Totp> {
    let kind_end = rest.find(['/', '?']).unwrap_or(rest.len());
    let kind = &rest[..kind_end];
    if kind.eq_ignore_ascii_case("hotp") {
        return Err(invalid(
            "This entry uses counter-based one-time passwords (HOTP), which aren't supported yet.",
        ));
    }
    if !kind.eq_ignore_ascii_case("totp") {
        return Err(invalid("The otp field doesn't hold TOTP settings."));
    }
    let query = rest.split_once('?').map_or("", |(_, q)| q);
    let query = query.split_once('#').map_or(query, |(q, _)| q);
    let params = query_params(query);
    let secret =
        param(&params, "secret").ok_or_else(|| invalid("The TOTP settings have no secret."))?;
    let secret = decode_secret(secret, SecretEncoding::Base32)?;
    let steam = param(&params, "encoder").is_some_and(|e| e.eq_ignore_ascii_case("steam"));
    let length = param(&params, "digits").map(parse_length).transpose()?;
    let period = param(&params, "period").map(parse_period).transpose()?;
    let algorithm = param(&params, "algorithm")
        .map(parse_algorithm)
        .transpose()?;
    Totp::new(
        secret,
        algorithm.unwrap_or(TotpAlgorithm::Sha1),
        length.unwrap_or(DEFAULT_DIGITS),
        period.unwrap_or(DEFAULT_PERIOD),
        steam,
    )
}

/// `a=1&b=2`, percent-decoded, names lowercased. Values hold secrets, so they're wiped.
fn query_params(query: &str) -> Vec<(String, Zeroizing<String>)> {
    query
        .split('&')
        .filter_map(|pair| {
            let (name, value) = pair.split_once('=')?;
            Some((
                percent_decode(name).to_ascii_lowercase(),
                percent_decode(value),
            ))
        })
        .collect()
}

fn param<'a>(params: &'a [(String, Zeroizing<String>)], name: &str) -> Option<&'a str> {
    params
        .iter()
        .find(|(n, _)| n == name)
        .map(|(_, v)| v.trim())
        .filter(|v| !v.is_empty())
}

/// `%XX` escapes. Anything that doesn't decode to UTF-8 is kept as it was written.
fn percent_decode(s: &str) -> Zeroizing<String> {
    let bytes = s.as_bytes();
    let mut out = Zeroizing::new(Vec::with_capacity(bytes.len()));
    let mut i = 0;
    while i < bytes.len() {
        let hex = |b: u8| (b as char).to_digit(16);
        if bytes[i] == b'%'
            && let (Some(hi), Some(lo)) = (
                bytes.get(i + 1).copied().and_then(hex),
                bytes.get(i + 2).copied().and_then(hex),
            )
        {
            out.push((hi * 16 + lo) as u8);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    match std::str::from_utf8(&out) {
        Ok(text) => Zeroizing::new(text.to_owned()),
        Err(_) => Zeroizing::new(s.to_owned()),
    }
}

fn decode_secret(text: &str, encoding: SecretEncoding) -> Result<Zeroizing<Vec<u8>>> {
    let secret = match encoding {
        SecretEncoding::Utf8 => Some(Zeroizing::new(text.as_bytes().to_vec())),
        SecretEncoding::Hex => hex_decode(text).map(Zeroizing::new),
        SecretEncoding::Base32 => base32_decode(text),
        SecretEncoding::Base64 => {
            let compact: Zeroizing<String> =
                Zeroizing::new(text.chars().filter(|c| !c.is_whitespace()).collect());
            BASE64.decode(compact.as_bytes()).ok().map(Zeroizing::new)
        }
    };
    let secret = secret.ok_or_else(|| {
        invalid(match encoding {
            SecretEncoding::Utf8 => unreachable!("any text is UTF-8"),
            SecretEncoding::Hex => "The TOTP secret isn't valid hexadecimal.",
            SecretEncoding::Base32 => "The TOTP secret isn't valid Base32.",
            SecretEncoding::Base64 => "The TOTP secret isn't valid Base64.",
        })
    })?;
    if secret.is_empty() {
        return Err(invalid("The TOTP secret is empty."));
    }
    Ok(secret)
}

/// RFC 4648 Base32, as setup pages show it: either case, spaces and dashes between groups, and
/// the `=` padding optional.
fn base32_decode(text: &str) -> Option<Zeroizing<Vec<u8>>> {
    let text = text.trim_end_matches(|c: char| c == '=' || c.is_whitespace());
    let mut out = Zeroizing::new(Vec::with_capacity(text.len() * 5 / 8));
    let (mut buffer, mut bits) = (0u64, 0u32);
    for c in text.bytes() {
        let value = match c.to_ascii_uppercase() {
            c @ b'A'..=b'Z' => c - b'A',
            c @ b'2'..=b'7' => c - b'2' + 26,
            b' ' | b'\t' | b'\r' | b'\n' | b'-' => continue,
            _ => return None,
        };
        buffer = (buffer << 5) | u64::from(value);
        bits += 5;
        if bits >= 8 {
            bits -= 8;
            out.push((buffer >> bits) as u8);
            buffer &= (1 << bits) - 1;
        }
    }
    Some(out)
}

fn parse_length(text: &str) -> Result<u32> {
    text.trim()
        .parse()
        .ok()
        .filter(|n| (1..=MAX_DIGITS).contains(n))
        .ok_or_else(|| {
            invalid(format!(
                "A TOTP code length of \"{}\" isn't supported. It has to be 1 to {MAX_DIGITS} digits.",
                text.trim()
            ))
        })
}

fn parse_period(text: &str) -> Result<u32> {
    text.trim()
        .parse()
        .ok()
        .filter(|n| (1..=MAX_PERIOD).contains(n))
        .ok_or_else(|| {
            invalid(format!(
                "A TOTP period of \"{}\" isn't supported. It has to be 1 to {MAX_PERIOD} seconds.",
                text.trim()
            ))
        })
}

/// `SHA1`, `sha256`, `SHA-512`, or KeePass's `HMAC-SHA-256`.
fn parse_algorithm(text: &str) -> Result<TotpAlgorithm> {
    let name: String = text
        .trim()
        .to_ascii_uppercase()
        .trim_start_matches("HMAC-")
        .chars()
        .filter(|&c| c != '-')
        .collect();
    match name.as_str() {
        "SHA1" => Ok(TotpAlgorithm::Sha1),
        "SHA256" => Ok(TotpAlgorithm::Sha256),
        "SHA512" => Ok(TotpAlgorithm::Sha512),
        _ => Err(invalid(format!(
            "The TOTP algorithm \"{}\" isn't supported. Only SHA-1, SHA-256, and SHA-512 are.",
            text.trim()
        ))),
    }
}

/// Whether a field holds one-time code settings in any of the ways `Totp::from_entry` reads,
/// so setting up a new code can replace all of them.
pub fn is_settings_field(key: &str) -> bool {
    key == OTP_FIELD || key.starts_with("TimeOtp-") || key == LEGACY_SEED || key == LEGACY_SETTINGS
}

/// The shortest setup key `setup` takes typed or pasted on its own: 80 bits (16 Base32
/// characters), RFC 4226's minimum and the shortest sites use. A shorter one is almost always a
/// key copied in part.
const MIN_SETUP_SECRET: usize = 10;

/// What `setup` makes of what the user pasted.
pub struct TotpSetup {
    /// What to store in the `otp` field. Holds the secret.
    pub value: Zeroizing<String>,
    pub totp: Totp,
}

/// Turns what a site shows when two-factor sign-in is set up into the `otp` field's value, as
/// KeePassXC stores it, after checking it makes codes:
///
/// - an `otpauth://totp/…` link (what the QR code holds), kept as it is;
/// - KeeOTP's `key=…&size=…&step=…`, kept as it is;
/// - a bare setup key (Base32, in either case, with spaces or dashes), turned into an
///   `otpauth://` link labelled with the entry's title and username, as KeePassXC does.
///
/// Fails with `Invalid` (a sentence for the user) for anything else, a counter-based (HOTP)
/// link, or a setup key too short to be a whole one.
pub fn setup(input: &str, title: &str, username: &str) -> Result<TotpSetup> {
    let input = input.trim();
    if input.is_empty() {
        return Err(invalid(
            "Paste the setup key or the otpauth:// link the site shows.",
        ));
    }
    let scheme = "otpauth://";
    let is_link = input
        .get(..scheme.len())
        .is_some_and(|start| start.eq_ignore_ascii_case(scheme));
    if is_link || input.to_ascii_lowercase().contains("key=") {
        let totp = parse_otp_field(input)?;
        return Ok(TotpSetup {
            value: Zeroizing::new(input.to_owned()),
            totp,
        });
    }

    let compact: Zeroizing<String> = Zeroizing::new(
        input
            .chars()
            .filter(|c| !c.is_whitespace() && *c != '-')
            .map(|c| c.to_ascii_uppercase())
            .collect::<String>()
            .trim_end_matches('=')
            .to_owned(),
    );
    let secret = base32_decode(&compact).ok_or_else(|| {
        invalid(
            "That isn't a setup key. Setup keys use only the letters A to Z and the digits 2 to 7.",
        )
    })?;
    if secret.len() < MIN_SETUP_SECRET {
        return Err(invalid(
            "That setup key is too short. Setup keys are usually 16 or 32 characters: check that all of it was copied.",
        ));
    }
    let issuer = title.trim();
    let account = username.trim();
    let label = match (issuer.is_empty(), account.is_empty()) {
        (false, false) => format!("{}:{}", percent_encode(issuer), percent_encode(account)),
        (false, true) => percent_encode(issuer),
        (true, false) => percent_encode(account),
        (true, true) => "PocketKeePass".to_owned(),
    };
    let mut value = Zeroizing::new(format!(
        "otpauth://totp/{label}?secret={}&period={DEFAULT_PERIOD}&digits={DEFAULT_DIGITS}",
        compact.as_str()
    ));
    if !issuer.is_empty() {
        value.push_str("&issuer=");
        value.push_str(&percent_encode(issuer));
    }
    let totp = Totp::new(
        secret,
        TotpAlgorithm::Sha1,
        DEFAULT_DIGITS,
        DEFAULT_PERIOD,
        false,
    )?;
    Ok(TotpSetup { value, totp })
}

/// Percent-encodes everything but RFC 3986's unreserved characters, for a link's label and
/// issuer.
fn percent_encode(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for byte in text.bytes() {
        if byte.is_ascii_alphanumeric() || b"-._~".contains(&byte) {
            out.push(byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

fn invalid(message: impl Into<String>) -> Error {
    Error::Invalid(message.into())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn totp(secret: &[u8], algorithm: TotpAlgorithm, length: u32, period: u32) -> Totp {
        Totp::new(
            Zeroizing::new(secret.to_vec()),
            algorithm,
            length,
            period,
            false,
        )
        .unwrap()
    }

    /// RFC 6238, appendix B.
    #[test]
    fn rfc_6238_test_vectors() {
        let sha1 = totp(b"12345678901234567890", TotpAlgorithm::Sha1, 8, 30);
        let sha256 = totp(
            b"12345678901234567890123456789012",
            TotpAlgorithm::Sha256,
            8,
            30,
        );
        let sha512 = totp(
            b"1234567890123456789012345678901234567890123456789012345678901234",
            TotpAlgorithm::Sha512,
            8,
            30,
        );
        let vectors: [(u64, &str, &str, &str); 6] = [
            (59, "94287082", "46119246", "90693936"),
            (1_111_111_109, "07081804", "68084774", "25091201"),
            (1_111_111_111, "14050471", "67062674", "99943326"),
            (1_234_567_890, "89005924", "91819424", "93441116"),
            (2_000_000_000, "69279037", "90698825", "38618901"),
            (20_000_000_000, "65353130", "77737706", "47863826"),
        ];
        for (time, a, b, c) in vectors {
            assert_eq!(*sha1.code_at(time), a, "SHA-1 at {time}");
            assert_eq!(*sha256.code_at(time), b, "SHA-256 at {time}");
            assert_eq!(*sha512.code_at(time), c, "SHA-512 at {time}");
        }
    }

    /// RFC 4226, appendix D: HOTP values for counters 0 to 9, which TOTP gives with a period of
    /// one second at times 0 to 9.
    #[test]
    fn rfc_4226_test_vectors_keep_their_leading_zeros() {
        let t = totp(b"12345678901234567890", TotpAlgorithm::Sha1, 6, 1);
        let expected = [
            "755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583",
            "399871", "520489",
        ];
        for (time, code) in expected.iter().enumerate() {
            assert_eq!(*t.code_at(time as u64), *code);
        }
    }

    #[test]
    fn setup_turns_a_bare_key_into_a_link_keepassxc_reads() {
        // RFC 6238's secret, as a setup page might show it: lowercase, in groups, padded.
        let set = setup(
            "  gezd gnbv gy3t qojq gezd gnbv gy3t qojq====  ",
            "Example: Mail",
            "alice@example.com",
        )
        .unwrap();
        assert_eq!(
            *set.value,
            "otpauth://totp/Example%3A%20Mail:alice%40example.com\
             ?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&period=30&digits=6&issuer=Example%3A%20Mail"
        );
        assert_eq!(*set.totp.code_at(59), "287082");
        // What's stored reads back as the same settings.
        let again = parse_otp_field(&set.value).unwrap();
        assert_eq!(*again.code_at(59), "287082");
        assert_eq!((again.length(), again.period()), (6, 30));
    }

    #[test]
    fn setup_labels_a_key_with_whatever_the_entry_has() {
        let key = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        assert!(
            setup(key, "", "bob")
                .unwrap()
                .value
                .starts_with("otpauth://totp/bob?secret=")
        );
        let untitled = setup(key, " ", "").unwrap();
        assert!(
            untitled
                .value
                .starts_with("otpauth://totp/PocketKeePass?secret=")
        );
        assert!(!untitled.value.contains("issuer="));
    }

    #[test]
    fn setup_keeps_links_and_keeotp_as_they_are() {
        let link = "otpauth://totp/GitHub:alice?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&digits=8&issuer=GitHub";
        let set = setup(link, "Anything", "else").unwrap();
        assert_eq!(*set.value, link);
        assert_eq!(*set.totp.code_at(59), "94287082");
        let keeotp = "key=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&size=8&step=30";
        assert_eq!(*setup(keeotp, "", "").unwrap().value, keeotp);
    }

    #[test]
    fn settings_fields_in_every_format() {
        for key in [
            "otp",
            "TimeOtp-Secret-Base32",
            "TimeOtp-Period",
            "TOTP Seed",
            "TOTP Settings",
        ] {
            assert!(is_settings_field(key), "{key}");
        }
        for key in ["Password", "OTP", "otp2", "TOTP", "Notes"] {
            assert!(!is_settings_field(key), "{key}");
        }
    }

    #[test]
    fn setup_refuses_what_cant_make_codes() {
        let message = |input: &str| match setup(input, "T", "u") {
            Err(Error::Invalid(m)) => m,
            other => panic!("{input:?} gave {:?}", other.map(|s| s.totp)),
        };
        assert!(message("   ").contains("Paste"));
        assert!(message("hunter2!").contains("isn't a setup key"));
        assert!(message("GEZDGNBV").contains("too short"));
        assert!(message("otpauth://hotp/x?secret=GEZDGNBVGY3TQOJQ&counter=1").contains("HOTP"));
        assert!(message("otpauth://totp/x?secret=GEZDGNBVGY3TQOJQ&digits=12").contains("length"));
    }

    #[test]
    fn base32_accepts_what_setup_pages_show() {
        let expected = b"12345678901234567890";
        for text in [
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
            "gezdgnbvgy3tqojqgezdgnbvgy3tqojq",
            "GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ",
            "GEZD-GNBV-GY3T-QOJQ-GEZD-GNBV-GY3T-QOJQ",
        ] {
            assert_eq!(base32_decode(text).unwrap().as_slice(), expected, "{text}");
        }
        // Padding is optional.
        assert_eq!(base32_decode("MFRGG===").unwrap().as_slice(), b"abc");
        assert_eq!(base32_decode("MFRGG").unwrap().as_slice(), b"abc");
        assert!(base32_decode("GEZD1").is_none(), "1 isn't Base32");
        assert!(base32_decode("GE=ZD").is_none(), "padding only at the end");
    }

    #[test]
    fn otpauth_uris() {
        let t = parse_otp_field(
            "otpauth://totp/Example:jo%40example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example&digits=8&period=30",
        )
        .unwrap();
        assert_eq!(*t.code_at(59), "94287082");

        // Defaults: SHA-1, 6 digits, 30 seconds. Parameter names in any case, as some
        // exporters write them.
        let t =
            parse_otp_field("OTPAUTH://TOTP/x?SECRET=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ").unwrap();
        assert_eq!(
            (t.algorithm(), t.length(), t.period()),
            (TotpAlgorithm::Sha1, 6, 30)
        );
        assert_eq!(*t.code_at(59), "287082");

        let t = parse_otp_field(
            "otpauth://totp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA&algorithm=SHA256&digits=8",
        )
        .unwrap();
        assert_eq!(*t.code_at(1_111_111_109), "68084774");

        // A percent-encoded secret, and a period of a minute.
        let t =
            parse_otp_field("otpauth://totp/x?secret=GEZDGNBVGY3TQOJQ%3D%3D%3D&period=60").unwrap();
        assert_eq!(t.period(), 60);
        assert_eq!(t.secret.as_slice(), b"1234567890");
    }

    #[test]
    fn steam_codes_use_steams_alphabet() {
        let t = parse_otp_field(
            "otpauth://totp/Steam:jo?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Steam&encoder=steam",
        )
        .unwrap();
        assert!(t.is_steam());
        assert_eq!(t.length(), 5);
        // RFC 4226's truncated HMAC for counter 1 (time 59 here) is 1094287082. Steam writes it
        // in base 26, least significant first.
        let mut value = 1_094_287_082u64;
        let mut expected = String::new();
        for _ in 0..5 {
            expected.push(STEAM_ALPHABET[(value % 26) as usize] as char);
            value /= 26;
        }
        assert_eq!(*t.code_at(59), expected);
    }

    #[test]
    fn keeotp_and_bare_secrets() {
        let t = parse_otp_field("key=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&size=8&step=30").unwrap();
        assert_eq!(*t.code_at(59), "94287082");
        let t = parse_otp_field(
            "key=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA&size=8&otpHashMode=Sha256",
        )
        .unwrap();
        assert_eq!(*t.code_at(59), "46119246");
        let t = parse_otp_field(" GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ ").unwrap();
        assert_eq!(*t.code_at(59), "287082");
    }

    #[test]
    fn unusable_settings_are_explained() {
        let message = |value: &str| match parse_otp_field(value) {
            Err(Error::Invalid(m)) => m,
            other => panic!("{value}: {other:?}"),
        };
        assert!(message("otpauth://hotp/x?secret=GEZDGNBV&counter=1").contains("HOTP"));
        assert!(message("otpauth://totp/x?issuer=Nobody").contains("no secret"));
        assert!(message("otpauth://totp/x?secret=not*base32").contains("Base32"));
        assert!(message("otpauth://totp/x?secret=GEZDGNBV&algorithm=MD5").contains("MD5"));
        assert!(message("otpauth://totp/x?secret=GEZDGNBV&digits=12").contains("12"));
        assert!(message("otpauth://totp/x?secret=GEZDGNBV&period=0").contains("period"));
        assert!(message("otpauth://totp/x?secret=====").contains("empty"));
        assert!(message("https://example.com/").contains("couldn't be read"));
    }

    #[test]
    fn algorithm_names() {
        for (name, expected) in [
            ("SHA1", TotpAlgorithm::Sha1),
            ("sha-1", TotpAlgorithm::Sha1),
            ("HMAC-SHA-256", TotpAlgorithm::Sha256),
            ("Sha512", TotpAlgorithm::Sha512),
        ] {
            assert_eq!(parse_algorithm(name).unwrap(), expected, "{name}");
        }
    }

    #[test]
    fn debug_output_leaves_out_the_secret() {
        let t = totp(b"super secret", TotpAlgorithm::Sha1, 6, 30);
        let printed = format!("{t:?}");
        assert!(!printed.contains("super"), "{printed}");
        assert!(!printed.contains("115, 117"), "{printed}");
    }
}
