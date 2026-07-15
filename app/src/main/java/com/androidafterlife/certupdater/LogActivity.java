package com.androidafterlife.certupdater;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

public class LogActivity extends Activity implements LogStore.Listener {

    private TextView logTextView;
    private ScrollView logScrollView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_log);
            logTextView = (TextView) findViewById(R.id.logTextView);
            logScrollView = (ScrollView) findViewById(R.id.logScrollView);
            logTextView.setText(LogStore.getAll());
        } catch (Throwable t) {
            TextView fallback = new TextView(this);
            fallback.setText("Could not display log: " + RootUtils.describeThrowable(t));
            setContentView(fallback);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        LogStore.setListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop receiving updates once we're not visible, so we never touch
        // views belonging to a paused/destroyed activity.
        LogStore.setListener(null);
    }

    @Override
    public void onLogChanged(final String fullText) {
        if (logTextView == null) return;
        logTextView.setText(fullText);
        if (logScrollView != null) {
            logScrollView.post(new Runnable() {
                @Override
                public void run() {
                    logScrollView.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }
}
