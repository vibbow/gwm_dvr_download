package net.vsean.gwm_dvr_download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.IOException
import java.io.OutputStream

/** 视频存到 Movies/CarVideo，图片存到 Pictures/CarVideo，其他存到 Download/CarVideo。 */
class MediaStoreSink(context: Context) : FileSink {
    private val resolver = context.contentResolver
    private val pending = HashMap<RemoteFile, Uri>()

    override fun open(file: RemoteFile): OutputStream {
        val name = file.displayName
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val (collection, dir) = when {
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volume) to Environment.DIRECTORY_MOVIES
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volume) to Environment.DIRECTORY_PICTURES
            else -> MediaStore.Downloads.getContentUri(volume) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/CarVideo")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("无法创建文件 $name")
        pending[file] = uri
        return resolver.openOutputStream(uri) ?: run {
            resolver.delete(uri, null, null)
            pending.remove(file)
            throw IOException("无法写入文件 $name")
        }
    }

    override fun commit(file: RemoteFile) {
        val uri = pending.remove(file) ?: return
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
    }

    override fun abort(file: RemoteFile) {
        val uri = pending.remove(file) ?: return
        resolver.delete(uri, null, null)
    }
}
