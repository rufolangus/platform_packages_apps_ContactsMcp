/*
 * Copyright (C) 2024 The AAOSP Project
 * Licensed under the Apache License, Version 2.0
 */

package com.android.contacts.mcp;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Translucent, UI-less activity whose only job is to host a runtime
 * {@link #requestPermissions} call so that {@link ContactsMcpService}
 * (a bound background service with no Activity context) can prompt the
 * user for dangerous permissions without bouncing them out to Settings.
 *
 * <p>Protocol:
 * <ol>
 *   <li>Service calls {@link #newGate()} → gets a {@code Gate} with a
 *       unique {@code id}.</li>
 *   <li>Service fires {@code Intent(service, PermissionRequestActivity.class)}
 *       with {@code EXTRA_PERMISSION} + {@code EXTRA_GATE_ID} and
 *       {@code FLAG_ACTIVITY_NEW_TASK}.</li>
 *   <li>Service blocks on {@link Gate#await}. Activity invokes
 *       {@code requestPermissions}, reports the result via
 *       {@link Gate#resolve}, and finishes.</li>
 *   <li>If BAL (background activity launch) is blocked on Android 15,
 *       {@code startActivity} throws or silently no-ops and the gate
 *       times out → service falls back to the "open settings" error
 *       path, which the launcher handles.</li>
 * </ol>
 *
 * <p>Must be declared in AndroidManifest.xml with the
 * {@code @android:style/Theme.Translucent.NoTitleBar} theme.
 */
public class PermissionRequestActivity extends Activity {

    private static final String TAG = "PermRequestActivity";
    private static final int REQ_CODE = 0x7e01;

    public static final String EXTRA_PERMISSION = "permission";
    public static final String EXTRA_GATE_ID = "gateId";

    /** Open gates keyed by id. Static — activity + service share the process. */
    private static final ConcurrentHashMap<Long, Gate> sGates = new ConcurrentHashMap<>();
    private static final AtomicLong sGateSeq = new AtomicLong(1);

    /** Create a new gate for a service call. Caller passes {@link Gate#id}
     *  through {@link #EXTRA_GATE_ID} and awaits on the gate. */
    public static Gate newGate() {
        Gate g = new Gate(sGateSeq.getAndIncrement());
        sGates.put(g.id, g);
        return g;
    }

    private long mGateId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String perm = getIntent().getStringExtra(EXTRA_PERMISSION);
        mGateId = getIntent().getLongExtra(EXTRA_GATE_ID, -1);
        if (perm == null || mGateId < 0) {
            Log.w(TAG, "Missing extras — finishing");
            finish();
            return;
        }
        // Already granted? (Race with another caller.)
        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
            resolve(true);
            finish();
            return;
        }
        requestPermissions(new String[]{perm}, REQ_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        if (requestCode != REQ_CODE) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, "Permission result: granted=" + granted);
        resolve(granted);
        finish();
    }

    @Override
    protected void onDestroy() {
        // If the user swiped away before answering, unblock the service.
        resolve(false);
        super.onDestroy();
    }

    private void resolve(boolean granted) {
        Gate g = sGates.remove(mGateId);
        if (g != null) g.resolve(granted);
    }

    /** Blocking handoff from Activity → Service. */
    public static final class Gate {
        public final long id;
        private final CountDownLatch mLatch = new CountDownLatch(1);
        private volatile boolean mGranted = false;

        private Gate(long id) { this.id = id; }

        public boolean await(long timeout, TimeUnit unit) {
            try {
                return mLatch.await(timeout, unit) && mGranted;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void resolve(boolean granted) {
            mGranted = granted;
            mLatch.countDown();
        }
    }
}
