package com.termux.terminal;

import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SessionBackend} backed by a local pseudoterminal subprocess created via {@link JNI}
 * (the upstream Termux mechanism). This is what the local PRoot container mode uses — it needs
 * a real PTY for interactive shells (readline, ANSI, full-screen programs).
 *
 * <p>Owns the pty master file descriptor and the process id. {@link #getInputStream()} /
 * {@link #getOutputStream()} wrap the fd; {@link #resize} calls {@link JNI#setPtyWindowSize};
 * {@link #waitForExit} calls {@link JNI#waitFor}; {@link #close} calls {@link JNI#close} and
 * sends SIGKILL if still running.
 */
final class SubprocessBackend implements SessionBackend {

    private final int mPtyFd;
    private final int mPid;
    private final FileDescriptor mWrappedFd;
    private final InputStream mInputStream;
    private final OutputStream mOutputStream;

    /**
     * 幂等关闭标记。
     *
     * <p>{@link SessionBackend#close()} 的契约是「可重复调用」，而本类此前无任何守卫：
     * {@code JNI.close(mPtyFd)} 是裸 close(2)，对同一 fd 调用两次就会关掉一个「已被回收、
     * 号数被系统复用给别人（如 Vulkan fence 的 unique_fd）」的描述符。Android 的 fdsan
     * 会发现 owner 不符并直接 abort 整个进程（Abort message: attempted to close file
     * descriptor N, expected to be unowned, actually owned by unique_fd）。
     *
     * <p>正式版 2026-09-29 的两次崩溃栈直接落在这里（{@code 11:04:49}、{@code 15:20:02}，
     * 均为 close ← LocalBackendHolder.close ← cleanupResources）；同日 {@code 14:09:32}
     * 崩在 libhwui/libgsl 的 Vulkan fence 上，abort 消息同类但栈不同，**属同一类 fd 复用
     * 事故的推断，未直接证实**。
     *
     * <p>竞争来源：进程退出时 {@code TermSessionWaiter} 触发 {@code cleanupResources()}，
     * 用户/AI 关标签时 {@code finishIfRunning()} 也会调 {@code close()}，两条路径无锁并发。
     */
    private final AtomicBoolean mClosed = new AtomicBoolean(false);

    SubprocessBackend(String shellPath, String cwd, String[] args, String[] env, int rows, int columns) {
        int[] processId = new int[1];
        mPtyFd = JNI.createSubprocess(shellPath, cwd, args, env, processId, rows, columns);
        mPid = processId[0];
        // 原生层失败时（/dev/ptmx 打不开、fork 被拒）返回 fd=-1 且 pid 保持 0，但它只在 stderr
        // 打一行 perror（落在 logcat，不进 App 日志）。若不在这里主动失败，会话会被当成「运行中」
        // （0 != -1）永久挂起、终端一片空白且无任何日志——正是用户报的形态。显式抛错让上层进失败态。
        if (mPtyFd < 0 || mPid <= 0) {
            throw new IllegalStateException(
                "createSubprocess 失败：ptyFd=" + mPtyFd + " pid=" + mPid
                    + " shell=" + shellPath + " cwd=" + cwd
                    + "（/dev/ptmx 或 fork 被内核/SELinux 拒绝，perror 详情见 logcat）");
        }
        mWrappedFd = wrapFileDescriptor(mPtyFd);
        mInputStream = new FileInputStream(mWrappedFd);
        mOutputStream = new FileOutputStream(mWrappedFd);
    }

    int getPid() {
        return mPid;
    }

    @Override
    public InputStream getInputStream() {
        return mInputStream;
    }

    @Override
    public OutputStream getOutputStream() {
        return mOutputStream;
    }

    @Override
    public void resize(int columns, int rows) {
        JNI.setPtyWindowSize(mPtyFd, rows, columns);
    }

    @Override
    public int waitForExit() {
        return JNI.waitFor(mPid);
    }

    @Override
    public void close() {
        // 只允许真正关闭一次；后到的调用直接返回，避免对已复用的 fd 号二次 close。
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        if (mPid > 0) {
            try {
                Os.kill(mPid, OsConstants.SIGKILL);
            } catch (Exception ignored) {
            }
        }
        JNI.close(mPtyFd);
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            throw new RuntimeException("Error accessing FileDescriptor#descriptor private field", e);
        }
        return result;
    }
}
