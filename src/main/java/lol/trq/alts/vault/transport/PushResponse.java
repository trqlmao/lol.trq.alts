package lol.trq.alts.vault.transport;

import com.google.gson.annotations.SerializedName;

/**
 * The result of a push. On success {@code accepted} is true and the new server version/epoch are
 * echoed back; on an optimistic-concurrency clash {@code conflict} is true and the client must pull,
 * re-apply, and retry.
 *
 * @param accepted true if the push was applied
 * @param payloadVersion the server's payload version after this call
 * @param keyEpoch the server's key epoch after this call
 * @param conflict true if the push was rejected because the base version was stale
 * @author trq
 * @since 0.2.0
 */
public record PushResponse(
        @SerializedName("accepted") boolean accepted,
        @SerializedName("payloadVersion") long payloadVersion,
        @SerializedName("keyEpoch") long keyEpoch,
        @SerializedName("conflict") boolean conflict) {}
