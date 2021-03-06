package com.stardust.autojs.core.looper;

import android.os.Handler;
import android.os.Looper;

import com.stardust.autojs.runtime.ScriptRuntime;
import com.stardust.autojs.runtime.api.Threads;
import com.stardust.autojs.runtime.api.Timers;
import com.stardust.autojs.runtime.exception.ScriptInterruptedException;
import com.stardust.lang.ThreadCompat;

/**
 * Created by Stardust on 2017/7/29.
 */

public class Loopers {

    public interface LooperQuitHandler {
        boolean shouldQuit();
    }

    private static final Runnable EMPTY_RUNNABLE = () -> {
    };

    private volatile ThreadLocal<Boolean> waitWhenIdle = new ThreadLocal<>();
    private volatile Looper mServantLooper;
    private Timers mTimers;
    private ScriptRuntime mScriptRuntime;
    private LooperQuitHandler mMainLooperQuitHandler;
    private Handler mMainHandler;
    private Looper mMainLooper;
    private Threads mThreads;

    public Loopers(ScriptRuntime runtime) {
        mTimers = runtime.timers;
        mThreads = runtime.threads;
        mScriptRuntime = runtime;
        prepare();
        mMainLooper = Looper.myLooper();
        mMainHandler = new Handler();
    }


    public Looper getMainLooper() {
        return mMainLooper;
    }

    private boolean shouldQuitLooper() {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        if (mTimers.hasPendingCallbacks()) {
            return false;
        }
        return !waitWhenIdle.get();
    }


    private void initServantThread() {
        new ThreadCompat(() -> {
            Looper.prepare();
            final Object lock = Loopers.this;
            mServantLooper = Looper.myLooper();
            synchronized (lock) {
                lock.notifyAll();
            }
            Looper.loop();
        }).start();
    }

    public Looper getServantLooper() {
        if (mServantLooper == null) {
            initServantThread();
            synchronized (this) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    throw new ScriptInterruptedException();
                }
            }
        }
        return mServantLooper;
    }

    public void quitServantLooper() {
        if (mServantLooper == null)
            return;
        mServantLooper.quit();
    }

    public void waitWhenIdle(boolean b) {
        waitWhenIdle.set(b);
    }

    public void quitAll() {
        quitServantLooper();
    }

    public void setMainLooperQuitHandler(LooperQuitHandler mainLooperQuitHandler) {
        mMainLooperQuitHandler = mainLooperQuitHandler;
    }

    public void prepare() {
        if (Looper.myLooper() == null)
            Looper.prepare();
        Looper.myQueue().addIdleHandler(() -> {
            Looper l = Looper.myLooper();
            if (l == null)
                return true;
            if (l == mMainLooper) {
                if (shouldQuitLooper() && !mThreads.hasRunningThreads() &&
                        mMainLooperQuitHandler != null && mMainLooperQuitHandler.shouldQuit()) {
                    l.quit();
                }
            } else {
                if (shouldQuitLooper()) {
                    l.quit();
                }
            }
            return true;
        });
        waitWhenIdle.set(Looper.myLooper() == Looper.getMainLooper());
    }

    public void notifyThreadExit(TimerThread thread) {
        //当子线程退成时，主线程需要检查自身是否退出（主线程在所有子线程执行完成后才能退出，如果主线程已经执行完任务仍然要等待所有子线程），
        //此时通过向主线程发送一个空的Runnable，主线程执行完这个Runnable后会触发IdleHandler，从而检查自身是否退出
        mMainHandler.post(EMPTY_RUNNABLE);
    }
}
