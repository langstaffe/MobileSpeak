// MobileSpeak modifies Ping/Pong tracking and RTT measurements; see ../MOBILESPEAK.md.
use std::cmp::{min, Ord, Ordering};
use std::collections::{BTreeMap, BinaryHeap};
use std::convert::From;
use std::fmt;
use std::hash::{Hash, Hasher};
use std::ops::{Add, Sub};
use std::pin::Pin;
use std::task::{Context, Poll};

use futures::prelude::*;
use num_traits::ToPrimitive;
use pin_project_lite::pin_project;
use tokio::time::{Duration, Instant, Sleep};
use tracing::{info, trace, warn};
use tsproto_packets::packets::*;

use crate::connection::{Connection, StreamItem};
use crate::{Error, Result, UDP_SINK_CAPACITY};

// TODO implement fast retransmit: 2 Acks received but earlier packet not acked -> retransmit
// TODO implement slow start and redo slow start when send window reaches 1, also reset all tries then

// Use cubic for congestion control: https://en.wikipedia.org/wiki/CUBIC_TCP
// But scaling with number of sent packets instead of time because we might not
// send packets that often.

/// Congestion windows gets down to 0.3*w_max for BETA=0.7
const BETA: f32 = 0.7;
/// Increase over w_max after roughly 5 packets (C=0.2 needs seven packets).
const C: f32 = 0.5;
/// Maximum number of outstanding RTT probes.
const PING_COUNT: usize = 30;
/// Duration in seconds between sending two ping packets.
const PING_SECONDS: u64 = 1;
/// Duration in seconds between resetting the statistic counters.
const STAT_SECONDS: u64 = 1;
/// Size of an UDP header in bytes.
const UDP_HEADER_SIZE: u32 = 8;
/// Size of an IPv4 header in bytes.
const IPV4_HEADER_SIZE: u32 = 20;
// TODO Use correct IPv6 header size to compute bandwidth
// Size of an IPv6 header in bytes.
//const IPV6_HEADER_SIZE: u32 = 40;

/// Events to inform a resender of the current state of a connection.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Hash)]
pub enum ResenderState {
	/// The connection is starting, reduce the timeout time.
	Connecting,
	/// The handshake is completed, this is the normal operation mode.
	Connected,
	/// The connection is tearing down, reduce the timeout time.
	Disconnecting,
	/// The connection is gone, we only send ack packets.
	Disconnected,
}

#[derive(Clone, Copy, Default, Eq, Hash, PartialEq)]
pub struct PartialPacketId {
	pub generation_id: u32,
	pub packet_id: u16,
}

#[derive(Clone, Copy, Eq, Hash, PartialEq)]
pub struct PacketId {
	pub packet_type: PacketType,
	pub part: PartialPacketId,
}

#[derive(Clone, Debug)]
pub struct SendRecordId {
	/// The last time when the packet was sent.
	pub last: Instant,
	/// How often the packet was already resent.
	pub tries: usize,
	id: PacketId,
}

/// A record of a packet that can be resent.
#[derive(Debug)]
struct SendRecord {
	/// When this packet was originally sent.
	pub sent: Instant,
	pub id: SendRecordId,
	pub packet: OutUdpPacket,
}

#[derive(Debug)]
struct Ping {
	id: PartialPacketId,
	/// When the ping packet was sent.
	sent: Instant,
}

/// Enum for different collected packet statistics.
#[derive(Clone, Copy, Debug)]
pub enum PacketStat {
	/// Commands and acks
	InControl,
	/// Ping and Pong
	InKeepalive,
	/// Voice
	InSpeech,
	/// Commands and acks
	OutControl,
	/// Ping and Pong
	OutKeepalive,
	/// Voice
	OutSpeech,
}

/// Metrics collected to compute packet loss.
///
/// The count of incoming packets includes packets that were lost. It is computed by the difference
/// of packet ids.
///
/// Packet losses that are impossible to track: Outgoing voice, incoming commands, outgoing pongs.
#[derive(Clone, Copy, Debug)]
enum PacketLossStat {
	/// Count of incoming voice packets.
	VoiceInCount,
	/// Count of packet ids that we should have received but did not get.
	VoiceInLost,
	/// Count of outgoing acks.
	AckOutCount,
	/// Count of already acked commands that were received.
	AckOutLost,
	/// Count of incoming ack packets.
	AckInCount,
	/// Count of packet ids that we should have received but did not get.
	AckInLost,
	/// Count of command packets that were resent.
	///
	/// If a command packet needs to be resent, either the command or the ack was lost.
	AckInOrCommandOutLost,
	/// Count of outgoing command packets.
	CommandOutCount,
	/// Count of incoming pong packets.
	PongInCount,
	/// Count of packet ids that we should have received but did not get.
	PongInLost,
	// Count of ping packets for which we did not get an answer.
	//
	// If no pong is received for a ping, either the ping or the pong was lost.
	// We do not handle many pings, so just use PingOutCount - PongInCount.
	//PongInOrPingOutLost,
	/// Count of outgoing pings.
	PingOutCount,
	/// Count of incoming pings.
	PingInCount,
	/// Count of packet ids that we should have received but did not get.
	PingInLost,
}

/// Network statistics of a connection.
#[derive(Clone)]
pub struct ConnectionStats {
	/// Exponentially averaged Round Trip Time.
	pub rtt: Duration,
	/// Deviation of the rtt.
	pub rtt_dev: Duration,
	/// Count and monotonic time of accepted RTT measurements, even if values stay equal.
	pub rtt_measurements: u64,
	pub rtt_measured_at: Option<Instant>,
	/// Total count of packets since start of the connection. Indexed by `PacketStat`.
	pub total_packets: [u64; 6],
	/// Total count of bytes since start of the connection. Indexed by `PacketStat`.
	pub total_bytes: [u64; 6],
	/// Stats collected to compute packet loss. Indexed by `PacketLossStat`.
	packet_loss: [u32; 13],
	/// Bytes in the last second. Indexed by `PacketStat`.
	///
	/// For the last 60 seconds, so the per minute stats can be computed.
	pub last_second_bytes: [[u32; 6]; 60],
	/// The index in `last_second_bytes` that will be written next.
	next_second_index: u8,
}

/// An intermediate struct to collect network statistics.
///
/// Collected for one second and reset after moving them to `ConnectionStats`.
#[derive(Clone, Debug, Default)]
struct ConnectionStatsCounter {
	bytes: [u32; 6],
	/// Stats collected to compute packet loss. Indexed by `PacketLossStat`.
	packet_loss: [u32; 13],
}

pin_project! {
	/// Timers are extra because they need to be pinned.
	#[derive(Debug)]
	struct Timers {
		// The future to wake us up when the next packet should be resent.
		#[pin]
		timeout: Sleep,
		// The timer used for sending ping packets.
		#[pin]
		ping_timeout: Sleep,
		// The timer used for disconnecting the connection.
		#[pin]
		state_timeout: Sleep,
		// The timer used to update network statistics.
		#[pin]
		stats_timeout: Sleep,
	}
}

/// Resend command and init packets until the other side acknowledges them.
#[derive(Debug)]
pub struct Resender {
	/// Send queue ordered by when a packet has to be sent.
	///
	/// The maximum in this queue is the next packet that should be resent.
	/// This is a part of `full_send_queue`.
	send_queue: BinaryHeap<SendRecordId>,
	/// Send queue ordered by packet id.
	///
	/// There is one queue per packet type: `Init`, `Command` and `CommandLow`.
	full_send_queue: [BTreeMap<PartialPacketId, SendRecord>; 3],
	/// All packets with an id less than this index id are currently in the
	/// `send_queue`. Packets with an id greater or equal to this index are not
	/// in the send queue.
	send_queue_indices: [PartialPacketId; 3],
	config: ResendConfig,
	state: ResenderState,
	/// A list of the last sent pings that were not yet acknowledged.
	last_pings: Vec<Ping>,
	/// In progress counters.
	stats_counter: ConnectionStatsCounter,
	/// Current up to date statistics.
	pub stats: ConnectionStats,

	// Congestion control
	/// The maximum send window before the last reduction.
	w_max: u16,
	/// The time when the last packet loss occurred.
	///
	/// This is not necessarily the accurate time, but the duration until
	/// now/no_congestion_since is accurate.
	last_loss: Instant,
	/// The send queue was never full since this time. We use this to not
	/// increase the send window in this case.
	no_congestion_since: Option<Instant>,

	/// Last packet reception, used to detect a dead connected peer.
	last_receive: Instant,
	/// Incoming traffic must not postpone RTT probes indefinitely.
	last_ping: Instant,
	/// When the last packet was added to the send queue.
	///
	/// This is used to handle timeouts when disconnecting.
	last_send: Instant,
	/// When the statistics were last reset.
	last_stat: Instant,

	timers: Pin<Box<Timers>>,
}

#[derive(Clone, Debug)]
pub struct ResendConfig {
	// Close the connection after no packet is received for this duration.
	pub connecting_timeout: Duration,
	pub normal_timeout: Duration,
	pub disconnect_timeout: Duration,

	/// Smoothed Round Trip Time.
	pub srtt: Duration,
	/// Deviation of the srtt.
	pub srtt_dev: Duration,
}

impl Ord for PartialPacketId {
	fn cmp(&self, other: &Self) -> Ordering {
		self.generation_id
			.cmp(&other.generation_id)
			.then_with(|| self.packet_id.cmp(&other.packet_id))
	}
}

impl PartialOrd for PartialPacketId {
	fn partial_cmp(&self, other: &Self) -> Option<Ordering> { Some(self.cmp(other)) }
}

impl Add<u16> for PartialPacketId {
	type Output = Self;
	fn add(self, rhs: u16) -> Self::Output {
		let (packet_id, next_gen) = self.packet_id.overflowing_add(rhs);
		Self {
			generation_id: if next_gen {
				self.generation_id.wrapping_add(1)
			} else {
				self.generation_id
			},
			packet_id,
		}
	}
}

impl Sub<u16> for PartialPacketId {
	type Output = Self;
	fn sub(self, rhs: u16) -> Self::Output {
		let (packet_id, last_gen) = self.packet_id.overflowing_sub(rhs);
		Self {
			generation_id: if last_gen {
				self.generation_id.wrapping_sub(1)
			} else {
				self.generation_id
			},
			packet_id,
		}
	}
}

impl Sub<PartialPacketId> for PartialPacketId {
	type Output = i64;
	fn sub(self, rhs: Self) -> Self::Output {
		let gen_diff = i64::from(self.generation_id) - i64::from(rhs.generation_id);
		let id_diff = i64::from(self.packet_id) - i64::from(rhs.packet_id);

		gen_diff * i64::from(u16::MAX) + id_diff
	}
}

impl PartialOrd for PacketId {
	fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
		if self.packet_type == other.packet_type { Some(self.part.cmp(&other.part)) } else { None }
	}
}

impl From<&OutUdpPacket> for PacketId {
	fn from(packet: &OutUdpPacket) -> Self {
		Self {
			packet_type: packet.packet_type(),
			part: PartialPacketId {
				generation_id: packet.generation_id(),
				packet_id: packet.packet_id(),
			},
		}
	}
}

impl fmt::Debug for PacketId {
	fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
		write!(f, "{:?}{:?}", self.packet_type, self.part)?;
		Ok(())
	}
}

impl fmt::Debug for PartialPacketId {
	fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
		write!(f, "({:x}:{:x})", self.generation_id, self.packet_id)?;
		Ok(())
	}
}

impl Ord for SendRecordId {
	fn cmp(&self, other: &Self) -> Ordering {
		// If the packet was not already sent, it is more important
		if self.tries == 0 {
			if other.tries != 0 {
				return Ordering::Greater;
			}
		} else if other.tries == 0 {
			return Ordering::Less;
		}
		// The smallest time is the most important time
		self.last.cmp(&other.last).reverse().then_with(||
			// Else, the lower packet id is more important
			self.id.part.cmp(&other.id.part).reverse())
	}
}

impl PartialOrd for SendRecordId {
	fn partial_cmp(&self, other: &Self) -> Option<Ordering> { Some(self.cmp(other)) }
}

impl PartialEq for SendRecordId {
	fn eq(&self, other: &Self) -> bool { self.id.eq(&other.id) }
}
impl Eq for SendRecordId {}

impl Hash for SendRecordId {
	fn hash<H: Hasher>(&self, state: &mut H) { self.id.hash(state); }
}

impl Default for Resender {
	fn default() -> Self {
		let now = Instant::now();
		Self {
			send_queue: Default::default(),
			full_send_queue: Default::default(),
			send_queue_indices: Default::default(),
			config: Default::default(),
			state: ResenderState::Connecting,
			last_pings: Default::default(),
			stats: Default::default(),
			stats_counter: Default::default(),

			w_max: UDP_SINK_CAPACITY as u16,
			last_loss: now,
			no_congestion_since: Some(now),

			last_receive: now,
			last_ping: now,
			last_send: now,
			last_stat: now,

			timers: Box::pin(Timers {
				timeout: tokio::time::sleep(std::time::Duration::from_secs(1)),
				ping_timeout: tokio::time::sleep(std::time::Duration::from_secs(PING_SECONDS)),
				state_timeout: tokio::time::sleep(std::time::Duration::from_secs(1)),
				stats_timeout: tokio::time::sleep(std::time::Duration::from_secs(STAT_SECONDS)),
			}),
		}
	}
}

impl Resender {
	fn packet_type_to_index(t: PacketType) -> usize {
		match t {
			PacketType::Init => 0,
			PacketType::Command => 1,
			PacketType::CommandLow => 2,
			_ => panic!("Resender cannot handle packet type {:?}", t),
		}
	}

	pub fn ack_packet(con: &mut Connection, cx: &mut Context, p_type: PacketType, p_id: u16) {
		if p_type == PacketType::Ping {
			Self::ack_ping(con, p_id);
			return;
		}

		// Remove from ordered queue
		let queue = &mut con.resender.full_send_queue[Self::packet_type_to_index(p_type)];
		let mut queue_iter = queue.iter();
		if let Some((first, _)) = queue_iter.next() {
			let id = if first.packet_id == p_id {
				let p_id = if let Some((_, rec2)) = queue_iter.next() {
					// Ack all until the next packet
					rec2.id.id.part
				} else if p_type == PacketType::Init {
					// Ack the current packet
					PartialPacketId { generation_id: 0, packet_id: p_id + 1 }
				} else {
					// Ack all until the next packet to send
					con.codec.outgoing_p_ids[p_type.to_usize().unwrap()]
				};

				let id = PacketId { packet_type: p_type, part: p_id - 1 };
				con.stream_items.push_back(StreamItem::AckPacket(id));

				*first
			} else {
				PartialPacketId {
					generation_id: if p_id < first.packet_id {
						first.generation_id.wrapping_add(1)
					} else {
						first.generation_id
					},
					packet_id: p_id,
				}
			};

			if let Some(rec) = queue.remove(&id) {
				// Update srtt if the packet was not resent
				if rec.id.tries == 1 {
					let now = Instant::now();
					con.resender.update_srtt(now - rec.sent);
				}

				// Notify the waker that we can send another packet from the
				// send queue.
				cx.waker().wake_by_ref();
			}
		}
	}

	pub fn ack_ping(con: &mut Connection, p_id: u16) {
		// Wire IDs wrap, so the bounded pending list is not sorted by packet_id.
		if let Some(i) = con.resender.last_pings.iter().position(|p| p.id.packet_id == p_id) {
			let ping = con.resender.last_pings.remove(i);
			let now = Instant::now();
			if now - ping.sent < con.resender.get_timeout() {
				con.resender.update_srtt(now - ping.sent);
			}
		}
	}

	pub fn received_packet(&mut self) { self.last_receive = Instant::now(); }

	pub fn handle_loss_incoming(&mut self, packet: &InPacket, in_recv_win: bool, cur_next: u16) {
		let p_type = packet.header().packet_type();
		let id = packet.header().packet_id();
		let id_diff = u32::from(id.wrapping_sub(cur_next));
		let p_stat = PacketStat::from(p_type, true);
		let len = (packet.header().data().len() + packet.content().len()) as u32
			+ UDP_HEADER_SIZE
			+ IPV4_HEADER_SIZE;

		self.stats_counter.bytes[p_stat as usize] += len;
		self.stats.total_packets[p_stat as usize] += 1;
		self.stats.total_bytes[p_stat as usize] += u64::from(len);

		let stats;
		if p_type.is_voice() {
			stats = Some((PacketLossStat::VoiceInCount, PacketLossStat::VoiceInLost));
		} else if p_type.is_ack() {
			stats = Some((PacketLossStat::AckInCount, PacketLossStat::AckInLost));
		} else if p_type == PacketType::Pong {
			stats = Some((PacketLossStat::PongInCount, PacketLossStat::PongInLost));
		} else if p_type == PacketType::Ping {
			stats = Some((PacketLossStat::PingInCount, PacketLossStat::PingInLost));
		} else {
			stats = None;
		}

		if let Some((count, lost)) = stats {
			if in_recv_win {
				self.stats_counter.packet_loss[count as usize] += id_diff + 1;
				self.stats_counter.packet_loss[lost as usize] += id_diff;
			} else {
				self.stats_counter.packet_loss[lost as usize] =
					self.stats_counter.packet_loss[lost as usize].saturating_sub(1);
			}
		}
	}

	pub fn handle_loss_outgoing(&mut self, packet: &OutUdpPacket) {
		let p_type = packet.data().header().packet_type();
		let p_stat = PacketStat::from(p_type, false);
		// 8 byte UDP header
		let len = packet.data().data().len() as u32 + UDP_HEADER_SIZE + IPV4_HEADER_SIZE;
		self.stats_counter.handle_loss_outgoing(packet, p_stat, len);
		self.stats.handle_loss_outgoing(p_stat, u64::from(len));
	}

	pub fn handle_loss_resend_ack(&mut self) {
		self.stats_counter.packet_loss[PacketLossStat::AckOutLost as usize] += 1;
	}

	fn get_timeout(&self) -> Duration {
		match self.state {
			ResenderState::Connecting => self.config.connecting_timeout,
			ResenderState::Disconnecting | ResenderState::Disconnected => {
				self.config.disconnect_timeout
			}
			ResenderState::Connected => self.config.normal_timeout,
		}
	}

	/// Inform the resender of state changes of the connection.
	pub fn set_state(&mut self, state: ResenderState) {
		info!(from = ?self.state, to = ?state, "Resender: Changed state");
		self.state = state;
		if state == ResenderState::Connected {
			self.last_ping = Instant::now();
		} else {
			self.last_pings.clear();
		}

		self.last_send = Instant::now();
		let new_timeout = self.last_send + self.get_timeout();
		let timers = self.timers.as_mut().project();
		timers.state_timeout.reset(new_timeout);
	}

	pub fn get_state(&self) -> ResenderState { self.state }

	/// If the send queue is full if it reached the congestion window size or
	/// it contains packets that were not yet sent once.
	pub fn is_full(&self) -> bool { self.full_send_queue.len() >= self.get_window() as usize }

	/// If the send queue is empty.
	pub fn is_empty(&self) -> bool { self.full_send_queue.iter().all(|q| q.is_empty()) }

	/// Take the first packets from `to_send_ordered` and put them into
	/// `to_send`.
	///
	/// This is done on packet loss, when the send queue is rebuilt.
	fn rebuild_send_queue(&mut self) {
		self.send_queue.clear();
		self.send_queue_indices = Default::default();
		self.fill_up_send_queue();
	}

	/// Fill up to the send window size.
	fn fill_up_send_queue(&mut self) {
		let get_skip_closure = |i: usize| {
			let start = self.send_queue_indices[i];
			move |r: &&SendRecord| r.id.id.part < start
		};
		let mut iters = [
			self.full_send_queue[0].values().skip_while(get_skip_closure(0)).peekable(),
			self.full_send_queue[1].values().skip_while(get_skip_closure(1)).peekable(),
			self.full_send_queue[2].values().skip_while(get_skip_closure(2)).peekable(),
		];

		for _ in self.send_queue.len()..(self.get_window() as usize) {
			let mut max_i = None;
			let mut min_time = None;

			for (i, iter) in iters.iter_mut().enumerate() {
				if let Some(rec) = iter.peek() {
					if min_time.map(|t| t < rec.sent).unwrap_or(true) {
						max_i = Some(i);
						min_time = Some(rec.sent);
					}
				}
			}

			if let Some(max_i) = max_i {
				let max = iters[max_i].next().unwrap().id.clone();
				self.send_queue_indices[max_i] = max.id.part + 1;
				self.send_queue.push(max);
			} else {
				if self.no_congestion_since.is_none() {
					self.no_congestion_since = Some(Instant::now());
				}
				return;
			}
		}

		if let Some(until) = self.no_congestion_since.take() {
			self.last_loss = Instant::now() - (until - self.last_loss);
		}
	}

	/// The amount of packets that can be in-flight concurrently.
	///
	/// The CUBIC congestion control window.
	fn get_window(&self) -> u16 {
		let time = self.no_congestion_since.unwrap_or_else(Instant::now) - self.last_loss;
		let res = C
			* (time.as_secs_f32() - (self.w_max as f32 * BETA / C).powf(1.0 / 3.0)).powf(3.0)
			+ self.w_max as f32;
		let max = u16::MAX / 2;
		if res > max as f32 {
			max
		} else if res < 1.0 {
			1
		} else {
			res as u16
		}
	}

	/// Add another duration to the stored smoothed rtt.
	fn update_srtt(&mut self, rtt: Duration) {
		let diff =
			if rtt > self.config.srtt { rtt - self.config.srtt } else { self.config.srtt - rtt };
		self.config.srtt_dev = self.config.srtt_dev * 3 / 4 + diff / 4;
		self.config.srtt = self.config.srtt * 7 / 8 + rtt / 8;

		// Display RTT uses the existing faster exponential average.
		let diff = if rtt > self.stats.rtt { rtt - self.stats.rtt } else { self.stats.rtt - rtt };
		self.stats.rtt_dev = self.stats.rtt_dev * 3 / 4 + diff / 4;
		self.stats.rtt = self.stats.rtt / 2 + rtt / 2;
		self.stats.rtt_measurements = self.stats.rtt_measurements.wrapping_add(1);
		self.stats.rtt_measured_at = Some(Instant::now());
	}

	pub fn send_packet(con: &mut Connection, packet: OutUdpPacket) {
		con.resender.last_send = Instant::now();
		let rec = SendRecord {
			sent: Instant::now(),
			id: SendRecordId { last: Instant::now(), tries: 0, id: (&packet).into() },
			packet,
		};

		trace!(record = ?rec, window = con.resender.get_window(), "Adding send record");
		let i = Self::packet_type_to_index(rec.id.id.packet_type);
		// Reduce index if necessary (needed e.g. for resending lower init packets)
		if con.resender.send_queue_indices[i] > rec.id.id.part {
			con.resender.send_queue_indices[i] = rec.id.id.part;
		}

		con.resender.full_send_queue[i].insert(rec.id.id.part, rec);
		con.resender.fill_up_send_queue();
		trace!(state = ?con.resender.send_queue, indices = ?con.resender.send_queue_indices, "After adding send record");
	}

	/// Returns an error if the timeout is exceeded and the connection is
	/// considered dead or another unrecoverable error occurs.
	pub fn poll_resend(con: &mut Connection, cx: &mut Context) -> Result<()> {
		trace!(state = ?con.resender.send_queue, "Poll resend");
		let timeout = con.resender.get_timeout();
		// Send a packet at least every second
		let max_send_rto = Duration::from_secs(1);

		// Check if there are packets to send.
		loop {
			let now = Instant::now();
			let window = con.resender.get_window();

			// Retransmission timeout
			let mut rto: Duration = con.resender.config.srtt + con.resender.config.srtt_dev * 4;
			if rto > max_send_rto {
				rto = max_send_rto;
			}
			let last_threshold = now - rto;

			let mut rec = if let Some(rec) = con.resender.send_queue.peek_mut() {
				rec
			} else {
				break;
			};
			trace!("Polling send record");

			// Skip if not contained in full_send_queue. This happens when the
			// packet was acknowledged.
			let full_queue =
				&mut con.resender.full_send_queue[Self::packet_type_to_index(rec.id.packet_type)];
			let full_rec = if let Some(r) = full_queue.get_mut(&rec.id.part) {
				r
			} else {
				drop(rec);
				con.resender.send_queue.pop();
				con.resender.fill_up_send_queue();
				continue;
			};

			// Check if we should resend this packet or not
			if rec.tries != 0 && rec.last > last_threshold {
				// Schedule next send
				let dur = rec.last - last_threshold;
				let timers = con.resender.timers.as_mut().project();
				timers.timeout.reset(now + dur);
				let timers = con.resender.timers.as_mut().project();
				if let Poll::Ready(()) = timers.timeout.poll(cx) {
					continue;
				}
				break;
			}

			if now - full_rec.sent > timeout {
				return Err(Error::Timeout("Packet was not acked"));
			}

			// Try to send this packet
			trace!("Try sending packet");
			match Connection::static_poll_send_udp_packet(
				&*con.udp_socket,
				con.address,
				&con.event_listeners,
				cx,
				&full_rec.packet,
			) {
				Poll::Pending => break,
				Poll::Ready(r) => {
					r?;
					if rec.tries != 0 {
						let to_s = if con.is_client { "S" } else { "C" };
						warn!(id = ?rec.id,
							tries = rec.tries,
							last = %format!("{:?} ago", now - rec.last),
							to = to_s,
							srtt = ?con.resender.config.srtt,
							srtt_dev = ?con.resender.config.srtt_dev,
							?rto,
							send_window = window,
							"Resend"
						);
						con.resender.stats_counter.handle_loss_resend_command();
					}

					// Successfully started sending the packet, update record
					rec.last = now;
					rec.tries += 1;
					full_rec.id = rec.clone();

					let p_type = full_rec.packet.data().header().packet_type();
					let p_stat = PacketStat::from(p_type, false);
					let len = full_rec.packet.data().data().len() as u32
						+ UDP_HEADER_SIZE + IPV4_HEADER_SIZE;
					con.resender.stats_counter.handle_loss_outgoing(&full_rec.packet, p_stat, len);
					con.resender.stats.handle_loss_outgoing(p_stat, u64::from(len));

					if rec.tries != 1 {
						drop(rec);
						// Double srtt on packet loss
						con.resender.config.srtt *= 2;
						if con.resender.config.srtt > timeout {
							con.resender.config.srtt = timeout;
						}

						// Handle congestion window
						con.resender.w_max = con.resender.get_window();
						con.resender.last_loss = Instant::now();
						con.resender.no_congestion_since = None;
						con.resender.rebuild_send_queue();
					} else {
						drop(rec);
					}
				}
			}
		}

		Ok(())
	}

	/// Returns an error if the timeout is exceeded and the connection is
	/// considered dead or another unrecoverable error occurs.
	pub fn poll_ping(con: &mut Connection, cx: &mut Context) -> Result<()> {
		let now = Instant::now();
		let timeout = con.resender.get_timeout();

		if con.resender.state == ResenderState::Disconnecting {
			if now - con.resender.last_send >= timeout {
				return Err(Error::Timeout("No disconnect ack received"));
			}

			let timers = con.resender.timers.as_mut().project();
			timers.state_timeout.reset(con.resender.last_send + timeout);
			let timers = con.resender.timers.as_mut().project();
			if let Poll::Ready(()) = timers.state_timeout.poll(cx) {
				return Err(Error::Timeout("No disconnect ack received"));
			}
		}

		// Update stats
		loop {
			let next_reset = con.resender.last_stat + Duration::from_secs(STAT_SECONDS);
			let timers = con.resender.timers.as_mut().project();
			timers.stats_timeout.reset(next_reset);

			let timers = con.resender.timers.as_mut().project();
			if let Poll::Ready(()) = timers.stats_timeout.poll(cx) {
				// Reset stats
				con.resender.last_stat = Instant::now();
				let stats = &mut con.resender.stats;
				let counter = &mut con.resender.stats_counter;
				let second_index = stats.next_second_index;
				stats.last_second_bytes[second_index as usize] = counter.bytes;
				stats.next_second_index = (second_index + 1) % stats.last_second_bytes.len() as u8;
				stats.packet_loss = counter.packet_loss;
				counter.reset();

				con.stream_items.push_back(StreamItem::NetworkStatsUpdated);
			} else {
				break;
			}
		}

		if con.resender.get_state() == ResenderState::Connected {
			// Packet reception proves liveness; it must not delete pending RTT probes.
			con.resender.last_pings.retain(|ping| now - ping.sent < timeout);
			if now - con.resender.last_receive >= timeout {
				return Err(Error::Timeout("Server did not respond to pings"));
			}
		}

		if con.resender.get_state() == ResenderState::Connected
			&& !con.resender.full_send_queue.iter().any(|q| !q.is_empty())
		{
			// Send pings if we are connected and there are no packets in the queue
			loop {
				let now = Instant::now();
				let next_ping = con.resender.last_ping + Duration::from_secs(PING_SECONDS);
				let timers = con.resender.timers.as_mut().project();
				timers.ping_timeout.reset(next_ping);

				let timers = con.resender.timers.as_mut().project();
				if let Poll::Ready(()) = timers.ping_timeout.poll(cx) {
					// Send ping
					let dir = if con.is_client { Direction::C2S } else { Direction::S2C };
					let packet = OutPacket::new_with_dir(dir, Flags::empty(), PacketType::Ping);
					let p_id = con.send_packet(packet)?;
					con.resender.last_ping = now;
					con.resender.last_pings.push(Ping { id: p_id.part, sent: now });
					if con.resender.last_pings.len() > PING_COUNT {
						con.resender.last_pings.remove(0);
					}
					// Flush the queued Ping without waiting for another packet or timer.
					cx.waker().wake_by_ref();
				} else {
					break;
				}
			}
		}

		Ok(())
	}
}

impl ConnectionStatsCounter {
	fn handle_loss_outgoing(&mut self, packet: &OutUdpPacket, p_stat: PacketStat, len: u32) {
		let p_type = packet.data().header().packet_type();

		self.bytes[p_stat as usize] += len;

		if p_type.is_ack() {
			self.packet_loss[PacketLossStat::AckOutCount as usize] += 1;
		} else if p_type == PacketType::Ping {
			self.packet_loss[PacketLossStat::PingOutCount as usize] += 1;
		} else if p_type.is_command() {
			self.packet_loss[PacketLossStat::CommandOutCount as usize] += 1;
		}
	}

	pub(crate) fn handle_loss_resend_command(&mut self) {
		self.packet_loss[PacketLossStat::AckInOrCommandOutLost as usize] += 1;
	}

	fn reset(&mut self) { *self = Default::default(); }
}

impl ConnectionStats {
	fn handle_loss_outgoing(&mut self, p_stat: PacketStat, len: u64) {
		self.total_packets[p_stat as usize] += 1;
		self.total_bytes[p_stat as usize] += len;
	}

	pub fn get_last_second_bytes(&self) -> &[u32; 6] {
		&self.last_second_bytes[((self.next_second_index + 60 - 1) % 60) as usize]
	}

	pub fn get_last_minute_bytes(&self) -> [u64; 6] {
		let mut bytes = [0; 6];
		for bs in &self.last_second_bytes[..] {
			for (i, b) in bs.iter().enumerate() {
				bytes[i] += u64::from(*b);
			}
		}
		bytes
	}

	// Packetloss stats for TeamSpeak
	pub fn get_packetloss_s2c_speech(&self) -> f32 {
		let count = self.packet_loss[PacketLossStat::VoiceInCount as usize];
		if count == 0 {
			0.0
		} else {
			self.packet_loss[PacketLossStat::VoiceInLost as usize] as f32 / count as f32
		}
	}

	pub fn get_packetloss_s2c_keepalive(&self) -> f32 {
		let count = self.packet_loss[PacketLossStat::PingInCount as usize]
			+ self.packet_loss[PacketLossStat::PongInCount as usize];
		if count == 0 {
			0.0
		} else {
			(self.packet_loss[PacketLossStat::PingInLost as usize] as f32
				+ self.packet_loss[PacketLossStat::PongInLost as usize] as f32)
				/ count as f32
		}
	}

	pub fn get_packetloss_s2c_control(&self) -> f32 {
		let count = self.packet_loss[PacketLossStat::AckInCount as usize];
		if count == 0 {
			0.0
		} else {
			self.packet_loss[PacketLossStat::AckInLost as usize] as f32 / count as f32
		}
	}

	pub fn get_packetloss_s2c_total(&self) -> f32 {
		let count = self.packet_loss[PacketLossStat::VoiceInCount as usize]
			+ self.packet_loss[PacketLossStat::PingInCount as usize]
			+ self.packet_loss[PacketLossStat::PongInCount as usize]
			+ self.packet_loss[PacketLossStat::AckInCount as usize];
		if count == 0 {
			0.0
		} else {
			(self.packet_loss[PacketLossStat::VoiceInLost as usize] as f32
				+ self.packet_loss[PacketLossStat::PingInLost as usize] as f32
				+ self.packet_loss[PacketLossStat::PongInLost as usize] as f32
				+ self.packet_loss[PacketLossStat::AckInLost as usize] as f32)
				/ count as f32
		}
	}

	/// Compute the average incoming and outgoing packet loss.
	pub fn get_packetloss(&self) -> f32 {
		let count = self.packet_loss[PacketLossStat::VoiceInCount as usize]
			+ self.packet_loss[PacketLossStat::PingInCount as usize]
			+ self.packet_loss[PacketLossStat::PongInCount as usize]
			+ self.packet_loss[PacketLossStat::AckInCount as usize]
			+ self.packet_loss[PacketLossStat::AckOutCount as usize]
			+ self.packet_loss[PacketLossStat::CommandOutCount as usize];
		// Do not use ping count, it is unreliable
		if count == 0 {
			0.0
		} else {
			let command_out_lost = min(
				self.packet_loss[PacketLossStat::AckInOrCommandOutLost as usize]
					.saturating_sub(self.packet_loss[PacketLossStat::AckInLost as usize]),
				self.packet_loss[PacketLossStat::CommandOutCount as usize],
			);

			(self.packet_loss[PacketLossStat::VoiceInLost as usize] as f32
				+ self.packet_loss[PacketLossStat::PingInLost as usize] as f32
				+ self.packet_loss[PacketLossStat::PongInLost as usize] as f32
				+ self.packet_loss[PacketLossStat::AckInLost as usize] as f32
				+ self.packet_loss[PacketLossStat::AckOutLost as usize] as f32
				+ command_out_lost as f32)
				/ count as f32
		}
	}
}

impl Default for ResendConfig {
	fn default() -> Self {
		Self {
			connecting_timeout: Duration::from_secs(5),
			normal_timeout: Duration::from_secs(30),
			disconnect_timeout: Duration::from_secs(5),

			srtt: Duration::from_millis(500),
			srtt_dev: Duration::from_millis(0),
		}
	}
}

impl Default for ConnectionStats {
	fn default() -> Self {
		Self {
			rtt: Duration::from_millis(0),
			rtt_dev: Duration::from_millis(0),
			rtt_measurements: 0,
			rtt_measured_at: None,
			total_packets: Default::default(),
			total_bytes: Default::default(),
			packet_loss: Default::default(),
			last_second_bytes: [Default::default(); 60],
			next_second_index: Default::default(),
		}
	}
}

impl fmt::Debug for ConnectionStats {
	fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
		f.debug_struct("ConnectionStats")
			.field("rtt", &self.rtt)
			.field("rtt_dev", &self.rtt_dev)
			.field("rtt_measurements", &self.rtt_measurements)
			.field("rtt_measured_at", &self.rtt_measured_at)
			.field("total_packets", &self.total_packets)
			.field("total_bytes", &self.total_bytes)
			.field("packet_loss", &self.packet_loss)
			.field("last_second_bytes", self.get_last_second_bytes())
			.field("next_second_index", &self.next_second_index)
			.finish()
	}
}

impl PacketStat {
	fn from(t: PacketType, incoming: bool) -> Self {
		match t {
			PacketType::Voice | PacketType::VoiceWhisper => {
				if incoming {
					PacketStat::InSpeech
				} else {
					PacketStat::OutSpeech
				}
			}
			PacketType::Command
			| PacketType::CommandLow
			| PacketType::Ack
			| PacketType::AckLow
			| PacketType::Init => {
				if incoming {
					PacketStat::InControl
				} else {
					PacketStat::OutControl
				}
			}
			PacketType::Ping | PacketType::Pong => {
				if incoming {
					PacketStat::InKeepalive
				} else {
					PacketStat::OutKeepalive
				}
			}
		}
	}
}

#[cfg(test)]
mod network_rtt_tests {
	use super::*;
	use crate::connection::Socket;
	use std::{io, net::SocketAddr};
	use tokio::io::ReadBuf;

	struct TestSocket;
	impl Socket for TestSocket {
		fn poll_recv_from(&self, _: &mut Context, _: &mut ReadBuf) -> Poll<io::Result<SocketAddr>> {
			Poll::Pending
		}
		fn poll_send_to(&self, _: &mut Context, buf: &[u8], _: SocketAddr) -> Poll<io::Result<usize>> {
			Poll::Ready(Ok(buf.len()))
		}
		fn local_addr(&self) -> io::Result<SocketAddr> { Ok("127.0.0.1:12345".parse().unwrap()) }
	}

	fn connected() -> Connection {
		let mut con = Connection::new(true, "127.0.0.1:9987".parse().unwrap(), Box::new(TestSocket));
		con.resender.set_state(ResenderState::Connected);
		con.resender.stats.rtt = Duration::from_millis(100);
		con
	}

	fn poll(con: &mut Connection) -> Result<()> {
		let waker = futures::task::noop_waker();
		Resender::poll_ping(con, &mut Context::from_waker(&waker))
	}

	#[tokio::test(start_paused = true)]
	async fn network_rtt_other_packet_before_pong_preserves_measurement() {
		let mut con = connected();
		tokio::time::advance(Duration::from_secs(1)).await;
		poll(&mut con).unwrap();
		assert_eq!(con.resender.last_pings.len(), 1);
		tokio::time::advance(Duration::from_millis(30)).await;
		con.resender.received_packet();
		poll(&mut con).unwrap();
		assert_eq!(con.resender.last_pings.len(), 1);
		Resender::ack_ping(&mut con, 0);
		assert_eq!(con.resender.stats.rtt, Duration::from_millis(65));
		assert_eq!(con.resender.stats.rtt_measurements, 1);
		assert_eq!(con.resender.stats.rtt_measured_at, Some(Instant::now()));
		Resender::ack_ping(&mut con, 0);
		Resender::ack_ping(&mut con, 123);
		assert_eq!(con.resender.stats.rtt_measurements, 1);
	}

	#[tokio::test(start_paused = true)]
	async fn network_rtt_traffic_does_not_postpone_probes_or_fake_timeout() {
		let mut con = connected();
		for _ in 0..160 {
			tokio::time::advance(Duration::from_millis(500)).await;
			con.resender.received_packet();
			poll(&mut con).unwrap();
			assert!(con.resender.last_pings.len() <= PING_COUNT);
		}
		assert_eq!(con.codec.outgoing_p_ids[PacketType::Ping as usize].packet_id, 80);
		assert_eq!(con.resender.last_pings.len(), PING_COUNT);
		assert_eq!(con.resender.stats.rtt_measurements, 0);
		assert!(con.resender.last_pings.iter().all(|ping| Instant::now() - ping.sent < Duration::from_secs(30)));
	}

	#[tokio::test(start_paused = true)]
	async fn network_rtt_missing_traffic_still_times_out() {
		let mut con = connected();
		tokio::time::advance(Duration::from_secs(29)).await;
		poll(&mut con).unwrap();
		tokio::time::advance(Duration::from_secs(1)).await;
		assert!(matches!(poll(&mut con), Err(Error::Timeout(_))));
		con.resender.received_packet();
		poll(&mut con).unwrap();
		con.resender.set_state(ResenderState::Disconnecting);
		tokio::time::advance(Duration::from_secs(5)).await;
		assert!(matches!(poll(&mut con), Err(Error::Timeout("No disconnect ack received"))));
	}

	#[tokio::test(start_paused = true)]
	async fn network_rtt_wrapped_ids_expired_pongs_and_connection_teardown() {
		let mut con = connected();
		let sent = Instant::now();
		con.resender.last_pings = vec![
			Ping { id: PartialPacketId { generation_id: 0, packet_id: u16::MAX }, sent },
			Ping { id: PartialPacketId { generation_id: 1, packet_id: 0 }, sent },
		];
		tokio::time::advance(Duration::from_millis(100)).await;
		Resender::ack_ping(&mut con, u16::MAX);
		Resender::ack_ping(&mut con, 0);
		// A real measurement can leave the RTT unchanged.
		assert_eq!(con.resender.stats.rtt, Duration::from_millis(100));
		assert_eq!(con.resender.stats.rtt_measurements, 2);
		con.resender.last_pings.push(Ping { id: PartialPacketId::default(), sent });
		tokio::time::advance(Duration::from_secs(30)).await;
		Resender::ack_ping(&mut con, 0);
		assert_eq!(con.resender.stats.rtt_measurements, 2);
		con.resender.last_pings.push(Ping { id: PartialPacketId::default(), sent: Instant::now() });
		con.resender.set_state(ResenderState::Disconnected);
		assert!(con.resender.last_pings.is_empty());
	}

	#[tokio::test(start_paused = true)]
	async fn network_rtt_retransmitted_commands_do_not_count_as_measurements() {
		for tries in [1, 2] {
			let mut con = connected();
			let id = PartialPacketId { generation_id: 0, packet_id: 1 };
			con.send_packet(OutPacket::new_with_dir(Direction::C2S, Flags::empty(), PacketType::Command)).unwrap();
			con.resender.full_send_queue[1].get_mut(&id).unwrap().id.tries = tries;
			tokio::time::advance(Duration::from_millis(100)).await;
			let waker = futures::task::noop_waker();
			Resender::ack_packet(&mut con, &mut Context::from_waker(&waker), PacketType::Command, 1);
			assert_eq!(con.resender.stats.rtt_measurements, u64::from(tries == 1));
		}
	}

	#[tokio::test(start_paused = true)]
	async fn network_rtt_due_ping_wakes_sender_before_the_next_statistics_tick() {
		use futures::task::{waker, ArcWake};
		use std::sync::{Arc, atomic::{AtomicBool, Ordering}};
		struct Wake(AtomicBool);
		impl ArcWake for Wake {
			fn wake_by_ref(arc: &Arc<Self>) { arc.0.store(true, Ordering::SeqCst); }
		}
		let mut con = connected();
		tokio::time::advance(Duration::from_millis(100)).await;
		con.resender.set_state(ResenderState::Connected);
		tokio::time::advance(Duration::from_millis(900)).await;
		let wake = Arc::new(Wake(AtomicBool::new(false)));
		let waker = waker(wake.clone());
		let mut cx = Context::from_waker(&waker);
		assert!(matches!(con.poll_next_unpin(&mut cx), Poll::Ready(Some(Ok(StreamItem::NetworkStatsUpdated)))));
		tokio::time::advance(Duration::from_millis(100)).await;
		wake.0.store(false, Ordering::SeqCst);
		assert!(matches!(con.poll_next_unpin(&mut cx), Poll::Pending));
		assert_eq!(con.resender.last_pings.len(), 1);
		assert!(wake.0.load(Ordering::SeqCst), "Queued Ping must wake the UDP sender immediately");
	}
}
