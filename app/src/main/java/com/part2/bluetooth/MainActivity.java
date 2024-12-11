package com.part2.bluetooth;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Base64;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "";
    private static final UUID SPP_UUID = UUID.fromString("");
    private static final String TARGET_DEVICE_NAME = "";

    private static final String NTRIP_SERVER_URL = "";
    private static final int NTRIP_SERVER_PORT = 0;
    private static final String NTRIP_MOUNTPOINT = "";
    private static final String NTRIP_USERNAME = "";
    private static final String NTRIP_PASSWORD = "";

    private boolean isNtripConnected = false;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private BluetoothDevice targetDevice;

    private TextView statusTextView;
    private TextView gpsDataTextView;
    private TextView rtkDataTextView;
    private TextView rtkReceiveDataTextView;
    private Button connectButton;

    private OutputStream espOutputStream;
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI components
        statusTextView = findViewById(R.id.statusTextView);
        gpsDataTextView = findViewById(R.id.gpsDataTextView);
        rtkDataTextView = findViewById(R.id.rtkDataTextView);
        rtkReceiveDataTextView = findViewById(R.id.rtkReceiveDataTextView);
        connectButton = findViewById(R.id.connectButton);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        registerReceiver(receiver, filter);

        connectButton.setOnClickListener(v -> {
            if (checkPermissions()) {
                startDiscovery();
            }
        });
    }

    private boolean checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            return false;
        }
        return true;
    }

    private void startDiscovery() {
        statusTextView.setText("Searching for ESP-32 device...");
        bluetoothAdapter.startDiscovery();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && TARGET_DEVICE_NAME.equals(device.getName())) {
                    bluetoothAdapter.cancelDiscovery();
                    targetDevice = device;
                    statusTextView.setText("Device found: " + device.getName());
                    connectToDevice();
                }
            }
        }
    };

    private void connectToDevice() {
        new Thread(() -> {
            try {
                bluetoothSocket = targetDevice.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothSocket.connect();
                espOutputStream = bluetoothSocket.getOutputStream();
                handler.post(() -> statusTextView.setText("Connected to " + TARGET_DEVICE_NAME));

                // Start receiving GPS data directly
                receiveGpsDataAndSendToNtrip();

                // Delay before NTRIP connection
                handler.postDelayed(this::startNtripConnection, 15000);
            } catch (IOException e) {
                Log.e(TAG, "Bluetooth connection failed", e);
                handler.post(() -> statusTextView.setText("Connection failed"));
            }
        }).start();
    }

    private void receiveGpsDataAndSendToNtrip() {
        new Thread(() -> {
            byte[] buffer = new byte[1024];
            int bytes;
            try (InputStream inputStream = bluetoothSocket.getInputStream()) {
                StringBuilder dataBuilder = new StringBuilder();
                while (true) {
                    bytes = inputStream.read(buffer);
                    if (bytes > 0) {
                        String gpsData = new String(buffer, 0, bytes);
                        dataBuilder.append(gpsData);

                        // 메시지 종료를 확인하여 완전한 NMEA 문장 추출
                        if (gpsData.contains("\r\n")) {
                            String completeData = dataBuilder.toString();
                            dataBuilder.setLength(0);

                            // 로그 및 UI 업데이트
                            if (completeData.contains("$GNGGA")) {
                                handler.post(() -> rtkReceiveDataTextView.setText("Corrected GNGGA Data: " + completeData));
                            } else {
                                handler.post(() -> gpsDataTextView.setText("GPS Data: " + completeData));
                            }

                            sendGGADataToNtrip(completeData);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error receiving GPS data from ESP-32", e);
            }
        }).start();
    }


    private void sendGGADataToNtrip(String gpsData) {
        if (isNtripConnected && socket != null && socket.isConnected()) {
            try {
                String[] lines = gpsData.split("\\r?\\n");
                for (String line : lines) {
                    if (line.startsWith("$GNGGA") || line.startsWith("$GPGGA")) {
                        OutputStream outputStream = socket.getOutputStream();
                        outputStream.write((line + "\r\n").getBytes());
                        outputStream.flush();
                        Log.d(TAG, "Sent GGA data to NTRIP server: " + line);


                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error sending GGA data to NTRIP server", e);
            }
        }
    }

    private void startNtripConnection() {
        new Thread(() -> {
            if (isNtripConnected) {
                Log.d(TAG, "NTRIP server is already connected.");
                return;
            }

            try {
                socket = new Socket(NTRIP_SERVER_URL, NTRIP_SERVER_PORT);
                isNtripConnected = true;

                String auth = NTRIP_USERNAME + ":" + NTRIP_PASSWORD;
                String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

                String requestHeader = "GET /" + NTRIP_MOUNTPOINT + " HTTP/1.0\r\n"
                        + "User-Agent: Android GNSS\r\n"
                        + "Host: " + NTRIP_SERVER_URL + "\r\n"
                        + "Authorization: Basic " + encodedAuth + "\r\n"
                        + "\r\n";

                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out.print(requestHeader);
                out.flush();

                String responseLine;
                while ((responseLine = in.readLine()) != null) {
                    if (responseLine.contains("ICY 200 OK")) {
                        Log.d(TAG, "Connected to NTRIP server.");
                        break;
                    }
                }

                receiveRTCMDataFromNtrip();

            } catch (IOException e) {
                Log.e(TAG, "I/O Exception in NTRIP connection", e);
            }
        }).start();
    }

    private void receiveRTCMDataFromNtrip() {
        new Thread(() -> {
            try {
                while (isNtripConnected) {
                    byte[] buffer = new byte[4096];
                    int bytesRead = socket.getInputStream().read(buffer);

                        // bytesRead != -1 변경

                    if (bytesRead > 0 ) {
                        StringBuilder hexString = new StringBuilder();
                        for (int i = 0; i < bytesRead; i++) {
                            hexString.append(String.format("%02X ", buffer[i]));
                        }
                        String rtkData = hexString.toString();

                        handler.post(() -> rtkDataTextView.setText("RTK Data: " + rtkData));
                        Log.d("Hex Data",""+rtkData);

                        if (espOutputStream != null) {
                            espOutputStream.write(buffer, 0, bytesRead);
                            espOutputStream.flush();
                            Log.d(TAG, "Sent RTCM data to ESP-32");
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error receiving RTCM data from NTRIP server", e);
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(receiver);
        try {
            if (bluetoothSocket != null) {
                bluetoothSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing Bluetooth socket", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startDiscovery();
        }
    }
}
