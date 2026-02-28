package no.nordicsemi.android.swaromesh.node.adapter;

import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import no.nordicsemi.android.swaromesh.R;
import no.nordicsemi.android.swaromesh.databinding.NetworkItemBinding;
import no.nordicsemi.android.swaromesh.transport.Element;
import no.nordicsemi.android.swaromesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromesh.utils.CompanyIdentifiers;
import no.nordicsemi.android.swaromesh.utils.MeshAddress;
import no.nordicsemi.android.swaromesh.utils.MeshParserUtils;
import no.nordicsemi.android.swaromesh.widgets.RemovableViewHolder;

public class NodeAdapter extends RecyclerView.Adapter<NodeAdapter.ViewHolder> {

    private static final String TAG = "NodeAdapter";

    private final AsyncListDiffer<ProvisionedMeshNode> differ =
            new AsyncListDiffer<>(this, new NodeDiffCallback());

    private final Set<Integer> expandedPositions = new HashSet<>();
    private OnItemClickListener mOnItemClickListener;

    // backup list for filtering
    private List<ProvisionedMeshNode> allNodes = new ArrayList<>();

    public NodeAdapter(@NonNull final LifecycleOwner owner,
                       @NonNull final LiveData<List<ProvisionedMeshNode>> provisionedNodesLiveData) {

        provisionedNodesLiveData.observe(owner, nodes -> {
            if (nodes != null) {
                expandedPositions.clear();
                allNodes = new ArrayList<>(nodes);
                differ.submitList(new ArrayList<>(nodes));

                // Debug: Log all MAC addresses
                for (ProvisionedMeshNode node : nodes) {
                    Log.d(TAG, "Node: " + node.getNodeName() + ", MAC: " + node.getMacAddress());
                }
            }
        });
    }

    public void setOnItemClickListener(@NonNull final OnItemClickListener listener) {
        mOnItemClickListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        return new ViewHolder(
                NetworkItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false)
        );
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
        final ProvisionedMeshNode node = differ.getCurrentList().get(position);
        if (node == null) return;

        // NODE NAME
        holder.name.setText(node.getNodeName());

        final boolean expanded = expandedPositions.contains(position);
        holder.name.setMaxLines(expanded ? Integer.MAX_VALUE : 2);
        holder.name.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);

        // UNICAST ADDRESS
        holder.unicastAddress.setText(
                MeshParserUtils.bytesToHex(
                        MeshAddress.addressIntToBytes(node.getUnicastAddress()), false)
        );

        // MAC ADDRESS ⭐⭐ - DEBUG LOGGING
        final String mac = node.getMacAddress();
        Log.d(TAG, "Binding node: " + node.getNodeName() +
                ", MAC from DB: " + mac +
                ", Position: " + position);

        holder.macAddress.setText(!TextUtils.isEmpty(mac) ? mac : "--");

        // NODE INFO
        final Map<Integer, Element> elements = node.getElements();
        if (!elements.isEmpty()) {
            holder.nodeInfoContainer.setVisibility(View.VISIBLE);
            if (node.getCompanyIdentifier() != null) {
                holder.companyIdentifier.setText(
                        CompanyIdentifiers.getCompanyName(node.getCompanyIdentifier().shortValue()));
            } else {
                holder.companyIdentifier.setText(R.string.unknown);
            }
            holder.elements.setText(String.valueOf(elements.size()));
            holder.models.setText(String.valueOf(getModels(elements)));
        } else {
            holder.nodeInfoContainer.setVisibility(View.VISIBLE);
            holder.companyIdentifier.setText(R.string.unknown);
            holder.elements.setText(String.valueOf(node.getNumberOfElements()));
            holder.models.setText(R.string.unknown);
        }

        // CLICK
        holder.container.setOnClickListener(v -> {
            // expand/collapse
            if (expanded) {
                expandedPositions.remove(position);
            } else {
                expandedPositions.add(position);
            }
            notifyItemChanged(position);

            if (mOnItemClickListener != null) {
                mOnItemClickListener.onConfigureClicked(node);
            }
        });
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    public ProvisionedMeshNode getItem(final int position) {
        if (getItemCount() > 0 && position > -1) {
            return differ.getCurrentList().get(position);
        }
        return null;
    }

    public boolean isEmpty() {
        return getItemCount() == 0;
    }

    private int getModels(final Map<Integer, Element> elements) {
        int models = 0;
        for (Element element : elements.values()) {
            models += element.getMeshModels().size();
        }
        return models;
    }

    public void filter(String query) {
        final List<ProvisionedMeshNode> filteredList = new ArrayList<>();

        if (query == null || query.trim().isEmpty()) {
            filteredList.addAll(allNodes);
        } else {
            String lowerCaseQuery = query.toLowerCase().trim();

            for (ProvisionedMeshNode node : allNodes) {

                // no.nordicsemi.android.swaromesh.Device Name
                String nodeName = node.getNodeName() != null
                        ? node.getNodeName().toLowerCase()
                        : "";

                // Unicast Address (convert to hex string like UI shows)
                String addressHex = MeshParserUtils.bytesToHex(
                        MeshAddress.addressIntToBytes(node.getUnicastAddress()),
                        false
                ).toLowerCase();

                if (nodeName.contains(lowerCaseQuery) ||
                        addressHex.contains(lowerCaseQuery)) {

                    filteredList.add(node);
                }
            }
        }

        expandedPositions.clear();
        differ.submitList(filteredList);
    }

    @FunctionalInterface
    public interface OnItemClickListener {
        void onConfigureClicked(final ProvisionedMeshNode node);
    }

    final class ViewHolder extends RemovableViewHolder {

        FrameLayout container;
        TextView name;
        View nodeInfoContainer;
        TextView unicastAddress;
        TextView macAddress;
        TextView companyIdentifier;
        TextView elements;
        TextView models;

        private ViewHolder(@NonNull final NetworkItemBinding binding) {
            super(binding.getRoot());
            container = binding.container;
            name = binding.nodeName;
            nodeInfoContainer = binding.configuredNodeInfoContainer;
            unicastAddress = binding.unicast;
            macAddress = binding.macAddress;
            companyIdentifier = binding.companyIdentifier;
            elements = binding.elements;
            models = binding.models;
        }
    }
}