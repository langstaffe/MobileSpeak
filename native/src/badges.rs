use serde::Deserialize;
use std::sync::OnceLock;

// badges.json is generated from the pinned tsclientlib tsproto-structs Badges.csv declaration.
#[derive(Deserialize)]
pub(crate) struct BadgeMetadata {
    pub id: String,
    pub name: String,
    pub description: String,
    pub filename: String,
}

pub(crate) fn known_badges() -> &'static [BadgeMetadata] {
    static BADGES: OnceLock<Vec<BadgeMetadata>> = OnceLock::new();
    BADGES
        .get_or_init(|| {
            serde_json::from_str(include_str!("badges.json")).expect("embedded badge metadata")
        })
        .as_slice()
}
