//! Cues from ordered tsclientlib bookkeeping events, never from UI snapshots.
use std::collections::HashMap;
use tsclientlib::{events::{Event, PropertyId, PropertyValue}, ChannelId, ClientId, Reason};
use crate::sounds::Sound;

#[derive(Default)]
pub struct Session {
    channel: Option<ChannelId>,
    alerted: bool,
    pub output_muted: bool,
}

impl Session {
    pub fn unexpected(&mut self) -> Option<Sound> {
        if self.alerted { return None; }
        self.alerted = true;
        Some(Sound::Alert)
    }

    pub fn recovering(&mut self) -> Option<Sound> {
        self.channel = None;
        self.unexpected()
    }

    pub fn connected(&self) -> bool { self.channel.is_some() }

    fn entered(&mut self, channel: Option<ChannelId>) -> Option<Sound> {
        self.channel = channel.filter(|id| id.0 != 0);
        self.channel.map(|_| {
            self.alerted = false;
            Sound::SelfEnter
        })
    }

    // The new value lives in bookkeeping; for multiple moves in a single packet,
    // the next event's old value is the preceding move's destination.
    pub fn book(&mut self, events: &[Event], own: ClientId,
        channel: impl Fn(ClientId) -> Option<ChannelId>) -> Vec<Option<Sound>> {
        let mut destinations = vec![None; events.len()];
        let mut next = HashMap::new();
        for (i, event) in events.iter().enumerate().rev() {
            match event {
                Event::PropertyChanged { id: PropertyId::ClientChannel(id), old: PropertyValue::ChannelId(old), .. } => {
                    destinations[i] = next.get(id).copied().unwrap_or_else(|| channel(*id));
                    next.insert(*id, Some(*old));
                }
                Event::PropertyRemoved { id: PropertyId::Client(id), old: PropertyValue::Client(old), .. } => { next.insert(*id, Some(old.channel)); }
                Event::PropertyAdded { id: PropertyId::Client(id), .. } => {
                    destinations[i] = next.get(id).copied().unwrap_or_else(|| channel(*id));
                    next.insert(*id, None);
                }
                _ => {}
            }
        }
        let leaving = events.iter().any(|e| matches!(e, Event::PropertyRemoved {id: PropertyId::Client(id), ..} if *id == own));
        let mut cues = vec![None; events.len()];
        for (i, event) in events.iter().enumerate() {
            cues[i] = match event {
                Event::PropertyAdded { id: PropertyId::Server, .. } => {
                    if self.channel.is_some() { None } else { self.entered(channel(own)) }
                }
                Event::PropertyChanged { id: PropertyId::ClientChannel(id), old: PropertyValue::ChannelId(old), extra, .. } => {
                    let destination = destinations[i].filter(|id| id.0 != 0);
                    if *id == own {
                        if extra.reason == Some(Reason::KickChannel) {
                            self.channel = destination;
                            Some(Sound::Alert)
                        } else if destination == self.channel { None } else {
                            self.entered(destination)
                        }
                    } else if !leaving && real_membership(extra.reason) && destination != Some(*old) {
                        if Some(*old) == self.channel { Some(Sound::MemberLeave) }
                        else if destination.is_some() && destination == self.channel { Some(Sound::MemberEnter) }
                        else { None }
                    } else { None }
                }
                // InitServer creates an empty client map; our actual channel arrives later.
                Event::PropertyAdded { id: PropertyId::Client(id), extra, .. }
                    if *id == own && self.channel.is_none() => {
                        if extra.reason == Some(Reason::KickChannel) {
                            self.channel = destinations[i].filter(|id| id.0 != 0);
                            Some(Sound::Alert)
                        } else { self.entered(destinations[i]) }
                    }
                Event::PropertyAdded { id: PropertyId::Client(id), extra, .. }
                    if *id != own && !leaving && real_membership(extra.reason)
                        && destinations[i].is_some() && destinations[i] == self.channel => Some(Sound::MemberEnter),
                Event::PropertyRemoved { id: PropertyId::Client(id), old: PropertyValue::Client(old), extra, .. } => {
                    if *id == own {
                        self.channel = None;
                        self.unexpected()
                    } else if !leaving && real_membership(extra.reason) && Some(old.channel) == self.channel {
                        Some(Sound::MemberLeave)
                    } else { None }
                }
                _ => None,
            };
        }
        cues
    }
}

fn real_membership(reason: Option<Reason>) -> bool {
    matches!(reason, Some(Reason::None | Reason::Moved | Reason::LostConnection |
        Reason::KickChannel | Reason::KickServer | Reason::KickServerBan |
        Reason::Clientdisconnect | Reason::Serverstop | Reason::ClientdisconnectServerShutdown))
}

#[cfg(test)]
mod tests {
    use super::*;
    use tsclientlib::events::ExtraInfo;
    fn enter() -> Event { Event::PropertyAdded {id:PropertyId::Server, invoker:None, extra:ExtraInfo::default()} }
    fn moved(id: u16, old: u64, reason: Reason) -> Event {
        Event::PropertyChanged { id:PropertyId::ClientChannel(ClientId(id)), old:PropertyValue::ChannelId(ChannelId(old)), invoker:None, extra:ExtraInfo {reason:Some(reason)} }
    }
    fn added(id: u16, reason: Reason) -> Event {
        Event::PropertyAdded {id:PropertyId::Client(ClientId(id)), invoker:None, extra:ExtraInfo {reason:Some(reason)}}
    }
    fn removed(id: u16, channel: u64, reason: Reason) -> Event {
        let client = serde_json::from_value(serde_json::json!({
            "id":id,"channel":channel,"uid":null,"name":"member","input_muted":false,
            "output_muted":false,"output_only_muted":false,"input_hardware_enabled":true,
            "output_hardware_enabled":true,"talk_power_granted":false,"metadata":"","is_recording":false,
            "database_id":1,"channel_group":1,"server_groups":[],"away_message":null,"client_type":"Normal",
            "avatar_hash":"","talk_power":0,"talk_power_request":null,"description":"","is_priority_speaker":false,
            "unread_messages":0,"phonetic_name":"","needed_serverquery_view_power":0,"icon":0,
            "is_channel_commander":false,"country_code":"","inherited_channel_group_from_channel":0,
            "badges":"","user_tag":null,"permission_hints":null,"optional_data":null,"connection_data":null
        })).unwrap();
        Event::PropertyRemoved {id:PropertyId::Client(ClientId(id)),old:PropertyValue::Client(client),invoker:None,extra:ExtraInfo {reason:Some(reason)}}
    }
    #[test]
    fn initial_reconnect_switch_move_kick_and_duplicate_states() {
        let mut s = Session::default();
        let own = ClientId(1);
        assert_eq!(s.book(&[enter()], own, |_| Some(ChannelId(2))), vec![Some(Sound::SelfEnter)]);
        assert_eq!(s.book(&[enter()], own, |_| Some(ChannelId(2))), vec![None]);
        assert_eq!(s.book(&[moved(1,2,Reason::None)], own, |_| Some(ChannelId(2))), vec![None]);
        assert_eq!(s.book(&[moved(1,2,Reason::KickChannel)], own, |_| Some(ChannelId(2))), vec![Some(Sound::Alert)]);
        assert_eq!(s.book(&[moved(1,2,Reason::Moved)], own, |_| Some(ChannelId(3))), vec![Some(Sound::SelfEnter)]);
        assert_eq!(s.book(&[moved(1,3,Reason::KickChannel)], own, |_| Some(ChannelId(2))), vec![Some(Sound::Alert)]);
        assert_eq!(s.book(&[moved(1,2,Reason::None)], own, |_| Some(ChannelId(2))), vec![None]);
        assert_eq!(s.recovering(), Some(Sound::Alert));
        assert_eq!(s.recovering(), None);
        assert_eq!(s.unexpected(), None);
        assert_eq!(s.book(&[enter()], own, |_| Some(ChannelId(2))), vec![Some(Sound::SelfEnter)]);
        assert_eq!(s.unexpected(), Some(Sound::Alert));
    }
    #[test]
    fn default_channel_entry_arrives_after_empty_server_book() {
        let mut s = Session::default();
        let own = ClientId(1);
        assert_eq!(s.book(&[enter()], own, |_| None), vec![None]);
        let channel = Event::PropertyAdded {id:PropertyId::Channel(ChannelId(2)),invoker:None,extra:ExtraInfo::default()};
        assert_eq!(s.book(&[channel], own, |_| None), vec![None]);
        assert_eq!(s.book(&[added(2,Reason::None),added(3,Reason::Subscription)], own, |id| (id != own).then_some(ChannelId(2))), vec![None,None]);
        assert_eq!(s.book(&[added(1,Reason::None)], own, |_| Some(ChannelId(2))), vec![Some(Sound::SelfEnter)]);
        assert!(s.connected());
        assert_eq!(s.book(&[added(1,Reason::None),added(3,Reason::Subscription),moved(1,2,Reason::None)], own, |_| Some(ChannelId(2))), vec![None,None,None]);
        assert_eq!(s.book(&[added(4,Reason::None)], own, |_| Some(ChannelId(2))), vec![Some(Sound::MemberEnter)]);
        assert_eq!(s.book(&[moved(1,2,Reason::Moved)], own, |_| Some(ChannelId(3))), vec![Some(Sound::SelfEnter)]);
    }
    #[test]
    fn initial_own_and_subscription_records_in_one_batch_only_play_self_enter() {
        let mut s = Session::default();
        let own = ClientId(1);
        s.book(&[enter()], own, |_| None);
        assert_eq!(s.book(&[added(2,Reason::None),added(1,Reason::None),added(3,Reason::Subscription)], own, |_| Some(ChannelId(2))),
            vec![None,Some(Sound::SelfEnter),None]);
        assert_eq!(s.book(&[added(1,Reason::None)], own, |_| Some(ChannelId(2))), vec![None]);
    }
    #[test]
    fn reconnect_waits_for_actual_channel_before_rearming_alerts() {
        let mut s = Session::default();
        let own = ClientId(1);
        s.book(&[enter()], own, |_| Some(ChannelId(2)));
        assert_eq!(s.recovering(), Some(Sound::Alert));
        assert_eq!(s.book(&[enter()], own, |_| None), vec![None]);
        assert_eq!(s.recovering(), None);
        assert_eq!(s.book(&[enter()], own, |_| None), vec![None]);
        assert_eq!(s.book(&[added(1,Reason::None)], own, |_| Some(ChannelId(0))), vec![None]);
        assert_eq!(s.book(&[moved(1,0,Reason::None)], own, |_| Some(ChannelId(2))), vec![Some(Sound::SelfEnter)]);
        assert_eq!(s.recovering(), Some(Sound::Alert));
        assert_eq!(s.book(&[enter()], own, |_| None), vec![None]);
        assert_eq!(s.book(&[added(1,Reason::None)], own, |_| Some(ChannelId(2))), vec![Some(Sound::SelfEnter)]);
        assert_eq!(s.recovering(), Some(Sound::Alert));
    }
    #[test]
    fn subscription_visibility_and_other_channels_are_not_membership() {
        let mut s = Session::default();
        let own = ClientId(1);
        s.book(&[enter()], own, |_| Some(ChannelId(2)));
        let events = [added(1,Reason::None), added(2,Reason::Subscription), added(3,Reason::None), added(4,Reason::Moved), moved(5,3,Reason::Moved), moved(6,2,Reason::Moved)];
        assert_eq!(s.book(&events, own, |id| Some(ChannelId(if id.0 == 3 || id.0 == 6 {3} else {2}))),
            vec![None,None,None,Some(Sound::MemberEnter),Some(Sound::MemberEnter),Some(Sound::MemberLeave)]);
    }
    #[test]
    fn ordered_moves_between_polls_are_all_preserved() {
        let mut s = Session::default();
        s.book(&[enter()], ClientId(1), |_| Some(ChannelId(2)));
        assert_eq!(s.book(&[moved(2,3,Reason::Moved),moved(2,2,Reason::Moved),moved(1,2,Reason::Moved),added(3,Reason::Subscription),added(4,Reason::None)], ClientId(1), |id| Some(ChannelId(if id.0 == 2 {4} else {3}))),
            vec![Some(Sound::MemberEnter),Some(Sound::MemberLeave),Some(Sound::SelfEnter),None,Some(Sound::MemberEnter)]);
    }
    #[test]
    fn server_kick_disconnect_and_visibility_removal_do_not_cascade() {
        let mut s = Session::default();
        let own = ClientId(1);
        s.book(&[enter()], own, |_| Some(ChannelId(2)));
        assert_eq!(s.book(&[removed(2,2,Reason::Subscription),removed(3,3,Reason::LostConnection),removed(4,2,Reason::KickChannel)], own, |_| None),
            vec![None,None,Some(Sound::MemberLeave)]);
        assert_eq!(s.book(&[removed(2,2,Reason::LostConnection),removed(1,2,Reason::KickServer),removed(3,2,Reason::Clientdisconnect)], own, |_| None),
            vec![None,Some(Sound::Alert),None]);
        assert!(!s.connected());
        assert_eq!(s.unexpected(), None);
        assert_eq!(s.recovering(), None);
        assert_eq!(s.book(&[added(3,Reason::Subscription)], own, |_| Some(ChannelId(2))), vec![None]);
    }
}
