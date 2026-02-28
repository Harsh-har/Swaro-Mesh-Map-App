package no.nordicsemi.android.swaromesh.provisioners.adapter;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;

import no.nordicsemi.android.swaromesh.AllocatedGroupRange;
import no.nordicsemi.android.swaromesh.AllocatedSceneRange;
import no.nordicsemi.android.swaromesh.AllocatedUnicastRange;
import no.nordicsemi.android.swaromesh.Range;

public class RangeDiffCallback<T extends Range> extends DiffUtil.ItemCallback<T> {

    @Override
    public boolean areItemsTheSame(@NonNull final T oldItem, @NonNull final T newItem) {
        return oldItem.equals(newItem);
        /*if (oldItem instanceof AllocatedUnicastRange) {
            final AllocatedUnicastRange oldRange = ((AllocatedUnicastRange) oldItem);
            final AllocatedUnicastRange newRange = ((AllocatedUnicastRange) newItem);
            return oldRange.getLowAddress() == newRange.getLowAddress() && oldRange.getHighAddress() == newRange.getHighAddress();
        } else if (oldItem instanceof AllocatedGroupRange) {
            final AllocatedGroupRange oldRange = ((AllocatedGroupRange) oldItem);
            final AllocatedGroupRange newRange = ((AllocatedGroupRange) newItem);
            return oldRange.getLowAddress() == newRange.getLowAddress() && oldRange.getHighAddress() == newRange.getHighAddress();
        } else {
            final AllocatedSceneRange oldRange = ((AllocatedSceneRange) oldItem);
            final AllocatedSceneRange newRange = ((AllocatedSceneRange) newItem);
            return oldRange.getFirstScene() == newRange.getFirstScene() && oldRange.getLastScene() == newRange.getLastScene();
        }*/
    }

    @Override
    public boolean areContentsTheSame(@NonNull final T oldItem, @NonNull final T newItem) {
        if (oldItem instanceof AllocatedUnicastRange) {
            final AllocatedUnicastRange oldRange = ((AllocatedUnicastRange) oldItem);
            final AllocatedUnicastRange newRange = ((AllocatedUnicastRange) newItem);
            return oldRange.getLowAddress() == newRange.getLowAddress() && oldRange.getHighAddress() == newRange.getHighAddress();
        } else if (oldItem instanceof AllocatedGroupRange) {
            final AllocatedGroupRange oldRange = ((AllocatedGroupRange) oldItem);
            final AllocatedGroupRange newRange = ((AllocatedGroupRange) newItem);
            return oldRange.getLowAddress() == newRange.getLowAddress() && oldRange.getHighAddress() == newRange.getHighAddress();
        } else {
            final AllocatedSceneRange oldRange = ((AllocatedSceneRange) oldItem);
            final AllocatedSceneRange newRange = ((AllocatedSceneRange) newItem);
            return oldRange.getFirstScene() == newRange.getFirstScene() && oldRange.getLastScene() == newRange.getLastScene();
        }
    }
}