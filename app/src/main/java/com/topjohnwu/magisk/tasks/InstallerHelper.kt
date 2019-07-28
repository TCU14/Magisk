package com.topjohnwu.magisk.tasks

import android.content.Context
import android.net.Uri
import com.topjohnwu.magisk.extensions.get
import com.topjohnwu.magisk.extensions.readUri
import java.io.File

object InstallerHelper {
    @JvmStatic
    fun copyFileTo(uri: Uri, zip: File) {
        zip.deleteRecursively()

        get<Context>().readUri(uri).use { input ->
            zip.outputStream().use { out -> input.copyTo(out) }
        }
    }
}