# MobileSpeak protocol patch

This crate's `src/` is copied from ReSpeak/tsclientlib at
`ee3bc6f45a7137db7793ba5593a321df400d53e5` (MIT OR Apache-2.0).
Only `src/resend.rs` is modified. Cargo patches the same pinned source for both
tsclientlib and the optional diagnostic dependency; no Cargo cache edits or
runtime downloads are needed.

Changes: preserve pending Ping records when other packets arrive, match Pong
IDs across wraparound, bound and expire pending records, schedule idle Pings
independently of incoming traffic, wake queued probes immediately, and use
incoming traffic for connection timeouts. Each accepted RTT measurement has a sequence and monotonic timestamp.
The RTT smoothing formulas are unchanged.

The manifest points the two sibling crates at the original Git revision and
keeps the dev dependencies needed for the library tests. Licenses and
the upstream README are included.
