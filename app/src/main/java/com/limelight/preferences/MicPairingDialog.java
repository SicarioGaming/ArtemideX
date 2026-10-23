package com.limelight.preferences;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.audio.VbanSender;
import com.limelight.binding.audio.securemic.MicPairing;
import com.limelight.binding.audio.securemic.MicPairingClient;
import com.limelight.binding.audio.securemic.MicPairingStore;
import com.limelight.binding.audio.securemic.PairingCode;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Settings > Sound & vibration > Send my microphone > Pair with the PC: pick the PC, type the code
 * its receiver shows, and pair (MicPairingClient). Pairing runs on a thread of its own; the result
 * comes back in plain words, and the dialog stays open after an error so the user can fix the code.
 */
final class MicPairingDialog {
    private MicPairingDialog() {
    }

    /** Shows the dialog (or, with no PCs yet, says to add one); returns it for the tests. */
    static AlertDialog show(Activity activity, int port, Runnable onPaired) {
        List<ComputerDetails> computers = new ArrayList<>();
        ComputerDatabaseManager db = new ComputerDatabaseManager(activity);
        try {
            computers.addAll(db.getAllComputers());
        }
        finally {
            db.close();
        }
        if (computers.isEmpty()) {
            return new AlertDialog.Builder(activity)
                    .setTitle(R.string.mic_pair_title)
                    .setMessage(R.string.mic_pair_no_pcs)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
        Collections.sort(computers, (a, b) -> String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name)));
        MicPairingStore store = new MicPairingStore(activity);

        int pad = dp(activity, 24);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, dp(activity, 8), pad, 0);

        TextView message = new TextView(activity);
        message.setText(R.string.mic_pair_message);
        layout.addView(message);

        Spinner pcs = new Spinner(activity);
        List<String> names = new ArrayList<>();
        int firstUnpaired = -1;
        for (int i = 0; i < computers.size(); i++) {
            ComputerDetails c = computers.get(i);
            names.add(c.name != null ? c.name : c.uuid);
            if (firstUnpaired < 0 && store.deviceIdFor(c.uuid) == null) {
                firstUnpaired = i;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pcs.setAdapter(adapter);
        pcs.setSelection(Math.max(firstUnpaired, 0));
        pcs.setPadding(0, dp(activity, 12), 0, dp(activity, 4));
        layout.addView(pcs);

        EditText code = new EditText(activity);
        code.setHint(R.string.mic_pair_code_hint);
        code.setSingleLine(true);
        code.setTypeface(Typeface.MONOSPACE);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        code.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Capitals and a hyphen every four characters, as the PC shows it
                String formatted = PairingCode.format(s.toString());
                if (!formatted.contentEquals(s)) {
                    s.replace(0, s.length(), formatted);
                }
            }
        });
        layout.addView(code);

        TextView status = new TextView(activity);
        status.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
        status.setVisibility(View.GONE);
        layout.addView(status);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.mic_pair_title)
                .setView(layout)
                .setPositiveButton(R.string.mic_pair_button, null)   // set below, so an error doesn't close it
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            Button pair = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            pair.setOnClickListener(v -> {
                if (PairingCode.decode(code.getText().toString()) == null) {
                    showStatus(status, activity.getString(R.string.mic_pair_bad_code));
                    return;
                }
                ComputerDetails pc = computers.get(Math.max(pcs.getSelectedItemPosition(), 0));
                String pcName = names.get(Math.max(pcs.getSelectedItemPosition(), 0));
                String typed = code.getText().toString();
                pair.setEnabled(false);
                pcs.setEnabled(false);
                code.setEnabled(false);
                showStatus(status, activity.getString(R.string.mic_pair_working, pcName));
                new Thread(() -> {
                    Result result = pairNow(activity, store, pc, pcName, typed, port);
                    activity.runOnUiThread(() -> {
                        if (result.paired && onPaired != null) {
                            onPaired.run();
                        }
                        if (!dialog.isShowing()) {
                            return;
                        }
                        showStatus(status, result.message);
                        if (result.paired) {
                            // Done: the only button left closes it
                            pair.setVisibility(View.GONE);
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText(android.R.string.ok);
                        }
                        else {
                            pair.setEnabled(true);
                            pcs.setEnabled(true);
                            code.setEnabled(true);
                        }
                    });
                }, "MicPairing").start();
            });
        });
        dialog.show();
        return dialog;
    }

    private static final class Result {
        final boolean paired;
        final String message;

        Result(boolean paired, String message) {
            this.paired = paired;
            this.message = message;
        }
    }

    private static void showStatus(TextView status, String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    // Pairs and saves; says what to tell the user. Runs on the pairing thread.
    private static Result pairNow(Activity activity, MicPairingStore store, ComputerDetails pc, String pcName,
                                  String typed, int port) {
        List<InetSocketAddress> receivers = homeAddresses(pc, port);
        MicPairingClient.Outcome outcome;
        try (UdpTransport transport = new UdpTransport()) {
            outcome = MicPairingClient.create(transport).pair(receivers, typed, deviceName(), store.deviceIdFor(pc.uuid),
                    pc.uuid, pcName);
        }
        catch (IOException e) {
            LimeLog.warning("Microphone pairing: no socket: " + e);
            return new Result(false, activity.getString(R.string.mic_pair_failed));
        }
        LimeLog.info("Microphone pairing with " + pc.uuid + ": " + outcome.result + " (" + receivers.size() + " addresses)");
        if (outcome.result == MicPairingClient.Result.PAIRED) {
            MicPairing pairing = outcome.pairing;
            return store.save(pairing) ? new Result(true, activity.getString(R.string.mic_pair_done, pcName))
                    : new Result(false, activity.getString(R.string.mic_pair_not_saved));
        }
        return new Result(false, message(activity, outcome.result, pcName));
    }

    static String message(Activity activity, MicPairingClient.Result result, String pcName) {
        switch (result) {
            case PAIRED:
                return activity.getString(R.string.mic_pair_done, pcName);
            case BAD_CODE:
                return activity.getString(R.string.mic_pair_bad_code);
            case WRONG_CODE:
                return activity.getString(R.string.mic_pair_wrong_code);
            case NOT_PAIRING:
                return activity.getString(R.string.mic_pair_not_pairing);
            case EXPIRED:
                return activity.getString(R.string.mic_pair_expired);
            case NO_ANSWER:
                return activity.getString(R.string.mic_pair_no_answer, pcName);
            case NO_ADDRESS:
                return activity.getString(R.string.mic_pair_no_address, pcName);
            default:
                return activity.getString(R.string.mic_pair_failed);
        }
    }

    /** The PC's addresses Starwire knows that are on the home network or a private VPN. */
    static List<InetSocketAddress> homeAddresses(ComputerDetails pc, int port) {
        List<InetSocketAddress> out = new ArrayList<>();
        ComputerDetails.AddressTuple[] tuples = {pc.activeAddress, pc.localAddress, pc.manualAddress, pc.ipv6Address};
        for (ComputerDetails.AddressTuple tuple : tuples) {
            if (tuple == null || tuple.address == null) {
                continue;
            }
            try {
                for (InetAddress address : InetAddress.getAllByName(tuple.address)) {
                    InetSocketAddress to = new InetSocketAddress(address, port);
                    if (VbanSender.isHomeNetworkAddress(address) && !out.contains(to)) {
                        out.add(to);
                    }
                }
            }
            catch (UnknownHostException | SecurityException e) {
                LimeLog.info("Microphone pairing: can't look up " + tuple.address);
            }
        }
        return out;
    }

    // What the PC calls this device: its model. Not the device name the user set, which often has
    // their own name in it, since the pairing request crosses the network unencrypted.
    private static String deviceName() {
        String model = Build.MODEL != null ? Build.MODEL : "Android device";
        String maker = Build.MANUFACTURER;
        if (maker != null && !maker.isEmpty() && !model.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT))) {
            model = Character.toUpperCase(maker.charAt(0)) + maker.substring(1) + " " + model;
        }
        return model;
    }

    private static int dp(Activity activity, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics());
    }

    private static final class UdpTransport implements MicPairingClient.Transport, AutoCloseable {
        private final DatagramSocket socket;

        UdpTransport() throws IOException {
            socket = new DatagramSocket();
        }

        @Override
        public void send(byte[] packet, int length, InetSocketAddress to) throws IOException {
            socket.send(new DatagramPacket(packet, length, to));
        }

        @Override
        public int receive(byte[] buffer, int timeoutMs) throws IOException {
            socket.setSoTimeout(Math.max(1, timeoutMs));
            DatagramPacket in = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(in);
            }
            catch (SocketTimeoutException e) {
                return -1;
            }
            return in.getLength();
        }

        @Override
        public void close() {
            socket.close();
        }
    }
}
