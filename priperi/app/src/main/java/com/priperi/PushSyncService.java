package com.priperi;
import android.app.job.*;import android.content.*;import android.os.Build;
/** One coalesced, non-periodic job. Android owns its lifetime; no chat data in job extras. */
public final class PushSyncService extends JobService {
 static final int JOB=21412;private int generation;private SyncCoordinator.Completion completion;
 static void schedule(Context context){JobScheduler scheduler=context.getSystemService(JobScheduler.class);ComponentName component=new ComponentName(context,PushSyncService.class);JobInfo.Builder builder=new JobInfo.Builder(JOB,component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL);
  if(Build.VERSION.SDK_INT>=31)builder.setExpedited(true);
  try{if(scheduler.schedule(builder.build())==JobScheduler.RESULT_SUCCESS)return;}catch(RuntimeException ignored){}
  try{scheduler.schedule(new JobInfo.Builder(JOB,component).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());}catch(RuntimeException ignored){/* Next foreground sync remains available if the OS refuses background work. */}
 }
 @Override public boolean onStartJob(JobParameters params){final int ticket=++generation;Repository.local(this,v->Session.uid()!=null&&v.account()!=null&&v.transferIn()==null&&v.transferOut()==null&&v.setting("notifications",false),enabled->{if(ticket!=generation)return;if(!enabled){jobFinished(params,false);return;}completion=retry->{if(ticket!=generation)return;completion=null;jobFinished(params,retry);};SyncCoordinator.requestBackground(this,completion);},ignored->{if(ticket==generation)jobFinished(params,false);});return true;}
 @Override public boolean onStopJob(JobParameters params){generation++;if(completion!=null){SyncCoordinator.cancelBackground(completion);completion=null;}return true;}
 @Override public void onDestroy(){generation++;if(completion!=null)SyncCoordinator.cancelBackground(completion);super.onDestroy();}
}
