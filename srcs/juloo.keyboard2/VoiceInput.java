package juloo.keyboard2;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.inputmethod.InputConnection;
import java.util.ArrayList;

/** Built-in voice typing using the system [SpeechRecognizer] (Google's speech
    services on most devices). Text is inserted directly into the editor: partial
    results are shown as composing text and committed when final. Dictation
    continues across pauses until [stop] or [cancel] is called or nothing is
    heard. */
public final class VoiceInput implements RecognitionListener
{
  public interface Callback
  {
    InputConnection getCurrentInputConnection();
    /** [listening] is false when voice input has ended. [level] is in [0, 1]
        and is meaningful only while listening. */
    void voice_status_changed(boolean listening, String status, float level);
  }

  final Context _ctx;
  final Handler _handler;
  final Callback _callback;
  SpeechRecognizer _recognizer = null;
  /** Whether a dictation session is ongoing. */
  boolean _active = false;
  /** Whether the user asked to stop. The current segment is committed but no
      new segment is started. */
  boolean _stopping = false;
  /** Whether at least one segment has been committed in this session. */
  boolean _got_results = false;
  /** Prefix (space) and capitalisation of the current segment, computed when
      the first text of the segment arrives. [null] when not yet computed. */
  String _segment_prefix = null;
  boolean _segment_capitalize = false;
  /** Whether composing text has been set for the current segment. */
  boolean _composing = false;

  public VoiceInput(Context ctx, Handler handler, Callback cb)
  {
    _ctx = ctx;
    _handler = handler;
    _callback = cb;
  }

  public static boolean is_available(Context ctx)
  {
    return SpeechRecognizer.isRecognitionAvailable(ctx);
  }

  public static boolean has_permission(Context ctx)
  {
    if (VERSION.SDK_INT < 23)
      return true;
    return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
      == PackageManager.PERMISSION_GRANTED;
  }

  public boolean is_active() { return _active; }

  /** Start a dictation session. Returns [false] if speech recognition is not
      available on this device. Opens the permission activity if needed. */
  public boolean start()
  {
    if (!is_available(_ctx))
      return false;
    if (!has_permission(_ctx))
    {
      VoicePermissionActivity.launch(_ctx);
      return true;
    }
    if (_active)
      return true;
    if (_recognizer == null)
    {
      _recognizer = SpeechRecognizer.createSpeechRecognizer(_ctx);
      _recognizer.setRecognitionListener(this);
    }
    _active = true;
    _stopping = false;
    _got_results = false;
    start_segment();
    return true;
  }

  /** Stop listening, commit what has been recognized so far. */
  public void stop()
  {
    if (!_active)
      return;
    _stopping = true;
    _recognizer.stopListening();
    status(str(R.string.voice_status_processing), 0.f);
  }

  /** Stop immediately, keeping the partial text that is already shown. */
  public void cancel()
  {
    if (!_active)
      return;
    if (_recognizer != null)
      _recognizer.cancel();
    finish_composing();
    end_session(null);
  }

  /** Toggle listening. Returns [false] if recognition is unavailable. */
  public boolean toggle()
  {
    if (_active)
    {
      stop();
      return true;
    }
    return start();
  }

  public void destroy()
  {
    cancel();
    if (_recognizer != null)
    {
      _recognizer.destroy();
      _recognizer = null;
    }
  }

  void start_segment()
  {
    _segment_prefix = null;
    _composing = false;
    Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
    intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,
        _ctx.getPackageName());
    status(str(R.string.voice_status_starting), 0.f);
    _recognizer.startListening(intent);
  }

  void end_session(String message)
  {
    _active = false;
    _stopping = false;
    _segment_prefix = null;
    _composing = false;
    _callback.voice_status_changed(false, message, 0.f);
  }

  void status(String s, float level)
  {
    _callback.voice_status_changed(true, s, level);
  }

  String str(int id) { return _ctx.getResources().getString(id); }

  /** Decide spacing and capitalisation from the text before the cursor. */
  void compute_segment_format(InputConnection conn)
  {
    CharSequence before = conn.getTextBeforeCursor(3, 0);
    String b = (before == null) ? "" : before.toString();
    if (b.length() == 0)
    {
      _segment_prefix = "";
      _segment_capitalize = true;
      return;
    }
    char last = b.charAt(b.length() - 1);
    _segment_prefix = Character.isWhitespace(last) ? "" : " ";
    String trimmed = b.trim();
    _segment_capitalize = trimmed.length() == 0 || last == '\n'
      || ".!?".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0;
  }

  String format_segment(InputConnection conn, String text)
  {
    if (_segment_prefix == null)
      compute_segment_format(conn);
    if (text.length() == 0)
      return text;
    if (_segment_capitalize)
      text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
    // No space before punctuation.
    if (".,!?;:".indexOf(text.charAt(0)) >= 0)
      return text;
    return _segment_prefix + text;
  }

  static String first_result(Bundle results)
  {
    if (results == null)
      return null;
    ArrayList<String> r =
      results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    if (r == null || r.size() == 0 || r.get(0) == null)
      return null;
    return r.get(0).trim();
  }

  void finish_composing()
  {
    InputConnection conn = _callback.getCurrentInputConnection();
    if (conn != null && _composing)
      conn.finishComposingText();
    _composing = false;
  }

  // RecognitionListener

  @Override
  public void onReadyForSpeech(Bundle params)
  {
    if (_active && !_stopping)
      status(str(R.string.voice_status_listening), 0.f);
  }

  @Override
  public void onBeginningOfSpeech() {}

  @Override
  public void onRmsChanged(float rmsdB)
  {
    if (!_active || _stopping)
      return;
    // rmsdB typically ranges from -2 to 10.
    float level = Math.max(0.f, Math.min(1.f, (rmsdB + 2.f) / 12.f));
    status(str(R.string.voice_status_listening), level);
  }

  @Override
  public void onBufferReceived(byte[] buffer) {}

  @Override
  public void onEndOfSpeech()
  {
    if (_active)
      status(str(R.string.voice_status_processing), 0.f);
  }

  @Override
  public void onPartialResults(Bundle partial)
  {
    if (!_active)
      return;
    String text = first_result(partial);
    InputConnection conn = _callback.getCurrentInputConnection();
    if (text == null || text.length() == 0 || conn == null)
      return;
    conn.setComposingText(format_segment(conn, text), 1);
    _composing = true;
  }

  @Override
  public void onResults(Bundle results)
  {
    if (!_active)
      return;
    String text = first_result(results);
    InputConnection conn = _callback.getCurrentInputConnection();
    if (conn != null)
    {
      if (text != null && text.length() > 0)
      {
        conn.commitText(format_segment(conn, text), 1);
        _got_results = true;
      }
      else if (_composing)
        conn.finishComposingText();
    }
    _composing = false;
    if (_stopping || conn == null)
      end_session(null);
    else
      // Continue dictation after a pause.
      _handler.post(new Runnable() {
        public void run() { if (_active && !_stopping) start_segment(); }
      });
  }

  @Override
  public void onError(int error)
  {
    if (!_active)
      return;
    finish_composing();
    String msg = null;
    switch (error)
    {
      case SpeechRecognizer.ERROR_NO_MATCH:
      case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
        // Silence. Not an error if something was already dictated.
        if (!_got_results && !_stopping)
          msg = str(R.string.voice_error_no_match);
        break;
      case SpeechRecognizer.ERROR_NETWORK:
      case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
        msg = str(R.string.voice_error_network);
        break;
      case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
        VoicePermissionActivity.launch(_ctx);
        break;
      case SpeechRecognizer.ERROR_CLIENT:
        break; // Happens when stopping or cancelling.
      default:
        msg = str(R.string.voice_error_generic) + " (" + error + ")";
        break;
    }
    end_session(msg);
  }

  @Override
  public void onEvent(int eventType, Bundle params) {}
}
