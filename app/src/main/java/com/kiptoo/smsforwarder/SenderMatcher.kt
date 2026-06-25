package com.kiptoo.smsforwarder

/**
 * Single source of truth for whitelist matching, shared by the realtime
 * receiver and the reconciliation sweep so the two capture paths can never
 * filter senders differently. Punctuation-tolerant: "M-PESA" matches "MPESA".
 *
 * Empty whitelist semantics are the CALLER's decision, not encoded here:
 *  - SmsReceiver treats empty whitelist as "forward all".
 *  - SweepWorker treats empty whitelist as "do not sweep" (returns before calling this).
 * This function assumes a non-empty whitelist and answers only: does this
 * sender match any entry?
 */
object SenderMatcher {
    private fun normalize(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /** True if [sender] matches any [whitelist] entry under normalized substring match. */
    fun matches(sender: String, whitelist: Set<String>): Boolean {
        val n = normalize(sender)
        if (n.isEmpty()) return false
        return whitelist.any { entry ->
            val e = normalize(entry)
            e.isNotEmpty() && n.contains(e)
        }
    }
}