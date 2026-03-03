package no.nordicsemi.android.swaromesh.Map;

import android.graphics.Picture;
import android.graphics.drawable.PictureDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.caverock.androidsvg.SVG;

import java.io.InputStream;

import no.nordicsemi.android.swaromesh.R;

public class SvgLoadActivity extends AppCompatActivity {

    ImageView svgImageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_svg_load);

        svgImageView = findViewById(R.id.svgImageView);

        String uriString = getIntent().getStringExtra("svg_uri");

        if (uriString != null) {
//            loadSVG(Uri.parse(uriString));
        }
    }

//    private void loadSVG(Uri uri) {
//        try {
//            InputStream inputStream = getContentResolver().openInputStream(uri);
//
//            SVG svg = SVG.getFromInputStream(inputStream);
//            Picture picture = svg.renderToPicture();
//            PictureDrawable drawable = new PictureDrawable(picture);
//
//            svgImageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
//            svgImageView.setImageDrawable(drawable);
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            Toast.makeText(this, "Error loading SVG", Toast.LENGTH_SHORT).show();
//        }
//    }
}