package juloo.keyboard2;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Input methods can't request permissions themselves. This transparent
    activity asks for microphone access on behalf of [VoiceInput]. */
public class VoicePermissionActivity extends Activity
{
  static final int REQUEST_CODE = 1;

  public static void launch(Context ctx)
  {
    Intent intent = new Intent(ctx, VoicePermissionActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
        | Intent.FLAG_ACTIVITY_NO_HISTORY);
    ctx.startActivity(intent);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    if (VERSION.SDK_INT < 23 || VoiceInput.has_permission(this))
    {
      finish();
      return;
    }
    requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO },
        REQUEST_CODE);
  }

  @Override
  public void onRequestPermissionsResult(int requestCode,
      String[] permissions, int[] results)
  {
    if (requestCode != REQUEST_CODE)
      return;
    if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
      Toast.makeText(this, R.string.voice_permission_granted,
          Toast.LENGTH_LONG).show();
    else if (VERSION.SDK_INT >= 23
        && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))
    {
      // Permanently denied: the dialog won't show again, open app settings.
      Toast.makeText(this, R.string.voice_permission_denied_settings,
          Toast.LENGTH_LONG).show();
      Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.fromParts("package", getPackageName(), null));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(intent);
    }
    else
      Toast.makeText(this, R.string.voice_permission_denied,
          Toast.LENGTH_LONG).show();
    finish();
  }
}
