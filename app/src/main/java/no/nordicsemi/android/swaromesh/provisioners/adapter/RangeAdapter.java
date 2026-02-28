

package no.nordicsemi.android.swaromesh.provisioners.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import no.nordicsemi.android.swaromesh.AddressRange;
import no.nordicsemi.android.swaromesh.AllocatedGroupRange;
import no.nordicsemi.android.swaromesh.AllocatedSceneRange;
import no.nordicsemi.android.swaromesh.AllocatedUnicastRange;
import no.nordicsemi.android.swaromesh.Provisioner;
import no.nordicsemi.android.swaromesh.Range;
import no.nordicsemi.android.swaromesh.utils.MeshAddress;
import no.nordicsemi.android.swaromesh.R;
import no.nordicsemi.android.swaromesh.databinding.RangeItemBinding;
import no.nordicsemi.android.swaromesh.widgets.RangeView;
import no.nordicsemi.android.swaromesh.widgets.RemovableViewHolder;

public class RangeAdapter<T extends Range> extends RecyclerView.Adapter<RangeAdapter<T>.ViewHolder> {

    private final AsyncListDiffer<T> differ = new AsyncListDiffer<>(this, new RangeDiffCallback<>());
    private final List<Provisioner> mProvisioners;
    private final String mUuid;
    private OnItemClickListener mOnItemClickListener;

    public RangeAdapter(@NonNull final String uuid, @NonNull final List<T> ranges, @NonNull final List<Provisioner> provisioners) {
        mUuid = uuid;
        mProvisioners = provisioners;
        differ.submitList(new ArrayList<>(ranges));
    }

    public void setOnItemClickListener(final OnItemClickListener listener) {
        mOnItemClickListener = listener;
    }

    public void updateData(@NonNull List<? extends Range> ranges) {
        final List<? extends Range> a = new ArrayList<>(ranges);
        //noinspection unchecked
        differ.submitList((List<T>) a);
    }



    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        return new ViewHolder(RangeItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
        final Range range = differ.getCurrentList().get(position);
        final String low, high;
        if (range instanceof AddressRange) {
            low = MeshAddress.formatAddress(((AddressRange) range).getLowAddress(), true);
            high = MeshAddress.formatAddress(((AddressRange) range).getHighAddress(), true);
        } else {
            low = MeshAddress.formatAddress(((AllocatedSceneRange) range).getFirstScene(), true);
            high = MeshAddress.formatAddress(((AllocatedSceneRange) range).getLastScene(), true);
        }
        holder.rangeValue.setText(holder.itemView.getContext().getString(R.string.range_adapter_format, low, high));
        holder.rangeView.clearRanges();
        holder.rangeView.addRange(range);
        addOverlappingRanges(range, holder.rangeView);
    }

    @Override
    public long getItemId(final int position) {
        return position;
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    public boolean isEmpty() {
        return getItemCount() == 0;
    }

    public Range getItem(final int position) {
        return differ.getCurrentList().get(position);
    }


    private void addOverlappingRanges(@NonNull final Range range, @NonNull final RangeView rangeView) {
        rangeView.clearOtherRanges();
        for (Provisioner p : mProvisioners) {
            if (!p.getProvisionerUuid().equalsIgnoreCase(mUuid)) {
                if (range instanceof AllocatedUnicastRange) {
                    for (AllocatedUnicastRange otherRange : p.getAllocatedUnicastRanges()) {
                        if (range.overlaps(otherRange)) {
                            rangeView.addOtherRange(otherRange);
                        }
                    }
                } else if (range instanceof AllocatedGroupRange) {
                    for (AllocatedGroupRange otherRange : p.getAllocatedGroupRanges()) {
                        if (range.overlaps(otherRange)) {
                            rangeView.addOtherRange(otherRange);
                        }
                    }
                } else {
                    for (AllocatedSceneRange otherRange : p.getAllocatedSceneRanges()) {
                        if (range.overlaps(otherRange)) {
                            rangeView.addOtherRange(otherRange);
                        }
                    }
                }
            }
        }
    }

    @FunctionalInterface
    public interface OnItemClickListener {
        void onItemClick(final int position, @NonNull final Range range);
    }

    final class ViewHolder extends RemovableViewHolder {
        TextView rangeValue;
        RangeView rangeView;

        private ViewHolder(final @NonNull RangeItemBinding binding) {
            super(binding.getRoot());
            rangeValue = binding.rangeText;
            rangeView = binding.range;
            binding.container.setOnClickListener(v -> {
                if (mOnItemClickListener != null) {
                    mOnItemClickListener.onItemClick(getAbsoluteAdapterPosition(), differ.getCurrentList().get(getAbsoluteAdapterPosition()));
                }
            });
        }
    }
}
