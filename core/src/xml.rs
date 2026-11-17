//! A small, lossless XML element tree.
//!
//! KeePass stores everything inside the encrypted payload as XML. We keep that XML as a generic
//! tree rather than parsing it into narrow structs, so elements we don't understand (newer KeePass
//! features, other apps' extensions) are carried through untouched. Typed access lives in
//! `model.rs` as views over this tree.
//!
//! KeePass XML has no mixed content: an element has either child elements or text, never both.
//! Whitespace between child elements is therefore dropped, and leaf text is kept exactly.

use quick_xml::Reader;
use quick_xml::escape::resolve_predefined_entity;
use quick_xml::events::{BytesStart, Event};
use zeroize::Zeroizing;

use crate::error::{Result, corrupt};

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

#[cfg(test)]
mod tests {
    use super::*;

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
