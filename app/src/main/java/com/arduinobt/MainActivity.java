package com.arduinobt;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static final UUID BT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private InputStream inputStream;
    private Thread readThread;
    private boolean connected = false;

    private MaterialButton btnConectar;
    private TextView lblVelocidad, lblTemperatura, lblEstado, lblVelocidadNum, lblTempNum;
    private ProgressBar barraVelocidad;
    private Handler mainHandler;

    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            boolean allGranted = true;
            for (Boolean granted : result.values()) {
                if (!granted) { allGranted = false; break; }
            }
            if (allGranted) mostrarDispositivosBT();
            else Toast.makeText(this, "Necesitas dar permisos Bluetooth para continuar", Toast.LENGTH_LONG).show();
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler = new Handler(Looper.getMainLooper());
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        btnConectar     = findViewById(R.id.btnConectar);
        lblVelocidad    = findViewById(R.id.lblVelocidad);
        lblTemperatura  = findViewById(R.id.lblTemperatura);
        lblEstado       = findViewById(R.id.lblEstado);
        lblVelocidadNum = findViewById(R.id.lblVelocidadNum);
        lblTempNum      = findViewById(R.id.lblTempNum);
        barraVelocidad  = findViewById(R.id.barraVelocidad);

        btnConectar.setOnClickListener(v -> {
            if (connected) desconectar();
            else verificarPermisosYConectar();
        });
    }

    private void verificarPermisosYConectar() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Este celular no tiene Bluetooth", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Activa el Bluetooth primero", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }

        List<String> permisos = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                permisos.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                permisos.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permisos.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (!permisos.isEmpty())
            permissionLauncher.launch(permisos.toArray(new String[0]));
        else
            mostrarDispositivosBT();
    }

    private void mostrarDispositivosBT() {
        Set<BluetoothDevice> pareados;
        try {
            pareados = bluetoothAdapter.getBondedDevices();
        } catch (SecurityException e) {
            Toast.makeText(this, "Permiso Bluetooth denegado", Toast.LENGTH_SHORT).show();
            return;
        }

        if (pareados == null || pareados.isEmpty()) {
            Toast.makeText(this, "No hay dispositivos Bluetooth pareados. Empareja el HC-05 primero.", Toast.LENGTH_LONG).show();
            return;
        }

        List<BluetoothDevice> lista = new ArrayList<>(pareados);
        String[] nombres = new String[lista.size()];
        for (int i = 0; i < lista.size(); i++)
            nombres[i] = lista.get(i).getName() + "\n" + lista.get(i).getAddress();

        new AlertDialog.Builder(this)
            .setTitle("Selecciona el HC-05")
            .setItems(nombres, (dialog, which) -> conectarA(lista.get(which)))
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void conectarA(BluetoothDevice device) {
        lblEstado.setText("Conectando...");
        btnConectar.setEnabled(false);

        new Thread(() -> {
            try {
                bluetoothSocket = device.createInsecureRfcommSocketToServiceRecord(BT_UUID);
                bluetoothSocket.connect();
                inputStream = bluetoothSocket.getInputStream();
                connected = true;

                mainHandler.post(() -> {
                    lblEstado.setText("✅ Conectado a " + device.getName());
                    btnConectar.setText("Desconectar");
                    btnConectar.setEnabled(true);
                });

                iniciarLectura();

            } catch (Exception e) {
                mainHandler.post(() -> {
                    lblEstado.setText("❌ Error al conectar");
                    btnConectar.setText("Conectar Bluetooth");
                    btnConectar.setEnabled(true);
                    Toast.makeText(this, "No se pudo conectar al HC-05", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void iniciarLectura() {
        readThread = new Thread(() -> {
            StringBuilder buffer = new StringBuilder();
            byte[] bytes = new byte[1024];

            while (connected) {
                try {
                    int count = inputStream.read(bytes);
                    String chunk = new String(bytes, 0, count);
                    buffer.append(chunk);

                    int newline;
                    while ((newline = buffer.indexOf("\n")) != -1) {
                        String linea = buffer.substring(0, newline).trim();
                        buffer.delete(0, newline + 1);
                        procesarDatos(linea);
                    }
                } catch (IOException e) {
                    if (connected) {
                        mainHandler.post(() -> {
                            lblEstado.setText("⚠️ Conexión perdida");
                            btnConectar.setText("Conectar Bluetooth");
                            connected = false;
                        });
                    }
                    break;
                }
            }
        });
        readThread.start();
    }

    private void procesarDatos(String datos) {
        String[] partes = datos.split(",");
        if (partes.length < 2) return;

        try {
            float velocidad   = Float.parseFloat(partes[0].trim());
            float temperatura = Float.parseFloat(partes[1].trim());

            mainHandler.post(() -> {
                lblVelocidadNum.setText(String.format("%.1f", velocidad));
                lblVelocidad.setText("km/h");
                lblTempNum.setText(String.format("%.1f°C", temperatura));
                lblTemperatura.setText("Temperatura");
                barraVelocidad.setProgress((int) Math.min(velocidad, 100));
            });

        } catch (NumberFormatException ignored) {}
    }

    private void desconectar() {
        connected = false;
        try {
            if (bluetoothSocket != null) bluetoothSocket.close();
        } catch (IOException ignored) {}
        lblEstado.setText("Sin conexión");
        btnConectar.setText("Conectar Bluetooth");
        lblVelocidadNum.setText("--");
        lblTempNum.setText("--°C");
        barraVelocidad.setProgress(0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        desconectar();
    }
}
