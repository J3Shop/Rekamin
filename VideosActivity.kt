package com.rekamin.app

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val size: Long
)

class VideosActivity : AppCompatActivity() {

    private lateinit var videoAdapter: VideoAdapter
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_videos)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        emptyText = findViewById(R.id.emptyText)
        videoAdapter = VideoAdapter(
            scope = lifecycleScope,
            onPlay = { play(it) },
            onShare = { share(it) },
            onDelete = { confirmDelete(it) }
        )
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@VideosActivity)
            adapter = videoAdapter
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun load() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { queryVideos() }
            videoAdapter.submit(items)
            emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun queryVideos(): List<VideoItem> {
        val result = ArrayList<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("Movies/Rekamin%")
        val order = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, args, order
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result += VideoItem(
                    id = id,
                    uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    name = c.getString(nameCol) ?: "video",
                    durationMs = c.getLong(durCol),
                    size = c.getLong(sizeCol)
                )
            }
        }
        return result
    }

    private fun play(item: VideoItem) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "-", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(item: VideoItem) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, item.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun confirmDelete(item: VideoItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm)
            .setMessage(item.name)
            .setPositiveButton(R.string.delete) { _, _ ->
                try {
                    contentResolver.delete(item.uri, null, null)
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                }
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

class VideoAdapter(
    private val scope: CoroutineScope,
    private val onPlay: (VideoItem) -> Unit,
    private val onShare: (VideoItem) -> Unit,
    private val onDelete: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.Holder>() {

    private var items: List<VideoItem> = emptyList()

    fun submit(list: List<VideoItem>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val name: TextView = view.findViewById(R.id.nameText)
        val meta: TextView = view.findViewById(R.id.metaText)
        val share: MaterialButton = view.findViewById(R.id.shareBtn)
        val delete: MaterialButton = view.findViewById(R.id.deleteBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val ctx: Context = holder.itemView.context
        holder.name.text = item.name
        val totalSec = item.durationMs / 1000
        holder.meta.text = String.format(
            "%02d:%02d · %s", totalSec / 60, totalSec % 60, Formatter.formatShortFileSize(ctx, item.size)
        )
        holder.itemView.setOnClickListener { onPlay(item) }
        holder.share.setOnClickListener { onShare(item) }
        holder.delete.setOnClickListener { onDelete(item) }

        holder.thumb.setImageDrawable(null)
        holder.thumb.tag = item.id
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.loadThumbnail(item.uri, Size(320, 180), null)
                } catch (e: Exception) {
                    null
                }
            }
            if (bmp != null && holder.thumb.tag == item.id) holder.thumb.setImageBitmap(bmp)
        }
    }
}
