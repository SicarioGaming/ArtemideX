package com.limelight.binding.audio.securemic;

/**
 * Starwire paired with one PC's microphone receiver: the device id it uses with that PC and the
 * 32-byte pairing key they agreed (docs/dev/mic-protocol.md), and who the PC is.
 */
public final class MicPairing {
    /** Starwire's id for the PC (ComputerDetails.uuid). */
    public final String computerUuid;
    /** Starwire's name for the PC when it was paired. */
    public final String computerName;
    public final byte[] deviceId;
    public final byte[] key;
    public final byte[] receiverId;
    /** What the receiver called itself, cleaned for showing. */
    public final String receiverName;
    public final long pairedAtMs;

    public MicPairing(String computerUuid, String computerName, byte[] deviceId, byte[] key,
                      byte[] receiverId, String receiverName, long pairedAtMs) {
        if (computerUuid == null || deviceId.length != SwmcCodec.ID_LENGTH || key.length != SwmcCodec.KEY_LENGTH
                || receiverId.length != SwmcCodec.ID_LENGTH) {
            throw new IllegalArgumentException("not a microphone pairing");
        }
        this.computerUuid = computerUuid;
        this.computerName = computerName != null ? computerName : "";
        this.deviceId = deviceId.clone();
        this.key = key.clone();
        this.receiverId = receiverId.clone();
        this.receiverName = receiverName != null ? receiverName : "";
        this.pairedAtMs = pairedAtMs;
    }

    /** The PC's name to show: Starwire's name for it, else the receiver's. */
    public String displayName() {
        return !computerName.isEmpty() ? computerName : receiverName;
    }
}
