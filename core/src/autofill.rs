//! Choosing which entries to offer when an app or a web page asks to be filled in.
//!
//! This decides where passwords may go, so it errs on the side of offering nothing: a wrong
//! match would put a password one tap away from the wrong app or site. Like lists and search,
//! it never reads a protected value, so a URL the user chose to protect matches nothing.
//!
//! An entry is linked to a **website** by its URL field, or by an extra URL in a custom field
//! named `KP2A_URL`, `KP2A_URL_1`, and so on (the convention Keepass2Android started and
//! KeePassXC's browser extension shares).
//!
//! An entry is linked to an **Android app** only explicitly, by package name: a URL of the
//! form `androidapp://com.example.app` (Keepass2Android's convention) in any of those places,
//! or a custom field named `AndroidApp`, `AndroidApp_1`, and so on holding the package name
//! (KeePassDX's). An app is never matched to a website's entry by guessing from its package
//! name: anyone can name an app `com.yourbank.something`.

use crate::browse::SearchHit;
use crate::model::{Database, Entry};

/// What is asking to be filled in.
#[derive(Clone, Copy, Debug)]
pub enum FillTarget<'a> {
    /// An Android app, by package name.
    App { package: &'a str },
    /// A web page. Only build this from what a browser the app trusts reports: any other app
    /// could claim to be showing any site.
    Web {
        /// The page's host name, e.g. "login.example.com".
        domain: &'a str,
        /// The page was loaded over plain `http`. Entries whose URL says `https` aren't offered
        /// then: someone on the network could have swapped the page.
        plain_http: bool,
    },
}

/// Custom fields holding more URLs for the same entry.
const EXTRA_URL_PREFIX: &str = "KP2A_URL";
/// Custom fields holding an Android package name.
const APP_FIELD_PREFIX: &str = "AndroidApp";
const APP_SCHEME: &str = "androidapp";

impl Database {
    /// The entries linked to `target`, best matches first: for a web page, entries for exactly
    /// that host come before entries for a parent domain. Within each, file order.
    ///
    /// A web page matches an entry whose URL has the same host, or a parent domain of it: an
    /// entry for `example.com` is offered on `login.example.com`, but not the other way round,
    /// and not on `example.com.evil.net` or `notexample.com`. A leading `www.` is ignored on
    /// both sides. Ports and paths are ignored.
    ///
    /// Entry history, the recycle bin, and groups hidden from search are left out.
    pub fn autofill_matches(&self, target: FillTarget<'_>) -> Vec<SearchHit<'_>> {
        let target = match target {
            FillTarget::App { package } => Target::App(package.trim()),
            FillTarget::Web { domain, plain_http } => match normalize_host(domain) {
                Some(host) => Target::Web { host, plain_http },
                None => return Vec::new(),
            },
        };
        let mut hits: Vec<(Match, SearchHit<'_>)> = Vec::new();
        self.for_each_searchable(|entry, path| {
            if let Some(quality) = best_match(&entry, &target) {
                let path = path.to_vec();
                hits.push((quality, SearchHit { entry, path }));
            }
        });
        hits.sort_by_key(|(quality, _)| *quality); // stable, so file order is kept within each
        hits.into_iter().map(|(_, hit)| hit).collect()
    }
}

enum Target<'a> {
    App(&'a str),
    Web { host: String, plain_http: bool },
}

/// How well an entry matches. Better matches sort first.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
enum Match {
    Exact,
    ParentDomain,
}

fn best_match(entry: &Entry<'_>, target: &Target<'_>) -> Option<Match> {
    entry
        .fields()
        .filter(|field| !field.protected)
        .filter_map(|field| {
            if field.key == "URL" || field.key.starts_with(EXTRA_URL_PREFIX) {
                match_url(field.value, target)
            } else if field.key.starts_with(APP_FIELD_PREFIX) {
                match target {
                    Target::App(package)
                        if !package.is_empty() && field.value.trim() == *package =>
                    {
                        Some(Match::Exact)
                    }
                    _ => None,
                }
            } else {
                None
            }
        })
        .min()
}

fn match_url(url: &str, target: &Target<'_>) -> Option<Match> {
    let url = Url::parse(url)?;
    match target {
        Target::App(package) => {
            let linked = url.scheme.eq_ignore_ascii_case(APP_SCHEME)
                && !package.is_empty()
                && url.authority == *package;
            linked.then_some(Match::Exact)
        }
        Target::Web { host, plain_http } => {
            let https = url.scheme.eq_ignore_ascii_case("https");
            let web = https || url.scheme.is_empty() || url.scheme.eq_ignore_ascii_case("http");
            if !web || (https && *plain_http) {
                return None;
            }
            match_host(host, &url.host()?)
        }
    }
}

/// Compares the page's host with an entry's. Both are already normalized.
fn match_host(page: &str, entry: &str) -> Option<Match> {
    if page == entry {
        return Some(Match::Exact);
    }
    // "login.example.com" is under "example.com": it ends with ".example.com". The entry's
    // host needs a dot of its own, so that "com" isn't a parent of everything, and it can't be
    // an IP address, which has no subdomains.
    let parent = entry.contains('.')
        && !is_ip_address(entry)
        && page
            .strip_suffix(entry)
            .is_some_and(|rest| rest.ends_with('.'));
    parent.then_some(Match::ParentDomain)
}

fn is_ip_address(host: &str) -> bool {
    host.contains(':') || host.bytes().all(|b| b.is_ascii_digit() || b == b'.')
}

/// A host name in the form hosts are compared in: lowercase, without a trailing dot or a
/// leading "www.". `None` if it can't be a host name at all.
fn normalize_host(host: &str) -> Option<String> {
    let host = host.trim().to_lowercase();
    let host = host.strip_suffix('.').unwrap_or(&host);
    let host = host.strip_prefix("www.").unwrap_or(host);
    let valid = !host.is_empty()
        && !host
            .chars()
            .any(|c| c.is_whitespace() || c.is_control() || "/\\?#@".contains(c));
    valid.then(|| host.to_owned())
}

/// The parts of an entry's URL that matching looks at.
///
/// This is our own small parser rather than a general URL library: it only has to find the
/// host the way a browser would, and to say "not a URL" for anything unusual.
struct Url<'a> {
    /// Empty when the URL has none, as in "example.com/login".
    scheme: &'a str,
    /// Between the scheme and the path: `user:password@host:port`.
    authority: &'a str,
}

impl<'a> Url<'a> {
    fn parse(url: &'a str) -> Option<Self> {
        let url = url.trim();
        let (scheme, rest) = match url.split_once("://") {
            Some((scheme, rest)) if is_scheme(scheme) => (scheme, rest),
            Some(_) => return None,
            None => ("", url),
        };
        // Browsers end the host at a backslash too.
        let end = rest.find(['/', '\\', '?', '#']).unwrap_or(rest.len());
        let authority = &rest[..end];
        (!authority.is_empty()).then_some(Self { scheme, authority })
    }

    /// The host, normalized. `None` if the authority isn't `host` or `host:port`, optionally
    /// after `user@`.
    fn host(&self) -> Option<String> {
        // Everything up to the last "@" is a username and password: in
        // "https://example.com@evil.net/", the host is evil.net. Without a scheme, something
        // with an "@" is more likely an email address ("mailto:me@example.com") than a URL.
        let host_port = match self.authority.rsplit_once('@') {
            Some(_) if self.scheme.is_empty() => return None,
            Some((_, host_port)) => host_port,
            None => self.authority,
        };
        let host = if let Some(bracketed) = host_port.strip_prefix('[') {
            // An IPv6 address: "[::1]" or "[::1]:8080".
            let (address, port) = bracketed.split_once(']')?;
            if !(port.is_empty() || is_port(port.strip_prefix(':')?)) {
                return None;
            }
            address
        } else {
            match host_port.rsplit_once(':') {
                Some((host, port)) if is_port(port) => host,
                Some(_) => return None, // "mailto:someone", say
                None => host_port,
            }
        };
        normalize_host(host)
    }
}

fn is_scheme(text: &str) -> bool {
    let mut chars = text.chars();
    chars.next().is_some_and(|c| c.is_ascii_alphabetic())
        && chars.all(|c| c.is_ascii_alphanumeric() || "+-.".contains(c))
}

fn is_port(text: &str) -> bool {
    text.bytes().all(|b| b.is_ascii_digit())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn host(url: &str) -> Option<String> {
        Url::parse(url)?.host()
    }

    #[test]
    fn hosts_are_found_the_way_a_browser_finds_them() {
        for (url, expected) in [
            ("https://example.com", "example.com"),
            ("https://Example.COM/Login?next=/a#top", "example.com"),
            ("  http://www.example.com:8080/  ", "example.com"),
            ("example.com", "example.com"),
            ("example.com/login", "example.com"),
            ("example.com:8443", "example.com"),
            ("https://example.com.", "example.com"),
            ("https://user:secret@example.com/", "example.com"),
            ("https://example.com@evil.net/", "evil.net"),
            ("https://example.com:pass@evil.net:443/x", "evil.net"),
            ("https://example.com\\@evil.net/", "example.com"),
            ("https://evil.net#@example.com", "evil.net"),
            ("https://evil.net?@example.com", "evil.net"),
            ("http://192.168.1.1/admin", "192.168.1.1"),
            ("http://[::1]:8080/", "::1"),
            ("http://[2001:DB8::1]", "2001:db8::1"),
            ("https://bücher.example/", "bücher.example"),
            ("wwwexample.com", "wwwexample.com"),
        ] {
            assert_eq!(host(url).as_deref(), Some(expected), "{url}");
        }
    }

    #[test]
    fn things_that_are_not_web_addresses_have_no_host() {
        for url in [
            "",
            "   ",
            "https://",
            "https:///path",
            "mailto:me@example.com",
            "me@example.com",
            "javascript:alert(1)",
            "https://example.com:port/",
            "https://exa mple.com/",
            "http://[::1/",
            "http://[::1]8080/",
            "1nvalid://example.com",
            "://example.com",
        ] {
            assert_eq!(host(url), None, "{url:?}");
        }
    }

    #[test]
    fn a_page_matches_its_own_host_and_parent_domains_only() {
        use Match::{Exact, ParentDomain};
        for (page, entry, expected) in [
            ("example.com", "example.com", Some(Exact)),
            ("login.example.com", "example.com", Some(ParentDomain)),
            ("a.b.example.com", "example.com", Some(ParentDomain)),
            ("a.b.example.com", "b.example.com", Some(ParentDomain)),
            // Never the other way round: an entry for one subdomain isn't for the whole site.
            ("example.com", "login.example.com", None),
            ("other.example.com", "login.example.com", None),
            // Look-alikes.
            ("notexample.com", "example.com", None),
            ("example.com.evil.net", "example.com", None),
            ("evil-example.com", "example.com", None),
            ("example.co", "example.com", None),
            // A bare word or an IP address has no subdomains.
            ("example.com", "com", None),
            ("localhost", "localhost", Some(Exact)),
            ("9.10.0.0.1", "10.0.0.1", None),
            ("10.0.0.1", "10.0.0.1", Some(Exact)),
        ] {
            assert_eq!(match_host(page, entry), expected, "{page} vs {entry}");
        }
    }

    #[test]
    fn urls_match_by_scheme_and_host() {
        let web = |domain: &str, plain_http| Target::Web {
            host: normalize_host(domain).unwrap(),
            plain_http,
        };
        let matches = |url: &str, target: &Target<'_>| match_url(url, target).is_some();

        assert!(matches(
            "https://example.com/login",
            &web("example.com", false)
        ));
        assert!(matches(
            "https://example.com",
            &web("WWW.Example.com.", false)
        ));
        assert!(matches("http://example.com", &web("example.com", false)));
        assert!(matches("example.com", &web("example.com", true)));
        assert!(matches("http://example.com", &web("example.com", true)));
        // An https entry isn't offered to a page that came over plain http.
        assert!(!matches("https://example.com", &web("example.com", true)));
        // Other schemes aren't websites.
        assert!(!matches("ftp://example.com", &web("example.com", false)));
        assert!(!matches(
            "cmd://ssh example.com",
            &web("example.com", false)
        ));
        assert!(!matches(
            "androidapp://example.com",
            &web("example.com", false)
        ));

        let app = Target::App("com.example.app");
        assert!(matches("androidapp://com.example.app", &app));
        assert!(matches("AndroidApp://com.example.app/", &app));
        // Package names are case-sensitive, and must match in full.
        assert!(!matches("androidapp://com.example.App", &app));
        assert!(!matches("androidapp://com.example.app.evil", &app));
        assert!(!matches("androidapp://com.example", &app));
        assert!(!matches("https://com.example.app", &app));
        assert!(!matches("com.example.app", &app));
        assert!(!matches("androidapp://", &Target::App("")));
    }
}
