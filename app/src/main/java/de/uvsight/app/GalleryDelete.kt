package de.uvsight.app

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns

/**
 * Deleting the gallery picture the user picked for Score from photo, once the end is done (Settings →
 * Photo scoring → Delete the gallery photo after scoring). Android never lets an app delete another
 * app's photo silently: from Android 11 on the deletion is a request the system either confirms with
 * the user or, when the app holds the Media management permission (Android 12+), carries out at once.
 */
object GalleryDelete {
    sealed class Outcome {
        object Deleted : Outcome()
        /** The system must run this request (a dialog unless Media management is granted). */
        class Launch(val sender: IntentSender) : Outcome()
        class Failed(val why: String) : Outcome()
    }

    /** True when Android deletes for the app without asking each time (Media management, Android 12+). */
    fun canManage(context: Context) = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(context)

    /** The media library item behind a picked URI: the photo picker and the documents provider hand out other forms. */
    fun mediaUri(context: Context, picked: Uri): Uri? {
        val auth = picked.authority ?: return null
        val path = picked.path ?: ""
        return when {
            auth == MediaStore.AUTHORITY && path.contains("/picker/") ->
                picked.lastPathSegment?.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it) }
            auth == MediaStore.AUTHORITY -> picked
            auth == "com.android.providers.media.documents" && Build.VERSION.SDK_INT >= 29 -> runCatching { MediaStore.getMediaUri(context, picked) }.getOrNull()
            else -> byNameAndSize(context, picked)
        }
    }

    /** Last resort for other providers: the one library picture with the same file name and size. */
    private fun byNameAndSize(context: Context, picked: Uri): Uri? {
        var name: String? = null; var size = -1L
        runCatching {
            context.contentResolver.query(picked, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0); size = c.getLong(1) }
            }
        }
        val n = name ?: return null
        val sel = MediaStore.Images.Media.DISPLAY_NAME + " = ?" + (if (size > 0) " AND " + MediaStore.Images.Media.SIZE + " = " + size else "")
        return runCatching {
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), sel, arrayOf(n), null)?.use { c ->
                if (c.count == 1 && c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null
            }
        }.getOrNull()
    }

    fun delete(context: Context, picked: Uri): Outcome {
        val uri = mediaUri(context, picked) ?: return Outcome.Failed("not found in the media library")
        val cr = context.contentResolver
        if (Build.VERSION.SDK_INT >= 30) {
            return runCatching<Outcome> { Outcome.Launch(MediaStore.createDeleteRequest(cr, listOf(uri)).intentSender) }
                .getOrElse { Outcome.Failed(it.message ?: "delete request refused") }
        }
        return try {
            if (cr.delete(uri, null, null) > 0) Outcome.Deleted else Outcome.Failed("the gallery kept it")
        } catch (e: SecurityException) {
            val rse = if (Build.VERSION.SDK_INT >= 29) e as? RecoverableSecurityException else null
            if (rse != null) Outcome.Launch(rse.userAction.actionIntent.intentSender) else Outcome.Failed(e.message ?: "not allowed")
        }
    }
}
