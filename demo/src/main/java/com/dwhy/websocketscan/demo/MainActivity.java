package com.dwhy.websocketscan.demo;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnServer = findViewById(R.id.btn_server);
        Button btnClient = findViewById(R.id.btn_client);

        btnServer.setOnClickListener(v ->
                startActivity(new Intent(this, ServerActivity.class)));

        btnClient.setOnClickListener(v ->
                startActivity(new Intent(this, ClientActivity.class)));
    }
}
