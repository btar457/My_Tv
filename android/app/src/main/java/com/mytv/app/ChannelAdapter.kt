package com.mytv.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(
    private val onClick: (position: Int) -> Unit,
    private val onToggleFavorite: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    var items: List<Channel> = emptyList()
        private set
    private var favorites: Set<String> = emptySet()
    private var dead: Set<String> = emptySet()

    fun submit(list: List<Channel>, favorites: Set<String>, dead: Set<String>) {
        items = list
        this.favorites = favorites
        this.dead = dead
        notifyDataSetChanged()
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val group: TextView = v.findViewById(R.id.group)
        val star: ImageButton = v.findViewById(R.id.star)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: Holder, position: Int) {
        val ch = items[position]
        val ctx = h.itemView.context
        h.name.text = ch.name
        val isDead = ch.url in dead
        h.group.text = if (isDead) "${ch.group} • لا يستجيب" else ch.group
        h.group.setTextColor(
            ContextCompat.getColor(ctx, if (isDead) R.color.dead else R.color.text_secondary)
        )
        h.logo.load(ch.logo) {
            crossfade(true)
            placeholder(R.drawable.ic_tv)
            error(R.drawable.ic_tv)
            fallback(R.drawable.ic_tv)
        }
        h.star.setImageResource(if (ch.url in favorites) R.drawable.ic_star else R.drawable.ic_star_border)
        h.star.setOnClickListener { onToggleFavorite(ch) }
        h.itemView.setOnClickListener { h.bindingAdapterPosition.takeIf { it >= 0 }?.let(onClick) }
    }
}
