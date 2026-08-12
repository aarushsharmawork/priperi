package com.priperi;

import android.animation.*;
import android.app.*;
import android.app.Activity;
import android.content.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.*;
import android.net.*;
import android.net.Uri;
import android.os.*;
import android.os.Bundle;
import android.os.Vibrator;
import android.text.*;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.style.*;
import android.util.*;
import android.view.*;
import android.view.View;
import android.view.View.*;
import android.view.animation.*;
import android.webkit.*;
import android.widget.*;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import java.io.*;
import java.io.InputStream;
import java.text.*;
import java.util.*;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.*;
import org.json.*;

public class MainActivity extends AppCompatActivity {
	
	private Timer _timer = new Timer();
	
	private String limits = "";
	
	private ArrayList<String> limit = new ArrayList<>();
	
	private LinearLayout l_1;
	private TextView eyes;
	private CardView cv;
	private LinearLayout cv_inside;
	private LinearLayout usr_nameid;
	private LinearLayout mt2;
	private LinearLayout usr_mailid;
	private LinearLayout mt3;
	private LinearLayout usr_passid;
	private LinearLayout log_l;
	private LinearLayout usr_img;
	private LinearLayout usr_namel;
	private EditText usr_name;
	private LinearLayout mail;
	private LinearLayout usr_maill;
	private EditText usr_mail;
	private LinearLayout pass;
	private LinearLayout usr_passl;
	private EditText usr_pass;
	private LinearLayout eye;
	private ImageView imageview1;
	private Button log;
	
	private TimerTask timer;
	private SharedPreferences view;
	private Vibrator vibrate;
	private ProgressDialog progress;
	private TimerTask load;
	private Intent transfer = new Intent();
	private Intent usr_ = new Intent();
	private SharedPreferences usr_n;
	private FirebaseAuth login;
	private OnCompleteListener<AuthResult> _login_create_user_listener;
	private OnCompleteListener<AuthResult> _login_sign_in_listener;
	private OnCompleteListener<Void> _login_reset_password_listener;
	private OnCompleteListener<Void> login_updateEmailListener;
	private OnCompleteListener<Void> login_updatePasswordListener;
	private OnCompleteListener<Void> login_emailVerificationSentListener;
	private OnCompleteListener<Void> login_deleteUserListener;
	private OnCompleteListener<Void> login_updateProfileListener;
	private OnCompleteListener<AuthResult> login_phoneAuthListener;
	private OnCompleteListener<AuthResult> login_googleSignInListener;
	private RequestNetwork net;
	private RequestNetwork.RequestListener _net_request_listener;
	
	@Override
	protected void onCreate(Bundle _savedInstanceState) {
		super.onCreate(_savedInstanceState);
		setContentView(R.layout.main);
		initialize(_savedInstanceState);
		FirebaseApp.initializeApp(this);
		initializeLogic();
	}
	
	private void initialize(Bundle _savedInstanceState) {
		l_1 = findViewById(R.id.l_1);
		eyes = findViewById(R.id.eyes);
		cv = findViewById(R.id.cv);
		cv_inside = findViewById(R.id.cv_inside);
		usr_nameid = findViewById(R.id.usr_nameid);
		mt2 = findViewById(R.id.mt2);
		usr_mailid = findViewById(R.id.usr_mailid);
		mt3 = findViewById(R.id.mt3);
		usr_passid = findViewById(R.id.usr_passid);
		log_l = findViewById(R.id.log_l);
		usr_img = findViewById(R.id.usr_img);
		usr_namel = findViewById(R.id.usr_namel);
		usr_name = findViewById(R.id.usr_name);
		mail = findViewById(R.id.mail);
		usr_maill = findViewById(R.id.usr_maill);
		usr_mail = findViewById(R.id.usr_mail);
		pass = findViewById(R.id.pass);
		usr_passl = findViewById(R.id.usr_passl);
		usr_pass = findViewById(R.id.usr_pass);
		eye = findViewById(R.id.eye);
		imageview1 = findViewById(R.id.imageview1);
		log = findViewById(R.id.log);
		view = getSharedPreferences("view", Activity.MODE_PRIVATE);
		vibrate = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
		usr_n = getSharedPreferences("usr_n", Activity.MODE_PRIVATE);
		login = FirebaseAuth.getInstance();
		net = new RequestNetwork(this);
		
		usr_name.addTextChangedListener(new TextWatcher() {
			@Override
			public void onTextChanged(CharSequence _param1, int _param2, int _param3, int _param4) {
				final String _charSeq = _param1.toString();
				
			}
			
			@Override
			public void beforeTextChanged(CharSequence _param1, int _param2, int _param3, int _param4) {
				
			}
			
			@Override
			public void afterTextChanged(Editable _param1) {
				
			}
		});
		
		usr_mail.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				
			}
		});
		
		usr_pass.addTextChangedListener(new TextWatcher() {
			@Override
			public void onTextChanged(CharSequence _param1, int _param2, int _param3, int _param4) {
				final String _charSeq = _param1.toString();
				if (_charSeq.length() > 15) {
					limits = _charSeq.substring((int)(0), (int)(14));
					usr_pass.setText("");
				}
				if (_charSeq.length() == 0) {
					usr_pass.append(limits);
				}
			}
			
			@Override
			public void beforeTextChanged(CharSequence _param1, int _param2, int _param3, int _param4) {
				
			}
			
			@Override
			public void afterTextChanged(Editable _param1) {
				
			}
		});
		
		imageview1.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				if (view.getString("lol", "").equals("lol")) {
					imageview1.setImageResource(R.drawable.ic_visibility_off_black);
					usr_pass.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
					view.edit().remove("lol").commit();
				} else {
					imageview1.setImageResource(R.drawable.ic_remove_red_eye_black);
					usr_pass.setTransformationMethod(android.text.method.HideReturnsTransformationMethod.getInstance());
					view.edit().putString("lol", "lol").commit();
				}
				eyes.setText("hide");
				eyes.setText("show");
			}
		});
		
		log.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View _view) {
				if (SketchwareUtil.isConnected(getApplicationContext())) {
					if (usr_name.getText().toString().equals("")) {
						((EditText)usr_name).setError("");
					} else {
						if ((usr_mail.getText().toString().length() == 0) && (usr_pass.getText().toString().length() == 0)) {
							((EditText)usr_mail).setError("");
							((EditText)usr_pass).setError("");
						} else {
							login.signInWithEmailAndPassword(usr_mail.getText().toString(), usr_pass.getText().toString()).addOnCompleteListener(MainActivity.this, _login_sign_in_listener);
						}
					}
				} else {
					SketchwareUtil.showMessage(getApplicationContext(), "User Not Connected To Internet");
				}
				log.setOnTouchListener(new android.view.View.OnTouchListener() {
					@Override
					public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
						
						if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
							
							// Pressed — moves down and slightly shrinks
							v.animate()
							.translationY(5)
							.scaleX(0.97f)
							.scaleY(0.97f)
							.setDuration(80)
							.start();
							
						} 
						else if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
							
							// Released — returns smoothly
							v.animate()
							.translationY(0)
							.scaleX(1f)
							.scaleY(1f)
							.setDuration(100)
							.start();
							
							v.performClick();
							
						} 
						else if (event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
							
							v.animate()
							.translationY(0)
							.scaleX(1f)
							.scaleY(1f)
							.setDuration(100)
							.start();
						}
						
						return true;
					}
				});
			}
		});
		
		_net_request_listener = new RequestNetwork.RequestListener() {
			@Override
			public void onResponse(String _param1, String _param2, HashMap<String, Object> _param3) {
				final String _tag = _param1;
				final String _response = _param2;
				final HashMap<String, Object> _responseHeaders = _param3;
				
			}
			
			@Override
			public void onErrorResponse(String _param1, String _param2) {
				final String _tag = _param1;
				final String _message = _param2;
				
			}
		};
		
		login_updateEmailListener = new OnCompleteListener<Void>() {
			@Override
			public void onComplete(Task<Void> _param1) {
				final boolean _success = _param1.isSuccessful();
				final String _errorMessage = _param1.getException() != null ? _param1.getException().getMessage() : "";
				
			}
		};
		
		login_updatePasswordListener = new OnCompleteListener<Void>() {
			@Override
			public void onComplete(Task<Void> _param1) {
				final boolean _success = _param1.isSuccessful();
				final String _errorMessage = _param1.getException() != null ? _param1.getException().getMessage() : "";
				
			}
		};
		
		login_emailVerificationSentListener = new OnCompleteListener<Void>() {
			@Override
			public void onComplete(Task<Void> _param1) {
				final boolean _success = _param1.isSuccessful();
				final String _errorMessage = _param1.getException() != null ? _param1.getException().getMessage() : "";
				
			}
		};
		
		login_deleteUserListener = new OnCompleteListener<Void>() {
			@Override
			public void onComplete(Task<Void> _param1) {
				final boolean _success = _param1.isSuccessful();
				final String _errorMessage = _param1.getException() != null ? _param1.getException().getMessage() : "";
				
			}
		};
		
		login_phoneAuthListener = new OnCompleteListener<AuthResult>() {
			@Override
			public void onComplete(Task<AuthResult> task) {
				final boolean _success = task.isSuccessful();
				final String _errorMessage = task.getException() != null ? task.getException().getMessage() : "";
				
			}
		};
		
		login_updateProfileListener = new OnCompleteListener<Void>() {
			@Override
			public void onComplete(Task<Void> _param1) {
				final boolean _success = _param1.isSuccessful();
				final String _errorMessage = _param1.getException() != null ? _param1.getException().getMessage() : "";
				
			}
		};
		
		login_googleSignInListener = new OnCompleteListener<AuthResult>() {
			@Override
			public void onComplete(Task<AuthResult> task) {
				final boolean _success = task.isSuccessful();
				final String _errorMessage = task.getException() != null ? task.getException().getMessage() : "";
				
			}
		};
		
		_login_create_user_listener = new OnCompleteListener<AuthResult>() {
			@Override
			public void onComplete(Task<AuthResult> _param1) {
				final boolean _success = _param1.isSuccessful();
				final String _errorMessage = _param1.getException() != null ? _param1.getException().getMessage() : "";
				
			}
		};
		
		_login_sign_in_listener = new OnCompleteListener<AuthResult>() {
			@Override
			public void onComplete(Task<AuthResult> _param1) {
				final boolean _success = _param1.isSuccessful();
				final String _errorMessage = _param1.getException() != null ? _param1.getException().getMessage() : "";
				if (_success) {
					log.setScaleX((float)(1.2d));
					log.setScaleY((float)(1.2d));
					timer = new TimerTask() {
						@Override
						public void run() {
							runOnUiThread(new Runnable() {
								@Override
								public void run() {
									log.setScaleX((float)(1.0d));
									log.setScaleY((float)(1.0d));
								}
							});
						}
					};
					_timer.schedule(timer, (int)(122));
					vibrate.vibrate((long)(20));
					usr_n.edit().putString("usrname", usr_name.getText().toString()).commit();
					progress = new ProgressDialog(MainActivity.this);
					progress.setCanceledOnTouchOutside(false);
					progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);
					progress.setCancelable(false);
					load = new TimerTask() {
						@Override
						public void run() {
							runOnUiThread(new Runnable() {
								@Override
								public void run() {
									progress.setMessage("Please Wait");
								}
							});
						}
					};
					_timer.scheduleAtFixedRate(load, (int)(0), (int)(1000));
					progress.show();
					load = new TimerTask() {
						@Override
						public void run() {
							runOnUiThread(new Runnable() {
								@Override
								public void run() {
									progress.setMessage("Please Wait.");
								}
							});
						}
					};
					_timer.scheduleAtFixedRate(load, (int)(200), (int)(800));
					load = new TimerTask() {
						@Override
						public void run() {
							runOnUiThread(new Runnable() {
								@Override
								public void run() {
									progress.setMessage("Please Wait..");
								}
							});
						}
					};
					_timer.scheduleAtFixedRate(load, (int)(400), (int)(600));
					load = new TimerTask() {
						@Override
						public void run() {
							runOnUiThread(new Runnable() {
								@Override
								public void run() {
									progress.setMessage("Please Wait...");
								}
							});
						}
					};
					_timer.scheduleAtFixedRate(load, (int)(600), (int)(400));
					timer = new TimerTask() {
						@Override
						public void run() {
							runOnUiThread(new Runnable() {
								@Override
								public void run() {
									transfer.setAction(Intent.ACTION_VIEW);
									transfer.putExtra("transfers", "w");
									transfer.setClass(getApplicationContext(), AfterActivity.class);
									startActivity(transfer);
								}
							});
						}
					};
					_timer.schedule(timer, (int)(800));
				} else {
					SketchwareUtil.showMessage(getApplicationContext(), "Incorrect Mail Id Or Incorrect Password");
				}
			}
		};
		
		_login_reset_password_listener = new OnCompleteListener<Void>() {
			@Override
			public void onComplete(Task<Void> _param1) {
				final boolean _success = _param1.isSuccessful();
				
			}
		};
	}
	
	private void initializeLogic() {
		SketchwareUtil.showMessage(getApplicationContext(), "WelcomeUser");
		log.setBackground(new GradientDrawable() { public GradientDrawable getIns(int a, int b) { this.setCornerRadius(a); this.setColor(b); return this; } }.getIns((int)30, 0xFF8A5CF6));
		eyes.setVisibility(View.GONE);
		view.edit().putString("lol", "lol").commit();
		usr_mail.setSingleLine(true);
		
		usr_mail.setOnKeyListener(new View.OnKeyListener() {
			@Override
			public boolean onKey(View v, int keyCode, KeyEvent event) {
				return keyCode == KeyEvent.KEYCODE_ENTER;
			}
		});
		usr_pass.setSingleLine(true);
		
		usr_pass.setOnKeyListener(new View.OnKeyListener() {
			@Override
			public boolean onKey(View v, int keyCode, KeyEvent event) {
				return keyCode == KeyEvent.KEYCODE_ENTER;
			}
		});
		usr_name.setSingleLine(true);
		
		usr_name.setOnKeyListener(new View.OnKeyListener() {
			@Override
			public boolean onKey(View v, int keyCode, KeyEvent event) {
				return keyCode == KeyEvent.KEYCODE_ENTER;
			}
		});
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