package com.anland.shell.ds;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * All droidspaces CLI interaction, run as root through {@link RootExec}.
 *
 * Conventions (Droidspaces-OSS):
 *   show --format → current Droidspaces JSON, or legacy
 *                   CONT_<name>=<pid> KEY=VALUE lines
 *   -n <name> pid → init PID or NONE
 *   --config <path> start → boot the container (boot-module convention)
 *   -n <name> run <arg> → execute inside the container (root, sh -c when the
 *     single argument contains spaces)
 *
 * Every container shell snippet is base64-wrapped into the FIXED shape
 *
 *     droidspaces -n '<name>' run "$(echo <B64> | base64 -d)"
 *
 * The $( ) substitution runs in the root-side Android shell (toybox base64 is
 * always present) and yields one argument, which droidspaces then hands to
 * sh -c inside the container. Snippet content therefore never needs escaping
 * at any layer — quotes, $, newlines are all opaque base64.
 *
 * The anland runtime conventions baked in here:
 *   · container sees the host wayland runtime dir at /run/anland
 *     (bind_mounts=/data/local/tmp/awl:/run/anland, pre-configured)
 *   · when the anland session runs (xwm/anland-session.sh, systemd --user),
 *     it publishes the app environment in ~/.anlandx-env AND as the systemd
 *     user session environment — apps are launched via systemd-run --user,
 *     run as the container's desktop user (auto = first non-root account of
 *     the user list; chromium/electron refuse root) and land in their own
 *     app.slice unit
 *   · apps launch as direct anland clients with XDG_RUNTIME_DIR +
 *     WAYLAND_DISPLAY (from the session env when present, the /run/anland
 *     built-ins otherwise) plus the kgsl Mesa overrides
 *   · X apps: the anland session (Xwayland -rootless + mini-wm) publishes
 *     its display as ":N" in the desktop user's ~/.anlandx while running —
 *     the session probe reads it as DISPLAY, preferring it over the legacy
 *     :0 socket.
 */
public final class DsCli {

    public static final String WORKSPACE = "/data/local/Droidspaces";
    public static final String CONTAINERS = WORKSPACE + "/Containers";

    /** anland xdg dir inside the container (bind-mounted by convention). */
    public static final String XDG_RUNTIME_DIR = "/run/anland";
    public static final String WAYLAND_DISPLAY = "wayland-0";

    /** Built-in launch environment (anland + kgsl GPU conventions); user
     *  customizations are merged over these (see EnvVars.merge). */
    public static List<String[]> defaultEnvPairs() {
        List<String[]> p = new ArrayList<>();
        p.add(new String[]{"XDG_RUNTIME_DIR", XDG_RUNTIME_DIR});
        p.add(new String[]{"WAYLAND_DISPLAY", WAYLAND_DISPLAY});
        p.add(new String[]{"XDG_SESSION_TYPE", "wayland"});
        p.add(new String[]{"MESA_LOADER_DRIVER_OVERRIDE", "kgsl"});
        p.add(new String[]{"GALLIUM_DRIVER", "kgsl"});
        p.add(new String[]{"FD_FORCE_KGSL", "1"});
        return p;
    }

    private static volatile String dsBin;    /* resolved once: "droidspaces" or full path */
    private static volatile String dsError;  /* why resolution failed (null when ok) */

    private DsCli() {}

    // ------------------------------------------------------------------ core

    /** Resolve the droidspaces binary: PATH first, canonical install path as
     *  fallback. Returns null (and records a reason) when neither is usable. */
    public static synchronized String ds() {
        if (dsBin != null)
            return dsBin.isEmpty() ? null : dsBin;
        RootExec.Result r = RootExec.exec(
                "command -v droidspaces 2>/dev/null || printf '%s' " +
                ShellUtils.shQuote(WORKSPACE + "/bin/droidspaces"), 10_000);
        String v = r.stdout == null ? "" : r.stdout.trim();
        if (!r.ok || v.isEmpty()) {
            if (r.error != null)
                dsError = "su: " + r.error;
            else if (r.exit != 0)
                dsError = "su exit " + r.exit;
            else
                dsError = "not found in PATH or " + WORKSPACE + "/bin";
            dsBin = "";
            return null;
        }
        dsBin = v.split("\n", 2)[0].trim();
        return dsBin;
    }

    /** Human-readable reason the CLI is unusable, or null when it is fine. */
    public static String unavailableReason() {
        ds();
        return dsError;
    }

    /** Run a shell snippet inside the container (base64-wrapped, root). */
    public static RootExec.Result runSh(String name, String snippet, long timeoutMs) {
        String bin = ds();
        if (bin == null)
            return new RootExec.Result("", "", -1, "droidspaces binary not found");
        String b64 = Base64.encodeToString(
                snippet.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String cmd = bin + " -n " + ShellUtils.shQuote(name) +
                     " run \"$(echo " + b64 + " | base64 -d)\"";
        return RootExec.exec(cmd, timeoutMs);
    }

    public static RootExec.Result runSh(String name, String snippet) {
        return runSh(name, snippet, 30_000);
    }

    // ------------------------------------------------------------- containers

    /** All known containers: defined dirs + running-only ones, running first. */
    public static List<ContainerState> listContainers() {
        List<ContainerState> out = new ArrayList<>();
        String bin = ds();
        if (bin == null)
            return out;

        /* Running map from current JSON or legacy CONT_<name>=<pid> output. */
        RootExec.Result r = RootExec.exec(bin + " show --format", 15_000);
        List<ContainerState> running = new ArrayList<>();
        if (r.stdout != null && r.stdout.trim().startsWith("{")) {
            try {
                JSONArray entries = new JSONObject(r.stdout).optJSONArray("running");
                if (entries != null) {
                    for (int i = 0; i < entries.length(); i++) {
                        JSONObject entry = entries.optJSONObject(i);
                        if (entry == null)
                            continue;
                        String name = entry.optString("name", "");
                        int pid = entry.optInt("pid", -1);
                        if (name.isEmpty() || name.contains("/") || pid <= 0)
                            continue;
                        ContainerState c = new ContainerState(
                                name, CONTAINERS + "/" + name + "/container.config");
                        c.pid = pid;
                        running.add(c);
                    }
                }
            } catch (JSONException ignored) {
                /* Invalid output is treated as an empty running set. */
            }
        } else if (r.stdout != null) {
            for (String line : r.stdout.split("\n")) {
                line = line.trim();
                if (!line.startsWith("CONT_"))
                    continue;
                String rest = line.substring(5);
                int eq = rest.indexOf('=');
                if (eq <= 0)
                    continue;
                String name = rest.substring(0, eq);
                int pid;
                try {
                    String pidText = rest.substring(eq + 1).trim();
                    int end = 0;
                    while (end < pidText.length() &&
                            !Character.isWhitespace(pidText.charAt(end)))
                        end++;
                    pid = Integer.parseInt(pidText.substring(0, end));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (name.isEmpty() || pid <= 0)
                    continue;
                ContainerState c = new ContainerState(
                        name, CONTAINERS + "/" + name + "/container.config");
                c.pid = pid;
                running.add(c);
            }
        }

        /* defined (may be stopped) */
        List<ContainerState> defined = new ArrayList<>();
        RootExec.Result l = RootExec.exec("ls " + CONTAINERS + " 2>/dev/null", 10_000);
        if (l.stdout != null) {
            for (String line : l.stdout.split("\n")) {
                String name = line.trim();
                if (name.isEmpty() || name.contains(":") || name.contains(" "))
                    continue;
                defined.add(new ContainerState(
                        name, CONTAINERS + "/" + name + "/container.config"));
            }
        }

        /* merge: running first (with their pids from show --format), then
         * stopped defined ones; running containers missing from Containers/
         * are still shown */
        running.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        defined.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        for (ContainerState c : running)
            out.add(c);
        for (ContainerState c : defined) {
            boolean known = false;
            for (ContainerState o : out)
                if (o.name.equals(c.name)) { known = true; break; }
            if (!known)
                out.add(c);
        }
        return out;
    }

    /** Init PID or -1 (not running / unknown). */
    public static int pid(String name) {
        String bin = ds();
        if (bin == null)
            return -1;
        RootExec.Result r = RootExec.exec(
                bin + " -n " + ShellUtils.shQuote(name) + " pid", 10_000);
        return parsePid(r);
    }

    private static int parsePid(RootExec.Result r) {
        if (!r.ok || r.stdout == null)
            return -1;
        String v = r.stdout.trim();
        if (v.isEmpty() || "NONE".equals(v))
            return -1;
        try {
            int pid = Integer.parseInt(v.split("\n", 2)[0].trim());
            return pid > 0 ? pid : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Boot a container from its stored config (boot-module convention). */
    public static RootExec.Result start(String name) {
        String bin = ds();
        if (bin == null)
            return new RootExec.Result("", "", -1, "droidspaces binary not found");
        String cfg = CONTAINERS + "/" + name + "/container.config";
        return RootExec.exec(bin + " --config " + ShellUtils.shQuote(cfg) + " start",
                180_000);
    }

    /** Stop a container. */
    public static RootExec.Result stop(String name) {
        String bin = ds();
        if (bin == null)
            return new RootExec.Result("", "", -1, "droidspaces binary not found");
        return RootExec.exec(bin + " -n " + ShellUtils.shQuote(name) + " stop",
                120_000);
    }

    /** Poll pid() until the container is up or the deadline passes. */
    public static boolean awaitRunning(String name, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (pid(name) > 0)
                return true;
            Thread.sleep(1_000);
        }
        return pid(name) > 0;
    }

    // ------------------------------------------------------------------- apps

    /** Concatenated .desktop dump of every applications dir (system + user). */
    public static RootExec.Result listDesktopDump(String name) {
        return runSh(name,
                "for f in /usr/share/applications/*.desktop " +
                "/usr/local/share/applications/*.desktop " +
                "/root/.local/share/applications/*.desktop " +
                "/home/*/.local/share/applications/*.desktop; do " +
                "[ -f \"$f\" ] || continue; echo \"=== $f\"; cat \"$f\"; echo; done",
                90_000);
    }

    /** Candidate icon files for a themed icon name (name → hicolor/pixmaps paths). */
    public static RootExec.Result findIcons(String name, String icon) {
        return runSh(name,
                "find /usr/share/icons /usr/share/pixmaps " +
                "/root/.local/share/icons /home/*/.local/share/icons -type f " +
                "-name " + ShellUtils.shQuote(icon + ".*") + " 2>/dev/null | head -20",
                20_000);
    }

    /** base64 of an icon file (single line, tr strips base64's line wraps). */
    public static RootExec.Result fetchIconB64(String name, String path) {
        return runSh(name,
                "base64 " + ShellUtils.shQuote(path) + " | tr -d '\\n'",
                20_000);
    }

    // ------------------------------------------------------------ app launch

    /** Desktop session user discovered inside the container (app launch
     *  target — chromium/electron refuse to run as root). */
    public static final class SessionInfo {
        public String uid;
        public String user;
        public String home;
        public String bus;    /* unix:path=/run/user/<uid>/bus, if present */
        public String disp;   /* X display: anlandx ":N" from ~/.anlandx, else :0 */
        public String xa;     /* first /run/user/<uid>/xauth_* file, if any */
        /** anland session environment (KEY=VALUE from ~/.anlandx-env, published
         *  by anland-session: XDG_RUNTIME_DIR, WAYLAND_DISPLAY, DBUS address).
         *  Overlays the built-in launch defaults when present. */
        public final List<String[]> anlandEnv = new ArrayList<>();
    }

    /** Emit UID/USER/HOME/BUS/DISP/XA KEY=VALUE lines for $ent (a passwd
     *  entry); BUS/DISP/XA only when the corresponding sockets exist. The
     *  anland-session environment file is forwarded with an E_ prefix per
     *  line (E_KEY=VALUE → SessionInfo.anlandEnv). */
    private static final String PROBE_EMIT =
            "uid=$(echo \"$ent\" | cut -d: -f3)\n" +
            "home=$(echo \"$ent\" | cut -d: -f6)\n" +
            "echo \"UID=$uid\"\n" +
            "echo \"USER=${ent%%:*}\"\n" +
            "echo \"HOME=$home\"\n" +
            "[ -S \"/run/user/$uid/bus\" ] && echo \"BUS=unix:path=/run/user/$uid/bus\"\n" +
            "[ -S /tmp/.X11-unix/X0 ] && echo \"DISP=:0\"\n" +
            "ax=$(cat \"$home/.anlandx\" 2>/dev/null)\n" +
            "[ -n \"$ax\" ] && echo \"DISP=$ax\"\n" +
            "xa=$(ls /run/user/$uid/xauth_* 2>/dev/null | head -1)\n" +
            "[ -n \"$xa\" ] && echo \"XA=$xa\"\n" +
            "[ -f \"$home/.anlandx-env\" ] && sed \"s/^/E_/\" \"$home/.anlandx-env\"\n";

    /** Probe one explicit user. Marker output distinguishes a missing account
     *  from a broken getent/container command without reserving exit codes. */
    private static String userProbe(String user) {
        return "command -v getent >/dev/null 2>&1 || { " +
               "printf '%s\\n' '__ANLAND_PROBE_ERROR__ getent-not-found; exit 72; }\n" +
               "ent=$(getent passwd " + ShellUtils.shQuote(user) + ")\n" +
               "rc=$?\n" +
               "if [ $rc -ne 0 ] || [ -z \"$ent\" ]; then\n" +
               "  printf '%s\\n' '__ANLAND_USER_MISSING__'\n" +
               "  exit 0\n" +
               PROBE_EMIT;
    }

    /** Parsed session or the exact probe command outcome when no session could
     *  be produced. The latter is returned intact by launchApp for diagnostics. */
    private static final class SessionProbe {
        final SessionInfo session;
        final RootExec.Result command;
        final boolean missing;

        SessionProbe(SessionInfo session, RootExec.Result command, boolean missing) {
            this.session = session;
            this.command = command;
            this.missing = missing;
        }
    }

    private static SessionProbe probeSession(String name, String user) {
        RootExec.Result r = runSh(name, userProbe(user), 15_000);
        if (!r.ok)
            return new SessionProbe(null, r, false);
        if (r.stdout.contains("__ANLAND_PROBE_ERROR__"))
            return new SessionProbe(null, r, false);
        if (r.stdout.contains("__ANLAND_USER_MISSING__"))
            return new SessionProbe(null, r, true);
        SessionInfo s = new SessionInfo();
        for (String line : r.stdout.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0)
                continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (k.startsWith("E_") && k.length() > 2) {
                s.anlandEnv.add(new String[]{k.substring(2), v});
                continue;
            }
            switch (k) {
                case "UID":  s.uid = v;  break;
                case "USER": s.user = v; break;
                case "HOME": s.home = v; break;
                case "BUS":  s.bus = v;  break;
                case "DISP": s.disp = v; break;
                case "XA":   s.xa = v;   break;
                default: break;
            }
        }
        if (s.user == null || s.user.isEmpty() || s.uid == null || s.uid.isEmpty() ||
                !user.equals(s.user))
            return new SessionProbe(null, r, false);
        return new SessionProbe(s, r, false);
    }

    /**
     * Launch a GUI app through the selected desktop user's systemd manager.
     * The manager already owns the Anland DISPLAY/Wayland/D-Bus environment;
     * this entry point never starts GUI processes as UID 0 and never falls
     * back to a detached root process when the user manager is unavailable.
     */
    public static RootExec.Result launchApp(String name, List<String> execArgs) {
        return launchApp(name, execArgs, "");
    }

    /**
     * @param userOverride "" = auto (first non-root account of the user
     *        list), "root" = reject GUI launch, anything else = that account
     *        (error when it doesn't exist)
     */
    public static RootExec.Result launchApp(String name, List<String> execArgs,
                                            String userOverride) {
        return launchApp(name, execArgs, userOverride, null);
    }
    static String launchUserError(String userOverride, String autoUser) {
        String user = userOverride == null ? "" : userOverride;
        if ("root".equals(user))
            return "root cannot launch desktop GUI; choose a non-root user";
        if (user.isEmpty() && (autoUser == null || autoUser.isEmpty()))
            return "no non-root desktop user found in container";
        return null;
    }


    /**
     * @param customEnv KEY=VALUE pairs merged over the built-in launch
     *        environment — same name wins, empty value removes the built-in
     *        (EnvVars.merge); null = built-ins only
     */
    public static RootExec.Result launchApp(String name, List<String> execArgs,
                                            String userOverride,
                                            List<String[]> customEnv) {
        if (execArgs == null || execArgs.isEmpty())
            return new RootExec.Result("", "", -1, "empty app command");

        String user = userOverride == null ? "" : userOverride;
        String auto = user.isEmpty() ? autoUser(name) : "";
        String userError = launchUserError(user, auto);
        if (userError != null)
            return new RootExec.Result("", "", -1, userError);
        if (user.isEmpty())
            user = auto;

        SessionProbe probe = probeSession(name, user);
        if (probe.session == null) {
            if (probe.missing)
                return new RootExec.Result(probe.command.stdout, probe.command.stderr,
                        probe.command.exit, "user " + user + " not found in container");
            return new RootExec.Result(probe.command.stdout, probe.command.stderr,
                    probe.command.exit, probeFailure(user, probe.command));
        }
        SessionInfo s = probe.session;
        String inner = systemdLaunchCommand(s, execArgs, customEnv);
        String b64 = Base64.encodeToString(
                inner.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return runSh(name,
                "su - " + ShellUtils.shQuote(s.user) +
                " -c \"$(echo " + b64 + " | base64 -d)\"", 20_000);
    }

    private static String probeFailure(String user, RootExec.Result r) {
        if (r.error != null)
            return "failed to probe user " + user + " in container: " + r.error;
        String detail = r.stderr.trim();
        if (detail.isEmpty())
            detail = r.stdout.trim();
        if (detail.isEmpty())
            detail = "no probe output";
        int newline = detail.indexOf('\n');
        if (newline >= 0)
            detail = detail.substring(0, newline);
        return "failed to probe user " + user + " in container (exit " +
                r.exit + "): " + detail;
    }

    static String systemdLaunchCommand(SessionInfo s, List<String> execArgs,
                                       List<String[]> customEnv) {
        /* env: built-ins < anland-session env (~/.anlandx-env) < user custom;
         * the probed bus/display/xauth assert themselves last */
        List<String[]> env = EnvVars.merge(defaultEnvPairs(), s.anlandEnv);
        env = EnvVars.merge(env, customEnv);
        StringBuilder envPfx = new StringBuilder(EnvVars.envPrefix(env));
        if (s.bus != null)
            envPfx.append(" DBUS_SESSION_BUS_ADDRESS=").append(ShellUtils.shQuote(s.bus));
        if (s.disp != null)
            envPfx.append(" DISPLAY=").append(ShellUtils.shQuote(s.disp));
        if (s.xa != null)
            envPfx.append(" XAUTHORITY=").append(ShellUtils.shQuote(s.xa));
        StringBuilder cmd = new StringBuilder();
        for (String arg : execArgs)
            cmd.append(' ').append(ShellUtils.shQuote(arg));

        /* The login shell establishes HOME/USER/PATH; explicit runtime/bus
         * exports let systemd-run find this user's manager even without
         * pam_systemd. A down manager must fail loudly. */
        StringBuilder inner = new StringBuilder("cd ~ 2>/dev/null\n");
        inner.append("export XDG_RUNTIME_DIR=")
             .append(ShellUtils.shQuote("/run/user/" + s.uid)).append('\n');
        if (s.bus != null)
            inner.append("export DBUS_SESSION_BUS_ADDRESS=")
                 .append(ShellUtils.shQuote(s.bus)).append('\n');
        inner.append("exec systemd-run --user --quiet --collect ")
             .append("--property=ExitType=cgroup --unit=")
             .append(ShellUtils.shQuote(unitName(execArgs)))
             .append(" -- ").append(envPfx).append(cmd);
        return inner.toString();
    }

    /** Process-wide sequence prevents same-millisecond transient-unit clashes. */
    private static final AtomicLong UNIT_SEQUENCE = new AtomicLong();
    /** A unique, valid transient unit name for a session launch:
     *  app-&lt;exe&gt;-&lt;millis&gt;-&lt;sequence&gt; (service under app.slice). */
    static String unitName(List<String> execArgs) {
        String base = "app";
        if (execArgs != null && !execArgs.isEmpty()) {
            String a = execArgs.get(0);
            int slash = a.lastIndexOf('/');
            if (slash >= 0)
                a = a.substring(slash + 1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length() && sb.length() < 24; i++) {
                char c = a.charAt(i);
                sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.'
                        ? c : '-');
            }
            if (sb.length() > 0)
                base = sb.toString();
        }
        return "app-" + base + "-" + System.currentTimeMillis() +
                "-" + UNIT_SEQUENCE.incrementAndGet();
    }

    /** root + regular accounts with a real shell, ordered by uid (root first). */
    public static List<String> listUsers(String name) {
        RootExec.Result r = runSh(name,
                "getent passwd | awk -F: " +
                "'($3==0 || ($3>=1000 && $3<60000)) && $7 !~ /(nologin|false)$/ " +
                "{print $3\" \"$1}'", 15_000);
        List<String[]> byUid = new ArrayList<>();
        if (r.ok && r.stdout != null) {
            for (String line : r.stdout.split("\n")) {
                line = line.trim();
                int sp = line.indexOf(' ');
                if (sp <= 0)
                    continue;
                try {
                    byUid.add(new String[]{
                            String.valueOf(Integer.parseInt(line.substring(0, sp))),
                            line.substring(sp + 1)});
                } catch (NumberFormatException ignored) {
                }
            }
        }
        byUid.sort((a, b) -> Integer.parseInt(a[0]) - Integer.parseInt(b[0]));
        List<String> out = new ArrayList<>();
        for (String[] e : byUid)
            out.add(e[1]);
        return out;
    }

    /** The "auto" launch user: the first non-root account of {@link #listUsers}
     *  (regular accounts with a real shell, uid order). "" when there is
     *  none — callers fall back to root. */
    public static String autoUser(String name) {
        for (String u : listUsers(name))
            if (!"root".equals(u))
                return u;
        return "";
    }

    /** Whether the Anland session (rootless Xwayland + mini-wm) is available
     *  for the launch user. The native package installs it in /usr/bin;
     *  older source-tarball installs used ~/.local/bin/anland-session (or
     *  the legacy anlandx-start). user "" = auto ({@link #autoUser}). */
    public static boolean anlandxInstalled(String name, String user) {
        if (user == null || user.isEmpty())
            user = autoUser(name);
        if (user.isEmpty())
            return false;
        RootExec.Result r = runSh(name,
                "home=$(getent passwd " + ShellUtils.shQuote(user) +
                " | cut -d: -f6)\n" +
                "[ -x /usr/bin/anland-session ] || { [ -n \"$home\" ] && " +
                "{ [ -x \"$home/.local/bin/anland-session\" ] || " +
                "[ -x \"$home/.local/bin/anlandx-start\" ]; }; }",
                15_000);
        return r.ok;
    }

    // ---------------------------------------------------------------- console

    /**
     * Persistent in-container console session, as a direct ProcessBuilder
     * argv. Runs as the selected launch user ("" / "root" = root; "auto" is
     * resolved by the caller via probeSession) — the same user app launches
     * use, so the session's ~/.anlandx is that user's anlandx display and X
     * access is authorized (Xwayland only admits the user who started it).
     *
     * DS_NO_PROXY=1 is the key: the daemon-proxied `run` path does NOT
     * forward stdin (only the PTY protocol does, and `enter` hard-fails
     * without a tty). With proxying disabled, run_in_rootfs fork/execs with
     * inherited stdio, so plain pipes give a fully interactive session whose
     * cd/env state persists across lines.
     */
    public static String[] consoleArgv(String name, String user) {
        String bin = ds() == null ? "droidspaces" : ds();
        /* the su chain is quoted whole so `run` gets ONE argument and hands
         * it to sh -c inside the container */
        String inner = user == null || user.isEmpty() || "root".equals(user)
                ? "sh"
                : "\"su - " + ShellUtils.shQuote(user) + " -c sh\"";
        return new String[]{
                "su", "-c",
                "DS_NO_PROXY=1 " + bin + " -n " + ShellUtils.shQuote(name)
                        + " run " + inner
        };
    }

    /** Preamble written to a fresh console session: home dir + the launch
     *  environment (built-ins overlaid with customEnv) as exports, then the
     *  anland-session environment (~/.anlandx-env) and the X display from
     *  ~/.anlandx when the session runs. */
    public static String consolePreamble(List<String[]> customEnv) {
        return "cd ~; " + EnvVars.exportLine(
                EnvVars.merge(defaultEnvPairs(), customEnv))
                + "; if [ -f ~/.anlandx-env ]; then"
                + " while IFS= read -r l; do"
                + " case \"$l\" in ''|'#'*) ;; *) export \"$l\";; esac;"
                + " done < ~/.anlandx-env; fi"
                + "; ax=$(cat ~/.anlandx 2>/dev/null); "
                + "[ -n \"$ax\" ] && export DISPLAY=\"$ax\"";
    }
}
