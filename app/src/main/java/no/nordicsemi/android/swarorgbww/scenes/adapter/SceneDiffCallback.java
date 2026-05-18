package no.nordicsemi.android.swarorgbww.scenes.adapter;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import no.nordicsemi.android.swarorgbww.Scene;

public class SceneDiffCallback extends DiffUtil.ItemCallback<Scene> {

    @Override
    public boolean areItemsTheSame(@NonNull final Scene oldItem, @NonNull final Scene newItem) {
        return oldItem.equals(newItem);
    }

    @Override
    public boolean areContentsTheSame(@NonNull final Scene oldItem, @NonNull final Scene newItem) {
        return oldItem.equals(newItem);
    }
}