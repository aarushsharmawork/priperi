package com.priperi;
import android.content.Context;
import java.io.File;
/** These app-private temporary files contain ciphertext only. */
final class BackupFiles {
    static void clear(Context context){File[] files=context.getCacheDir().listFiles();if(files!=null)for(File file:files)if(file.isFile()&&file.getName().startsWith("priperi-backup-")&&file.getName().endsWith(".enc"))file.delete();}
    private BackupFiles(){}
}
