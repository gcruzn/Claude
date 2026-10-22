package cl.gcruz.patrontouch;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Pide a Android el permiso de captura de pantalla («Iniciar grabación») y se lo pasa a
 * CaptureService. Android lo pide de nuevo cada vez: no hay forma de dejarlo concedido.
 */
public class CaptureActivity extends Activity {

    private static final int REQ = 1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ && res == RESULT_OK && data != null) {
            Intent i = new Intent(this, CaptureService.class)
                    .putExtra(CaptureService.EXTRA_CODE, res)
                    .putExtra(CaptureService.EXTRA_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } else {
            Toast.makeText(this, "Sin permiso de captura: la dirección del joystick no se lee", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
