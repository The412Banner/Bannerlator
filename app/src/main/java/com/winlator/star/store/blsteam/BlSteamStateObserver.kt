package com.winlator.star.store.blsteam

/**
 * Lifecycle + arbitrary-message observer for [BlSteamSession]. Callbacks
 * fire on a native worker thread — marshal to your own dispatcher.
 *
 * `state` ordinal mapping (matches native `cm_client::ClientState`):
 *   0 = Disconnected
 *   1 = Connecting
 *   2 = Connected   (encrypted channel established, not yet logged on)
 *   3 = LoggedOn    (CMsgClientLogonResponse OK; heartbeat is running)
 *
 * `onClientMessage` is the firehose of inbound application-layer
 * proto-flagged messages. `emsg` and `eresult` come from
 * CMsgProtoBufHeader; `body` is the protobuf payload for the specific
 * message type. Most Phase-2 callers only care about `onStateChanged`.
 */
interface BlSteamStateObserver {
    fun onStateChanged(state: Int)
    fun onClientMessage(emsg: Int, eresult: Int, body: ByteArray)
}
