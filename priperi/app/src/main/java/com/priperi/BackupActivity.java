package com.priperi;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.lifecycle.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit user-selected documents only. Provider I/O and password KDF never occupy the vault worker. */
public final class BackupActivity extends PrivateActivity {
    private static final ExecutorService TRANSFERS=Executors.newFixedThreadPool(2);
    private Model model;private TextView notice;private ProgressBar progress;private MaterialButton create,restore;
    private boolean privateReady;private androidx.appcompat.app.AlertDialog passwordDialog;
    private EditText first,second;
    private static final class Model extends ViewModel {
        final MutableLiveData<String> message=new MutableLiveData<>("");final MutableLiveData<Boolean> busy=new MutableLiveData<>(false);
        final AtomicReference<Closeable> active=new AtomicReference<>();
        volatile int generation;volatile CancellationSignal signal=new CancellationSignal();volatile File file;volatile boolean closed;String owner;boolean launchSave;Uri pending;int request;
        void cancel(){generation++;signal.cancel();launchSave=false;pending=null;request=0;Closeable c=active.getAndSet(null);if(c!=null)try{c.close();}catch(IOException ignored){}File f=file;if(f!=null)f.delete();file=null;busy.setValue(false);}
        @Override protected void onCleared(){closed=true;cancel();}
    }
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);model=new ViewModelProvider(this,new ViewModelProvider.Factory(){@Override public <T extends ViewModel>T create(Class<T> type){return type.cast(new Model());}}).get(Model.class);
        root=Ui.page(this);root.setVisibility(View.INVISIBLE);Ui.header(this,root,"Backups","Done",v->finish());LinearLayout body=Ui.scroll(this,root);
        Ui.space(this,body,18);body.addView(Ui.symbol(this,"backup",40,R.color.accent));Ui.space(this,body,18);body.addView(Ui.title(this,"Keep a private copy",24));Ui.space(this,body,12);
        body.addView(Ui.text(this,"Save an encrypted message backup to Google Drive or another location you choose in your phone’s file picker. Priperi can access only the file you select.",16,R.color.muted));
        Ui.space(this,body,20);LinearLayout card=Ui.card(this);card.addView(Ui.title(this,"What is included",16));Ui.space(this,card,8);card.addView(Ui.text(this,"Saved messages only. Unsent messages, login details, private keys and account settings stay out of the backup.",14,R.color.muted));Ui.space(this,card,12);card.addView(Ui.text(this,"Restore needs this account’s registered keys. Complete Move account before restoring on another phone. A backup file alone cannot sign you in or recover a lost phone.",14,R.color.muted));body.addView(card);
        Ui.space(this,body,20);create=Ui.button(this,"Create encrypted backup",true,v->SettingsActivity.reauthenticate(this,()->password(true),message->model.message.setValue(message)));body.addView(create);Ui.space(this,body,8);restore=Ui.button(this,"Restore saved messages",false,v->chooseRestore());body.addView(restore);
        body.addView(Ui.button(this,"Move account and saved chats",false,v->startActivity(new Intent(this,TransferActivity.class))));body.addView(Ui.button(this,"Create a recovery backup",false,v->startActivity(new Intent(this,RecoveryActivity.class))));
        progress=new ProgressBar(this);progress.setVisibility(View.GONE);body.addView(progress);notice=Ui.status(this,body);Ui.space(this,body,18);
        body.addView(Ui.text(this,"Keep the backup password somewhere safe. You’ll need it to restore. Each backup supports up to 128 MB of saved messages. Manual backup files stay in your chosen storage until you delete them, including after account deletion. Automatic Android backups remain off.",13,R.color.muted));
        model.message.observe(this,value->notice.setText(value));model.busy.observe(this,value->{boolean busy=Boolean.TRUE.equals(value);create.setEnabled(!busy);restore.setEnabled(!busy);progress.setVisibility(busy?View.VISIBLE:View.GONE);});
        model.message.observe(this,value->continuePicker());
    }
    private boolean usable(){return privateReady&&!environmentBlocked&&!isFinishing()&&!isDestroyed()&&!model.closed&&model.owner!=null&&model.owner.equals(Session.uid());}
    @Override protected void ready(){
        privateReady=true;String uid=Session.uid();if(model.owner!=null&&!model.owner.equals(uid))model.cancel();model.owner=uid;continuePicker();
        if(model.pending!=null){Uri uri=model.pending;int request=model.request;model.pending=null;model.request=0;if(request==91)publish(uri);else if(request==92)password(false,uri);}
    }
    private void continuePicker(){if(!usable()||!model.launchSave)return;model.launchSave=false;try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE,"Priperi-backup-"+new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date())+".priperi"),91);}catch(ActivityNotFoundException e){model.cancel();model.message.setValue("No file picker is available on this phone.");}}
    private void chooseRestore(){if(!usable()||Boolean.TRUE.equals(model.busy.getValue()))return;try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),92);}catch(ActivityNotFoundException e){model.message.setValue("No file picker is available on this phone.");}}
    private void password(boolean exporting){password(exporting,null);}
    private void password(boolean exporting,Uri source){
        if(!usable()||Boolean.TRUE.equals(model.busy.getValue()))return;String owner=model.owner;
        LinearLayout box=Ui.column(this);Ui.pad(this,box,20);box.addView(Ui.text(this,exporting?"Choose a separate backup password with 16–128 characters. Four or more random words are a good starting point.":"Enter the password you chose for this backup.",14,R.color.muted));Ui.space(this,box,14);
        first=Ui.input(this,box,"Backup password",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);first.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);first.setAutofillHints((String[])null);first.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        if(exporting){second=Ui.input(this,box,"Repeat backup password",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);second.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);second.setAutofillHints((String[])null);second.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});}else second=null;
        ScrollView scroll=new ScrollView(this);scroll.addView(box);
        passwordDialog=Ui.dialog(this).setTitle(exporting?"Protect your backup":"Open your backup").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton(exporting?"Continue":"Restore",null).create();
        passwordDialog.setOnShowListener(d->passwordDialog.getButton(-1).setOnClickListener(v->{
            if(!usable()||!owner.equals(Session.uid()))return;char[] pass=new char[first.length()];TextUtils.getChars(first.getText(),0,first.length(),pass,0);
            if(!BackupCodec.password(pass)){Arrays.fill(pass,'\0');first.setError("Use 16–128 characters.");return;}
            if(exporting&&!TextUtils.equals(first.getText(),second.getText())){Arrays.fill(pass,'\0');second.setError("The passwords do not match.");return;}
            clearPassword();passwordDialog.dismiss();if(exporting)prepare(pass);else read(source,pass);
        }));passwordDialog.setOnDismissListener(d->clearPassword());passwordDialog.show();
    }
    private void clearPassword(){if(first!=null)first.setText("");if(second!=null)second.setText("");}
    private int begin(String message){model.generation++;model.signal=new CancellationSignal();model.busy.setValue(true);model.message.setValue(message);return model.generation;}
    private void check(int generation,String owner)throws Exception{if(model.closed||generation!=model.generation||!owner.equals(Session.uid()))throw new IOException("Backup cancelled");DeviceSecurity.requireAllowed();}
    private File temporary()throws IOException{File file=File.createTempFile("priperi-backup-",".enc",getCacheDir());model.file=file;return file;}
    private void prepare(char[] pass){
        int generation=begin("Encrypting saved messages…");String owner=model.owner;Context app=getApplicationContext();
        TRANSFERS.execute(()->{byte[] key=null;File file=null;try{
            check(generation,owner);byte[] salt=BackupCodec.salt();key=BackupCodec.key(pass,salt);Arrays.fill(pass,'\0');check(generation,owner);file=temporary();File output=file;byte[] derived=key;
            Repository.work(app,v->{try{check(generation,owner);return v.exportMessages(output,derived,salt,()->check(generation,owner));}finally{Arrays.fill(derived,(byte)0);}},count->{
                if(model.closed||generation!=model.generation){output.delete();return;}model.launchSave=true;model.message.setValue("Encrypted "+count+" messages. Choose Google Drive or another location.");continuePicker();
            },message->{Arrays.fill(derived,(byte)0);output.delete();fail(generation,"Could not create a backup. Your saved messages are unchanged.");});
        }catch(Exception e){if(key!=null)Arrays.fill(key,(byte)0);if(file!=null)file.delete();fail(generation,"Could not create a backup. Your saved messages are unchanged.");}finally{Arrays.fill(pass,'\0');}});
    }
    private void publish(Uri uri){
        if(!usable()||model.file==null){model.cancel();model.message.setValue("Backup interrupted. Please create it again.");return;}int generation=model.generation;String owner=model.owner;File file=model.file;Context app=getApplicationContext();model.message.setValue("Saving the encrypted file…");
        TRANSFERS.execute(()->{try{check(generation,owner);try(ParcelFileDescriptor fd=app.getContentResolver().openFileDescriptor(uri,"w",model.signal)){if(fd==null)throw new IOException("File unavailable");try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(fd);InputStream in=new FileInputStream(file)){model.active.set(out);copy(in,out,()->check(generation,owner));out.flush();model.active.compareAndSet(out,null);}}file.delete();post(()->{if(generation==model.generation&&!model.closed){model.file=null;model.busy.setValue(false);model.message.setValue("Backup saved. Keep its password somewhere safe.");}});}catch(Exception e){file.delete();fail(generation,"Could not finish saving. An incomplete encrypted file may remain in the location you chose.");}finally{model.active.set(null);}});
    }
    private void read(Uri uri,char[] pass){
        int generation=begin("Opening the encrypted backup…");String owner=model.owner;Context app=getApplicationContext();
        TRANSFERS.execute(()->{byte[] key=null;File file=null;try{check(generation,owner);file=temporary();try(ParcelFileDescriptor fd=app.getContentResolver().openFileDescriptor(uri,"r",model.signal)){if(fd==null)throw new IOException("File unavailable");try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd);OutputStream out=new FileOutputStream(file)){model.active.set(in);copy(BackupCodec.limited(in),out,()->check(generation,owner));model.active.compareAndSet(in,null);}}
            byte[] header;try(InputStream in=new FileInputStream(file)){header=BackupCodec.header(in);}key=BackupCodec.key(pass,BackupCodec.salt(header));Arrays.fill(pass,'\0');check(generation,owner);File input=file;byte[] derived=key;
            Repository.work(app,v->{try{return v.restoreMessages(input,derived,()->check(generation,owner));}finally{Arrays.fill(derived,(byte)0);input.delete();}},count->{if(generation==model.generation&&!model.closed){model.file=null;model.busy.setValue(false);model.message.setValue("Restored "+count+" saved messages. Existing chats and settings were kept.");}},message->{Arrays.fill(derived,(byte)0);input.delete();fail(generation,"Could not restore this backup. Check its password and use the same account on this phone. Existing chats are unchanged.");});
        }catch(Exception e){if(key!=null)Arrays.fill(key,(byte)0);if(file!=null)file.delete();fail(generation,"Could not open this backup. Check its password, file and available phone storage. Existing chats are unchanged.");}finally{Arrays.fill(pass,'\0');model.active.set(null);}});
    }
    private static void copy(InputStream in,OutputStream out,BackupCodec.Check check)throws Exception{byte[] bytes=new byte[8192];long count=0;for(int n;(n=in.read(bytes))!=-1;){check.run();if((count+=n)>BackupCodec.MAX_BYTES)throw new IOException("Backup exceeds 128 MB");out.write(bytes,0,n);}check.run();}
    private static void post(Runnable action){new Handler(Looper.getMainLooper()).post(action);}
    private void fail(int generation,String text){post(()->{if(generation==model.generation&&!model.closed){model.busy.setValue(false);model.launchSave=false;model.file=null;model.message.setValue(text);}});}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=91&&request!=92)return;if(result!=RESULT_OK||data==null||data.getData()==null){model.cancel();model.message.setValue("Backup cancelled.");return;}Uri uri=data.getData();if(!DocumentUris.isProviderDocument(uri)){model.cancel();model.message.setValue("Choose a file in the Android file picker.");return;}model.pending=uri;model.request=request;if(usable())ready();}
    @Override protected void onPause(){privateReady=false;clearPassword();if(passwordDialog!=null)passwordDialog.dismiss();super.onPause();}
    @Override protected void onEnvironmentBlocked(){privateReady=false;clearPassword();if(passwordDialog!=null)passwordDialog.dismiss();if(model!=null){model.cancel();model.message.setValue("Backup stopped because phone security settings changed.");}}
}
