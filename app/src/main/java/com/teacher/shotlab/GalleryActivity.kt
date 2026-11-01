package com.teacher.shotlab

import android.content.Intent
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.teacher.shotlab.databinding.ActivityGalleryBinding

/** 素材库：读取 Pictures/ShotLab 与 Movies/ShotLab 下的照片与视频，长按可删除 */
class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private var shots: List<Shot> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.grid.setOnItemClickListener { _, _, position, _ ->
            val shot = shots.getOrNull(position) ?: return@setOnItemClickListener
            val mime = if (shot.isVideo) "video/mp4" else "image/jpeg"
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(shot.uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "打开素材"))
            }.onFailure {
                Toast.makeText(this, "没有可打开的应用", Toast.LENGTH_SHORT).show()
            }
        }

        // 长按删除（带确认）
        binding.grid.setOnItemLongClickListener { _, _, position, _ ->
            val shot = shots.getOrNull(position) ?: return@setOnItemLongClickListener true
            val what = if (shot.isVideo) "视频" else "照片"
            AlertDialog.Builder(this)
                .setTitle("删除$what")
                .setMessage("删除后无法恢复，确定删除这条$what 吗？")
                .setPositiveButton("删除") { _, _ ->
                    val ok = runCatching {
                        contentResolver.delete(shot.uri, null, null)
                    }.getOrDefault(0) > 0
                    Toast.makeText(this, if (ok) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
                    if (ok) reload()
                }
                .setNegativeButton("取消", null)
                .show()
            true
        }

        reload()
    }

    private fun reload() {
        shots = ShotStore.query(contentResolver)
        binding.tvCount.text = "${shots.size} 项 · 长按删除"
        binding.tvEmpty.visibility = if (shots.isEmpty()) View.VISIBLE else View.GONE
        binding.grid.visibility = if (shots.isEmpty()) View.GONE else View.VISIBLE
        binding.grid.adapter = ShotAdapter(shots)
    }

    private inner class ShotAdapter(private val items: List<Shot>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_shot, parent, false)
            val shot = items[position]
            val thumb = view.findViewById<ImageView>(R.id.thumb)
            val badge = view.findViewById<TextView>(R.id.badge)
            badge.visibility = if (shot.isVideo) View.VISIBLE else View.GONE
            badge.text = if (shot.isVideo) "视频" else "照片"
            thumb.setImageDrawable(null)
            runCatching {
                val bitmap = contentResolver.loadThumbnail(shot.uri, Size(300, 300), null)
                thumb.setImageBitmap(bitmap)
            }
            return view
        }
    }
}
