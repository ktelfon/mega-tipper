package dev.tipbot.spike

/**
 * A pending tip request. The [commentNonce] is the single-use tag we ask the tipper's
 * wallet to attach; it is the only thing linking an on-chain transfer back to this
 * request, so it must be unguessable and time-boxed.
 *
 * @property commentNonce     exact comment text expected on-chain, e.g. "tip_9f3a1c7b"
 * @property recipientAddress raw-form creator address the funds must land on
 * @property expectedNanoTon  exact amount in nanoTON; no tolerance
 * @property createdAtEpoch   unix seconds the invoice was issued
 * @property expiresAtEpoch   unix seconds after which the invoice is dead
 */
data class TipInvoice(
    val commentNonce: String,
    val recipientAddress: String,
    val expectedNanoTon: Long,
    val createdAtEpoch: Long,
    val expiresAtEpoch: Long,
) {
    fun isWithinWindow(eventTimestamp: Long): Boolean =
        eventTimestamp in createdAtEpoch..expiresAtEpoch
}
