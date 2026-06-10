package no.nordicsemi.android.swarorgbww;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class LightSelectorFragment extends Fragment {

    public LightSelectorFragment() {}

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_light_selector, container, false);

        view.findViewById(R.id.cardRgb).setOnClickListener(v -> navigateTo(new NodeControllerFragment()));
        view.findViewById(R.id.cardTunable).setOnClickListener(v -> navigateTo(new TunableWhiteFragment()));
        view.findViewById(R.id.cardDimmer).setOnClickListener(v -> navigateTo(new DimmerFragment()));

        return view;
    }

    private void navigateTo(Fragment fragment) {
        requireActivity().getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(
                        android.R.anim.fade_in,
                        android.R.anim.fade_out,
                        android.R.anim.fade_in,
                        android.R.anim.fade_out)
                .replace(R.id.fragment_network, fragment)
                .addToBackStack("selection")
                .commit();
    }
}
