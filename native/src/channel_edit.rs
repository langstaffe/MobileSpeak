use crate::{channel_files::Files, receive_audio, Output, PendingOperation};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{
    collections::{HashMap, HashSet},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use tsclientlib::{
    data::{Channel, ChannelOptions, Connection as Book},
    messages::c2s::OutChannelDeletePart,
    prelude::*,
    ChannelId, ChannelType, ClientId, CommandError, Connection, MaxClients, MessageHandle, TsError,
};
use tsproto_packets::packets::OutCommand;

#[derive(Deserialize)]
pub struct Target {
    #[serde(rename = "connectionId")]
    connection_id: String,
    server: String,
    channel: u64,
    request_id: String,
}

impl Target {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.channel == 0 {
            return Err("Invalid channel request");
        }
        self.validate_scope()
    }

    pub fn validate_create(&self, create: &Create) -> Result<(), &'static str> {
        self.validate_scope()?;
        if !valid_name(&create.name)
            || !valid_text(&create.password, 1024)
            || (self.channel == 0) != create.position.is_some()
        {
            return Err("Invalid channel settings");
        }
        Ok(())
    }

    fn validate_scope(&self) -> Result<(), &'static str> {
        if self.connection_id.is_empty()
            || self.connection_id.len() > 128
            || !receive_audio::valid_identity(&self.server)
            || self.request_id.is_empty()
            || self.request_id.len() > 128
            || !self.request_id.is_ascii()
        {
            return Err("Invalid channel request");
        }
        Ok(())
    }

    fn book<'a>(&self, con: &'a Connection) -> Option<&'a Book> {
        let state = con.get_state().ok()?;
        if state.server.public_key.get_uid().to_string() != self.server {
            return None;
        }
        Some(state)
    }

    pub(super) fn unavailable(&self, operation: &str, out: &Arc<Mutex<Output>>) {
        self.report(operation, Some("connection_recovering"), None, None, out);
    }

    fn report(
        &self,
        operation: &str,
        error: Option<&str>,
        detail: Option<String>,
        channel: Option<ChannelId>,
        out: &Arc<Mutex<Output>>,
    ) {
        out.lock().unwrap().event(json!({
            "type":"channel_command_result", "operation":operation,
            "connectionId":self.connection_id, "requestId":self.request_id,
            "error":error, "detail":detail, "channel":channel.map(|id| id.0),
        }));
    }
}

#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Kind {
    Temporary,
    SemiPermanent,
    Permanent,
}

pub fn kind(ty: ChannelType) -> Kind {
    match ty {
        ChannelType::Temporary => Kind::Temporary,
        ChannelType::SemiPermanent => Kind::SemiPermanent,
        ChannelType::Permanent => Kind::Permanent,
    }
}

impl From<Kind> for ChannelType {
    fn from(kind: Kind) -> Self {
        match kind {
            Kind::Temporary => Self::Temporary,
            Kind::SemiPermanent => Self::SemiPermanent,
            Kind::Permanent => Self::Permanent,
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "snake_case")]
enum Edge {
    Top,
    Bottom,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Create {
    name: String,
    #[serde(default)]
    password: String,
    channel_type: Kind,
    position: Option<Edge>,
}

impl Create {
    fn options(&self, parent: ChannelId) -> ChannelOptions<'_> {
        let mut options =
            ChannelOptions::new(self.name.trim()).channel_type(self.channel_type.into());
        if parent.0 != 0 {
            options = options.parent_id(parent);
        }
        // The server appends by default. Sending an order requires a separate permission.
        if matches!(self.position, Some(Edge::Top)) {
            options = options.order(ChannelId(0));
        }
        if !self.password.is_empty() {
            options = options.password(&self.password);
        }
        options
    }
}

#[derive(Deserialize)]
pub struct Sibling {
    id: u64,
    order: u64,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Move {
    parent: u64,
    order: u64,
    original_parent: u64,
    original_order: u64,
    siblings: Vec<Sibling>,
}

fn valid_text(value: &str, max: usize) -> bool {
    value.len() <= max && !value.contains('\0')
}

fn valid_name(value: &str) -> bool {
    !value.trim().is_empty() && valid_text(value, 1024)
}

#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(tag = "mode", content = "value", rename_all = "snake_case")]
pub enum Limit {
    Unlimited,
    Limited(u16),
}

pub fn limit(value: MaxClients) -> Option<Limit> {
    match value {
        MaxClients::Unlimited => Some(Limit::Unlimited),
        MaxClients::Limited(count) => Some(Limit::Limited(count)),
        MaxClients::Inherited => None,
    }
}

#[derive(Deserialize)]
#[serde(tag = "mode", content = "value", rename_all = "snake_case")]
enum Password {
    Set(String),
    Remove,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Changes {
    name: Option<String>,
    topic: Option<String>,
    description: Option<String>,
    password: Option<Password>,
    max_clients: Option<Limit>,
    channel_type: Option<Kind>,
}

impl Changes {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.name.as_deref().is_some_and(|v| !valid_name(v))
            || self.topic.as_deref().is_some_and(|v| !valid_text(v, 1024))
            || self
                .description
                .as_deref()
                .is_some_and(|v| !valid_text(v, 65_536))
            || matches!(&self.password, Some(Password::Set(v)) if v.is_empty() || !valid_text(v, 1024))
        {
            return Err("Invalid channel settings");
        }
        if self.name.is_none()
            && self.topic.is_none()
            && self.description.is_none()
            && self.password.is_none()
            && self.max_clients.is_none()
            && self.channel_type.is_none()
        {
            return Err("No channel settings were changed");
        }
        Ok(())
    }

    fn command(&self, channel: &Channel) -> tsclientlib::messages::c2s::OutChannelEditPart<'_> {
        let mut edit = channel.edit();
        if let Some(name) = &self.name {
            edit = edit.set_name(name);
        }
        if let Some(topic) = &self.topic {
            edit = edit.set_topic(topic);
        }
        if let Some(description) = &self.description {
            edit = edit.set_description(description);
        }
        if let Some(password) = &self.password {
            edit = edit.set_password(match password {
                Password::Set(value) => Some(value.as_str()),
                Password::Remove => None,
            });
        }
        if let Some(limit) = self.max_clients {
            edit = edit.set_max_clients(match limit {
                Limit::Unlimited => MaxClients::Unlimited,
                Limit::Limited(count) => MaxClients::Limited(count),
            });
        }
        if let Some(kind) = self.channel_type {
            edit = edit.set_channel_type(kind.into());
        }
        edit
    }
}

pub(super) enum Action {
    Description,
    Edit(Changes),
    Create(Create),
    Move(Move),
    Delete,
}

enum Completion {
    Description,
    Edit(Option<Password>),
    Create {
        name: String,
        password: String,
        created: Option<ChannelId>,
    },
    Move {
        parent: ChannelId,
        order: ChannelId,
    },
    Delete,
}

pub(super) struct Pending {
    target: Target,
    operation: &'static str,
    failure: &'static str,
    completion: Completion,
    acknowledged: bool,
    deadline: Instant,
}

impl Pending {
    pub(super) fn created(&mut self, id: ChannelId, invoker: ClientId, con: &Connection) {
        if let Completion::Create { name, created, .. } = &mut self.completion {
            if self.target.book(con).is_some_and(|state| {
                invoker == state.own_client
                    && state.channels.get(&id).is_some_and(|channel| {
                        channel.parent.0 == self.target.channel && channel.name == *name
                    })
            }) {
                *created = Some(id);
            }
        }
    }

    pub(super) fn result(
        mut self,
        result: Result<(), CommandError>,
        con: &Connection,
        files: &mut Files,
        out: &Arc<Mutex<Output>>,
    ) -> Option<Self> {
        if let Err(error) = result {
            let code = match error.error {
                TsError::ChannelNotEmpty if self.operation == "delete" => {
                    "channel_delete_not_empty"
                }
                TsError::Permissions | TsError::PermissionsClientInsufficient => {
                    "channel_permission_denied"
                }
                _ if error.missing_permission.is_some() => "channel_permission_denied",
                _ => self.failure,
            };
            let detail = error
                .missing_permission
                .map(|permission| format!("failed_permid={}", permission.0))
                .or_else(|| (code != "channel_permission_denied").then(|| error.to_string()));
            self.target
                .report(self.operation, Some(code), detail, None, out);
            return None;
        }
        if let Completion::Edit(Some(password)) = &mut self.completion {
            let value = match password {
                Password::Set(value) => std::mem::take(value),
                Password::Remove => String::new(),
            };
            files.join_password(self.target.channel, value);
        }
        self.acknowledged = true;
        self.waiting(con, files, out).then_some(self)
    }

    pub(super) fn waiting(
        &mut self,
        con: &Connection,
        files: &mut Files,
        out: &Arc<Mutex<Output>>,
    ) -> bool {
        if self.acknowledged {
            if let Some(state) = self.target.book(con) {
                let target = ChannelId(self.target.channel);
                let channel = state.channels.get(&target);
                let done = match &mut self.completion {
                    Completion::Description => {
                        channel.filter(|c| c.optional_data.is_some()).map(|c| c.id)
                    }
                    Completion::Edit(_) => channel.map(|c| c.id),
                    Completion::Create {
                        created, password, ..
                    } => created.map(|id| {
                        if !password.is_empty() {
                            files.join_password(id.0, std::mem::take(password));
                        }
                        id
                    }),
                    Completion::Move { parent, order } => channel
                        .filter(|c| c.parent == *parent && c.order == *order)
                        .map(|c| c.id),
                    Completion::Delete => channel.is_none().then_some(target),
                };
                if let Some(id) = done {
                    self.target
                        .report(self.operation, None, None, Some(id), out);
                    return false;
                }
            }
        }
        if Instant::now() >= self.deadline {
            self.target
                .report(self.operation, Some(self.failure), None, None, out);
            return false;
        }
        true
    }
}

fn move_packet(
    channels: &HashMap<ChannelId, Channel>,
    channel: &Channel,
    change: Move,
) -> Result<OutCommand, &'static str> {
    let parent = ChannelId(change.parent);
    let order = ChannelId(change.order);
    let mut ancestor = parent;
    let mut visited = HashSet::new();
    while ancestor.0 != 0 {
        if ancestor == channel.id || !visited.insert(ancestor) {
            return Err("channel_invalid_parent");
        }
        ancestor = channels
            .get(&ancestor)
            .ok_or("channel_position_changed")?
            .parent;
    }
    let mut expected: HashMap<_, _> = change
        .siblings
        .iter()
        .map(|s| (ChannelId(s.id), ChannelId(s.order)))
        .collect();
    if expected.len() != change.siblings.len()
        || channel.parent.0 != change.original_parent
        || channel.order.0 != change.original_order
        || (order.0 != 0 && !expected.contains_key(&order))
        || channels
            .values()
            .filter(|c| c.parent == parent && c.id != channel.id)
            .any(|c| expected.remove(&c.id) != Some(c.order))
        || !expected.is_empty()
    {
        return Err("channel_position_changed");
    }
    Ok(if channel.parent == parent {
        channel.edit().set_order(order).to_packet()
    } else {
        channel.channel_move(parent, order).to_packet()
    })
}

impl Action {
    fn names(&self) -> (&'static str, &'static str) {
        match self {
            Self::Description => ("description", "channel_description_failed"),
            Self::Edit(_) => ("edit", "channel_edit_failed"),
            Self::Create(_) => ("create", "channel_create_failed"),
            Self::Move(_) => ("move", "channel_move_failed"),
            Self::Delete => ("delete", "channel_delete_failed"),
        }
    }

    fn prepare(
        self,
        state: &Book,
        target: ChannelId,
    ) -> Result<Option<(OutCommand, Completion)>, &'static str> {
        if let Self::Create(create) = self {
            if target.0 != 0 && !state.channels.contains_key(&target) {
                return Err("channel_unavailable");
            }
            let command = state.server.add_channel(create.options(target));
            return Ok(Some((
                command,
                Completion::Create {
                    name: create.name.trim().to_owned(),
                    password: create.password,
                    created: None,
                },
            )));
        }
        let channel = state.channels.get(&target).ok_or("channel_unavailable")?;
        Ok(Some(match self {
            Self::Description if channel.optional_data.is_some() => return Ok(None),
            Self::Description => (channel.description().to_packet(), Completion::Description),
            Self::Edit(changes) => (
                changes.command(channel).to_packet(),
                Completion::Edit(changes.password),
            ),
            Self::Move(change) => {
                let completion = Completion::Move {
                    parent: ChannelId(change.parent),
                    order: ChannelId(change.order),
                };
                (move_packet(&state.channels, channel, change)?, completion)
            }
            Self::Delete => {
                // TeamSpeak can delete empty subchannels with force=false. Check the
                // latest bookkeeping immediately before sending the standard command.
                if state.channels.values().any(|c| c.parent == target)
                    || state.clients.values().any(|c| c.channel == target)
                {
                    return Err("channel_delete_not_empty");
                }
                (
                    OutChannelDeletePart {
                        channel_id: target,
                        force: false,
                    }
                    .to_packet(),
                    Completion::Delete,
                )
            }
            Self::Create(_) => unreachable!(),
        }))
    }
}

pub(super) fn submit(
    target: Target,
    action: Action,
    con: &mut Connection,
    operations: &mut HashMap<MessageHandle, PendingOperation>,
    out: &Arc<Mutex<Output>>,
) {
    let (operation, failure) = action.names();
    let active = {
        let output = out.lock().unwrap();
        output.connection_id.as_deref() == Some(&target.connection_id)
            && output.snapshot["status"] == "connected"
    };
    let Some(state) = target.book(con).filter(|_| active) else {
        target.report(operation, Some("channel_unavailable"), None, None, out);
        return;
    };
    // Creation notifications have an invoker and channel ID, but no command handle.
    if operation == "create"
        && operations.values().any(
            |pending| matches!(pending, PendingOperation::Channel(p) if p.operation == "create"),
        )
    {
        target.report(operation, Some(failure), None, None, out);
        return;
    }
    let (command, completion) = match action.prepare(state, ChannelId(target.channel)) {
        Ok(Some(packet)) => packet,
        Ok(None) => {
            target.report(operation, None, None, Some(ChannelId(target.channel)), out);
            return;
        }
        Err(code) => {
            target.report(operation, Some(code), None, None, out);
            return;
        }
    };
    match command.send_with_result(con) {
        Ok(handle) => {
            operations.insert(
                handle,
                PendingOperation::Channel(Pending {
                    target,
                    operation,
                    failure,
                    completion,
                    acknowledged: false,
                    deadline: Instant::now() + Duration::from_secs(12),
                }),
            );
        }
        Err(_) => target.report(operation, Some(failure), None, None, out),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn channel(id: u64, parent: u64, order: u64) -> Channel {
        serde_json::from_value(json!({
            "id":id, "parent":parent, "order":order, "name":format!("Channel {id}"), "codec":"OpusVoice",
            "channel_type":"Permanent", "forced_silence":false, "subscribed":false,
        })).unwrap()
    }

    #[test]
    fn channel_creation_only_sends_custom_sort_order_for_top() {
        let mut create = Create {
            name: " Voice ".into(),
            password: String::new(),
            channel_type: Kind::Temporary,
            position: Some(Edge::Bottom),
        };
        let bottom = serde_json::to_value(create.options(ChannelId(0))).unwrap();
        assert_eq!(bottom["name"], "Voice");
        assert!(bottom["order"].is_null());
        assert!(bottom["password"].is_null());

        create.position = Some(Edge::Top);
        let top = serde_json::to_value(create.options(ChannelId(0))).unwrap();
        assert_eq!(top["order"], 0);

        create.position = None;
        let child = serde_json::to_value(create.options(ChannelId(12))).unwrap();
        assert_eq!(child["parent_id"], 12);
        assert!(child["order"].is_null());
    }

    #[test]
    fn move_rejects_stale_sibling_chain_and_cycles() {
        let channels: HashMap<_, _> = [channel(7, 0, 0), channel(9, 0, 7), channel(10, 7, 0)]
            .into_iter()
            .map(|c| (c.id, c))
            .collect();
        let change = |parent, order, sibling_order| Move {
            parent,
            order,
            original_parent: 0,
            original_order: 0,
            siblings: vec![Sibling {
                id: 9,
                order: sibling_order,
            }],
        };
        let source = &channels[&ChannelId(7)];
        assert!(move_packet(&channels, source, change(0, 9, 7)).is_ok());
        assert_eq!(
            move_packet(&channels, source, change(0, 9, 0)).err(),
            Some("channel_position_changed")
        );
        assert_eq!(
            move_packet(&channels, source, change(10, 0, 7)).err(),
            Some("channel_invalid_parent")
        );
    }

    #[test]
    fn channel_edits_omit_untouched_fields_and_allow_explicit_clears() {
        let channel: Channel = serde_json::from_value(json!({
            "id": 7, "parent": 0, "order": 0, "name": "Lobby", "codec": "OpusVoice",
            "channel_type": "Permanent", "forced_silence": false, "subscribed": false,
        }))
        .unwrap();
        let changes: Changes = serde_json::from_value(json!({"name":"Welcome"})).unwrap();
        let edit = changes.command(&channel);
        assert!(edit.name.is_some());
        assert!(edit.topic.is_none() && edit.description.is_none() && edit.has_password.is_none());
        assert!(edit.max_clients.is_none() && edit.is_permanent.is_none());

        let changes: Changes = serde_json::from_value(json!({
            "topic":"", "description":"", "password":{"mode":"remove"},
            "max_clients":{"mode":"limited","value":0}, "channel_type":"semi_permanent",
        }))
        .unwrap();
        assert!(changes.validate().is_ok());
        let edit = changes.command(&channel);
        assert_eq!(edit.topic.as_deref(), Some(""));
        assert_eq!(edit.description.as_deref(), Some(""));
        assert_eq!(edit.has_password, Some(false));
        assert_eq!(edit.max_clients, Some(0));
        assert_eq!(edit.is_max_clients_unlimited, Some(false));
        assert_eq!(edit.is_semi_permanent, Some(true));
    }

    #[test]
    fn rejects_empty_edits_unsupported_fields_and_bad_values() {
        for changes in [
            json!({}),
            json!({"name":" "}),
            json!({"topic":"\0"}),
            json!({"password":{"mode":"set","value":""}}),
        ] {
            assert!(serde_json::from_value::<Changes>(changes)
                .unwrap()
                .validate()
                .is_err());
        }
        for changes in [
            json!({"needed_talk_power":10}),
            json!({"max_clients":{"mode":"limited","value":65536}}),
        ] {
            assert!(serde_json::from_value::<Changes>(changes).is_err());
        }
    }
}
