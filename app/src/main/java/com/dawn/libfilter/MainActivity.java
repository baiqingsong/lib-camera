package com.dawn.libfilter;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnImageFilter = findViewById(R.id.btn_image_filter);
        Button btnCameraFilter = findViewById(R.id.btn_camera_filter);

        btnImageFilter.setOnClickListener(v ->
                startActivity(new Intent(this, ImageFilterActivity.class)));

        btnCameraFilter.setOnClickListener(v ->
                startActivity(new Intent(this, CameraFilterActivity.class)));
    }
}
