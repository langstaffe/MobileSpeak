//! Passive packet timing; never sends probes or changes the displayed statistics.
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tsclientlib::Connection;
use tsproto::{connection::Event, resend::PartialPacketId};
use tsproto_packets::packets::PacketType;

static CONNECTION_SEQUENCE: AtomicU64 = AtomicU64::new(0);
type Key = (PacketType, u16);

#[derive(Default)]
struct Packet {
    queued: Option<Instant>,
    first_send: Option<Instant>,
    last_send: Option<Instant>,
    sends: u32,
}

struct Trace {
    connection: u64,
    packets: HashMap<Key, Packet>,
    queued_ping: Option<Instant>,
    reply_sequence: u64,
    last_reply: Option<Instant>,
    last_udp: Option<Instant>,
    library_values: (Duration, Duration),
    measurement_sequence: u64,
    last_library_change: Option<Instant>,
}

fn wall_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
}

fn age_ms(now: Instant, then: Option<Instant>) -> f64 {
    then.map_or(-1.0, |then| {
        now.saturating_duration_since(then).as_secs_f64() * 1000.0
    })
}

impl Trace {
    fn packet(&mut self, key: Key, now: Instant) -> &mut Packet {
        self.packets
            .retain(|_, p| age_ms(now, p.first_send.or(p.queued)) < 30_000.0);
        if !self.packets.contains_key(&key) && self.packets.len() >= 256 {
            if let Some(oldest) = self
                .packets
                .iter()
                .min_by_key(|(_, p)| p.first_send.or(p.queued))
                .map(|(key, _)| *key)
            {
                self.packets.remove(&oldest);
            }
        }
        self.packets.entry(key).or_default()
    }

    fn sent(&mut self, key: Key, now: Instant) {
        let queued = if key.0 == PacketType::Ping {
            self.queued_ping.take()
        } else {
            None
        };
        let connection = self.connection;
        let p = self.packet(key, now);
        if queued.is_some() {
            p.queued = queued;
        }
        p.first_send.get_or_insert(now);
        p.last_send = Some(now);
        p.sends += 1;
        diagnostic!("NetworkDiag phase=send wall_ms={} connection={} source={:?} packet_id={} sends={} queue_ms={:.3}",
            wall_ms(), connection, key.0, key.1, p.sends, age_ms(now, p.queued));
    }

    fn reply(&mut self, key: Key, now: Instant) -> Option<Packet> {
        let p = self.packets.remove(&key)?;
        if p.first_send.is_none() {
            return None;
        }
        self.reply_sequence += 1;
        self.last_reply = Some(now);
        diagnostic!("NetworkDiag phase=reply wall_ms={} connection={} reply_seq={} source={:?} packet_id={} sends={} first_send_to_reply_ms={:.3} last_send_to_reply_ms={:.3} queued_to_reply_ms={:.3}",
            wall_ms(), self.connection, self.reply_sequence, key.0, key.1, p.sends,
            age_ms(now, p.first_send), age_ms(now, p.last_send), age_ms(now, p.queued));
        Some(p)
    }

    fn event(&mut self, event: &Event<'_>, now: Instant) {
        match event {
            Event::SendPacket(packet) if packet.header().packet_type() == PacketType::Ping => {
                self.queued_ping = Some(now);
            }
            Event::SendUdpPacket(packet) => {
                let header = packet.data().header();
                let kind = header.packet_type();
                if matches!(
                    kind,
                    PacketType::Ping | PacketType::Command | PacketType::CommandLow
                ) {
                    self.sent((kind, header.packet_id()), now);
                }
            }
            Event::ReceiveUdpPacket(_) => self.last_udp = Some(now),
            Event::ReceivePacket(packet) => {
                let kind = match packet.header().packet_type() {
                    PacketType::Pong => PacketType::Ping,
                    PacketType::Ack => PacketType::Command,
                    PacketType::AckLow => PacketType::CommandLow,
                    _ => return,
                };
                if let Ok(Some(id)) = packet.ack_packet() {
                    self.reply((kind, id), now);
                }
            }
            _ => {}
        }
    }
}

pub(crate) struct Diagnostics(Arc<Mutex<Trace>>);

impl Diagnostics {
    pub(crate) fn attach(con: &mut Connection) -> Option<Self> {
        let client = con.get_tsproto_client_mut().ok()?;
        let connection = CONNECTION_SEQUENCE.fetch_add(1, Ordering::Relaxed) + 1;
        let trace = Arc::new(Mutex::new(Trace {
            connection,
            packets: HashMap::new(),
            queued_ping: None,
            reply_sequence: 0,
            last_reply: None,
            last_udp: None,
            library_values: (client.resender.stats.rtt, client.resender.stats.rtt_dev),
            measurement_sequence: client.resender.stats.rtt_measurements,
            last_library_change: None,
        }));
        diagnostic!("NetworkDiag phase=connected wall_ms={} connection={} initial_rtt_ms={:.3} initial_deviation_ms={:.3}",
            wall_ms(), connection,
            client.resender.stats.rtt.as_secs_f64() * 1000.0,
            client.resender.stats.rtt_dev.as_secs_f64() * 1000.0);
        let listener = trace.clone();
        client.event_listeners.push(Box::new(move |event| {
            if let Ok(mut trace) = listener.lock() {
                trace.event(event, Instant::now());
            }
        }));
        Some(Self(trace))
    }

    // shortcut: queue timing covers Ping and the one-packet mute command; extend for other probes.
    pub(crate) fn queued_mute(&self, con: &Connection, input: bool, output: bool) {
        if let (Ok(client), Ok(mut trace)) = (con.get_tsproto_client(), self.0.lock()) {
            let PartialPacketId { packet_id, .. } =
                client.codec.outgoing_p_ids[PacketType::Command as usize];
            let now = Instant::now();
            trace.packet((PacketType::Command, packet_id), now).queued = Some(now);
            diagnostic!(
                "NetworkDiag phase=mute wall_ms={} connection={} packet_id={} input={} output={}",
                wall_ms(),
                trace.connection,
                packet_id,
                input,
                output
            );
        }
    }

    pub(crate) fn observe(&self, con: &Connection) {
        if let (Ok(stats), Ok(mut trace)) = (con.get_network_stats(), self.0.lock()) {
            if stats.rtt_measurements != trace.measurement_sequence {
                trace.measurement_sequence = stats.rtt_measurements;
                diagnostic!("NetworkDiag phase=rtt_measurement wall_ms={} connection={} rtt_seq={} rtt_ms={:.3} deviation_ms={:.3} measurement_age_ms={:.3}",
                    wall_ms(), trace.connection, stats.rtt_measurements,
                    stats.rtt.as_secs_f64() * 1000.0, stats.rtt_dev.as_secs_f64() * 1000.0,
                    age_ms(Instant::now(), stats.rtt_measured_at.map(|time| time.into_std())));
            }
            let values = (stats.rtt, stats.rtt_dev);
            if values != trace.library_values {
                trace.library_values = values;
                trace.last_library_change = Some(Instant::now());
                diagnostic!("NetworkDiag phase=library_change wall_ms={} connection={} reply_seq={} rtt_ms={:.3} deviation_ms={:.3}",
                    wall_ms(), trace.connection, trace.reply_sequence,
                    stats.rtt.as_secs_f64() * 1000.0, stats.rtt_dev.as_secs_f64() * 1000.0);
            }
        }
    }

    pub(crate) fn snapshot(&self, con: &Connection) {
        if let (Ok(stats), Ok(trace)) = (con.get_network_stats(), self.0.lock()) {
            let now = Instant::now();
            diagnostic!("NetworkDiag phase=stats wall_ms={} connection={} reply_seq={} rtt_seq={} rtt_ms={:.3} deviation_ms={:.3} rtt_measurement_age_ms={:.3} reply_age_ms={:.3} library_change_age_ms={:.3} receive_age_ms={:.3} loss_percent={:.3}",
                wall_ms(), trace.connection, trace.reply_sequence,
                stats.rtt_measurements,
                stats.rtt.as_secs_f64() * 1000.0, stats.rtt_dev.as_secs_f64() * 1000.0,
                age_ms(now, stats.rtt_measured_at.map(|time| time.into_std())),
                age_ms(now, trace.last_reply), age_ms(now, trace.last_library_change),
                age_ms(now, trace.last_udp), stats.get_packetloss() * 100.0);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn trace() -> Trace {
        Trace {
            connection: 1,
            packets: HashMap::new(),
            queued_ping: None,
            reply_sequence: 0,
            last_reply: None,
            last_udp: None,
            library_values: (Duration::ZERO, Duration::ZERO),
            measurement_sequence: 0,
            last_library_change: None,
        }
    }

    #[test]
    fn separates_queue_wait_and_reply_time_and_counts_retransmissions() {
        let start = Instant::now();
        let key = (PacketType::Command, 7);
        let mut trace = trace();
        trace.packet(key, start).queued = Some(start);
        trace.sent(key, start + Duration::from_millis(70));
        trace.sent(key, start + Duration::from_millis(170));
        let p = trace
            .reply(key, start + Duration::from_millis(203))
            .unwrap();
        assert_eq!(p.sends, 2);
        assert_eq!(age_ms(p.first_send.unwrap(), p.queued), 70.0);
        assert_eq!(
            age_ms(start + Duration::from_millis(203), p.last_send),
            33.0
        );
        assert_eq!(age_ms(start + Duration::from_millis(203), p.queued), 203.0);
        assert!(trace.reply(key, start + Duration::from_secs(1)).is_none());
        assert_eq!(trace.reply_sequence, 1);
    }

    #[test]
    fn preserves_ping_queue_time_and_bounds_missing_replies() {
        let start = Instant::now();
        let mut trace = trace();
        trace.queued_ping = Some(start);
        trace.sent((PacketType::Ping, 1), start + Duration::from_millis(20));
        let p = trace
            .reply((PacketType::Ping, 1), start + Duration::from_millis(53))
            .unwrap();
        assert_eq!(age_ms(p.first_send.unwrap(), p.queued), 20.0);
        assert_eq!(p.sends, 1);
        for id in 0..300 {
            trace.sent((PacketType::Command, id), start);
        }
        assert_eq!(trace.packets.len(), 256);
        trace.sent((PacketType::Command, 301), start + Duration::from_secs(31));
        assert_eq!(trace.packets.len(), 1);
    }

    #[test]
    fn packet_hooks_match_pong_and_ack_ids_and_ignore_invalid_ack() {
        use tsproto_packets::packets::{Direction, Flags, OutAck, OutPacket, OutUdpPacket};
        let start = Instant::now();
        let mut trace = trace();
        for kind in [
            PacketType::Ping,
            PacketType::Command,
            PacketType::CommandLow,
        ] {
            let mut packet = OutPacket::new_with_dir(Direction::C2S, Flags::empty(), kind);
            trace.event(&Event::SendPacket(&packet), start);
            packet.packet_id(7);
            let udp = OutUdpPacket::new(0, packet);
            trace.event(
                &Event::SendUdpPacket(&udp),
                start + Duration::from_millis(20),
            );
            let reply = OutAck::new(Direction::S2C, kind, 7);
            trace.event(
                &Event::ReceivePacket(&reply.packet()),
                start + Duration::from_millis(53),
            );
            assert!(!trace.packets.contains_key(&(kind, 7)));
        }
        assert_eq!(trace.reply_sequence, 3);
        let invalid = OutPacket::new_with_dir(Direction::S2C, Flags::empty(), PacketType::Ack);
        trace.event(&Event::ReceivePacket(&invalid.packet()), start);
        assert_eq!(trace.reply_sequence, 3);
    }
}
