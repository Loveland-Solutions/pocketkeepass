//! A small, lossless XML element tree.
//!
//! KeePass stores everything inside the encrypted payload as XML. We keep that XML as a generic
//! tree rather than parsing it into narrow structs, so elements we don't understand (newer KeePass
//! features, other apps' extensions) are carried through untouched. Typed access lives in
//! `model.rs` as views over this tree.
//!
//! KeePass XML has no mixed content: an element has either child elements or text, never both.
//! Whitespace between child elements is therefore dropped, and leaf text is kept exactly.
//!
//! Writing has one deliberate exception to "kept exactly": characters that XML 1.0 can't
//! represent at all (most control characters) are left out of unprotected text, the same as
//! KeePass 2.x does. Writing them would produce a file that KeePass and KeePassXC refuse to open.
//! Protected values are stored encrypted and base64-encoded, so they're never affected.

use quick_xml::Reader;
use quick_xml::escape::resolve_predefined_entity;
use quick_xml::events::{BytesStart, Event};
use zeroize::Zeroizing;

use crate::error::{Result, corrupt};
use crate::util::SecretBuf;

/// One XML element. All text is wiped from memory when the tree is dropped, because after
/// decryption it holds passwords.
pub struct Element {
    pub name: String,
    pub attrs: Vec<(String, String)>,
    pub children: Vec<Element>,
    pub text: Zeroizing<String>,
}

impl Element {
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            attrs: Vec::new(),
            children: Vec::new(),
            text: Zeroizing::default(),
        }
    }

    pub fn attr(&self, name: &str) -> Option<&str> {
        self.attrs
            .iter()
            .find(|(k, _)| k == name)
            .map(|(_, v)| v.as_str())
    }

    pub fn child(&self, name: &str) -> Option<&Element> {
        self.children.iter().find(|c| c.name == name)
    }

    pub fn children_named<'a>(&'a self, name: &'a str) -> impl Iterator<Item = &'a Element> + 'a {
        self.children.iter().filter(move |c| c.name == name)
    }

    /// Follows a chain of child names, e.g. `["Meta", "DatabaseName"]`.
    pub fn path(&self, names: &[&str]) -> Option<&Element> {
        names.iter().try_fold(self, |el, n| el.child(n))
    }

    /// The text of a direct child, or "" if it's missing.
    pub fn child_text(&self, name: &str) -> &str {
        self.child(name).map(|c| c.text.as_str()).unwrap_or("")
    }

    pub fn child_mut(&mut self, name: &str) -> Option<&mut Element> {
        self.children.iter_mut().find(|c| c.name == name)
    }

    /// Whether the file stores this element's text encrypted (`Protected="True"`), as it does
    /// for passwords. In memory the text is always plaintext.
    pub fn is_protected(&self) -> bool {
        self.attr("Protected")
            .is_some_and(|v| v.eq_ignore_ascii_case("true"))
    }

    /// Whether `saved` is exactly what writing this tree and parsing it again should give.
    /// Saving uses this to prove that the new file holds everything before handing it over.
    pub(crate) fn round_trips_to(&self, saved: &Element) -> bool {
        fn same(ours: &str, saved: &str) -> bool {
            ours.chars().filter(|&c| is_xml_char(c)).eq(saved.chars())
        }
        let text_matches = if !self.children.is_empty() {
            // Text next to child elements can't be written, so it must not exist.
            self.text.is_empty() && saved.text.is_empty()
        } else if self.is_protected() {
            *self.text == *saved.text
        } else {
            same(&self.text, &saved.text)
        };
        text_matches
            && self.name == saved.name
            && self.attrs.len() == saved.attrs.len()
            && self
                .attrs
                .iter()
                .zip(&saved.attrs)
                .all(|((k1, v1), (k2, v2))| k1 == k2 && same(v1, v2))
            && self.children.len() == saved.children.len()
            && self
                .children
                .iter()
                .zip(&saved.children)
                .all(|(a, b)| a.round_trips_to(b))
    }

    /// Pre-order, document-order walk with mutable access. KeePass's protected-value stream
    /// depends on this order.
    pub fn walk_mut(&mut self, f: &mut impl FnMut(&mut Element) -> Result<()>) -> Result<()> {
        f(self)?;
        for c in &mut self.children {
            c.walk_mut(f)?;
        }
        Ok(())
    }
}

/// Parses an XML document into its root element.
pub fn parse(xml: &str) -> Result<Element> {
    let mut reader = Reader::from_str(xml);
    let mut stack: Vec<Element> = Vec::new();
    let mut root: Option<Element> = None;

    loop {
        let event = reader
            .read_event()
            .map_err(|e| corrupt(format!("invalid XML ({e})")))?;
        match event {
            Event::Start(start) => stack.push(element_from(&start)?),
            Event::Empty(start) => {
                let el = element_from(&start)?;
                attach(&mut stack, &mut root, el)?;
            }
            Event::End(_) => {
                let mut el = stack.pop().ok_or_else(|| corrupt("unbalanced XML"))?;
                if !el.children.is_empty() {
                    // Only inter-element whitespace can be here; see the module docs.
                    el.text = Zeroizing::default();
                }
                attach(&mut stack, &mut root, el)?;
            }
            Event::Text(t) => {
                if let Some(top) = stack.last_mut() {
                    top.text.push_str(&t.xml10_content());
                }
            }
            Event::CData(c) => {
                if let Some(top) = stack.last_mut() {
                    top.text.push_str(&c.xml10_content());
                }
            }
            Event::GeneralRef(r) => {
                let Some(top) = stack.last_mut() else {
                    continue;
                };
                if r.is_char_ref() {
                    let ch = r
                        .resolve_char_ref()
                        .map_err(|e| corrupt(format!("invalid XML character ({e})")))?
                        .ok_or_else(|| corrupt("invalid XML character"))?;
                    top.text.push(ch);
                } else {
                    let name = r.xml10_content();
                    let value = resolve_predefined_entity(&name)
                        .ok_or_else(|| corrupt(format!("unknown XML entity &{name};")))?;
                    top.text.push_str(value);
                }
            }
            Event::Eof => break,
            // Declarations, comments, processing instructions, doctypes: not used by KeePass.
            _ => {}
        }
    }
    if !stack.is_empty() {
        return Err(corrupt("XML ends early"));
    }
    root.ok_or_else(|| corrupt("empty XML"))
}

fn element_from(start: &BytesStart) -> Result<Element> {
    let mut el = Element::new(start.name().into_inner());
    for attr in start.attributes() {
        let attr = attr.map_err(|e| corrupt(format!("invalid XML attribute ({e})")))?;
        let value = attr
            .normalized_value(quick_xml::XmlVersion::Implicit1_0)
            .map_err(|e| corrupt(format!("invalid XML attribute ({e})")))?;
        el.attrs
            .push((attr.key.into_inner().to_owned(), value.into_owned()));
    }
    Ok(el)
}

fn attach(stack: &mut [Element], root: &mut Option<Element>, el: Element) -> Result<()> {
    match stack.last_mut() {
        Some(parent) => parent.children.push(el),
        None if root.is_none() => *root = Some(el),
        None => return Err(corrupt("XML has more than one root element")),
    }
    Ok(())
}

/// Whether XML 1.0 can represent this character at all, escaped or not.
fn is_xml_char(c: char) -> bool {
    matches!(c, '\t' | '\n' | '\r' | '\u{20}'..='\u{D7FF}' | '\u{E000}'..='\u{FFFD}' | '\u{10000}'..)
}

/// Writes a whole document: the XML declaration, then `root` and everything under it, with no
/// added whitespace.
///
/// `protect` is called for the text of every protected element, in document order (the order
/// KeePass's protected-value stream depends on), and returns what to store in the file instead.
pub(crate) fn write(root: &Element, out: &mut SecretBuf, protect: &mut dyn FnMut(&str) -> String) {
    out.push_str(r#"<?xml version="1.0" encoding="utf-8" standalone="yes"?>"#);
    write_element(root, out, protect);
}

fn write_element(el: &Element, out: &mut SecretBuf, protect: &mut dyn FnMut(&str) -> String) {
    out.push_str("<");
    out.push_str(&el.name);
    for (name, value) in &el.attrs {
        out.push_str(" ");
        out.push_str(name);
        out.push_str("=\"");
        write_escaped(value, true, out);
        out.push_str("\"");
    }
    if el.children.is_empty() && el.text.is_empty() {
        out.push_str("/>");
        return;
    }
    out.push_str(">");
    if !el.children.is_empty() {
        for child in &el.children {
            write_element(child, out, protect);
        }
    } else if el.is_protected() {
        out.push_str(&protect(&el.text));
    } else {
        write_escaped(&el.text, false, out);
    }
    out.push_str("</");
    out.push_str(&el.name);
    out.push_str(">");
}

/// Escapes text or an attribute value. Carriage returns (and, in attributes, tabs and newlines)
/// are written as character references, because a parser would otherwise normalize them away.
fn write_escaped(s: &str, in_attr: bool, out: &mut SecretBuf) {
    let mut done = 0;
    for (i, c) in s.char_indices() {
        let replacement = match c {
            '&' => "&amp;",
            '<' => "&lt;",
            '>' => "&gt;",
            '\r' => "&#xD;",
            '"' if in_attr => "&quot;",
            '\t' if in_attr => "&#x9;",
            '\n' if in_attr => "&#xA;",
            c if !is_xml_char(c) => "", // see the module docs
            _ => continue,
        };
        out.push_str(&s[done..i]);
        out.push_str(replacement);
        done = i + c.len_utf8();
    }
    out.push_str(&s[done..]);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn to_string(root: &Element) -> String {
        let mut out = SecretBuf::with_capacity(64);
        write(root, &mut out, &mut |text| format!("[{text}]"));
        String::from_utf8(out.as_slice().to_vec()).unwrap()
    }

    fn leaf(name: &str, text: &str) -> Element {
        let mut el = Element::new(name);
        el.text = Zeroizing::new(text.to_owned());
        el
    }

    #[test]
    fn writes_what_it_parsed() {
        let source = r#"<?xml version="1.0" encoding="utf-8" standalone="yes"?><Root a="1 &amp; &quot;2&quot;"><Leaf>Tom &amp; Jerry &lt;✓&gt; 🔑</Leaf><Empty/><Spaces>  keep me  </Spaces><Nested><Deep x="y">'quoted'</Deep></Nested></Root>"#;
        assert_eq!(to_string(&parse(source).unwrap()), source);
    }

    #[test]
    fn awkward_text_survives_a_round_trip() {
        let mut root = Element::new("Root");
        root.attrs
            .push(("attr".into(), "tab\there \"q\" <&>\nnew\r\nline".into()));
        for text in [
            "line one\nline two",
            "windows\r\nline endings\r",
            "]]> <![CDATA[ not cdata ]]> &amp; &#x41;",
            " ",
            "\t\n ",
            "üñíçødé ✓ 🔑 \u{FFFD}",
        ] {
            root.children.push(leaf("Value", text));
        }
        let reparsed = parse(&to_string(&root)).unwrap();
        assert!(root.round_trips_to(&reparsed));
        assert_eq!(reparsed.attr("attr"), root.attr("attr"));
        for (a, b) in root.children.iter().zip(&reparsed.children) {
            assert_eq!(*a.text, *b.text);
        }
    }

    #[test]
    fn protected_text_goes_through_the_callback_in_document_order() {
        let mut root = Element::new("Root");
        for (text, protected) in [("one", true), ("plain", false), ("", true), ("two", true)] {
            let mut value = leaf("Value", text);
            if protected {
                value.attrs.push(("Protected".into(), "True".into()));
            }
            root.children.push(value);
        }
        let mut seen = Vec::new();
        let mut out = SecretBuf::with_capacity(64);
        write(&root, &mut out, &mut |text| {
            seen.push(text.to_owned());
            "x".into()
        });
        // An empty protected value has nothing to encrypt, exactly as when reading.
        assert_eq!(seen, ["one", "two"]);
        assert!(
            std::str::from_utf8(out.as_slice())
                .unwrap()
                .ends_with(r#"<Value>plain</Value><Value Protected="True"/><Value Protected="True">x</Value></Root>"#)
        );
    }

    #[test]
    fn characters_xml_cannot_hold_are_left_out_of_plain_text_only() {
        let mut root = Element::new("Root");
        root.children.push(leaf("Plain", "a\u{1}b\u{B}c\u{FFFE}d"));
        let mut secret = leaf("Value", "a\u{1}b");
        secret.attrs.push(("Protected".into(), "True".into()));
        root.children.push(secret);

        let written = to_string(&root);
        assert!(written.contains("<Plain>abcd</Plain>"));
        assert!(written.contains("[a\u{1}b]"));

        // The safety check accepts this loss and nothing else.
        let mut reparsed = parse(&written).unwrap();
        reparsed.children[1].text = Zeroizing::new("a\u{1}b".to_owned());
        assert!(root.round_trips_to(&reparsed));
    }

    #[test]
    fn round_trip_check_notices_every_kind_of_difference() {
        let source = r#"<Root a="1"><A>text</A><B Protected="True">secret</B><C><D/></C></Root>"#;
        let original = parse(source).unwrap();
        assert!(original.round_trips_to(&parse(source).unwrap()));
        for changed in [
            r#"<Root a="2"><A>text</A><B Protected="True">secret</B><C><D/></C></Root>"#,
            r#"<Root><A>text</A><B Protected="True">secret</B><C><D/></C></Root>"#,
            r#"<Root a="1"><A>texT</A><B Protected="True">secret</B><C><D/></C></Root>"#,
            r#"<Root a="1"><A>text</A><B Protected="True">secreT</B><C><D/></C></Root>"#,
            r#"<Root a="1"><A>text</A><B Protected="True">secret</B><C/></Root>"#,
            r#"<Root a="1"><A>text</A><B Protected="True">secret</B><C><E/></C></Root>"#,
            r#"<Root a="1"><B Protected="True">secret</B><A>text</A><C><D/></C></Root>"#,
        ] {
            assert!(
                !original.round_trips_to(&parse(changed).unwrap()),
                "{changed}"
            );
        }
    }

    #[test]
    fn parses_nested_elements_attributes_and_entities() {
        let doc = parse(
            r#"<?xml version="1.0"?>
            <Root a="1 &amp; 2">
                <Leaf>Tom &amp; Jerry &#x2713; <![CDATA[<raw>]]></Leaf>
                <Empty/>
                <Spaces>  keep me  </Spaces>
            </Root>"#,
        )
        .unwrap();
        assert_eq!(doc.name, "Root");
        assert_eq!(doc.attr("a"), Some("1 & 2"));
        assert_eq!(doc.text.as_str(), "");
        assert_eq!(doc.child_text("Leaf"), "Tom & Jerry ✓ <raw>");
        assert!(doc.child("Empty").is_some());
        assert_eq!(doc.child_text("Spaces"), "  keep me  ");
    }

    #[test]
    fn rejects_broken_xml() {
        assert!(parse("<a><b></a>").is_err());
        assert!(parse("<a>").is_err());
        assert!(parse("").is_err());
    }
}
