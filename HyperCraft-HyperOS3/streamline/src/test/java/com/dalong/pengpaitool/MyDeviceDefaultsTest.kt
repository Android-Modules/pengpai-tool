package com.dalong.pengpaitool

import com.dalong.pengpaitool.xp.MyDeviceCfg
import org.junit.Assert.assertFalse
import org.junit.Test

class MyDeviceDefaultsTest {
    @Test fun newHostLeavesSystemDevicePageUnchangedUntilEnabled() {
        assertFalse(MyDeviceCfg.read(null).enabled)
        assertFalse(MyDeviceCfg.read(null).pet)
    }
}
