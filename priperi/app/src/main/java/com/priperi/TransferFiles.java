package com.priperi;
import android.content.Context;import java.io.File;
final class TransferFiles {
 static void clear(Context context){File[] files=context.getCacheDir().listFiles();if(files!=null)for(File f:files)if(f.isFile()&&f.getName().startsWith("priperi-transfer-")&&f.getName().endsWith(".enc"))f.delete();}
 private TransferFiles(){}
}
