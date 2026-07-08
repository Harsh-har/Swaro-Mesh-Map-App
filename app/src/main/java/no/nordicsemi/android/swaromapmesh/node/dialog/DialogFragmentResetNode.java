package no.nordicsemi.android.swaromapmesh.node.dialog;

import android.app.Dialog;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import no.nordicsemi.android.swaromapmesh.R;
import no.nordicsemi.android.swaromapmesh.dialog.DialogFragmentMessage;

public class DialogFragmentResetNode extends DialogFragmentMessage {

    public interface DialogFragmentNodeResetListener {
        void onNodeReset();
    }

    public static DialogFragmentResetNode newInstance(final String title, final String message) {
        Bundle args = new Bundle();
        DialogFragmentResetNode fragment = new DialogFragmentResetNode();
        args.putString(TITLE, title);
        args.putString(MESSAGE, message);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        alertDialogBuilder = new AlertDialog.Builder(requireActivity());
        alertDialogBuilder.setIcon(R.drawable.ic_reset);
        alertDialogBuilder.setNegativeButton(getString(R.string.no), (dialog, which) -> {
            getParentFragmentManager().setFragmentResult("RESET_DIALOG_CLOSED", new Bundle());
        });
        alertDialogBuilder.setPositiveButton(getString(R.string.yes), (dialog, which) -> (
                (DialogFragmentNodeResetListener)requireActivity()).onNodeReset());

        return super.onCreateDialog(savedInstanceState);
    }

    @Override
    public void onCancel(@NonNull android.content.DialogInterface dialog) {
        super.onCancel(dialog);
        getParentFragmentManager().setFragmentResult("RESET_DIALOG_CLOSED", new Bundle());
    }
}
