package no.nordicsemi.android.swaromapmesh.node.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.databinding.ElementItemBinding;
import no.nordicsemi.android.swaromapmesh.models.VendorModel;
import no.nordicsemi.android.swaromapmesh.transport.Element;
import no.nordicsemi.android.swaromapmesh.transport.MeshModel;
import no.nordicsemi.android.swaromapmesh.transport.ProvisionedMeshNode;
import no.nordicsemi.android.swaromapmesh.utils.CompositionDataParser;

public class ElementAdapter extends RecyclerView.Adapter<ElementAdapter.ViewHolder> {

    private final AsyncListDiffer<Element> differ =
            new AsyncListDiffer<>(this, new ElementDiffCallback());

    private OnItemClickListener mOnItemClickListener;
    private ProvisionedMeshNode meshNode;

    // ✅ Generic OnOff SIG Models
    private static final int GENERIC_ONOFF_SERVER = 0x1000;
    private static final int GENERIC_ONOFF_CLIENT = 0x1001;

    /* ---------------------------------------------------------- */

    public void update(@NonNull final ProvisionedMeshNode meshNode) {
        this.meshNode = meshNode;
        differ.submitList(populateList(meshNode));
    }

    private List<Element> populateList(@NonNull final ProvisionedMeshNode meshNode) {
        final List<Element> elements = new ArrayList<>();
        for (Element element : meshNode.getElements().values()) {
            try {
                elements.add(element.clone());
            } catch (CloneNotSupportedException e) {
                e.printStackTrace();
            }
        }
        return elements;
    }

    public void setOnItemClickListener(@NonNull final OnItemClickListener listener) {
        mOnItemClickListener = listener;
    }

    /* ---------------------------------------------------------- */

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(
                ElementItemBinding.inflate(
                        LayoutInflater.from(parent.getContext()),
                        parent,
                        false
                )
        );
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final Element element = differ.getCurrentList().get(position);

        holder.mElementTitle.setText(element.getName());

        // ✅ Count only OnOff Server + Client
        int modelCount = getGenericOnOffCount(element);

        holder.mElementSubtitle.setText(
                holder.mElementSubtitle.getContext()
                        .getString(R.string.model_count, modelCount)
        );

        inflateModelViews(holder, new ArrayList<>(element.getMeshModels().values()));
    }

    /* ---------------------------------------------------------- */

    private int getGenericOnOffCount(@NonNull Element element) {
        int count = 0;
        for (MeshModel model : element.getMeshModels().values()) {
            int id = model.getModelId();
            if (id == GENERIC_ONOFF_SERVER || id == GENERIC_ONOFF_CLIENT) {
                count++;
            }
        }
        return count;
    }

    private boolean isGenericOnOffModel(@NonNull MeshModel model) {
        int id = model.getModelId();
        return id == GENERIC_ONOFF_SERVER || id == GENERIC_ONOFF_CLIENT;
    }

    /* ---------------------------------------------------------- */

    private void inflateModelViews(@NonNull ViewHolder holder,
                                   @NonNull List<MeshModel> models) {

        holder.mModelContainer.removeAllViews();
        final Context context = holder.mModelContainer.getContext();

        for (MeshModel model : models) {

            // ❌ Skip all other models
            if (!isGenericOnOffModel(model)) {
                continue;
            }

            final View modelView = LayoutInflater.from(context)
                    .inflate(R.layout.model_item, holder.mElementContainer, false);

            modelView.setTag(model.getModelId());

            TextView modelNameView = modelView.findViewById(R.id.title);
            TextView modelIdView = modelView.findViewById(R.id.subtitle);

            // ✅ Clean names
            modelNameView.setText(
                    model.getModelId() == GENERIC_ONOFF_SERVER
                            ? "Generic OnOff Server"
                            : "Generic OnOff Client"
            );

            if (model instanceof VendorModel) {
                modelIdView.setText(
                        context.getString(
                                R.string.format_vendor_model_id,
                                CompositionDataParser.formatModelIdentifier(
                                        model.getModelId(), true
                                )
                        )
                );
            } else {
                modelIdView.setText(
                        context.getString(
                                R.string.format_sig_model_id,
                                CompositionDataParser.formatModelIdentifier(
                                        model.getModelId(), true
                                )
                        )
                );
            }

            modelView.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && mOnItemClickListener != null) {
                    Element element = differ.getCurrentList().get(pos);
                    mOnItemClickListener.onModelClicked(meshNode, element, model);
                }
            });

            holder.mModelContainer.addView(modelView);
        }

        // ✅ Hide if no OnOff models exist
        holder.mModelContainer.setVisibility(
                holder.mModelContainer.getChildCount() == 0
                        ? View.GONE
                        : View.VISIBLE
        );
    }

    /* ---------------------------------------------------------- */

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    @Override
    public long getItemId(int position) {
        return differ.getCurrentList().get(position).getElementAddress();
    }

    /* ---------------------------------------------------------- */

    public interface OnItemClickListener {
        void onElementClicked(@NonNull Element element);

        void onModelClicked(@NonNull ProvisionedMeshNode meshNode,
                            @NonNull Element element,
                            @NonNull MeshModel model);
    }

    /* ---------------------------------------------------------- */

    final class ViewHolder extends RecyclerView.ViewHolder
            implements View.OnClickListener {

        ConstraintLayout mElementContainer;
        ImageView mIcon;
        TextView mElementTitle;
        TextView mElementSubtitle;
        ImageButton mElementExpand;
        ImageButton mEdit;
        LinearLayout mModelContainer;

        ViewHolder(@NonNull ElementItemBinding binding) {
            super(binding.getRoot());
            mElementContainer = binding.elementItemContainer;
            mIcon = binding.icon;
            mElementTitle = binding.elementTitle;
            mElementSubtitle = binding.elementSubtitle;
            mElementExpand = binding.elementExpand;
            mEdit = binding.edit;
            mModelContainer = binding.modelContainer;

            mElementExpand.setOnClickListener(this);
            mEdit.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            if (v.getId() == R.id.element_expand) {
                boolean expanded = mModelContainer.getVisibility() == View.VISIBLE;
                mModelContainer.setVisibility(expanded ? View.GONE : View.VISIBLE);
                mElementExpand.setImageResource(
                        expanded
                                ? R.drawable.ic_round_expand_more
                                : R.drawable.ic_round_expand_less
                );
            } else if (v.getId() == R.id.edit) {
                if (mOnItemClickListener != null) {
                    mOnItemClickListener.onElementClicked(
                            differ.getCurrentList().get(getAbsoluteAdapterPosition())
                    );
                }
            }
        }
    }
}
