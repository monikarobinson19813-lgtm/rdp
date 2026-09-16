package com.remotephone.direct;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(42, 80, 42, 42);

        TextView title = text("RemotePhone Direct", 28);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = text("Choose how this phone will be used.", 16);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 12, 0, 38);
        root.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        Button host = new Button(this);
        host.setText("SET UP AS HOST");
        root.addView(host, new LinearLayout.LayoutParams(-1, -2));

        TextView hostHelp = text("Host = phone kept at home/office and accessed remotely.", 14);
        hostHelp.setPadding(0, 6, 0, 28);
        root.addView(hostHelp, new LinearLayout.LayoutParams(-1, -2));

        Button controller = new Button(this);
        controller.setText("USE AS CONTROLLER");
        root.addView(controller, new LinearLayout.LayoutParams(-1, -2));

        TextView controllerHelp = text("Controller = phone you carry and use to view/control a Host.", 14);
        controllerHelp.setPadding(0, 6, 0, 24);
        root.addView(controllerHelp, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);

        host.setOnClickListener(v -> startActivity(new Intent(this, HostActivity.class)));
        controller.setOnClickListener(v -> startActivity(new Intent(this, ViewerActivity.class)));
    }

    private TextView text(String s, float size) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        return v;
    }
}
