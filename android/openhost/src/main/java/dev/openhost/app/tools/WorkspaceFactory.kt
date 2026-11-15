package dev.openhost.app.tools

import android.content.Context
import android.net.Uri
import dev.openhost.app.data.AppSettings
import java.io.File

object WorkspaceFactory {
    fun create(context: Context, settings: AppSettings): Workspace? {
        val uri = settings.workspaceUri
        return try {
            if (uri != null) SafWorkspace(context, Uri.parse(uri))
            else LocalWorkspace(File(context.filesDir, "workspace"), "app storage")
        } catch (_: Exception) {
            try {
                LocalWorkspace(File(context.filesDir, "workspace"), "app storage")
            } catch (_: Exception) {
                null
            }
        }
    }
}
