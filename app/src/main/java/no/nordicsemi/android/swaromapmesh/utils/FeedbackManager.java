package no.nordicsemi.android.swaromapmesh.utils;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

@Singleton
public class FeedbackManager {
    private static final String TAG = "FeedbackManager";
    
    private final Context context;
    private TextToSpeech mTextToSpeech;
    private ToneGenerator mToneGenerator;
    private boolean mTtsReady = false;

    @Inject
    public FeedbackManager(@ApplicationContext Context context) {
        this.context = context;
        initFeedback();
    }

    private void initFeedback() {
        mTextToSpeech = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                mTextToSpeech.setLanguage(Locale.US);
                mTtsReady = true;
            }
        });
        try {
            mToneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create ToneGenerator", e);
        }
    }

    public void performLongHaptic() {
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Long continuous vibration (400ms)
                v.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(400);
            }
        }
    }

    /**
     * Triggers a long haptic vibration and a beep sound, without any voice feedback.
     * Used for provisioning and publication success.
     */
    public void performLongHapticWithBeep() {
        // 1. Beep Sound
        if (mToneGenerator != null) {
            try {
                mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100);
            } catch (Exception e) {
                Log.e(TAG, "ToneGenerator error", e);
            }
        }

        // 2. Long Haptic
        performLongHaptic();
    }

    public void performSuccessFeedback(String message) {
        Log.d(TAG, "Feedback: " + message);

        // 1. Tick Sound
        if (mToneGenerator != null) {
            try {
                mToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 100);
            } catch (Exception e) {
                Log.e(TAG, "ToneGenerator error", e);
            }
        }

        // 2. Haptic
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                long[] pattern = {0, 60, 40, 60};
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(200);
            }
        }

        // 3. Voice
        if (mTtsReady && mTextToSpeech != null) {
            mTextToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "SuccessFeedback");
        }
    }
}
