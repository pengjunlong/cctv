package com.cctv.tvapp.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.cctv.tvapp.R;
import com.cctv.tvapp.model.ChannelItem;

import java.util.List;

/**
 * 频道列表适配器（为 TV 遥控器焦点导航优化）
 */
public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder> {

    public interface OnChannelSelectedListener {
        void onChannelSelected(ChannelItem channel, int position);
    }

    private final List<ChannelItem> channels;
    private final OnChannelSelectedListener listener;
    private int selectedPosition = 0;

    public ChannelAdapter(List<ChannelItem> channels, OnChannelSelectedListener listener) {
        this.channels = channels;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ChannelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel, parent, false);
        return new ChannelViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
        ChannelItem item = channels.get(position);
        holder.tvChannelName.setText(item.getName());

        // 高亮当前选中项
        boolean isSelected = (position == selectedPosition);
        holder.itemView.setSelected(isSelected);
        holder.itemView.setActivated(isSelected);

        // 为每个条目设置焦点变化监听，TV 遥控器方向键会触发焦点变化
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                int adapterPosition = holder.getAdapterPosition();
                if (adapterPosition != RecyclerView.NO_ID) {
                    setSelectedPosition(adapterPosition);
                    if (listener != null) {
                        listener.onChannelSelected(channels.get(adapterPosition), adapterPosition);
                    }
                }
            }
        });

        holder.itemView.setOnClickListener(v -> {
            int adapterPosition = holder.getAdapterPosition();
            if (adapterPosition != RecyclerView.NO_ID) {
                setSelectedPosition(adapterPosition);
                if (listener != null) {
                    listener.onChannelSelected(channels.get(adapterPosition), adapterPosition);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    public void setSelectedPosition(int position) {
        int oldPosition = selectedPosition;
        selectedPosition = position;
        notifyItemChanged(oldPosition);
        notifyItemChanged(selectedPosition);
    }

    public ChannelItem getSelectedChannel() {
        if (selectedPosition >= 0 && selectedPosition < channels.size()) {
            return channels.get(selectedPosition);
        }
        return null;
    }

    static class ChannelViewHolder extends RecyclerView.ViewHolder {
        TextView tvChannelName;

        ChannelViewHolder(@NonNull View itemView) {
            super(itemView);
            tvChannelName = itemView.findViewById(R.id.tv_channel_name);
        }
    }
}
