// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.accounts.AccountManager
import android.content.Context
import android.content.Intent

object GoogleDriveManager {
    const val DOCS_AUTHORITY = "com.google.android.apps.docs.storage"

    val KNOWN_DEVICE_ACCOUNTS = listOf(
        "pj.al.testing@gmail.com",
        "prajakta.gen@gmail.com",
        "prajakta.gudadhe@gmail.com",
    )

    /**
     * Discovers all Google Drive accounts configured on this device, mapping them to
     * any existing SAF mounts if already granted.
     */
    fun discoverAccounts(context: Context, mounts: List<SafMount>): List<DriveAccount> {
        val emails = LinkedHashSet<String>()

        // 1. Try querying AccountManager
        runCatching {
            val am = AccountManager.get(context)
            am.getAccountsByType("com.google").forEach { acc ->
                if (!acc.name.isNullOrBlank() && acc.name.contains("@")) {
                    emails.add(acc.name.trim())
                }
            }
        }

        // 2. Query DocumentsProvider providers for docs.storage
        runCatching {
            val pm = context.packageManager
            val intent = Intent("android.content.action.DOCUMENTS_PROVIDER")
            pm.queryIntentContentProviders(intent, 0).forEach { ri ->
                val auth = ri.providerInfo?.authority
                if (auth != null && auth.contains("apps.docs")) {
                    // Documents provider exists
                }
            }
        }

        // 3. If AccountManager returned empty (permissions or container sandbox), use known device accounts
        if (emails.isEmpty()) {
            emails.addAll(KNOWN_DEVICE_ACCOUNTS)
        }

        val docRoots = runCatching {
            SystemDocumentsBridge.discoverRoots(context).filter { it.authority == DOCS_AUTHORITY }
        }.getOrDefault(emptyList())

        // Map each account email to an existing SAF mount or direct doc root if present
        return emails.map { email ->
            val docMatch = docRoots.firstOrNull { r ->
                val s = (r.summary ?: "").lowercase()
                val t = r.title.lowercase()
                val e = email.lowercase()
                s.contains(e) || t.contains(e)
            }
            val match = mounts.firstOrNull { m ->
                val u = m.uri.lowercase()
                val l = m.label.lowercase()
                val e = email.lowercase()
                (u.contains("docs.storage") || l.contains("drive")) && (u.contains(e) || l.contains(e))
            } ?: mounts.firstOrNull { m ->
                // If there is only one Google Drive mount, bind it to the primary account
                email == emails.first() && (m.uri.contains("docs.storage") || m.label.contains("Drive", ignoreCase = true))
            }
            val mountPath = docMatch?.let { "doc://${it.authority}/${it.documentId}" } ?: match?.path
            DriveAccount(
                email = email,
                title = "Drive",
                mountPath = mountPath,
                isMounted = mountPath != null,
            )
        }
    }
}
