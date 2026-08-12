package com.priperi;

import android.animation.*;
import android.app.*;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.net.*;
import android.os.*;
import android.text.*;
import android.text.style.*;
import android.util.*;
import android.view.*;
import android.view.View;
import android.view.View.*;
import android.view.animation.*;
import android.webkit.*;
import android.widget.*;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.google.firebase.FirebaseApp;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.*;
import org.json.*;

public class AfterActivity extends AppCompatActivity {
	
	private Timer _timer = new Timer();
	
	private LinearLayout linear3;
	private LinearLayout linear4;
	private TextView textview1;
	private TextView textview2;
	private LinearLayout linear6;
	private TextView time_;
	
	private AlertDialog.Builder exit;
	private SharedPreferences usr_name;
	private SharedPreferences usr_n;
	private TimerTask time;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		setContentView(R.layout.after);
		initialize(_savedInstanceState);
		FirebaseApp.initializeApp(this);
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
		linear3 = findViewById(R.id.linear3);
		linear4 = findViewById(R.id.linear4);
		textview1 = findViewById(R.id.textview1);
		textview2 = findViewById(R.id.textview2);
		linear6 = findViewById(R.id.linear6);
		time_ = findViewById(R.id.time_);
		exit = new AlertDialog.Builder(this);
		usr_name = getSharedPreferences("usr_name", Activity.MODE_PRIVATE);
		usr_n = getSharedPreferences("usr_n", Activity.MODE_PRIVATE);
		
		textview2.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				
			}
		});
	}
	
	private void initializeLogic() {
		if ("w".equals(getIntent().getStringExtra("transfers"))) {
			SketchwareUtil.showMessage(getApplicationContext(), "Succcess");
			textview2.setText(usr_n.getString("usrname", ""));
			java.util.Calendar cal = java.util.Calendar.getInstance();
			cal.add(java.util.Calendar.HOUR_OF_DAY, 2);
			cal.add(java.util.Calendar.MINUTE, 30);
			java.text.SimpleDateFormat timeFormat = new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault());
			time_.setText(timeFormat.format(cal.getTime()));
			
		} else {
			finishAffinity();
		}
	}
	
	@Override
	public void onBackPressed() {
		exit.setTitle("Are You Sure You Wanna Exit");
		exit.setIcon(R.drawable.ic_arrow_back_black);
		exit.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface _dialog, int _which) {
				finishAffinity();
			}
		});
		exit.setNegativeButton("No", new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface _dialog, int _which) {
				
			}
		});
		exit.create().show();
	}
	
	@Deprecated
	public void showMessage(String _s) {
		Toast.makeText(getApplicationContext(), _s, Toast.LENGTH_SHORT).show();
	}
	
	@Deprecated
	public int getLocationX(View _v) {
		int _location[] = new int[2];
		_v.getLocationInWindow(_location);
		return _location[0];
	}
	
	@Deprecated
	public int getLocationY(View _v) {
		int _location[] = new int[2];
		_v.getLocationInWindow(_location);
		return _location[1];
	}
	
	@Deprecated
	public int getRandom(int _min, int _max) {
		Random random = new Random();
		return random.nextInt(_max - _min + 1) + _min;
	}
	
	@Deprecated
	public ArrayList<Double> getCheckedItemPositionsToArray(ListView _list) {
		ArrayList<Double> _result = new ArrayList<Double>();
		SparseBooleanArray _arr = _list.getCheckedItemPositions();
		for (int _iIdx = 0; _iIdx < _arr.size(); _iIdx++) {
			if (_arr.valueAt(_iIdx))
			_result.add((double)_arr.keyAt(_iIdx));
		}
		return _result;
	}
	
	@Deprecated
	public float getDip(int _input) {
		return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, _input, getResources().getDisplayMetrics());
	}
	
	@Deprecated
	public int getDisplayWidthPixels() {
		return getResources().getDisplayMetrics().widthPixels;
	}
	
	@Deprecated
	public int getDisplayHeightPixels() {
		return getResources().getDisplayMetrics().heightPixels;
	}
}