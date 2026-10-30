package com.priperi;
import org.junit.Test;
import static org.junit.Assert.*;
public class DeviceSecurityTest {
    @Test public void rejectsCommonEmulatorSignalsWithoutRejectingOrdinaryPhones(){
        assertTrue(DeviceSecurity.isEmulator("google/sdk_gphone64_arm64/emu64a:15/dev:user/release-keys","sdk_gphone64_arm64","ranchu","sdk_gphone64_arm64","Google"));
        assertTrue(DeviceSecurity.isEmulator("generic/device","Android SDK built for x86","goldfish","sdk_x86","unknown"));
        assertFalse(DeviceSecurity.isEmulator("google/husky/husky:15/release:user/release-keys","Pixel 8 Pro","husky","husky","Google"));
        assertFalse(DeviceSecurity.isEmulator("samsung/a55/a55:14/release:user/release-keys","SM-A556E","s5e8845","a55nsxx","samsung"));
        assertFalse(DeviceSecurity.isEmulator(null,null,null,null,null));
    }
}
