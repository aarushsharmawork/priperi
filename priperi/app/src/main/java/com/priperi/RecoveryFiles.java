package com.priperi;
import android.content.Context;import java.io.File;
/** Only abandoned encrypted cache copies; never touches a resumable private import. */
final class RecoveryFiles {
 static void clear(Context context){File[] files=context.getCacheDir().listFiles();if(files!=null)for(File f:files)if(f.isFile()&&f.getName().startsWith("priperi-recovery-")&&f.getName().endsWith(".enc"))f.delete();}
 private RecoveryFiles(){}
}
