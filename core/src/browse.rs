//! Browsing and searching an open database: what the app's group lists and search results show.
//!
//! Nothing here ever reads a protected value. Lists and search only see fields the file stores
//! unprotected, so a password (or any field the user chose to protect) can't leak into a list,
//! and typing part of a password into the search box finds nothing.

use crate::model::{Database, Entry, Group};

/// One group as the app shows it: its subgroups and entries, and the way back up.
pub struct Listing<'a> {
    pub group: Group<'a>,
    /// From the root group down to this group's parent. Empty for the root group.
    pub parents: Vec<Group<'a>>,
    /// Subgroups in file order, except that the recycle bin always comes last.
    pub groups: Vec<Group<'a>>,
    /// Entries in file order.
    pub entries: Vec<Entry<'a>>,
}

/// An entry that matched a search (or was looked up by UUID), and where it lives.
pub struct SearchHit<'a> {
    pub entry: Entry<'a>,
    /// The groups from below the root down to the entry's own group. Empty for root entries.
    pub path: Vec<Group<'a>>,
}

impl Database {
    /// Lists a group: the root group if `uuid` is `None`. Returns `None` if no group has that
    /// UUID. Entry history is never listed.
    pub fn listing(&self, uuid: Option<&[u8; 16]>) -> Option<Listing<'_>> {
        let mut parents = Vec::new();
        let group = match uuid {
            None => self.root(),
            Some(uuid) => find_group(self.root(), uuid, &mut parents)?,
        };
        let (mut groups, bins): (Vec<_>, Vec<_>) =
            group.groups().partition(|g| !self.is_recycle_bin(g));
        groups.extend(bins);
        Some(Listing {
            group,
            parents,
            groups,
            entries: group.entries().collect(),
        })
    }

    /// Finds an entry anywhere in the database (history excluded), with the groups from below
    /// the root down to its own group.
    pub fn find_entry(&self, uuid: &[u8; 16]) -> Option<SearchHit<'_>> {
        fn walk<'a>(
            group: Group<'a>,
            uuid: &[u8; 16],
            path: &mut Vec<Group<'a>>,
        ) -> Option<Entry<'a>> {
            if let Some(entry) = group.entries().find(|e| e.uuid().as_ref() == Some(uuid)) {
                return Some(entry);
            }
            for child in group.groups() {
                path.push(child);
                if let Some(entry) = walk(child, uuid, path) {
                    return Some(entry);
                }
                path.pop();
            }
            None
        }
        let mut path = Vec::new();
        let entry = walk(self.root(), uuid, &mut path)?;
        Some(SearchHit { entry, path })
    }

    /// Whether `group` is this database's recycle bin.
    pub fn is_recycle_bin(&self, group: &Group<'_>) -> bool {
        let bin = self.recycle_bin();
        bin.is_some() && group.uuid() == bin
    }

    /// Finds entries in the whole database, in file order.
    ///
    /// The query is split into words, and an entry matches if every word appears, ignoring
    /// case, in its title, username, URL, notes, or tags (any of them, not necessarily the same
    /// one). Protected fields are never searched. Neither are old versions in entry history, the
    /// recycle bin, or groups whose "searchable" setting is off. A blank query finds nothing.
    pub fn search(&self, query: &str) -> Vec<SearchHit<'_>> {
        let terms: Vec<String> = query.split_whitespace().map(str::to_lowercase).collect();
        let mut hits = Vec::new();
        if !terms.is_empty() {
            self.for_each_searchable(|entry, path| {
                if matches(&entry, &terms) {
                    hits.push(SearchHit {
                        entry,
                        path: path.to_vec(),
                    });
                }
            });
        }
        hits
    }

    /// Calls `visit` for every entry that search and autofill may offer, in file order, with
    /// the groups from below the root down to the entry's own group. That leaves out old
    /// versions in entry history, the recycle bin, and groups whose "searchable" setting is off.
    pub(crate) fn for_each_searchable<'a>(
        &'a self,
        mut visit: impl FnMut(Entry<'a>, &[Group<'a>]),
    ) {
        let root = self.root();
        let searchable = root.enable_searching().unwrap_or(true);
        let bin = self.recycle_bin();
        visit_group(root, bin, searchable, &mut Vec::new(), &mut visit);
    }
}

/// Depth-first search for a group below `group`, recording the way down in `parents`.
fn find_group<'a>(
    group: Group<'a>,
    uuid: &[u8; 16],
    parents: &mut Vec<Group<'a>>,
) -> Option<Group<'a>> {
    if group.uuid().as_ref() == Some(uuid) {
        return Some(group);
    }
    parents.push(group);
    for child in group.groups() {
        if let Some(found) = find_group(child, uuid, parents) {
            return Some(found);
        }
    }
    parents.pop();
    None
}

fn visit_group<'a>(
    group: Group<'a>,
    bin: Option<[u8; 16]>,
    searchable: bool,
    path: &mut Vec<Group<'a>>,
    visit: &mut impl FnMut(Entry<'a>, &[Group<'a>]),
) {
    if searchable {
        for entry in group.entries() {
            visit(entry, path);
        }
    }
    for child in group.groups() {
        if bin.is_some() && child.uuid() == bin {
            continue;
        }
        let searchable = child.enable_searching().unwrap_or(searchable);
        path.push(child);
        visit_group(child, bin, searchable, path, visit);
        path.pop();
    }
}

fn matches(entry: &Entry<'_>, terms: &[String]) -> bool {
    let fields: Vec<String> = ["Title", "UserName", "URL", "Notes"]
        .into_iter()
        .map(|key| entry.unprotected_field(key))
        .chain([entry.tags()])
        .filter(|text| !text.is_empty())
        .map(str::to_lowercase)
        .collect();
    terms
        .iter()
        .all(|term| fields.iter().any(|field| field.contains(term.as_str())))
}
