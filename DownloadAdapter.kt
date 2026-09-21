package com.example.downloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.downloader.databinding.ItemDownloadBinding

class DownloadAdapter : ListAdapter<DownloadItem, DownloadAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemDownloadBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadItem) {
            binding.fileNameText.text = item.fileName
            binding.statusText.text = statusLabel(item.status)
            binding.progressBar.progress = item.progress
            binding.progressPercentText.text = "${item.progress}%"
        }

        private fun statusLabel(status: String): String = when (status) {
            DownloadItem.STATUS_PENDING -> "در صف انتظار"
            DownloadItem.STATUS_RUNNING -> "در حال دانلود"
            DownloadItem.STATUS_PAUSED -> "متوقف شده"
            DownloadItem.STATUS_DONE -> "کامل شد"
            DownloadItem.STATUS_FAILED -> "خطا"
            else -> status
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem) =
            oldItem == newItem
    }
}
