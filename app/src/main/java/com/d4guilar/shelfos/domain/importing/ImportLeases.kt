// SPDX-License-Identifier: MPL-2.0
package com.d4guilar.shelfos.domain.importing

import kotlinx.coroutines.CompletableDeferred

/**
 * Which sources unfinished imports still depend on. Persisted grants are process-wide and cleanup outlives the
 * review that abandoned it, so one instance is shared by every import in the process.
 *
 * An import holds its source from the moment it starts until its preparation is committed or abandoned. Abandoned
 * work releases its grant only when no library item references the source and no import holds it; otherwise the
 * grant passes to the imports still holding the source, and the last of them to end without committing disposes
 * of it. Library items own the grants of committed sources. A future durable owner (for example a Library Source)
 * joins the caller's `referenced` check rather than releasing grants itself.
 */
class ImportLeases {
    private class Lease {
        var holders = 0
        /** A grant inherited from abandoned work whose private copy is already gone. */
        var inherited: PreparedImport? = null
        /** Set while a decided release is in progress. */
        var releasing: CompletableDeferred<Unit>? = null
    }

    private val leases = HashMap<String, Lease>()

    fun hold(sourceUri: String): Unit = synchronized(leases) { leases.getOrPut(sourceUri, ::Lease).holders++ }

    /** Waits for a release of the source's grant decided before this import held it, so it never finds a grant about to go. */
    suspend fun awaitRelease(sourceUri: String) { synchronized(leases) { leases[sourceUri]?.releasing }?.await() }

    /** Ends a hold whose preparation was committed: the library item now owns the source's grant. */
    fun committed(sourceUri: String): Unit = synchronized(leases) {
        val lease = leases[sourceUri] ?: return
        if (lease.holders > 0) lease.holders--
        lease.inherited = null
        prune(sourceUri, lease)
    }

    /**
     * Ends a hold that did not commit. Returns what the caller must now [dispose] of: its own [prepared] work, plus
     * any grant inherited by the source's last hold.
     */
    fun end(sourceUri: String, prepared: PreparedImport?): PreparedImport? = synchronized(leases) {
        val lease = leases[sourceUri] ?: return prepared
        if (lease.holders > 0) lease.holders--
        val inherited = lease.inherited.takeIf { lease.holders == 0 }
        if (inherited != null) lease.inherited = null
        prune(sourceUri, lease)
        when {
            inherited == null -> prepared
            prepared == null -> inherited
            else -> prepared.copy(acquiredGrant = true)
        }
    }

    /**
     * Disposes of abandoned work once the library has been checked ([referenced]). Its private copy always goes; its
     * grant is released only when nothing depends on the source, and otherwise passes to the imports holding it.
     * [discard] is [PublicationImporter.discard]. Imports starting meanwhile wait in [awaitRelease].
     */
    fun dispose(prepared: PreparedImport, referenced: Boolean, discard: (PreparedImport, Boolean) -> Unit) {
        val uri = prepared.item.sourceUri
        val (stillUsed, release) = synchronized(leases) {
            val lease = leases.getOrPut(uri, ::Lease)
            val held = lease.holders > 0
            val decision = when {
                referenced || !prepared.acquiredGrant -> (referenced || held) to null
                held -> {
                    if (lease.inherited == null) lease.inherited = prepared.copy(item = prepared.item.copy(managedPath = null))
                    true to null
                }
                lease.releasing != null -> true to null // The same grant is already being released.
                else -> false to CompletableDeferred<Unit>().also { lease.releasing = it }
            }
            prune(uri, lease)
            decision
        }
        try { discard(prepared, stillUsed) }
        finally {
            release?.let { done ->
                synchronized(leases) { leases[uri]?.let { lease -> lease.releasing = null; prune(uri, lease) } }
                done.complete(Unit)
            }
        }
    }

    private fun prune(uri: String, lease: Lease) {
        if (lease.holders == 0 && lease.inherited == null && lease.releasing == null) leases.remove(uri)
    }
}
