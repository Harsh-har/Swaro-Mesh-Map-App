package no.nordicsemi.android.swarorgbww.node.adapter;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import no.nordicsemi.android.swarorgbww.transport.Element;

public class ElementDiffCallback extends DiffUtil.ItemCallback<Element> {

    @Override
    public boolean areItemsTheSame(@NonNull final Element oldItem, @NonNull final Element newItem) {
        return oldItem.equals(newItem);
    }

    @Override
    public boolean areContentsTheSame(@NonNull final Element oldItem, @NonNull final Element newItem) {
        return oldItem.equals(newItem);
    }


    @Override
    public Boolean getChangePayload(@NonNull final Element oldItem, @NonNull final Element newItem) {
        return (!oldItem.getName().equals(newItem.getName()));
    }
}