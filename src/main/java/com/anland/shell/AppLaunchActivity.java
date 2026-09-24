package com.anland.shell;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.anland.shell.ds.DsCli;
import com.anland.shell.ds.EnvVars;
import com.anland.shell.ds.RootExec;
import com.anland.shell.ds.ShellUtils;

import java.util.List;

/**
 * Translucent trampoline used by both grid taps and pinned home-screen
 * shortcuts: make sure the container is running (boot + poll if not), then
 * launch the app inside it (DsCli.launchApp — a systemd-run --user unit,
 * returns once the unit is started, survives this process) and finish.
 * noHistory + excludeFromRecents keep it invisible in the flow.
 */
public final class AppLaunchActivity extends Activity {

    private TextView msg;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final String container = getIntent().getStringExtra("container");
        final String exec = getIntent().getStringExtra("exec");
        final String user = getIntent().getStringExtra("user");
        String name = getIntent().getStringExtra("app_name");
        if (container == null || container.isEmpty() || exec == null || exec.isEmpty()) {
            finish();
            return;
        }
        final String appName = name == null || name.isEmpty() ? exec : name;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setKeepScreenOn(true);

        ProgressBar bar = new ProgressBar(this);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        msg = new TextView(this);
        msg.setText(getString(R.string.launching_fmt, appName));
        msg.setTextSize(14);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, dp(16), 0, 0);
        root.addView(msg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        RootExec.POOL.execute(() -> launch(container, appName, exec, user));
    }

    private void launch(String container, String appName, String exec, String user) {
        try {
            if (DsCli.pid(container) <= 0) {
                post(R.string.launch_starting_fmt, container);
                RootExec.Result r = DsCli.start(container);
                if (!r.ok || !DsCli.awaitRunning(container, 90_000)) {
                    fail(getString(r.ok ? R.string.start_timeout_fmt : R.string.start_failed_fmt,
                            container, r.ok ? "" : errText(r)));
                    return;
                }
            }

            List<String> argv = ShellUtils.parseExec(exec);
            if (argv.isEmpty()) {
                fail(getString(R.string.launch_failed_fmt, appName,
                        getString(R.string.launch_empty_exec)));
                return;
            }

            List<String[]> customEnv = EnvVars.parse(Prefs.launchEnv(this, container));
            RootExec.Result r = DsCli.launchApp(container, argv, user, customEnv);
            if (r.ok) {
                runOnUiThread(() -> {
                    Toast.makeText(this, getString(R.string.launch_ok_fmt, appName),
                            Toast.LENGTH_SHORT).show();
                    finish();
                });
            } else {
                fail(getString(R.string.launch_failed_fmt, appName, errText(r)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    private void post(final int res, final Object... args) {
        runOnUiThread(() -> msg.setText(getString(res, args)));
    }

    private void fail(final String text) {
        runOnUiThread(() -> {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private static String errText(RootExec.Result r) {
        if (r.error != null)
            return r.error;
        String e = r.stderr.trim();
        return e.isEmpty() ? "exit " + r.exit : e.split("\n", 2)[0];
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
