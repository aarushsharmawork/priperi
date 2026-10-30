package com.priperi;
import android.os.*;import android.security.keystore.*;import androidx.biometric.*;import androidx.core.content.ContextCompat;import java.security.*;import javax.crypto.*;
/** Strong biometric, bound to a fresh Keystore cryptographic operation; no PIN fallback. */
final class BiometricGate {
 private static final String ALIAS="priperi.transfer.biometric.v1";
 private final GuardedActivity activity;private BiometricPrompt prompt;private boolean running;private int generation;private long confirmedAt;
 BiometricGate(GuardedActivity activity){this.activity=activity;}
 void authenticate(Runnable success,Repository.Failure failure){
  if(running)return;
  if(BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS){failure.fail("Set up a supported fingerprint or face unlock in your phone settings before moving or recovering an account. A screen-lock PIN alone cannot approve a move.");return;}
  final int request=++generation;confirmedAt=0;try{
   KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
   if(!keys.containsAlias(ALIAS)){KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");KeyGenParameterSpec.Builder spec=new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true);if(Build.VERSION.SDK_INT>=30)spec.setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG);else spec.setUserAuthenticationValidityDurationSeconds(-1);generator.init(spec.build());generator.generateKey();}
   Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,keys.getKey(ALIAS,null));running=true;
   prompt=new BiometricPrompt(activity,ContextCompat.getMainExecutor(activity),new BiometricPrompt.AuthenticationCallback(){@Override public void onAuthenticationError(int code,CharSequence message){if(request!=generation)return;running=false;failure.fail("Biometric confirmation was cancelled or unavailable. No move was approved.");}@Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){if(request!=generation||!running)return;running=false;try{if(result.getCryptoObject()==null||result.getCryptoObject().getCipher()==null)throw new GeneralSecurityException("Missing biometric operation");result.getCryptoObject().getCipher().doFinal(new byte[32]);if(!activity.dialogAllowed())throw new GeneralSecurityException("Reopen account transfer and confirm again");confirmedAt=SystemClock.elapsedRealtime();success.run();}catch(Exception e){failure.fail("Couldn’t confirm this phone. Try again.");}}});
   prompt.authenticate(new BiometricPrompt.PromptInfo.Builder().setTitle("Confirm it’s you").setSubtitle("Use your fingerprint or face on this phone").setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText("Cancel").setConfirmationRequired(true).build(),new BiometricPrompt.CryptoObject(cipher));
  }catch(android.security.keystore.KeyPermanentlyInvalidatedException e){try{KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry(ALIAS);}catch(Exception ignored){}failure.fail("Biometrics changed. Open account transfer again to confirm with your current biometrics.");}catch(Exception e){running=false;failure.fail("Biometric confirmation is unavailable. No move was approved.");}
 }
 static void clearKey(){try{KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry(ALIAS);}catch(Exception ignored){}}
 boolean recent(){return confirmedAt>0&&SystemClock.elapsedRealtime()-confirmedAt<120000&&activity.dialogAllowed();}
 void cancel(){generation++;confirmedAt=0;running=false;if(prompt!=null)prompt.cancelAuthentication();}
}
