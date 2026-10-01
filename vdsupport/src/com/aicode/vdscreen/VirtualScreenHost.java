package com.aicode.vdscreen;

import android.content.Context;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 虚拟屏宿主：由 Shizuku 以 shell(uid 2000) 或 root(uid 0) 身份经 app_process 拉起，
 * 负责创建并保活一块真·独立虚拟显示器（type=VIRTUAL，非 overlay 浮窗）。
 *
 * <p>为什么不是 Shizuku UserService：UserService 进程不是合法的 Android 应用进程，
 * 拿不到可用 Context，而 createVirtualDisplay 需要 Context 且其包名必须与调用 uid 匹配。
 * 故此处自行经 ActivityThread.systemMain() 取 systemContext，再按 uid 换成匹配的包 context。
 *
 * <p>运行方式（Shizuku 侧，daemon 模式）：
 * <pre>
 *   CLASSPATH=/data/data/&lt;pkg&gt;/files/vdsupport/host.dex \
 *   app_process -Dpkg=&lt;callerPkg&gt; /system/bin --nice-name=vdshost \
 *       com.aicode.vdscreen.VirtualScreenHost --server 19600
 * </pre>
 *
 * <p>控制经 socket 逐行指令：
 * <pre>
 *   OPEN &lt;width&gt; &lt;height&gt; &lt;dpi&gt; &lt;flags&gt;   创建并回 VDS_OPENED &lt;displayId&gt;
 *   LAUNCH &lt;displayId&gt; &lt;packageName&gt;     投屏并回 VDS_LAUNCHED
 *   CLOSE &lt;displayId&gt;                      先 force-stop 再释放，回 VDS_CLOSED
 *   LIST                                   回 VDS_LIST &lt;id...&gt;（当前存活屏）
 *   PING                                   回 VDS_PONG（探活，不产生副作用）
 *   EXIT                                   释放全部屏并退出
 * </pre>
 *
 * <p><b>为何用 socket 而非 stdin</b>：Shizuku 的 {@code runCommand} 是一次性调用，
 * 无法向长驻进程持续喂 stdin。改用 socket 后，每次操作只需
 * {@code printf 'OPEN ...\n' | toybox nc 127.0.0.1 &lt;port&gt;}——
 * host 与 client 同为 shell(uid 2000)，避开跨 uid 的 SELinux 限制。
 *
 * <p><b>为何要 {@code --server}</b>：daemon 以 detached 方式（setsid/nohup）启动时 stdin 立即 EOF，
 * 若只靠 stdin 里的 {@code SERVER} 指令，主循环会立刻结束、进程随即退出。
 * 故支持命令行直接进服务模式。
 */
public class VirtualScreenHost {

    /**
     * 协议版本。PING 会回传它，供 App 判定「已在跑的 daemon 是不是当前这版」。
     *
     * <p>必要性：daemon 跨 App 重启存活，若 App 升级后 dex 变了而旧 daemon 仍在，
     * 只凭 PING 通就复用会继续跑旧代码。带上版本号即可识别并重建。
     *
     * <p><b>修改任何指令语义/响应格式时必须递增。</b>但注意：App 侧已改用 dex 内容
     * 指纹判定（见 VirtualScreenHostManager 的 deployDex），即使忘了递增也不会复用旧 daemon；
     * 此常量现仅作协议语义变更的显式标记。
     */
    private static final int PROTOCOL = 2;

    private static final int FLAG_PUBLIC = 0x1;
    private static final int FLAG_OWN_CONTENT_ONLY = 0x8;
    private static final int FLAG_DESTROY_CONTENT_ON_REMOVAL = 0x100;

    /**
     * `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP`（隐藏常量，1&lt;&lt;11）。
     * `ALWAYS_UNLOCKED` 只在「不属于默认 display group」的虚拟屏上生效，故必须一起传。
     */
    private static final int FLAG_OWN_DISPLAY_GROUP = 0x800;

    /**
     * `VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED`（隐藏常量，1&lt;&lt;12）。
     *
     * <p>**不加它的后果（实测）**：公共虚拟屏在设备锁屏时会被锁屏盖住——
     * 投进去的 App 虽在运行、任务也在该屏上，但窗口被 {@code KEYGUARD_DIALOG} 压住、
     * {@code visibleRequested=false}，无障碍读到的只有锁屏的 8 个节点（miui_keyguard），
     * 根本进不了 App 界面。而「用户锁屏时 AI 干活」正是本功能最有价值的场景。
     *
     * <p>需要 {@code ADD_ALWAYS_UNLOCKED_DISPLAY} 权限（系统权限，但 com.android.shell
     * 实测持有，故 uid 2000 可用）；缺失时会抛 SecurityException。
     */
    private static final int FLAG_ALWAYS_UNLOCKED = 0x1000;

    /**
     * 默认 flags：独立内容 + 移除即销毁（避免污染主屏 Recent）+ 自成 display group 且始终解锁。
     *
     * <p>实测结论：前三个的组合（0x109）在锁屏下不可用；加上后两个
     * （{@code 0x1|0x8|0x100|0x800|0x1000 = 6409}）后，锁屏状态下投 App 可正常显示与操作
     * （读到 110 个真实节点，click/swipe 均生效）。
     */
    private static final int DEFAULT_FLAGS =
            FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY | FLAG_DESTROY_CONTENT_ON_REMOVAL
                    | FLAG_OWN_DISPLAY_GROUP | FLAG_ALWAYS_UNLOCKED;

    private static Context systemContext;
    private static String callerPackage;

    /**
     * 已创建的虚拟屏，按 displayId 索引。
     *
     * <p>必须持有 {@link VirtualDisplay} 引用：实测仅靠进程退出**不会**销毁虚拟屏——
     * 进程已消失而屏仍以 {@code state=OFF} 残留在系统中（孤儿屏），故必须显式 {@code release()}。
     */
    private static final java.util.Map<Integer, VirtualDisplay> DISPLAYS =
            new java.util.HashMap<Integer, VirtualDisplay>();

    /**
     * 每块屏投过的包名，与 [DISPLAYS] 同生命周期。
     *
     * <p><b>为什么自己记账而不查询系统</b>：曾经用 `dumpsys activity activities | grep -A4
     * 'Display #<id> '` 反查，再从不含空格的 " u0 <pkg>/<activity>" 里截包名。该写法会
     * **越过 Display 段边界**——空屏段落自身没有匹配行时，`grep -A4` 继续吃后面的全局记录（如
     * `ResumedActivity: ...`），于是把**主屏前台应用**当成该屏的目标应用。实测后果：关闭一个
     * 空虚拟屏会 `am force-stop` 掉用户正在用的应用。包名在 [launch] 时本就已知，直接记下来
     * 既准确又不依赖 dumpsys 文本格式。
     */
    private static final java.util.Map<Integer, String> PACKAGES =
            new java.util.HashMap<Integer, String>();

    public static void main(String[] args) throws Exception {
        Looper.prepare();
        callerPackage = System.getProperty("pkg", "com.android.shell");

        // ActivityThread.systemMain() 必须在 Looper.prepare() 之后调用
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Method systemMain = atClass.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object at = systemMain.invoke(null);
        Method getSystemContext = atClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        systemContext = (Context) getSystemContext.invoke(at);

        println("VDS_READY uid=" + android.os.Process.myUid() + " pkg=" + callerPackage);

        // --server <port>：命令行直接进服务模式。detached 启动时 stdin 立即 EOF，
        // 只靠 stdin 的 SERVER 指令会导致主循环立刻结束、进程退出（见类注释）。
        int serverPort = 0;
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--server".equals(args[i])) serverPort = Integer.parseInt(args[i + 1]);
        }
        if (serverPort > 0) {
            serveSocket(serverPort);
            releaseAll();
            println("VDS_BYE");
            System.exit(0);
            return;
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                if (line.startsWith("EXIT")) break;
                if (line.startsWith("SERVER")) {
                    String[] sp = line.split("\\s+");
                    serveSocket(sp.length > 1 ? Integer.parseInt(sp[1]) : DEFAULT_PORT);
                    continue;
                }
                dispatch(line);
            } catch (Throwable t) {
                println("VDS_ERR " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        releaseAll();
        println("VDS_BYE");
        // 显式退出：非守护的 HandlerThread/Looper 会阻止 JVM 自然结束，
        // 仅靠 main 返回会导致进程残留（实测）。
        System.exit(0);
    }

    /** 默认监听端口（仅绑定 127.0.0.1，不对外暴露）。 */
    private static final int DEFAULT_PORT = 19600;

    /**
     * 监听本机端口，逐连接处理指令。单线程串行：虚拟屏操作本身必须有序，
     * 并发只会引入竞态（如同 displayId 被重复 OPEN/CLOSE）。
     */
    private static void serveSocket(int port) {
        java.net.ServerSocket server = null;
        try {
            server = new java.net.ServerSocket(port, 4, java.net.InetAddress.getByName("127.0.0.1"));
            println("VDS_LISTENING " + port);
            while (true) {
                java.net.Socket socket = server.accept();
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    java.io.Writer writer = new java.io.OutputStreamWriter(
                            socket.getOutputStream(), "UTF-8");
                    String cmd;
                    while ((cmd = reader.readLine()) != null) {
                        cmd = cmd.trim();
                        if (cmd.isEmpty()) continue;
                        String response;
                        try {
                            if (cmd.startsWith("EXIT")) {
                                writer.write("VDS_BYE\n");
                                writer.flush();
                                socket.close();
                                releaseAll();
                                System.exit(0);
                            }
                            response = dispatchForSocket(cmd);
                        } catch (Throwable t) {
                            response = "VDS_ERR " + t.getClass().getSimpleName() + ": " + t.getMessage();
                        }
                        if (response != null) {
                            writer.write(response + "\n");
                            writer.flush();
                        }
                    }
                } catch (Throwable t) {
                    println("VDS_WARN 连接处理失败: " + t);
                } finally {
                    try {
                        socket.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            println("VDS_ERR 监听 " + port + " 失败: " + t);
        }
    }

    /** socket 版 dispatch：返回结果文本而不直接打印。 */
    private static String dispatchForSocket(String line) throws Exception {
        String[] p = line.split("\\s+");
        switch (p[0]) {
            case "OPEN": {
                int w = p.length > 1 ? Integer.parseInt(p[1]) : 1080;
                int h = p.length > 2 ? Integer.parseInt(p[2]) : 2400;
                int dpi = p.length > 3 ? Integer.parseInt(p[3]) : 440;
                int flags = p.length > 4 ? Integer.parseInt(p[4]) : DEFAULT_FLAGS;
                return "VDS_OPENED " + open(w, h, dpi, flags);
            }
            case "LAUNCH":
                launch(Integer.parseInt(p[1]), p[2]);
                return "VDS_LAUNCHED";
            case "CLOSE":
                close(Integer.parseInt(p[1]));
                return "VDS_CLOSED";
            case "LIST": {
                StringBuilder sb = new StringBuilder("VDS_LIST");
                for (Integer id : DISPLAYS.keySet()) sb.append(' ').append(id);
                return sb.toString();
            }
            case "PING":
                return "VDS_PONG " + PROTOCOL;
            default:
                return "VDS_ERR unknown command: " + p[0];
        }
    }

    private static void dispatch(String line) throws Exception {
        String[] p = line.split("\\s+");
        switch (p[0]) {
            case "OPEN": {
                int w = p.length > 1 ? Integer.parseInt(p[1]) : 1080;
                int h = p.length > 2 ? Integer.parseInt(p[2]) : 2400;
                int dpi = p.length > 3 ? Integer.parseInt(p[3]) : 440;
                int flags = p.length > 4 ? Integer.parseInt(p[4]) : DEFAULT_FLAGS;
                println("VDS_OPENED " + open(w, h, dpi, flags));
                break;
            }
            case "LAUNCH": {
                launch(Integer.parseInt(p[1]), p[2]);
                println("VDS_LAUNCHED");
                break;
            }
            case "CLOSE": {
                close(Integer.parseInt(p[1]));
                println("VDS_CLOSED");
                break;
            }
            default:
                println("VDS_ERR unknown command: " + p[0]);
        }
    }

    /**
     * 创建虚拟屏。{@code ImageReader} 提供 BufferQueue 并消费帧——不给 Surface 时
     * 屏会停留在 {@code state=OFF}，Activity 不会真正落进去。
     */
    private static int open(int width, int height, int dpi, int flags) throws Exception {
        HandlerThread ht = new HandlerThread("vds");
        ht.start();
        Handler handler = new Handler(ht.getLooper());

        final ImageReader reader = ImageReader.newInstance(width, height, 1 /* RGBA_8888 */, 3);
        reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                try {
                    Image image = r.acquireLatestImage();
                    if (image != null) image.close();
                } catch (Throwable ignored) {
                }
            }
        }, handler);
        Surface surface = reader.getSurface();

        Class<?> builderClass =
                Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
        Constructor<?> ctor = builderClass.getDeclaredConstructor(
                String.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        Object builder = ctor.newInstance("aicode-vd", width, height, dpi);
        builderClass.getMethod("setFlags", int.class).invoke(builder, flags);
        builderClass.getMethod("setSurface", Surface.class).invoke(builder, surface);
        builderClass.getMethod("setDefaultBrightness", float.class).invoke(builder, 1.0f);
        Object config = builderClass.getMethod("build").invoke(builder);

        Context ctx = contextForCaller();

        Class<?> dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Method getInstance = dmgClass.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Object dmg = getInstance.invoke(null);
        Method create = null;
        for (Method m : dmgClass.getDeclaredMethods()) {
            if (m.getName().equals("createVirtualDisplay") && m.getParameterCount() == 5) {
                create = m;
            }
        }
        if (create == null) throw new IllegalStateException("createVirtualDisplay 未找到");
        create.setAccessible(true);
        VirtualDisplay vd = (VirtualDisplay) create.invoke(dmg, ctx, null, config, null, null);
        int displayId = vd.getDisplay().getDisplayId();
        DISPLAYS.put(displayId, vd);
        return displayId;
    }

    /**
     * 取与调用 uid 匹配的 Context。
     *
     * <p>实测：直接使用 systemContext（包名 {@code android}）在非 root 下会抛
     * {@code SecurityException: packageName must match the calling uid}。
     */
    private static Context contextForCaller() {
        if (android.os.Process.myUid() == 0) return systemContext;
        try {
            return systemContext.createPackageContext(callerPackage, 0);
        } catch (Throwable t) {
            println("VDS_WARN 取 " + callerPackage + " context 失败，回退 systemContext: " + t);
            return systemContext;
        }
    }

    /** 解析 launcher activity 并投入指定显示器；随后唤醒，规避初始 isSleeping。 */
    private static void launch(int displayId, String packageName) throws Exception {
        String activity = resolveLauncherActivity(packageName);
        shell("am start --display " + displayId + " -n " + activity);
        PACKAGES.put(displayId, packageName);
        shell("input -d " + displayId + " keyevent 224");
    }

    private static String resolveLauncherActivity(String packageName) throws Exception {
        String out = shell("cmd package resolve-activity --brief " + packageName);
        String[] lines = out.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i].trim();
            if (s.contains("/") && !s.contains(" ")) return s;
        }
        throw new IllegalStateException("无法解析 " + packageName + " 的 launcher activity");
    }

    /**
     * 释放虚拟屏。
     *
     * <p><b>顺序不可颠倒</b>：先 force-stop 目标应用，再 release 显示器。
     * DESTROY_CONTENT_ON_REMOVAL 只销毁窗口内容、**不删 Activity 任务记录**，
     * 漏做 force-stop 会把任务搬到主屏、污染 Recent（实测残留 6 条）。
     *
     * <p>最后必须 {@code release()}：仅靠进程退出不会销毁虚拟屏（实测残留孤儿屏）。
     */
    private static void close(int displayId) throws Exception {
        String pkg = PACKAGES.get(displayId);
        if (pkg != null && !pkg.isEmpty()) {
            shell("am force-stop " + pkg);
            releaseDisplay(displayId);
        } else {
            releaseDisplay(displayId);
        }
    }

    private static void releaseDisplay(int displayId) {
        VirtualDisplay vd = DISPLAYS.remove(displayId);
        PACKAGES.remove(displayId);
        if (vd == null) {
            println("VDS_WARN 未知 displayId=" + displayId + "，无引用可释放");
            return;
        }
        try {
            vd.release();
        } catch (Throwable t) {
            println("VDS_WARN release(" + displayId + ") 失败: " + t);
        }
    }

    /** 退出前释放全部虚拟屏，避免留下孤儿屏。 */
    private static void releaseAll() {
        for (Integer id : new java.util.ArrayList<Integer>(DISPLAYS.keySet())) {
            String pkg = PACKAGES.get(id);
            if (pkg != null && !pkg.isEmpty()) {
                try {
                    shell("am force-stop " + pkg);
                } catch (Throwable ignored) {
                }
            }
            releaseDisplay(id);
        }
    }

    private static String shell(String command) throws Exception {
        Process process = new ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true).start();
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String l;
        while ((l = r.readLine()) != null) sb.append(l).append('\n');
        process.waitFor();
        return sb.toString();
    }

    private static void println(String s) {
        System.out.println(s);
        System.out.flush();
    }
}
