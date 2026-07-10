package no.nordicsemi.android.swaromapmesh.swajaui;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import no.nordicsemi.android.swaromapmesh.R;

public class DialogFragmentHiddenAccess extends DialogFragment {

    private static final String CORRECT_PASSWORD = "12344321";

    public interface DialogFragmentHiddenAccessListener {
        void onPasswordCorrect();
    }

    private DialogFragmentHiddenAccessListener listener;

    public static DialogFragmentHiddenAccess newInstance() {
        return new DialogFragmentHiddenAccess();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof DialogFragmentHiddenAccessListener) {
            listener = (DialogFragmentHiddenAccessListener) getParentFragment();
        } else if (context instanceof DialogFragmentHiddenAccessListener) {
            listener = (DialogFragmentHiddenAccessListener) context;
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final LayoutInflater inflater = requireActivity().getLayoutInflater();
        final View view = inflater.inflate(R.layout.fragment_dialog_hidden_access, null);

        return new AlertDialog.Builder(requireContext())
                .setTitle("Enter Access Code")
                .setView(view)
                .setPositiveButton("OK", null) // overridden below to block auto-dismiss
                .setNegativeButton("Cancel", null)
                .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        final AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog == null) return;

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final EditText editTextPassword = dialog.findViewById(R.id.password_input);
            final String entered = editTextPassword != null
                    ? editTextPassword.getText().toString().trim()
                    : "";

            if (CORRECT_PASSWORD.equals(entered)) {
                if (listener != null) {
                    listener.onPasswordCorrect();
                }
                dialog.dismiss();
            } else {
                Toast.makeText(requireContext(), "Incorrect password", Toast.LENGTH_SHORT).show();
                if (editTextPassword != null) {
                    editTextPassword.setText("");
                }
            }
        });
    }
}