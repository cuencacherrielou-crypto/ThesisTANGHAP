package com.sti.tanghap.ui;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.sti.tanghap.R;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }
}