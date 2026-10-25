package org.plasterfamily.gameday;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Phone: "Share → Game Day" from YouTube, Chrome or anywhere sends the link to the TV. */
public class ShareActivity extends Activity {
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent in = getIntent();
        String text = in != null ? in.getStringExtra(Intent.EXTRA_TEXT) : null;
        Matcher m = text == null ? null : URL.matcher(text);
        if (m == null || !m.find()) {
            Toast.makeText(this, "No link to send", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        final String link = m.group();
        Toast.makeText(this, "Sending to the TV…", Toast.LENGTH_SHORT).show();
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String error;
            try {
                TvLink.Result r = TvLink.send(app, "/show?u=" + URLEncoder.encode(link, "UTF-8"));
                error = r.error;
                if (r.body != null) new Prefs(app).setLastStatus(r.body);
            } catch (Exception e) {
                error = "Couldn't send that link";
            }
            final String msg = error == null ? "Playing on the TV" : error;
            runOnUiThread(() -> {
                Toast.makeText(app, msg, Toast.LENGTH_SHORT).show();
                GameDayWidget.refreshAll(app);
                finish();
            });
        }).start();
    }
}
