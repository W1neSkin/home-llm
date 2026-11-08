package app.lamp;

import android.app.Activity;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.widget.Toast;

import java.util.concurrent.Executor;

/** Системный отпечаток. Кнопка «Код» и неудача оставляют обычный ввод. */
final class FingerGate {
    interface Done {
        void ok();

        void useCode();
    }

    private FingerGate() {
    }

    static boolean available(Activity activity) {
        BiometricManager manager = (BiometricManager) activity.getSystemService(android.content.Context.BIOMETRIC_SERVICE);
        if (manager == null) {
            return false;
        }
        int strong = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        if (strong == BiometricManager.BIOMETRIC_SUCCESS) {
            return true;
        }
        int weak = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
        return weak == BiometricManager.BIOMETRIC_SUCCESS;
    }

    static void start(Activity activity, String name, Done done) {
        Executor executor = activity::runOnUiThread;
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                .setTitle("LocAI")
                .setSubtitle(name)
                .setNegativeButton("Код", executor, (dialog, which) -> done.useCode());
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        }
        builder.build().authenticate(new CancellationSignal(), executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                done.ok();
            }

            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                // Отмена и сбой не должны оставлять человека без полей кода.
                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                    done.useCode();
                }
            }

            @Override
            public void onAuthenticationFailed() {
                Toast.makeText(activity, "Отпечаток не подошёл. Можно ввести код.", Toast.LENGTH_SHORT).show();
                done.useCode();
            }
        });
    }
}
