package no.nordicsemi.android.swaromesh.node.dialog;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;

import no.nordicsemi.android.swaromesh.Group;
import no.nordicsemi.android.swaromesh.transport.Element;
import no.nordicsemi.android.swaromesh.transport.MeshModel;
import no.nordicsemi.android.swaromesh.R;
import no.nordicsemi.android.swaromesh.adapter.GroupModelAdapter;
import no.nordicsemi.android.swaromesh.databinding.FragmentBottomSheetDialogBinding;

public class BottomSheetDetailsDialogFragment extends BottomSheetDialogFragment implements GroupModelAdapter.OnItemClickListener {

    private static final String GROUP = "GROUP";
    private static final String ELEMENTS = "ELEMENTS";

    private FragmentBottomSheetDialogBinding binding;

    private Group mGroup;
    private ArrayList<Element> mElements = new ArrayList<>();
    private GroupModelAdapter mGroupModelsAdapter;

    public interface BottomSheetDetailsListener {
        void onModelItemClicked(@NonNull final Element element, @NonNull final MeshModel model);

        void onGroupNameChanged(@NonNull final Group group);
    }

    public static BottomSheetDetailsDialogFragment getInstance(final Group group, final ArrayList<Element> elements) {
        final BottomSheetDetailsDialogFragment fragment = new BottomSheetDetailsDialogFragment();
        final Bundle args = new Bundle();
        args.putParcelable(GROUP, group);
        args.putParcelableArrayList(ELEMENTS, elements);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.CustomBottomSheetDialog);
        if (getArguments() != null) {
            mGroup = getArguments().getParcelable(GROUP);
            mElements = getArguments().getParcelableArrayList(ELEMENTS);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater, @Nullable final ViewGroup container, @Nullable final Bundle savedInstanceState) {
        binding = FragmentBottomSheetDialogBinding.inflate(getLayoutInflater());

        binding.textInput.setText(mGroup.getName());

        binding.groupItems.setLayoutManager(new LinearLayoutManager(requireContext()));
        mGroupModelsAdapter = new GroupModelAdapter(mGroup, mElements);
        mGroupModelsAdapter.setOnItemClickListener(this);
        binding.groupItems.setAdapter(mGroupModelsAdapter);

        binding.actionApply.setOnClickListener(v -> {
            final String groupName = binding.textInput.getEditableText().toString().trim();
            if (validateInput(groupName)) {
                hideKeyboard();
                binding.textInput.clearFocus();
                mGroup.setName(groupName);
                ((BottomSheetDetailsListener) requireActivity()).onGroupNameChanged(mGroup);
            }
        });

        return binding.getRoot();
    }

    @Override
    public void onModelItemClick(final Element element, final MeshModel model) {
        ((BottomSheetDetailsListener) requireActivity()).onModelItemClicked(element, model);
    }

    public void updateAdapter(final Group group, final ArrayList<Element> elements) {
        if (mGroupModelsAdapter != null) {
            mGroup = group;
            mElements = elements;
            mGroupModelsAdapter.updateAdapter(group, elements);
            mGroupModelsAdapter.notifyDataSetChanged();
        }
    }

    private boolean validateInput(final String groupName) {
        return !TextUtils.isEmpty(groupName);
    }

    private void hideKeyboard() {
        final InputMethodManager imm = (InputMethodManager) requireActivity().getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(binding.textInput.getWindowToken(), 0);
        }
    }
}
