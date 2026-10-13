# Fixed tsclientlib file-transfer cancellation patch

Source: ReSpeak/tsclientlib ee3bc6f45a7137db7793ba5593a321df400d53e5, tsclientlib crate.
MIT OR Apache-2.0; licenses included. Dependencies remain at the same revision.

The fixed upstream API owns TCP connection/key-write futures but exposes no cancellation.
Add `Connection::cancel_filetransfer`: tag these futures with their existing handle,
drop cancelled futures and already queued streams, and reject late transfer notifications.
Reuse of a handle clears its cancellation marker. No TeamSpeak wire command changes.
Both file-transfer ID allocation sites use wrapping addition to match the u16 wire field.

Cargo paths point to the fixed git revision. Upstream device/network test harness and dev
dependencies are omitted; cancellation regressions live in src/lib.rs and core media tests.
